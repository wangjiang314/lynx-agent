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
import com.juwan.lynx.runtime.Tool
import com.juwan.lynx.runtime.ToolParam
import com.juwan.lynx.safety.SafetyGuard
import com.juwan.lynx.state.ActionOutcome
import com.juwan.lynx.state.ActionRecord
import com.juwan.lynx.state.ActionResultCode
import com.juwan.lynx.state.PendingConfirmation
import com.juwan.lynx.state.WorldState
import com.juwan.lynx.state.WorldStateManager
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking

class ExecutorAgentPropertyTest : FunSpec({

    test("completed milestones should still only propose finished while pending confirmation exists") {
        val runtime = mockk<AgentRuntime>()
        val worldStateManager = mockk<WorldStateManager>(relaxed = true)
        val strategy = testStrategy()

        var state = WorldState(
            currentApp = "com.ss.android.ugc.aweme.lite",
            strategy = strategy.strategy,
            pendingConfirmation = PendingConfirmation(
                actionHash = "pending-send",
                actionSummary = "发送消息",
                reason = "等待用户确认"
            ),
            recentActions = listOf(
                ActionRecord(
                    action = "click(point=<point>897 961</point>)",
                    result = "已点击发送",
                    pageSignatureBefore = "chat_before_send",
                    pageSignatureAfter = "chat_after_send",
                    timestamp = 1L
                )
            ),
            recentOutcomes = listOf(
                ActionOutcome(
                    toolName = "click",
                    actionText = "click(point=<point>897 961</point>)",
                    resultCode = ActionResultCode.SUCCESS,
                    pageSignatureBefore = "chat_before_send",
                    pageSignatureAfter = "chat_after_send",
                    timestamp = 1L
                )
            )
        )
        every { worldStateManager.getState() } answers { state }
        coEvery { worldStateManager.refreshObservation() } returns Unit
        every { worldStateManager.getResolvedTargetPackage() } returns null
        every { worldStateManager.isStuck(any()) } returns false
        var executorCalls = 0
        coEvery { runtime.runAgent(any(), any()) } answers {
            val config = firstArg<com.juwan.lynx.runtime.AgentConfig>()
            if (config.name == "ExecutorAgent") {
                executorCalls += 1
                AgentResult.Finished("FINISH_TOOL_EXECUTED: done")
            } else {
                AgentResult.Error("verifier_unused_for_pending_confirmation")
            }
        }

        val executor = testExecutor(
            runtime = runtime,
            worldStateManager = worldStateManager,
            primitiveTools = listOf(namedTool("finished"))
        )

        val result = runBlocking { executor.executeStrategy(strategy) }

        result.shouldBeInstanceOf<ExecutionResult.FinishProposed>()
        executorCalls shouldBe 1
    }

    test("completed milestones should propose completion only after model issues explicit finished") {
        val runtime = mockk<AgentRuntime>()
        val worldStateManager = mockk<WorldStateManager>(relaxed = true)
        val strategy = testStrategy()

        var state = finishReadyState(strategy)
        every { worldStateManager.getState() } answers { state }
        coEvery { worldStateManager.refreshObservation() } returns Unit
        every { worldStateManager.getResolvedTargetPackage() } returns null
        every { worldStateManager.isStuck(any()) } returns false
        var executorCalls = 0
        coEvery { runtime.runAgent(any(), any()) } answers {
            val config = firstArg<com.juwan.lynx.runtime.AgentConfig>()
            if (config.name == "ExecutorAgent") {
                executorCalls += 1
                AgentResult.Finished("FINISH_TOOL_EXECUTED: done")
            } else {
                AgentResult.Error("verifier_unused_for_completed_milestones_finish")
            }
        }

        val executor = testExecutor(
            runtime = runtime,
            worldStateManager = worldStateManager,
            primitiveTools = listOf(namedTool("finished"))
        )

        val result = runBlocking { executor.executeStrategy(strategy) }

        result.shouldBeInstanceOf<ExecutionResult.FinishProposed>()
        executorCalls shouldBe 1
    }

    test("simple app launch goal should still require model finish when target app is active") {
        val runtime = mockk<AgentRuntime>()
        val worldStateManager = mockk<WorldStateManager>(relaxed = true)
        val strategy = Strategy(
            goal = "打开设置应用",
            app = "Settings",
            strategy = listOf("打开设置应用"),
            originalInstruction = "Open Settings app"
        )

        every { worldStateManager.getState() } returns WorldState(
            currentApp = "com.android.settings",
            goal = strategy.goal,
            targetApp = strategy.app,
            strategy = strategy.strategy
        )
        coEvery { worldStateManager.refreshObservation() } returns Unit
        every { worldStateManager.getResolvedTargetPackage() } returns "com.android.settings"
        every { worldStateManager.isStuck(any()) } returns false
        coEvery { runtime.runAgent(any(), any()) } returns AgentResult.Finished("FINISH_TOOL_EXECUTED: done")

        val executor = testExecutor(
            runtime = runtime,
            worldStateManager = worldStateManager,
            primitiveTools = listOf(namedTool("finished"))
        )

        val result = runBlocking { executor.executeStrategy(strategy) }

        result.shouldBeInstanceOf<ExecutionResult.FinishProposed>()
        coVerify(exactly = 1) { runtime.runAgent(any(), any()) }
    }

    test("simple app launch goal should not execute open_app locally before model action") {
        val runtime = mockk<AgentRuntime>()
        val worldStateManager = mockk<WorldStateManager>(relaxed = true)
        val strategy = Strategy(
            goal = "打开设置应用",
            app = "com.android.settings",
            strategy = listOf("打开设置应用"),
            originalInstruction = "Open Settings app"
        )
        var state = WorldState(
            currentApp = "com.juwan.lynx",
            goal = strategy.goal,
            targetApp = strategy.app,
            strategy = strategy.strategy
        )
        var openAppCalls = 0
        val openAppTool = object : Tool {
            override val name: String = "open_app"
            override val description: String = "open app"
            override val parameters: List<ToolParam> = emptyList()

            override suspend fun execute(args: Map<String, String>): String {
                openAppCalls += 1
                return "已启动应用: 设置 package_name=com.android.settings"
            }
        }

        every { worldStateManager.getState() } answers { state }
        coEvery { worldStateManager.refreshObservation() } returns Unit
        every { worldStateManager.getResolvedTargetPackage() } answers { state.resolvedTargetPackage }
        every { worldStateManager.isStuck(any()) } returns false
        coEvery { runtime.runAgent(any(), any()) } returns AgentResult.Error("should_not_call_model")

        val executor = ExecutorAgent(
            runtime = runtime,
            worldStateManager = worldStateManager,
            safetyGuard = SafetyGuard { true },
            visionModelConfig = ModelConfig(
                baseUrl = "http://test",
                modelName = "test-model",
                temperature = 0.0f,
                timeoutSeconds = 30
            ),
            primitiveTools = listOf(openAppTool, namedTool("finished"))
        )

        val result = runBlocking { executor.executeStrategy(strategy) }

        result.shouldBeInstanceOf<ExecutionResult.Failed>()
        openAppCalls shouldBe 0
        coVerify(exactly = 1) { runtime.runAgent(any(), any()) }
    }

    test("executor should use text-only model call on Lynx shell before target app is active") {
        val runtime = mockk<AgentRuntime>()
        val worldStateManager = mockk<WorldStateManager>(relaxed = true)
        val strategy = Strategy(
            goal = "打开设置应用",
            app = "com.android.settings",
            strategy = listOf("打开设置应用"),
            originalInstruction = "Open Settings app"
        )

        every { worldStateManager.getState() } returns WorldState(
            currentApp = "com.juwan.lynx",
            goal = strategy.goal,
            targetApp = strategy.app,
            strategy = strategy.strategy,
            screenshotBase64 = "fake-screenshot"
        )
        coEvery { worldStateManager.refreshObservation() } returns Unit
        every { worldStateManager.getResolvedTargetPackage() } returns null
        every { worldStateManager.isStuck(any()) } returns false

        var capturedUseVision: Boolean? = null
        var capturedImageBase64: String? = "unset"
        coEvery { runtime.runAgent(any(), any()) } answers {
            val config = firstArg<com.juwan.lynx.runtime.AgentConfig>()
            capturedUseVision = config.useVision
            capturedImageBase64 = config.imageBase64
            AgentResult.Finished("FINISH_TOOL_EXECUTED: done")
        }

        val executor = testExecutor(
            runtime = runtime,
            worldStateManager = worldStateManager,
            primitiveTools = listOf(
                namedTool("resolve_installed_app"),
                namedTool("open_app"),
                namedTool("finished")
            )
        )

        val result = runBlocking { executor.executeStrategy(strategy) }

        result.shouldBeInstanceOf<ExecutionResult.FinishProposed>()
        capturedUseVision shouldBe false
        capturedImageBase64 shouldBe null
        coVerify(exactly = 1) { runtime.runAgent(any(), any()) }
    }

    test("simple app launch goal should not finish locally from target text on Lynx screen") {
        val runtime = mockk<AgentRuntime>()
        val worldStateManager = mockk<WorldStateManager>(relaxed = true)
        val strategy = Strategy(
            goal = "打开设置应用",
            app = "Settings",
            strategy = listOf("打开设置应用"),
            originalInstruction = "Open Settings app"
        )

        every { worldStateManager.getState() } returns WorldState(
            currentApp = "com.juwan.lynx",
            goal = strategy.goal,
            targetApp = strategy.app,
            strategy = strategy.strategy,
            uiTexts = listOf("Lynx Agent", "Open Settings app"),
            contentDescriptions = listOf("Settings")
        )
        coEvery { worldStateManager.refreshObservation() } returns Unit
        every { worldStateManager.getResolvedTargetPackage() } returns null
        every { worldStateManager.isStuck(any()) } returns false
        coEvery { runtime.runAgent(any(), any()) } returns AgentResult.Error("model_path_required")

        val executor = testExecutor(
            runtime = runtime,
            worldStateManager = worldStateManager,
            primitiveTools = listOf(namedTool("finished"))
        )

        val result = runBlocking { executor.executeStrategy(strategy) }

        result.shouldBeInstanceOf<ExecutionResult.Failed>()
        coVerify(exactly = 1) { runtime.runAgent(any(), any()) }
    }

    test("app launch plus page navigation goal should not finish locally after app opens") {
        val runtime = mockk<AgentRuntime>()
        val worldStateManager = mockk<WorldStateManager>(relaxed = true)
        val strategy = Strategy(
            goal = "进入设置的 WLAN 页面",
            app = "com.android.settings",
            strategy = listOf("打开设置", "点击 WLAN", "确认进入 WLAN 页面"),
            originalInstruction = "打开设置并进入 WLAN 页面"
        )

        every { worldStateManager.getState() } returns WorldState(
            currentApp = "com.android.settings",
            goal = strategy.goal,
            targetApp = strategy.app,
            strategy = strategy.strategy,
            uiTexts = listOf("设置", "WLAN", "蓝牙")
        )
        coEvery { worldStateManager.refreshObservation() } returns Unit
        every { worldStateManager.getResolvedTargetPackage() } returns "com.android.settings"
        every { worldStateManager.isStuck(any()) } returns false
        coEvery { runtime.runAgent(any(), any()) } returns AgentResult.Error("model_path_required_for_page_navigation")

        val executor = testExecutor(
            runtime = runtime,
            worldStateManager = worldStateManager,
            primitiveTools = listOf(namedTool("click"), namedTool("finished"))
        )

        val result = runBlocking { executor.executeStrategy(strategy) }

        result.shouldBeInstanceOf<ExecutionResult.Failed>()
        coVerify(exactly = 1) { runtime.runAgent(any(), any()) }
    }

    test("settings WLAN page evidence should not bypass executor model path") {
        val runtime = mockk<AgentRuntime>()
        val worldStateManager = mockk<WorldStateManager>(relaxed = true)
        val strategy = Strategy(
            goal = "进入设置的 WLAN 页面",
            app = "com.android.settings",
            strategy = listOf("打开设置", "进入 WLAN 页面"),
            originalInstruction = "打开设置并进入 WLAN 页面"
        )

        every { worldStateManager.getState() } returns WorldState(
            currentApp = "com.android.settings",
            goal = strategy.goal,
            targetApp = strategy.app,
            strategy = strategy.strategy,
            uiTexts = listOf("WLAN", "更多 WLAN 设置", "WLAN 直连", "已开启")
        )
        coEvery { worldStateManager.refreshObservation() } returns Unit
        every { worldStateManager.getResolvedTargetPackage() } returns "com.android.settings"
        every { worldStateManager.isStuck(any()) } returns false
        coEvery { runtime.runAgent(any(), any()) } returns AgentResult.Error("model_path_required_no_runtime_page_hardcoding")

        val executor = testExecutor(
            runtime = runtime,
            worldStateManager = worldStateManager,
            primitiveTools = listOf(namedTool("finished"))
        )

        val result = runBlocking { executor.executeStrategy(strategy) }

        result.shouldBeInstanceOf<ExecutionResult.Failed>()
        coVerify(exactly = 1) { runtime.runAgent(any(), any()) }
    }

    test("settings home WLAN status text should not trigger local deterministic finish") {
        val runtime = mockk<AgentRuntime>()
        val worldStateManager = mockk<WorldStateManager>(relaxed = true)
        val strategy = Strategy(
            goal = "进入设置的 WLAN 页面",
            app = "com.android.settings",
            strategy = listOf("打开设置", "进入 WLAN 页面"),
            originalInstruction = "打开设置并进入 WLAN 页面"
        )

        every { worldStateManager.getState() } returns WorldState(
            currentApp = "com.android.settings",
            goal = strategy.goal,
            targetApp = strategy.app,
            strategy = strategy.strategy,
            uiTexts = listOf("设置", "WLAN", "蓝牙", "已开启")
        )
        coEvery { worldStateManager.refreshObservation() } returns Unit
        every { worldStateManager.getResolvedTargetPackage() } returns "com.android.settings"
        every { worldStateManager.isStuck(any()) } returns false
        coEvery { runtime.runAgent(any(), any()) } returns AgentResult.Error("model_path_required_for_weak_wlan_evidence")

        val executor = testExecutor(
            runtime = runtime,
            worldStateManager = worldStateManager,
            primitiveTools = listOf(namedTool("finished"))
        )

        val result = runBlocking { executor.executeStrategy(strategy) }

        result.shouldBeInstanceOf<ExecutionResult.Failed>()
        coVerify(exactly = 1) { runtime.runAgent(any(), any()) }
    }

    test("bluetooth page evidence should not trigger local deterministic WLAN finish") {
        val runtime = mockk<AgentRuntime>()
        val worldStateManager = mockk<WorldStateManager>(relaxed = true)
        val strategy = Strategy(
            goal = "进入设置的 WLAN 页面",
            app = "com.android.settings",
            strategy = listOf("打开设置", "进入 WLAN 页面"),
            originalInstruction = "打开设置并进入 WLAN 页面"
        )

        every { worldStateManager.getState() } returns WorldState(
            currentApp = "com.android.settings",
            goal = strategy.goal,
            targetApp = strategy.app,
            strategy = strategy.strategy,
            uiTexts = listOf("蓝牙", "设备名称", "可用设备", "WLAN")
        )
        coEvery { worldStateManager.refreshObservation() } returns Unit
        every { worldStateManager.getResolvedTargetPackage() } returns "com.android.settings"
        every { worldStateManager.isStuck(any()) } returns false
        coEvery { runtime.runAgent(any(), any()) } returns AgentResult.Error("model_path_required_for_wrong_settings_page")

        val executor = testExecutor(
            runtime = runtime,
            worldStateManager = worldStateManager,
            primitiveTools = listOf(namedTool("finished"))
        )

        val result = runBlocking { executor.executeStrategy(strategy) }

        result.shouldBeInstanceOf<ExecutionResult.Failed>()
        coVerify(exactly = 1) { runtime.runAgent(any(), any()) }
    }

    test("completed milestones should surface missing explicit finished through model path") {
        val runtime = mockk<AgentRuntime>()
        val worldStateManager = mockk<WorldStateManager>(relaxed = true)
        val strategy = testStrategy()

        var state = finishReadyState(strategy)
        every { worldStateManager.getState() } answers { state }
        coEvery { worldStateManager.refreshObservation() } returns Unit
        every { worldStateManager.getResolvedTargetPackage() } returns null
        every { worldStateManager.isStuck(any()) } returns false
        var executorCalls = 0
        coEvery { runtime.runAgent(any(), any()) } answers {
            val config = firstArg<com.juwan.lynx.runtime.AgentConfig>()
            if (config.name == "ExecutorAgent") {
                executorCalls += 1
                AgentResult.Error("Unknown tool: finished")
            } else {
                AgentResult.Error("verifier_unused_for_missing_finished")
            }
        }

        val executor = testExecutor(
            runtime = runtime,
            worldStateManager = worldStateManager,
            primitiveTools = listOf(namedTool("click"))
        )

        val result = runBlocking { executor.executeStrategy(strategy) }

        val failure = result.shouldBeInstanceOf<ExecutionResult.InsufficientProgress>()
        failure.code shouldBe ExecutionFailureCode.FINISH_HANDSHAKE_REJECTED
        executorCalls shouldBe 2
    }

    test("completed milestones should still only propose finished when recent business outcomes are all blocked") {
        val runtime = mockk<AgentRuntime>()
        val worldStateManager = mockk<WorldStateManager>(relaxed = true)
        val strategy = testStrategy()

        var state = WorldState(
            currentApp = "com.ss.android.ugc.aweme.lite",
            strategy = strategy.strategy,
            recentActions = listOf(
                ActionRecord(
                    action = "click(point=<point>897 961</point>)",
                    result = "错误: 被系统阻止",
                    pageSignatureBefore = "chat_before_send",
                    pageSignatureAfter = "chat_before_send",
                    timestamp = 1L
                ),
                ActionRecord(
                    action = "click(point=<point>897 961</point>)",
                    result = "错误: 被系统阻止",
                    pageSignatureBefore = "chat_before_send",
                    pageSignatureAfter = "chat_before_send",
                    timestamp = 2L
                )
            ),
            recentOutcomes = listOf(
                ActionOutcome(
                    toolName = "click",
                    actionText = "click(point=<point>897 961</point>)",
                    resultCode = ActionResultCode.BLOCKED,
                    pageSignatureBefore = "chat_before_send",
                    pageSignatureAfter = "chat_before_send",
                    timestamp = 1L
                ),
                ActionOutcome(
                    toolName = "click",
                    actionText = "click(point=<point>897 961</point>)",
                    resultCode = ActionResultCode.SUPPRESSED,
                    pageSignatureBefore = "chat_before_send",
                    pageSignatureAfter = "chat_before_send",
                    timestamp = 2L
                )
            )
        )
        every { worldStateManager.getState() } answers { state }
        coEvery { worldStateManager.refreshObservation() } returns Unit
        every { worldStateManager.getResolvedTargetPackage() } returns null
        every { worldStateManager.isStuck(any()) } returns false
        var executorCalls = 0
        coEvery { runtime.runAgent(any(), any()) } answers {
            val config = firstArg<com.juwan.lynx.runtime.AgentConfig>()
            if (config.name == "ExecutorAgent") {
                executorCalls += 1
                AgentResult.Finished("FINISH_TOOL_EXECUTED: done")
            } else {
                AgentResult.Error("verifier_unused_for_blocked_outcomes")
            }
        }

        val executor = testExecutor(
            runtime = runtime,
            worldStateManager = worldStateManager,
            primitiveTools = listOf(namedTool("finished"))
        )

        val result = runBlocking { executor.executeStrategy(strategy) }

        result.shouldBeInstanceOf<ExecutionResult.FinishProposed>()
        executorCalls shouldBe 1
    }

    test("executor should converge on repeated unknown business tool selection") {
        val runtime = mockk<AgentRuntime>()
        val worldStateManager = mockWorldStateManager()
        val strategy = testStrategy()

        var executorCalls = 0
        var secondHistory = emptyList<String>()
        coEvery { runtime.runAgent(any(), any()) } answers {
            val config = firstArg<com.juwan.lynx.runtime.AgentConfig>()
            if (config.name == "ExecutorAgent") {
                executorCalls += 1
                if (executorCalls == 2) {
                    secondHistory = config.conversationHistory.map { it.content }
                }
                AgentResult.Error("Unknown tool: dragg")
            } else {
                AgentResult.Error("verifier_unused_for_unknown_tool")
            }
        }

        val executor = testExecutor(
            runtime = runtime,
            worldStateManager = worldStateManager,
            primitiveTools = listOf(namedTool("click"), namedTool("finished"))
        )

        val result = runBlocking { executor.executeStrategy(strategy) }

        val failure = result.shouldBeInstanceOf<ExecutionResult.InsufficientProgress>()
        failure.code shouldBe ExecutionFailureCode.UNKNOWN_TOOL
        check(secondHistory.any { it.contains("Tool unavailable in current state") }) {
            secondHistory.joinToString(" || ")
        }
        executorCalls shouldBe 2
    }

    test("executor should treat repeated unknown finished selection as finish handshake rejection") {
        val runtime = mockk<AgentRuntime>()
        val worldStateManager = mockWorldStateManager()
        val strategy = testStrategy()

        var executorCalls = 0
        coEvery { runtime.runAgent(any(), any()) } answers {
            val config = firstArg<com.juwan.lynx.runtime.AgentConfig>()
            if (config.name == "ExecutorAgent") {
                executorCalls += 1
                if (executorCalls == 2) {
                    config.conversationHistory.any {
                        it.content.contains("Tool unavailable in current state")
                    } shouldBe true
                }
                AgentResult.Error("Unknown tool: finished")
            } else {
                AgentResult.Error("verifier_unused_for_unknown_tool")
            }
        }

        val executor = testExecutor(
            runtime = runtime,
            worldStateManager = worldStateManager,
            primitiveTools = listOf(namedTool("click"), namedTool("finished"))
        )

        val result = runBlocking { executor.executeStrategy(strategy) }

        val failure = result.shouldBeInstanceOf<ExecutionResult.InsufficientProgress>()
        failure.code shouldBe ExecutionFailureCode.FINISH_HANDSHAKE_REJECTED
        executorCalls shouldBe 2
    }

    test("executor should not auto-finish after post-action send evidence without explicit finished") {
        val runtime = mockk<AgentRuntime>()
        val worldStateManager = mockk<WorldStateManager>(relaxed = true)
        val strategy = richerMessageStrategy()

        var state = WorldState(
            currentApp = "com.ss.android.ugc.aweme.lite",
            pageAffordances = listOf("has_bottom_composer", "has_send_button", "has_back_nav"),
            visibleTexts = listOf("晓哥", "聊天"),
            actionCandidates = listOf(
                "[INPUT] \"发送消息\" -> point(383, 961)",
                "[BUTTON] \"M\" -> point(897, 961)"
            ),
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
            ),
            strategy = strategy.strategy,
        )
        every { worldStateManager.getState() } answers { state }
        coEvery { worldStateManager.refreshObservation() } returns Unit
        every { worldStateManager.getResolvedTargetPackage() } returns null
        every { worldStateManager.isStuck(any()) } returns false

        var runtimeCalls = 0
        coEvery { runtime.runAgent(any(), any()) } answers {
            runtimeCalls += 1
            when (runtimeCalls) {
                1 -> {
                    state = state.copy(
                        visibleTexts = listOf("晓哥", "发送成功", "已发送"),
                        actionCandidates = listOf("[TEXT] \"发送成功\" -> point(540, 220)"),
                        recentActions = state.recentActions + ActionRecord(
                            action = "click(point=<point>897 961</point>)",
                            result = "已点击发送",
                            pageSignatureBefore = "chat_after_input",
                            pageSignatureAfter = "chat_after_send",
                            timestamp = 2L
                        ),
                        recentOutcomes = state.recentOutcomes + ActionOutcome(
                            toolName = "click",
                            actionText = "click(point=<point>897 961</point>)",
                            resultCode = ActionResultCode.SUCCESS,
                            pageSignatureBefore = "chat_after_input",
                            pageSignatureAfter = "chat_after_send",
                            timestamp = 2L
                        )
                    )
                    AgentResult.ToolCallResult("click", "已点击发送")
                }

                else -> AgentResult.Error("stop_after_requires_explicit_finished")
            }
        }

        val executor = testExecutor(
            runtime = runtime,
            worldStateManager = worldStateManager,
            primitiveTools = listOf(namedTool("click"), namedTool("type"), namedTool("finished"))
        )

        val result = runBlocking { executor.executeStrategy(strategy) }

        result.shouldBeInstanceOf<ExecutionResult.Failed>()
        runtimeCalls shouldBe 2
        coVerify(exactly = 2) { runtime.runAgent(any(), any()) }
    }

    test("executor should stop at verify milestone after post-action input-only progress") {
        val runtime = mockk<AgentRuntime>()
        val worldStateManager = mockk<WorldStateManager>(relaxed = true)
        val strategy = richerMessageStrategy()

        var state = WorldState(
            currentApp = "com.ss.android.ugc.aweme.lite",
            pageAffordances = listOf("has_bottom_composer", "has_send_button", "has_back_nav"),
            visibleTexts = listOf("晓哥", "聊天"),
            actionCandidates = listOf("[INPUT] \"发送消息\" -> point(383, 961)"),
            recentActions = listOf(
                ActionRecord(
                    action = "click(point=<point>500 673</point>)",
                    result = "已进入聊天",
                    pageSignatureBefore = "message_list_before",
                    pageSignatureAfter = "chat_before_input",
                    timestamp = 1L
                )
            ),
            recentOutcomes = listOf(
                ActionOutcome(
                    toolName = "click",
                    actionText = "click(point=<point>500 673</point>)",
                    resultCode = ActionResultCode.SUCCESS,
                    pageSignatureBefore = "message_list_before",
                    pageSignatureAfter = "chat_before_input",
                    timestamp = 1L
                )
            ),
            strategy = strategy.strategy,
        )
        every { worldStateManager.getState() } answers { state }
        coEvery { worldStateManager.refreshObservation() } returns Unit
        every { worldStateManager.getResolvedTargetPackage() } returns null
        every { worldStateManager.isStuck(any()) } returns false

        var runtimeCalls = 0
        coEvery { runtime.runAgent(any(), any()) } answers {
            runtimeCalls += 1
            when (runtimeCalls) {
                1 -> {
                    state = state.copy(
                        visibleTexts = listOf("晓哥", "聊天", "你好", "发送消息"),
                        actionCandidates = listOf(
                            "[INPUT] \"发送消息\" -> point(383, 961)",
                            "[BUTTON] \"M\" -> point(897, 961)"
                        ),
                        recentActions = state.recentActions + ActionRecord(
                            action = "type(text=你好)",
                            result = "已输入文本: \"你好\"",
                            pageSignatureBefore = "chat_before_input",
                            pageSignatureAfter = "chat_after_input",
                            timestamp = 2L
                        ),
                        recentOutcomes = state.recentOutcomes + ActionOutcome(
                            toolName = "type",
                            actionText = "type(text=你好)",
                            resultCode = ActionResultCode.SUCCESS,
                            pageSignatureBefore = "chat_before_input",
                            pageSignatureAfter = "chat_after_input",
                            timestamp = 2L
                        )
                    )
                    AgentResult.ToolCallResult("type", "已输入文本: \"你好\"")
                }

                else -> AgentResult.Error("stop_after_post_action_input_progress")
            }
        }

        val executor = testExecutor(
            runtime = runtime,
            worldStateManager = worldStateManager,
            primitiveTools = listOf(namedTool("click"), namedTool("type"), namedTool("finished"))
        )

        val result = runBlocking { executor.executeStrategy(strategy) }

        result.shouldBeInstanceOf<ExecutionResult.Failed>()
        runtimeCalls shouldBe 2
        coVerify(exactly = 2) { runtime.runAgent(any(), any()) }
    }
}) {
    companion object {
        private fun testStrategy(): Strategy {
            return Strategy(
                goal = "给好友发送消息",
                app = "com.ss.android.ugc.aweme.lite",
                strategy = listOf("进入消息页", "进入会话", "发送消息"),
                originalInstruction = "给晓哥发送你好"
            )
        }

        private fun richerMessageStrategy(): Strategy {
            return Strategy(
                goal = "给好友发送消息",
                app = "com.ss.android.ugc.aweme.lite",
                strategy = listOf(
                    "打开目标应用并到达可定位好友的状态",
                    "定位好友晓哥并进入可发送消息的会话状态",
                    "输入消息内容并保持待发送状态",
                    "完成消息发送并确认发送成功"
                ),
                originalInstruction = "给晓哥发送你好"
            )
        }

        private fun finishReadyState(strategy: Strategy): WorldState {
            return WorldState(
                currentApp = "com.ss.android.ugc.aweme.lite",
                strategy = strategy.strategy,
                recentActions = listOf(
                    ActionRecord(
                        action = "click(point=<point>897 961</point>)",
                        result = "已点击发送",
                        pageSignatureBefore = "chat_before_send",
                        pageSignatureAfter = "chat_after_send",
                        timestamp = 1L
                    )
                ),
                recentOutcomes = listOf(
                    ActionOutcome(
                        toolName = "click",
                        actionText = "click(point=<point>897 961</point>)",
                        resultCode = ActionResultCode.SUCCESS,
                        pageSignatureBefore = "chat_before_send",
                        pageSignatureAfter = "chat_after_send",
                        timestamp = 1L
                    )
                )
            )
        }

        private fun mockWorldStateManager(): WorldStateManager {
            val manager = mockk<WorldStateManager>(relaxed = true)
            var state = WorldState(
                currentApp = "com.ss.android.ugc.aweme.lite",
                strategy = listOf("进入消息页", "进入会话", "发送消息"),
                taskHistoryActions = listOf(
                    ActionRecord(
                        action = "click(point=<point>120 980</point>)",
                        result = "已点击",
                        pageSignatureBefore = "before",
                        pageSignatureAfter = "after",
                        timestamp = 1L
                    )
                ),
                recentActions = listOf(
                    ActionRecord(
                        action = "click",
                        result = "success",
                        pageSignatureBefore = "sig-before",
                        pageSignatureAfter = "sig-after",
                        timestamp = 2L
                    )
                ),
                recentOutcomes = listOf(
                    ActionOutcome(
                        toolName = "click",
                        actionText = "click(point=<point>100 200</point>)",
                        resultCode = ActionResultCode.SUCCESS,
                        pageSignatureBefore = "sig-before",
                        pageSignatureAfter = "sig-after",
                        timestamp = 2L
                    )
                )
            )
            every { manager.setStrategy(any(), any()) } answers {
                state = state.copy(goal = firstArg(), strategy = secondArg())
            }
            every { manager.getState() } answers { state }
            coEvery { manager.refreshObservation() } returns Unit
            every { manager.getResolvedTargetPackage() } returns null
            every { manager.isStuck(any()) } returns false
            return manager
        }
    }
}
