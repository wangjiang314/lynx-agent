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
import com.juwan.lynx.runtime.ModelConfig
import com.juwan.lynx.state.ActionOutcome
import com.juwan.lynx.state.ActionResultCode
import com.juwan.lynx.state.ToolResult
import com.juwan.lynx.state.WorldState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.slot
import io.mockk.mockk
import kotlinx.coroutines.runBlocking

class VerifierAgentTest : FunSpec({

    test("parses strict verifier JSON") {
        val runtime = mockk<AgentRuntime>()
        coEvery { runtime.runAgent(any(), any()) } returns AgentResult.Finished(
            """
            {"complete":true,"confidence":0.93,"evidence":["已发送消息可见"],"missing":[],"next_hint":""}
            """.trimIndent()
        )

        val decision = runBlocking {
            verifier(runtime).verify(strategy(), worldState(), "FINISH_TOOL_EXECUTED: done")
        }

        decision.complete shouldBe true
        decision.confidence shouldBe 0.93f
        decision.evidence shouldBe listOf("已发送消息可见")
        decision.missing shouldBe emptyList()
    }

    test("parse failure becomes incomplete with missing reason") {
        val runtime = mockk<AgentRuntime>()
        coEvery { runtime.runAgent(any(), any()) } returns AgentResult.Finished("not json")

        val decision = runBlocking {
            verifier(runtime).verify(strategy(), worldState(), "FINISH_TOOL_EXECUTED: done")
        }

        decision.complete shouldBe false
        decision.confidence shouldBe 0.0f
        decision.missing shouldBe listOf("verifier_json_parse_failed")
    }

    test("model error becomes incomplete") {
        val runtime = mockk<AgentRuntime>()
        coEvery { runtime.runAgent(any(), any()) } returns AgentResult.Error("timeout")

        val decision = runBlocking {
            verifier(runtime).verify(strategy(), worldState(), "FINISH_TOOL_EXECUTED: done")
        }

        decision.complete shouldBe false
        decision.missing shouldBe listOf("verifier_model_error:timeout")
    }

    test("verification input should prefer structured tool results over legacy outcomes") {
        val runtime = mockk<AgentRuntime>()
        val inputSlot = slot<String>()
        coEvery { runtime.runAgent(any(), capture(inputSlot)) } returns AgentResult.Finished(
            """{"complete":false,"confidence":0.1,"evidence":[],"missing":["not_done"],"next_hint":""}"""
        )
        val state = worldState().copy(
            recentOutcomes = listOf(
                ActionOutcome(
                    toolName = "legacy",
                    actionText = "legacy_action",
                    resultCode = ActionResultCode.SUCCESS,
                    pageSignatureBefore = "legacy_before",
                    pageSignatureAfter = "legacy_after",
                    timestamp = 1L
                )
            ),
            recentToolResults = listOf(
                ToolResult(
                    toolName = "click",
                    actionText = "structured_action",
                    status = ActionResultCode.ERROR,
                    targetLabel = "发送",
                    changed = false,
                    rawSignatureChanged = false,
                    pageSignatureBefore = "before",
                    pageSignatureAfter = "after",
                    errorType = "tool_error",
                    timestamp = 2L
                )
            )
        )

        runBlocking {
            verifier(runtime).verify(strategy(), state, "FINISH_TOOL_EXECUTED: done")
        }

        inputSlot.captured shouldContain "Recent structured tool results"
        inputSlot.captured shouldContain "click:ERROR changed=false target=发送 error=tool_error"
        inputSlot.captured shouldNotContain "legacy_action"
    }
})

private fun verifier(runtime: AgentRuntime): VerifierAgent {
    return VerifierAgent(
        runtime = runtime,
        modelConfig = ModelConfig(
            baseUrl = "http://test",
            modelName = "vision-test",
            temperature = 0.0f,
            timeoutSeconds = 30
        )
    )
}

private fun strategy(): Strategy {
    return Strategy(
        goal = "给张三发送消息",
        app = "com.tencent.mm",
        strategy = listOf("进入会话", "完成发送"),
        successCriteria = listOf("会话中可见已发送消息"),
        originalInstruction = "打开微信给张三发消息"
    )
}

private fun worldState(): WorldState {
    return WorldState(
        currentApp = "com.tencent.mm",
        goal = "给张三发送消息",
        targetApp = "com.tencent.mm",
        successCriteria = listOf("会话中可见已发送消息"),
        visibleTexts = listOf("张三", "你好")
    )
}
