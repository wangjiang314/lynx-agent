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

import com.juwan.lynx.memory.TaskResultArchive
import com.juwan.lynx.state.ActionResultCode
import com.juwan.lynx.state.ToolResult
import com.juwan.lynx.state.VerifierDecision
import com.juwan.lynx.state.WorldState
import com.juwan.lynx.state.WorldStateManager
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking

/**
 * Property-based tests for AgentOrchestrator.
 *
 * Aligned with current orchestrator behavior:
 * - strategy passed to executor comes from WorldState after setStrategy()
 * - terminal state persistence + completion marking are part of normal flow
 */
class AgentOrchestratorPropertyTest : StringSpec({

    "Property 7a: finish proposal returns Success only after verifier accepts" {
        checkAll(10, Arb.string(1..50)) { instruction ->
            val planner = mockk<PlannerAgent>()
            val executor = mockk<ExecutorAgent>()
            val verifier = mockk<VerifierAgent>()
            val worldStateManager = mockk<WorldStateManager>()
            val taskResultArchive = mockk<TaskResultArchive>()
            val statusUpdates = mutableListOf<AgentStatus>()

            val plannedStrategy = Strategy(
                goal = "planned-goal",
                app = "com.test.app",
                strategy = listOf("step1", "step2")
            )

            val worldStateAfterSet = WorldState(
                currentApp = plannedStrategy.app,
                goal = plannedStrategy.goal,
                strategy = plannedStrategy.strategy,
                recentActions = emptyList()
            )
            every { worldStateManager.resetForNewTask() } returns Unit
            coEvery { worldStateManager.refreshObservation() } returns Unit
            coEvery { worldStateManager.recordVerifierDecision(any()) } returns Unit
            coEvery { worldStateManager.persistTerminalState(any()) } returns Unit
            coEvery { taskResultArchive.persistTaskResult(any(), any(), any()) } returns Unit
            coEvery { planner.createStrategy(instruction) } returns plannedStrategy
            every { worldStateManager.setStrategy(plannedStrategy) } returns Unit
            every { worldStateManager.getState() } returns worldStateAfterSet
            coEvery { verifier.verify(any(), any(), any()) } returns acceptedVerifierDecision()
            coEvery {
                executor.executeStrategy(
                    plannedStrategy,
                    any()
                )
            } returns ExecutionResult.FinishProposed("FINISH_TOOL_EXECUTED: done")

            val orchestrator = AgentOrchestrator(
                planner = planner,
                executor = executor,
                verifierAgent = verifier,
                worldStateManager = worldStateManager,
                taskResultArchive = taskResultArchive,
                onStatusUpdate = { statusUpdates.add(it) }
            )

            val result = runBlocking { orchestrator.run(instruction) }

            result.shouldBeInstanceOf<TaskResult.Success>()
            coVerify(exactly = 1) { planner.createStrategy(instruction) }
            coVerify(exactly = 1) {
                executor.executeStrategy(
                    plannedStrategy,
                    any()
                )
            }
            coVerify(exactly = 1) { verifier.verify(any(), any(), any()) }
        }
    }

    "app launch finish proposal should rely on verifier instead of deterministic shortcut" {
        val planner = mockk<PlannerAgent>()
        val executor = mockk<ExecutorAgent>()
        val verifier = mockk<VerifierAgent>()
        val worldStateManager = mockk<WorldStateManager>()
        val taskResultArchive = mockk<TaskResultArchive>()
        val statusUpdates = mutableListOf<AgentStatus>()

        val strategy = Strategy(
            goal = "打开设置应用",
            app = "Settings",
            strategy = listOf("打开设置应用"),
            originalInstruction = "Open Settings app"
        )
        val stateAfterLaunch = WorldState(
            currentApp = "com.android.settings",
            goal = strategy.goal,
            targetApp = strategy.app,
            strategy = strategy.strategy,
            resolvedTargetPackage = "com.android.settings",
            recentToolResults = listOf(
                ToolResult(
                    toolName = "open_app",
                    actionText = "open_app(app_name=Settings)",
                    status = ActionResultCode.SUCCESS,
                    changed = true,
                    rawSignatureChanged = true,
                    pageSignatureBefore = "lynx",
                    pageSignatureAfter = "settings",
                    message = "已启动应用: 设置 package_name=com.android.settings"
                )
            )
        )

        every { worldStateManager.resetForNewTask() } returns Unit
        coEvery { worldStateManager.refreshObservation() } returns Unit
        coEvery { worldStateManager.recordVerifierDecision(any()) } returns Unit
        coEvery { worldStateManager.persistTerminalState(any()) } returns Unit
        coEvery { taskResultArchive.persistTaskResult(any(), any(), any()) } returns Unit
        coEvery { planner.createStrategy("Open Settings app") } returns strategy
        every { worldStateManager.setStrategy(strategy) } returns Unit
        every { worldStateManager.getState() } returns stateAfterLaunch
        coEvery {
            executor.executeStrategy(strategy, any())
        } returns ExecutionResult.FinishProposed(
            "FINISH_TOOL_EXECUTED: app_launch_goal_satisfied current_app=com.android.settings"
        )
        coEvery { verifier.verify(any(), any(), any()) } returns acceptedVerifierDecision()

        val orchestrator = AgentOrchestrator(
            planner = planner,
            executor = executor,
            verifierAgent = verifier,
            worldStateManager = worldStateManager,
            taskResultArchive = taskResultArchive,
            onStatusUpdate = { statusUpdates.add(it) }
        )

        val result = runBlocking { orchestrator.run("Open Settings app") }

        result.shouldBeInstanceOf<TaskResult.Success>()
        coVerify(exactly = 1) { verifier.verify(any(), any(), any()) }
    }

    "settings page finish proposal should rely on verifier instead of runtime page hardcoding" {
        val planner = mockk<PlannerAgent>()
        val executor = mockk<ExecutorAgent>()
        val verifier = mockk<VerifierAgent>()
        val worldStateManager = mockk<WorldStateManager>()
        val taskResultArchive = mockk<TaskResultArchive>()
        val statusUpdates = mutableListOf<AgentStatus>()

        val strategy = Strategy(
            goal = "进入设置中的 WLAN 页面",
            app = "com.android.settings",
            strategy = listOf("打开设置", "进入 WLAN 页面"),
            originalInstruction = "打开设置并进入 WLAN 页面"
        )
        val state = WorldState(
            currentApp = "com.android.settings",
            goal = strategy.goal,
            targetApp = strategy.app,
            strategy = strategy.strategy,
            uiTexts = listOf("WLAN", "更多 WLAN 设置", "WLAN 直连", "已开启")
        )

        every { worldStateManager.resetForNewTask() } returns Unit
        coEvery { worldStateManager.refreshObservation() } returns Unit
        coEvery { worldStateManager.recordVerifierDecision(any()) } returns Unit
        coEvery { worldStateManager.persistTerminalState(any()) } returns Unit
        coEvery { taskResultArchive.persistTaskResult(any(), any(), any()) } returns Unit
        coEvery { planner.createStrategy("打开设置并进入 WLAN 页面") } returns strategy
        every { worldStateManager.setStrategy(strategy) } returns Unit
        every { worldStateManager.getState() } returns state
        coEvery {
            executor.executeStrategy(strategy, any())
        } returns ExecutionResult.FinishProposed(
            "FINISH_TOOL_EXECUTED: current screen satisfies the WLAN page goal"
        )
        coEvery { verifier.verify(any(), any(), any()) } returns acceptedVerifierDecision()

        val orchestrator = AgentOrchestrator(
            planner = planner,
            executor = executor,
            verifierAgent = verifier,
            worldStateManager = worldStateManager,
            taskResultArchive = taskResultArchive,
            onStatusUpdate = { statusUpdates.add(it) }
        )

        val result = runBlocking { orchestrator.run("打开设置并进入 WLAN 页面") }

        result.shouldBeInstanceOf<TaskResult.Success>()
        coVerify(exactly = 1) { verifier.verify(any(), any(), any()) }
    }

    "settings home WLAN status text should not satisfy deterministic WLAN page postcondition" {
        val planner = mockk<PlannerAgent>()
        val executor = mockk<ExecutorAgent>()
        val verifier = mockk<VerifierAgent>()
        val worldStateManager = mockk<WorldStateManager>()
        val taskResultArchive = mockk<TaskResultArchive>()
        val statusUpdates = mutableListOf<AgentStatus>()

        val strategy = Strategy(
            goal = "进入设置中的 WLAN 页面",
            app = "com.android.settings",
            strategy = listOf("打开设置", "进入 WLAN 页面"),
            originalInstruction = "打开设置并进入 WLAN 页面"
        )
        val state = WorldState(
            currentApp = "com.android.settings",
            goal = strategy.goal,
            targetApp = strategy.app,
            strategy = strategy.strategy,
            uiTexts = listOf("设置", "WLAN", "蓝牙", "已开启")
        )

        every { worldStateManager.resetForNewTask() } returns Unit
        coEvery { worldStateManager.refreshObservation() } returns Unit
        coEvery { worldStateManager.recordVerifierDecision(any()) } returns Unit
        coEvery { worldStateManager.persistTerminalState(any()) } returns Unit
        coEvery { taskResultArchive.persistTaskResult(any(), any(), any()) } returns Unit
        coEvery { planner.createStrategy("打开设置并进入 WLAN 页面") } returns strategy
        every { worldStateManager.setStrategy(strategy) } returns Unit
        every { worldStateManager.getState() } returns state
        coEvery {
            executor.executeStrategy(strategy, any())
        } returns ExecutionResult.FinishProposed(
            "FINISH_TOOL_EXECUTED: model believes WLAN page is complete"
        )
        coEvery { verifier.verify(any(), any(), any()) } returns acceptedVerifierDecision()

        val orchestrator = AgentOrchestrator(
            planner = planner,
            executor = executor,
            verifierAgent = verifier,
            worldStateManager = worldStateManager,
            taskResultArchive = taskResultArchive,
            onStatusUpdate = { statusUpdates.add(it) }
        )

        val result = runBlocking { orchestrator.run("打开设置并进入 WLAN 页面") }

        result.shouldBeInstanceOf<TaskResult.Success>()
        coVerify(exactly = 1) { verifier.verify(any(), any(), any()) }
    }

    "bluetooth page should not satisfy deterministic WLAN page postcondition" {
        val planner = mockk<PlannerAgent>()
        val executor = mockk<ExecutorAgent>()
        val verifier = mockk<VerifierAgent>()
        val worldStateManager = mockk<WorldStateManager>()
        val taskResultArchive = mockk<TaskResultArchive>()
        val statusUpdates = mutableListOf<AgentStatus>()

        val strategy = Strategy(
            goal = "进入设置中的 WLAN 页面",
            app = "com.android.settings",
            strategy = listOf("打开设置", "进入 WLAN 页面"),
            originalInstruction = "打开设置并进入 WLAN 页面"
        )
        val state = WorldState(
            currentApp = "com.android.settings",
            goal = strategy.goal,
            targetApp = strategy.app,
            strategy = strategy.strategy,
            uiTexts = listOf("蓝牙", "设备名称", "可用设备", "WLAN")
        )

        every { worldStateManager.resetForNewTask() } returns Unit
        coEvery { worldStateManager.refreshObservation() } returns Unit
        coEvery { worldStateManager.recordVerifierDecision(any()) } returns Unit
        coEvery { worldStateManager.persistTerminalState(any()) } returns Unit
        coEvery { taskResultArchive.persistTaskResult(any(), any(), any()) } returns Unit
        coEvery { planner.createStrategy("打开设置并进入 WLAN 页面") } returns strategy
        every { worldStateManager.setStrategy(strategy) } returns Unit
        every { worldStateManager.getState() } returns state
        coEvery {
            executor.executeStrategy(strategy, any())
        } returns ExecutionResult.FinishProposed(
            "FINISH_TOOL_EXECUTED: model believes WLAN page is complete"
        )
        coEvery { verifier.verify(any(), any(), any()) } returns acceptedVerifierDecision()

        val orchestrator = AgentOrchestrator(
            planner = planner,
            executor = executor,
            verifierAgent = verifier,
            worldStateManager = worldStateManager,
            taskResultArchive = taskResultArchive,
            onStatusUpdate = { statusUpdates.add(it) }
        )

        val result = runBlocking { orchestrator.run("打开设置并进入 WLAN 页面") }

        result.shouldBeInstanceOf<TaskResult.Success>()
        coVerify(exactly = 1) { verifier.verify(any(), any(), any()) }
    }

    "Property 7b: Structural stuck escalates to planner reflection without exhausting local recovery" {
        checkAll(10, Arb.string(1..50)) { instruction ->
            val planner = mockk<PlannerAgent>()
            val executor = mockk<ExecutorAgent>()
            val verifier = mockk<VerifierAgent>(relaxed = true)
            val worldStateManager = mockk<WorldStateManager>()
            val taskResultArchive = mockk<TaskResultArchive>()
            val statusUpdates = mutableListOf<AgentStatus>()

            val plannedStrategy = Strategy(
                goal = "planned-goal",
                app = "com.test.app",
                strategy = listOf("step1", "step2")
            )

            val worldState = WorldState(
                currentApp = plannedStrategy.app,
                goal = plannedStrategy.goal,
                strategy = plannedStrategy.strategy,
                recentActions = emptyList()
            )
            every { worldStateManager.resetForNewTask() } returns Unit
            coEvery { worldStateManager.persistTerminalState(any()) } returns Unit
            coEvery { taskResultArchive.persistTaskResult(any(), any(), any()) } returns Unit
            coEvery { planner.createStrategy(instruction) } returns plannedStrategy
            every { worldStateManager.setStrategy(plannedStrategy) } returns Unit
            every { worldStateManager.getState() } returns worldState
            coEvery {
                executor.executeStrategy(
                    plannedStrategy,
                    any()
                )
            } returns ExecutionResult.Stuck
            coEvery { planner.reflect(any(), any()) } returns PlannerDecision.Abort("stuck")

            val orchestrator = AgentOrchestrator(
                planner = planner,
                executor = executor,
                verifierAgent = verifier,
                worldStateManager = worldStateManager,
                taskResultArchive = taskResultArchive,
                onStatusUpdate = { statusUpdates.add(it) }
            )

            val result = runBlocking { orchestrator.run(instruction) }

            result.shouldBeInstanceOf<TaskResult.Failure>()
            coVerify(exactly = 1) {
                executor.executeStrategy(
                    plannedStrategy,
                    any()
                )
            }
            coVerify(exactly = 1) { planner.reflect(any(), any()) }
        }
    }

    "Property 7c: Generic failure escalates to planner reflection before abort" {
        checkAll(10, Arb.string(1..50)) { instruction ->
            val planner = mockk<PlannerAgent>()
            val executor = mockk<ExecutorAgent>()
            val verifier = mockk<VerifierAgent>(relaxed = true)
            val worldStateManager = mockk<WorldStateManager>()
            val taskResultArchive = mockk<TaskResultArchive>()
            val statusUpdates = mutableListOf<AgentStatus>()

            val plannedStrategy = Strategy(
                goal = "planned-goal",
                app = "com.test.app",
                strategy = listOf("step1", "step2")
            )

            val worldState = WorldState(
                currentApp = plannedStrategy.app,
                goal = plannedStrategy.goal,
                strategy = plannedStrategy.strategy,
                recentActions = emptyList()
            )
            every { worldStateManager.resetForNewTask() } returns Unit
            coEvery { worldStateManager.persistTerminalState(any()) } returns Unit
            coEvery { taskResultArchive.persistTaskResult(any(), any(), any()) } returns Unit
            coEvery { planner.createStrategy(instruction) } returns plannedStrategy
            every { worldStateManager.setStrategy(plannedStrategy) } returns Unit
            every { worldStateManager.getState() } returns worldState
            coEvery {
                executor.executeStrategy(
                    plannedStrategy,
                    any()
                )
            } returns ExecutionResult.Failed(
                reason = "still failing",
                code = ExecutionFailureCode.UNKNOWN
            )
            coEvery { planner.reflect(any(), any()) } returns PlannerDecision.Abort("still failing")

            val orchestrator = AgentOrchestrator(
                planner = planner,
                executor = executor,
                verifierAgent = verifier,
                worldStateManager = worldStateManager,
                taskResultArchive = taskResultArchive,
                onStatusUpdate = { statusUpdates.add(it) }
            )

            val result = runBlocking { orchestrator.run(instruction) }

            result.shouldBeInstanceOf<TaskResult.Failure>()
            coVerify(exactly = 1) {
                executor.executeStrategy(
                    plannedStrategy,
                    any()
                )
            }
            coVerify(exactly = 1) { planner.reflect(any(), any()) }
        }
    }

    "transient model empty response should recover locally without planner reflection" {
        val planner = mockk<PlannerAgent>()
        val executor = mockk<ExecutorAgent>()
        val verifier = mockk<VerifierAgent>()
        val worldStateManager = mockk<WorldStateManager>()
        val taskResultArchive = mockk<TaskResultArchive>()
        val statusUpdates = mutableListOf<AgentStatus>()

        val plannedStrategy = Strategy(
            goal = "进入设置中的 WLAN 页面",
            app = "com.android.settings",
            strategy = listOf("进入设置", "进入 WLAN 页面")
        )
        val worldState = WorldState(
            currentApp = plannedStrategy.app,
            goal = plannedStrategy.goal,
            strategy = plannedStrategy.strategy,
            recentActions = emptyList()
        )

        every { worldStateManager.resetForNewTask() } returns Unit
        coEvery { worldStateManager.refreshObservation() } returns Unit
        coEvery { worldStateManager.recordVerifierDecision(any()) } returns Unit
        coEvery { worldStateManager.persistTerminalState(any()) } returns Unit
        coEvery { taskResultArchive.persistTaskResult(any(), any(), any()) } returns Unit
        coEvery { planner.createStrategy("打开设置并进入 WLAN 页面") } returns plannedStrategy
        every { worldStateManager.setStrategy(plannedStrategy) } returns Unit
        every { worldStateManager.getState() } returns worldState
        var executorCalls = 0
        coEvery { executor.executeStrategy(plannedStrategy, any()) } answers {
            executorCalls += 1
            if (executorCalls == 1) {
                ExecutionResult.Failed(
                    reason = "LLM 连续空响应，执行层重试用尽",
                    code = ExecutionFailureCode.MODEL_EMPTY_RESPONSE
                )
            } else {
                ExecutionResult.FinishProposed("FINISH_TOOL_EXECUTED: done")
            }
        }
        coEvery { verifier.verify(any(), any(), any()) } returns acceptedVerifierDecision()

        val orchestrator = AgentOrchestrator(
            planner = planner,
            executor = executor,
            verifierAgent = verifier,
            worldStateManager = worldStateManager,
            taskResultArchive = taskResultArchive,
            onStatusUpdate = { statusUpdates.add(it) }
        )

        val result = runBlocking { orchestrator.run("打开设置并进入 WLAN 页面") }

        result.shouldBeInstanceOf<TaskResult.Success>()
        coVerify(exactly = 2) { executor.executeStrategy(plannedStrategy, any()) }
        coVerify(exactly = 0) { planner.reflect(any(), any()) }
    }

    "Property 7d: Structural failure can trigger replan and succeed" {
        checkAll(10, Arb.string(1..50)) { instruction ->
            val planner = mockk<PlannerAgent>()
            val executor = mockk<ExecutorAgent>()
            val verifier = mockk<VerifierAgent>()
            val worldStateManager = mockk<WorldStateManager>()
            val taskResultArchive = mockk<TaskResultArchive>()
            val statusUpdates = mutableListOf<AgentStatus>()

            val strategy1 = Strategy(
                goal = "goal-1",
                app = "com.test.app",
                strategy = listOf("s1", "s2")
            )
            val strategy2 = Strategy(
                goal = "goal-2",
                app = "com.test.app",
                strategy = listOf("s3", "s4")
            )
            val reconciledStrategy2 = strategy2.copy(originalInstruction = strategy1.goal)

            val stateForFirstAttempt = WorldState(
                currentApp = strategy1.app,
                goal = strategy1.goal,
                strategy = strategy1.strategy,
                recentActions = emptyList()
            )
            val stateForSecondAttempt = WorldState(
                currentApp = strategy2.app,
                goal = strategy2.goal,
                strategy = strategy2.strategy,
                recentActions = emptyList()
            )

            var stateReadCount = 0
            every { worldStateManager.resetForNewTask() } returns Unit
            coEvery { worldStateManager.refreshObservation() } returns Unit
            coEvery { worldStateManager.recordVerifierDecision(any()) } returns Unit
            coEvery { worldStateManager.persistTerminalState(any()) } returns Unit
            coEvery { taskResultArchive.persistTaskResult(any(), any(), any()) } returns Unit
            coEvery { planner.createStrategy(instruction) } returns strategy1
            every { worldStateManager.setStrategy(strategy1) } returns Unit
            every { worldStateManager.setStrategy(reconciledStrategy2) } returns Unit
            every { worldStateManager.getState() } answers {
                if (stateReadCount++ == 0) stateForFirstAttempt else stateForSecondAttempt
            }

            coEvery {
                executor.executeStrategy(
                    Strategy(stateForFirstAttempt.goal, stateForFirstAttempt.currentApp ?: "", stateForFirstAttempt.strategy)
                    ,
                    any()
                )
            } returns ExecutionResult.Failed(
                reason = "app ambiguous",
                code = ExecutionFailureCode.APP_AMBIGUOUS
            )

            coEvery { planner.reflect(any(), any()) } returns PlannerDecision.Replan(strategy2)
            every { planner.reconcileReplannedStrategy(strategy1, strategy2) } returns reconciledStrategy2
            coEvery { verifier.verify(any(), any(), any()) } returns acceptedVerifierDecision()

            coEvery {
                executor.executeStrategy(
                    match { executed ->
                        executed.goal == reconciledStrategy2.goal &&
                            executed.app == reconciledStrategy2.app &&
                            executed.strategy == reconciledStrategy2.strategy &&
                            executed.originalInstruction == strategy1.goal
                    },
                    any()
                )
            } returns ExecutionResult.FinishProposed("FINISH_TOOL_EXECUTED: done")

            val orchestrator = AgentOrchestrator(
                planner = planner,
                executor = executor,
                verifierAgent = verifier,
                worldStateManager = worldStateManager,
                taskResultArchive = taskResultArchive,
                onStatusUpdate = { statusUpdates.add(it) }
            )

            val result = runBlocking { orchestrator.run(instruction) }

            result.shouldBeInstanceOf<TaskResult.Success>()
            coVerify(exactly = 1) { planner.reflect(any(), any()) }
            coVerify(exactly = 1) {
                executor.executeStrategy(
                    match { executed ->
                        executed.goal == reconciledStrategy2.goal &&
                            executed.app == reconciledStrategy2.app &&
                            executed.strategy == reconciledStrategy2.strategy &&
                            executed.originalInstruction == strategy1.goal
                    },
                    any()
                )
            }
        }
    }
})

private fun acceptedVerifierDecision(): VerifierDecision {
    return VerifierDecision(
        complete = true,
        confidence = 0.95f,
        evidence = listOf("observable completion evidence"),
        missing = emptyList(),
        nextHint = ""
    )
}
