/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.juwan.lynx.agent

import com.juwan.lynx.runtime.AgentResult
import com.juwan.lynx.runtime.AgentRuntime
import com.juwan.lynx.state.ActionOutcome
import com.juwan.lynx.state.ActionRecord
import com.juwan.lynx.state.ActionResultCode
import com.juwan.lynx.state.PendingConfirmation
import com.juwan.lynx.state.WorldState
import com.juwan.lynx.state.WorldStateManager
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking

class ExecutorAgentFinishSignalTest : FunSpec({

    test("explicit finish marker should only propose completion") {
        val runtime = mockk<AgentRuntime>()
        val worldStateManager = mockWorldStateManager(state = buildFinishReadyState())
        coEvery { runtime.runAgent(any(), any()) } returns AgentResult.Finished("FINISH_TOOL_EXECUTED: done")

        val result = runBlocking {
            testExecutor(runtime, worldStateManager, primitiveTools = listOf(namedTool("finished")))
                .executeStrategy(messageStrategy())
        }

        result.shouldBeInstanceOf<ExecutionResult.FinishProposed>()
    }

    test("explicit finish marker should still be verifier-gated while confirmation is pending") {
        val runtime = mockk<AgentRuntime>()
        val pendingState = buildFinishReadyState(
            pendingConfirmation = PendingConfirmation(
                actionHash = "pending_send",
                actionSummary = "发送消息",
                reason = "等待确认"
            )
        )
        val worldStateManager = mockWorldStateManager(state = pendingState)
        coEvery { runtime.runAgent(any(), any()) } returnsMany listOf(
            AgentResult.Finished("FINISH_TOOL_EXECUTED: done"),
            AgentResult.Finished("FINISH_TOOL_EXECUTED: done")
        )

        val result = runBlocking {
            testExecutor(runtime, worldStateManager, primitiveTools = listOf(namedTool("finished")))
                .executeStrategy(messageStrategy())
        }

        result.shouldBeInstanceOf<ExecutionResult.FinishProposed>()
    }

    test("explicit finish marker should not require local business evidence") {
        val runtime = mockk<AgentRuntime>()
        val worldStateManager = mockWorldStateManager(state = buildInputOnlyState())
        coEvery { runtime.runAgent(any(), any()) } returnsMany listOf(
            AgentResult.Finished("FINISH_TOOL_EXECUTED: done"),
            AgentResult.Finished("FINISH_TOOL_EXECUTED: done")
        )

        val result = runBlocking {
            testExecutor(runtime, worldStateManager, primitiveTools = listOf(namedTool("finished")))
                .executeStrategy(messageStrategy())
        }

        result.shouldBeInstanceOf<ExecutionResult.FinishProposed>()
    }

    test("plain completion text should reject without explicit finished marker") {
        val runtime = mockk<AgentRuntime>()
        val worldStateManager = mockWorldStateManager(state = buildFinishReadyState())
        coEvery { runtime.runAgent(any(), any()) } returns AgentResult.Finished("任务完成")

        val result = runBlocking {
            testExecutor(runtime, worldStateManager, primitiveTools = listOf(namedTool("finished")))
                .executeStrategy(messageStrategy())
        }

        val failure = result.shouldBeInstanceOf<ExecutionResult.Failed>()
        failure.code shouldBe ExecutionFailureCode.FINISH_HANDSHAKE_REJECTED
    }

    test("empty LLM response should retry locally before failing execution") {
        val runtime = mockk<AgentRuntime>()
        val worldStateManager = mockWorldStateManager(state = buildFinishReadyState())
        var executorCalls = 0
        coEvery { runtime.runAgent(any(), any()) } answers {
            executorCalls += 1
            if (executorCalls == 1) {
                AgentResult.Error("Empty response from LLM")
            } else {
                AgentResult.Finished("FINISH_TOOL_EXECUTED: done")
            }
        }

        val result = runBlocking {
            testExecutor(runtime, worldStateManager, primitiveTools = listOf(namedTool("finished")))
                .executeStrategy(messageStrategy())
        }

        result.shouldBeInstanceOf<ExecutionResult.FinishProposed>()
        executorCalls shouldBe 2
    }

    test("missing tool call should retry locally before accepting an explicit finish") {
        val runtime = mockk<AgentRuntime>()
        val worldStateManager = mockWorldStateManager(state = buildFinishReadyState())
        var executorCalls = 0
        coEvery { runtime.runAgent(any(), any()) } answers {
            executorCalls += 1
            if (executorCalls == 1) {
                AgentResult.Error("No tool call in model response: done")
            } else {
                AgentResult.Finished("FINISH_TOOL_EXECUTED: done")
            }
        }

        val result = runBlocking {
            testExecutor(runtime, worldStateManager, primitiveTools = listOf(namedTool("finished")))
                .executeStrategy(messageStrategy())
        }

        result.shouldBeInstanceOf<ExecutionResult.FinishProposed>()
        executorCalls shouldBe 2
    }
})

private fun messageStrategy(): Strategy {
    return Strategy(
        goal = "给好友发送消息",
        app = "com.ss.android.ugc.aweme.lite",
        strategy = listOf("定位好友并进入会话", "输入消息内容", "完成消息发送并确认发送成功"),
        originalInstruction = "给好友发送你好"
    )
}

private fun buildFinishReadyState(
    pendingConfirmation: PendingConfirmation? = null
): WorldState {
    return WorldState(
        currentApp = "com.ss.android.ugc.aweme.lite",
        strategy = messageStrategy().strategy,
        pageCapabilities = listOf(
            com.juwan.lynx.state.CapabilityState.CAN_INPUT_TEXT,
            com.juwan.lynx.state.CapabilityState.CAN_SUBMIT_TEXT
        ),
        visibleTexts = listOf("发送消息", "你好"),
        actionCandidates = listOf("[INPUT] \"发送消息\"", "[BUTTON] \"发送\""),
        pendingConfirmation = pendingConfirmation,
        recentActions = listOf(
            ActionRecord(
                action = "type(text=你好)",
                result = "已输入文本: \"你好\"",
                pageSignatureBefore = "chat_before_input",
                pageSignatureAfter = "chat_after_input",
                timestamp = 1L
            ),
            ActionRecord(
                action = "click(point=<point>897 961</point>)",
                result = "已点击发送",
                pageSignatureBefore = "chat_after_input",
                pageSignatureAfter = "chat_after_send",
                timestamp = 2L
            )
        ),
        recentOutcomes = listOf(
            ActionOutcome(
                toolName = "type",
                actionText = "type(text=你好)",
                resultCode = ActionResultCode.SUCCESS,
                pageSignatureBefore = "chat_before_input",
                pageSignatureAfter = "chat_after_input",
                timestamp = 1L
            ),
            ActionOutcome(
                toolName = "click",
                actionText = "click(point=<point>897 961</point>)",
                resultCode = ActionResultCode.SUCCESS,
                pageSignatureBefore = "chat_after_input",
                pageSignatureAfter = "chat_after_send",
                timestamp = 2L
            )
        )
    )
}

private fun buildInputOnlyState(): WorldState {
    return WorldState(
        currentApp = "com.ss.android.ugc.aweme.lite",
        strategy = messageStrategy().strategy,
        pageCapabilities = listOf(
            com.juwan.lynx.state.CapabilityState.CAN_INPUT_TEXT,
            com.juwan.lynx.state.CapabilityState.CAN_SUBMIT_TEXT
        ),
        visibleTexts = listOf("发送消息", "你好"),
        actionCandidates = listOf("[INPUT] \"发送消息\"", "[BUTTON] \"发送\""),
        recentActions = listOf(
            ActionRecord(
                action = "type(text=你好)",
                result = "已输入文本: \"你好\"",
                pageSignatureBefore = "chat_before_input",
                pageSignatureAfter = "chat_after_input",
                timestamp = 1L
            )
        ),
        recentOutcomes = listOf(
            ActionOutcome(
                toolName = "type",
                actionText = "type(text=你好)",
                resultCode = ActionResultCode.SUCCESS,
                pageSignatureBefore = "chat_before_input",
                pageSignatureAfter = "chat_after_input",
                timestamp = 1L
            )
        )
    )
}

private fun mockWorldStateManager(state: WorldState): WorldStateManager {
    val manager = mockk<WorldStateManager>(relaxed = true)
    every { manager.getState() } returns state
    return manager
}
