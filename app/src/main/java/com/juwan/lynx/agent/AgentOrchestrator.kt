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

import android.util.Log
import com.juwan.lynx.memory.TaskResultArchive
import com.juwan.lynx.state.WorldStateManager
import kotlinx.coroutines.CancellationException

/**
 * Result of task execution.
 */
sealed class TaskResult {
    data class Success(val summary: String) : TaskResult()
    data class Failure(val reason: String) : TaskResult()
}

/**
 * AgentOrchestrator manages the task flow and supports structured status updates for visualization.
 */
class AgentOrchestrator(
    private val planner: PlannerAgent,
    private val executor: ExecutorAgent,
    private val verifierAgent: VerifierAgent,
    private val completionGate: CompletionGate = CompletionGate(),
    private val humanAssistanceGate: HumanAssistanceGate? = null,
    private val worldStateManager: WorldStateManager,
    private val taskResultArchive: TaskResultArchive,
    private val onStatusUpdate: (AgentStatus) -> Unit
) {
    private val tag = "AgentOrchestrator"

    companion object {
        private const val MAX_RECOVERY_BUDGET = 5
        private const val MAX_FINISH_REJECTION_BUDGET = 4
        private const val MAX_EXECUTE_ROUNDS = 60
    }

    suspend fun run(
        userInstruction: String? = null,
        traceRunId: String? = null,
        traceScenarioId: String? = null
    ): TaskResult {
        try {
            FlowTraceLogger.start(
                userInstruction = userInstruction,
                runId = traceRunId,
                scenarioId = traceScenarioId
            )
            val initialStrategy = if (userInstruction != null) {
                updateStatus(AgentPhase.PLANNING, "正在规划任务: $userInstruction")
                worldStateManager.resetForNewTask()

                planner.createStrategy(userInstruction).also { strategy ->
                    worldStateManager.setStrategy(strategy)
                    FlowTraceLogger.event(
                        stage = "planner_strategy_created",
                        kv = mapOf(
                            "goal" to strategy.goal,
                            "app" to strategy.app,
                            "strategy_size" to strategy.strategy.size
                        )
                    )
                    val planSummary = buildPlanSummary(strategy)
                    updateStatus(
                        AgentPhase.PLANNING,
                        "规划完成，目标应用: ${strategy.app}",
                        thought = planSummary
                    )
                }
            } else {
                updateStatus(AgentPhase.PLANNING, "尝试恢复任务...")
                val restored = worldStateManager.restoreState()
                if (!restored) {
                    updateStatus(AgentPhase.FAILED, "未找到可恢复的任务")
                    FlowTraceLogger.finish(status = "failed", reason = "no_task_to_resume")
                    return TaskResult.Failure("未找到可恢复的任务")
                }
                updateStatus(AgentPhase.PLANNING, "任务已恢复: ${worldStateManager.getState().goal}")
                FlowTraceLogger.event(
                    stage = "task_resumed",
                    kv = mapOf(
                        "goal" to worldStateManager.getState().goal,
                        "app" to (worldStateManager.getState().currentApp ?: "unknown")
                    )
                )

                val restoredState = worldStateManager.getState()
                Strategy(
                    goal = restoredState.goal,
                    app = restoredState.currentApp ?: "",
                    strategy = restoredState.strategy,
                    originalInstruction = restoredState.goal.take(300)
                )
            }

            return executeWithRetries(initialStrategy)
        } catch (e: CancellationException) {
            Log.i(tag, "Orchestrator cancelled: ${e.message}")
            runCatching { worldStateManager.persistTerminalState(markCompleted = false) }
            runCatching {
                FlowTraceLogger.finish(status = "cancelled", reason = e.message ?: "cancelled")
            }
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Orchestrator error", e)
            updateStatus(AgentPhase.FAILED, "执行异常: ${e.message}")
            runCatching { worldStateManager.persistTerminalState(markCompleted = false) }
            FlowTraceLogger.finish(status = "failed", reason = "orchestrator_exception:${e.message}")
            return TaskResult.Failure("执行异常: ${e.message}")
        }
    }

    private suspend fun executeWithRetries(initialStrategy: Strategy): TaskResult {
        var recoveryBudgetRemaining = MAX_RECOVERY_BUDGET
        var finishRejectionBudgetRemaining = MAX_FINISH_REJECTION_BUDGET
        var executeRoundsRemaining = MAX_EXECUTE_ROUNDS
        var strategy = initialStrategy
        var pendingRecoveryHint: String? = null

        while (recoveryBudgetRemaining >= 0 && executeRoundsRemaining > 0) {
            executeRoundsRemaining--
            val kv = linkedMapOf<String, Any?>(
                "recovery_budget_left" to recoveryBudgetRemaining,
                "finish_rejection_budget_left" to finishRejectionBudgetRemaining,
                "execute_rounds_left" to executeRoundsRemaining
            )
            FlowTraceLogger.event(stage = "execute_round_start", kv = kv)
            updateStatus(
                AgentPhase.EXECUTING,
                "正在执行任务",
                thought = "当前目标: ${strategy.goal}",
                action = "围绕可观察事实与可用能力继续推进",
                observation = buildExecutionObservation()
            )

            val result = executor.executeStrategy(strategy, pendingRecoveryHint)
            pendingRecoveryHint = null
            FlowTraceLogger.event(
                stage = "executor_result",
                kv = mapOf(
                    "type" to result::class.simpleName.orEmpty(),
                    "detail" to when (result) {
                        is ExecutionResult.FinishProposed -> result.output
                        is ExecutionResult.InsufficientProgress -> result.reason
                        is ExecutionResult.Failed -> result.reason
                        else -> "ok"
                    },
                    "code" to when (result) {
                        is ExecutionResult.InsufficientProgress -> result.code.name
                        is ExecutionResult.Failed -> result.code.name
                        else -> ExecutionFailureCode.NONE.name
                    }
                )
            )

            when (result) {
                is ExecutionResult.FinishProposed -> {
                    val verificationResult = handleFinishProposal(
                        strategy = strategy,
                        finishProposal = result.output
                    )
                    when (verificationResult) {
                        is FinishProposalResolution.Accepted -> {
                            return completeTask(strategy, verificationResult.summary)
                        }
                        is FinishProposalResolution.Rejected -> {
                            finishRejectionBudgetRemaining--
                            if (tryHumanAssistance(verificationResult.reason)) {
                                pendingRecoveryHint = "用户已协助处理外部阻塞。请刷新观察后继续推进原目标，必要时重新提出 finished。"
                                continue
                            }
                            pendingRecoveryHint = verificationResult.nextHint
                            if (finishRejectionBudgetRemaining > 0) {
                                updateStatus(
                                    AgentPhase.EXECUTING,
                                    "Verifier 未接受完成，继续收集证据",
                                    thought = verificationResult.reason,
                                    action = pendingRecoveryHint,
                                    observation = buildExecutionObservation()
                                )
                                continue
                            }
                            when (val resolution = handlePlannerReflection(
                                strategy,
                                verificationResult.reason,
                                recoveryBudgetRemaining
                            )) {
                                is ReflectionResolution.Replanned -> {
                                    strategy = resolution.strategy
                                    pendingRecoveryHint = null
                                    recoveryBudgetRemaining = resolution.recoveryBudgetRemaining
                                    finishRejectionBudgetRemaining = MAX_FINISH_REJECTION_BUDGET
                                }
                                is ReflectionResolution.Aborted -> return resolution.result
                            }
                        }
                    }
                }
                is ExecutionResult.AllCompleted -> {
                    val verificationResult = handleFinishProposal(
                        strategy = strategy,
                        finishProposal = "legacy_all_completed"
                    )
                    when (verificationResult) {
                        is FinishProposalResolution.Accepted -> {
                            return completeTask(strategy, verificationResult.summary)
                        }
                        is FinishProposalResolution.Rejected -> {
                            finishRejectionBudgetRemaining--
                            pendingRecoveryHint = verificationResult.nextHint
                            if (finishRejectionBudgetRemaining > 0) {
                                continue
                            }
                            when (val resolution = handlePlannerReflection(
                                strategy,
                                verificationResult.reason,
                                recoveryBudgetRemaining
                            )) {
                                is ReflectionResolution.Replanned -> {
                                    strategy = resolution.strategy
                                    pendingRecoveryHint = null
                                    recoveryBudgetRemaining = resolution.recoveryBudgetRemaining
                                    finishRejectionBudgetRemaining = MAX_FINISH_REJECTION_BUDGET
                                }
                                is ReflectionResolution.Aborted -> return resolution.result
                            }
                        }
                    }
                }
                is ExecutionResult.InsufficientProgress, is ExecutionResult.Stuck -> {
                    val reason = when (result) {
                        is ExecutionResult.InsufficientProgress -> result.reason
                        is ExecutionResult.Stuck -> "Executor 卡住，最近操作无效"
                        else -> "检测到进度停滞"
                    }
                    val failureCode = when (result) {
                        is ExecutionResult.InsufficientProgress -> result.code
                        is ExecutionResult.Stuck -> ExecutionFailureCode.STUCK_THRESHOLD
                        else -> ExecutionFailureCode.UNKNOWN
                    }
                    if (tryHumanAssistance(reason)) {
                        pendingRecoveryHint = "用户已处理外部阻塞。请基于最新屏幕继续原目标。"
                        continue
                    }
                    if (isFinishHandshakeFailure(failureCode)) {
                        if (recoveryBudgetRemaining > 0) {
                            recoveryBudgetRemaining--
                            pendingRecoveryHint = buildFinishRecoveryHint()
                            FlowTraceLogger.warn(
                                stage = "finish_handshake_recovery",
                                kv = mapOf(
                                    "reason" to reason,
                                    "recovery_budget_left" to recoveryBudgetRemaining
                                )
                            )
                            updateStatus(
                                AgentPhase.EXECUTING,
                                "完成握手失败，执行层恢复中",
                                thought = pendingRecoveryHint,
                                action = "继续当前策略，不触发 replan",
                                observation = buildExecutionObservation()
                            )
                            continue
                        }
                    }
                    if (shouldAttemptLocalExecutorRecovery(failureCode) && recoveryBudgetRemaining > 0) {
                        recoveryBudgetRemaining--
                        pendingRecoveryHint = "执行层局部恢复：$reason。请保持当前策略，换一种不同动作推进当前里程碑。"
                        FlowTraceLogger.warn(
                            stage = "executor_local_recovery",
                            kv = mapOf(
                                "reason" to reason,
                                "recovery_budget_left" to recoveryBudgetRemaining
                            )
                        )
                        updateStatus(
                            AgentPhase.EXECUTING,
                            "执行层局部恢复中",
                            thought = pendingRecoveryHint,
                            action = "不触发重规划，先在当前路径自恢复",
                            observation = buildExecutionObservation()
                        )
                        continue
                    }
                    if (!shouldEscalateToPlannerReflection(failureCode)) {
                        updateStatus(
                            AgentPhase.FAILED,
                            "执行失败，不升级反思: $reason",
                            observation = buildExecutionObservation()
                        )
                        persistTaskExperience(
                            strategy = strategy,
                            success = false,
                            summary = buildTaskSummary(strategy, detail = reason)
                        )
                        worldStateManager.persistTerminalState(markCompleted = false)
                        worldStateManager.resetForNewTask()
                        FlowTraceLogger.finish(
                            status = "failed",
                            reason = "executor_failure_no_reflection:${failureCode.name}:${reason}"
                        )
                        return TaskResult.Failure(reason)
                    }
                    updateStatus(
                        AgentPhase.REFLECTING,
                        "检测到结构性失败，升级反思: $reason",
                        observation = buildExecutionObservation()
                    )
                    when (val resolution = handlePlannerReflection(strategy, reason, recoveryBudgetRemaining)) {
                        is ReflectionResolution.Replanned -> {
                            strategy = resolution.strategy
                            pendingRecoveryHint = null
                            recoveryBudgetRemaining = resolution.recoveryBudgetRemaining
                        }
                        is ReflectionResolution.Aborted -> return resolution.result
                    }
                }
                is ExecutionResult.Failed -> {
                    val reason = result.reason
                    if (tryHumanAssistance(reason)) {
                        pendingRecoveryHint = "用户已处理外部阻塞。请基于最新屏幕继续原目标。"
                        continue
                    }
                    if (isTransientModelFailure(result.code, reason)) {
                        if (recoveryBudgetRemaining > 0) {
                            recoveryBudgetRemaining--
                            pendingRecoveryHint = buildTransientModelRecoveryHint(reason)
                            FlowTraceLogger.warn(
                                stage = "executor_transient_model_recovery",
                                kv = mapOf(
                                    "reason" to reason,
                                    "recovery_budget_left" to recoveryBudgetRemaining
                                )
                            )
                            updateStatus(
                                AgentPhase.EXECUTING,
                                "模型响应异常，沿用当前策略恢复",
                                thought = pendingRecoveryHint,
                                action = "刷新观察后继续，不触发重规划",
                                observation = buildExecutionObservation()
                            )
                            continue
                        }
                    }
                    if (isFinishHandshakeFailure(result.code)) {
                        if (recoveryBudgetRemaining > 0) {
                            recoveryBudgetRemaining--
                            pendingRecoveryHint = buildFinishRecoveryHint()
                            FlowTraceLogger.warn(
                                stage = "finish_handshake_recovery",
                                kv = mapOf(
                                    "reason" to reason,
                                    "recovery_budget_left" to recoveryBudgetRemaining
                                )
                            )
                            updateStatus(
                                AgentPhase.EXECUTING,
                                "完成握手失败，执行层恢复中",
                                thought = pendingRecoveryHint,
                                action = "继续当前策略，不触发 replan",
                                observation = buildExecutionObservation()
                            )
                            continue
                        }
                    }
                    if (!shouldEscalateToPlannerReflection(result.code)) {
                        updateStatus(
                            AgentPhase.FAILED,
                            "执行失败，不升级反思: $reason",
                            observation = buildExecutionObservation()
                        )
                        persistTaskExperience(
                            strategy = strategy,
                            success = false,
                            summary = buildTaskSummary(strategy, detail = reason)
                        )
                        worldStateManager.persistTerminalState(markCompleted = false)
                        worldStateManager.resetForNewTask()
                        FlowTraceLogger.finish(
                            status = "failed",
                            reason = "executor_failure_no_reflection:${result.code.name}:${reason}"
                        )
                        return TaskResult.Failure(reason)
                    }
                    updateStatus(
                        AgentPhase.REFLECTING,
                        "检测到结构性失败，正在反思: $reason",
                        observation = buildExecutionObservation()
                    )
                    when (val resolution = handlePlannerReflection(strategy, reason, recoveryBudgetRemaining)) {
                        is ReflectionResolution.Replanned -> {
                            strategy = resolution.strategy
                            pendingRecoveryHint = null
                            recoveryBudgetRemaining = resolution.recoveryBudgetRemaining
                        }
                        is ReflectionResolution.Aborted -> return resolution.result
                    }
                }
            }
        }
        updateStatus(
            AgentPhase.FAILED,
            "恢复预算已用尽",
            observation = buildExecutionObservation()
        )
        persistTaskExperience(
            strategy = strategy,
            success = false,
            summary = buildTaskSummary(strategy, detail = "恢复预算用尽")
        )
        worldStateManager.persistTerminalState(markCompleted = false)
        FlowTraceLogger.finish(status = "failed", reason = "recovery_budget_exhausted")
        return TaskResult.Failure("恢复预算已用尽")
    }

    private sealed class FinishProposalResolution {
        data class Accepted(val summary: String) : FinishProposalResolution()
        data class Rejected(
            val reason: String,
            val nextHint: String
        ) : FinishProposalResolution()
    }

    private suspend fun handleFinishProposal(
        strategy: Strategy,
        finishProposal: String
    ): FinishProposalResolution {
        updateStatus(
            AgentPhase.EXECUTING,
            "正在验证任务是否真正完成",
            thought = "finished 只是完成提议，开始读取最新屏幕证据",
            observation = buildExecutionObservation()
        )
        worldStateManager.refreshObservation()
        val state = worldStateManager.getState()
        val verifierDecision = verifierAgent.verify(
            strategy = strategy,
            state = state,
            finishProposal = finishProposal
        )
        worldStateManager.recordVerifierDecision(verifierDecision)
        FlowTraceLogger.event(
            stage = "verifier_decision",
            kv = mapOf(
                "complete" to verifierDecision.complete,
                "confidence" to verifierDecision.confidence,
                "evidence" to verifierDecision.evidence.joinToString(" | ").take(240),
                "missing" to verifierDecision.missing.joinToString(" | ").take(240)
            )
        )

        return when (val gateDecision = completionGate.evaluate(verifierDecision, worldStateManager.getState())) {
            is CompletionGate.Decision.Accepted -> {
                logCompletionGateResult(
                    accepted = true,
                    source = "verifier",
                    evidence = gateDecision.evidence
                )
                FlowTraceLogger.finish(status = "success", reason = "completion_gate_accepted")
                FinishProposalResolution.Accepted(
                    summary = gateDecision.evidence.joinToString("；").ifBlank { "任务完成并已验证" }
                )
            }
            is CompletionGate.Decision.Rejected -> {
                val missing = gateDecision.missing.joinToString("；").ifBlank { "缺少可观察完成证据" }
                val reason = "${gateDecision.reason}；缺失证据：$missing"
                logCompletionGateResult(
                    accepted = false,
                    source = "verifier",
                    reason = gateDecision.reason,
                    missing = gateDecision.missing,
                    nextHint = gateDecision.nextHint
                )
                FlowTraceLogger.warn(
                    stage = "completion_gate_rejected",
                    kv = mapOf(
                        "reason" to gateDecision.reason,
                        "missing" to missing,
                        "next_hint" to gateDecision.nextHint
                    )
                )
                FinishProposalResolution.Rejected(
                    reason = reason,
                    nextHint = "Verifier 未接受完成：$reason。下一步：${gateDecision.nextHint}"
                )
            }
        }
    }

    private fun logCompletionGateResult(
        accepted: Boolean,
        source: String,
        evidence: List<String> = emptyList(),
        reason: String = "",
        missing: List<String> = emptyList(),
        nextHint: String = ""
    ) {
        FlowTraceLogger.event(
            stage = "completion_gate_result",
            kv = mapOf(
                "accepted" to accepted,
                "source" to source,
                "reason" to reason,
                "evidence" to evidence.joinToString(" | ").take(240),
                "missing" to missing.joinToString(" | ").take(240),
                "next_hint" to nextHint
            )
        )
    }

    private suspend fun completeTask(strategy: Strategy, summary: String): TaskResult.Success {
        updateStatus(
            AgentPhase.COMPLETED,
            "任务成功完成",
            thought = summary.take(240),
            observation = buildExecutionObservation()
        )
        worldStateManager.persistTerminalState(markCompleted = true)
        persistTaskExperience(
            strategy = strategy,
            success = true,
            summary = buildTaskSummary(strategy, detail = summary.ifBlank { "任务完成" })
        )
        worldStateManager.resetForNewTask()
        return TaskResult.Success(summary.ifBlank { "任务完成" })
    }

    private suspend fun tryHumanAssistance(reason: String): Boolean {
        val gate = humanAssistanceGate ?: return false
        val request = gate.detect(reason) ?: return false
        updateStatus(
            AgentPhase.EXECUTING,
            "检测外部阻塞，准备请求用户协助",
            thought = "${request.reason}: ${request.prompt}".take(240),
            observation = buildExecutionObservation()
        )
        val assisted = gate.assist(request)
        FlowTraceLogger.event(
            stage = "human_assistance_result",
            kv = mapOf(
                "assisted" to assisted,
                "reason" to reason.take(160)
            )
        )
        if (assisted) {
            updateStatus(
                AgentPhase.EXECUTING,
                "用户协助已完成，继续任务",
                observation = buildExecutionObservation()
            )
        }
        return assisted
    }

    private suspend fun persistTaskExperience(
        strategy: Strategy,
        success: Boolean,
        summary: String
    ) {
        runCatching {
            taskResultArchive.persistTaskResult(strategy, success, summary.take(320))
        }.onFailure { error ->
            Log.w(tag, "Failed to persist task experience: ${error.message}")
        }
    }

    private fun buildTaskSummary(strategy: Strategy, detail: String): String {
        val state = worldStateManager.getState()
        val currentApp = state.currentApp ?: strategy.app.ifBlank { "unknown" }
        val lastAction = state.lastAction ?: "none"
        return buildString {
            append("goal=${strategy.goal}; ")
            append("app=$currentApp; ")
            append("last_action=$lastAction; ")
            append("detail=$detail")
        }.take(320)
    }

    private fun updateStatus(
        phase: AgentPhase,
        message: String,
        thought: String? = null,
        action: String? = null,
        observation: String? = null
    ) {
        val status = AgentStatus(
            phase = phase,
            message = message,
            thought = thought,
            action = action,
            observation = observation
        )
        onStatusUpdate(status)
    }

    private fun buildPlanSummary(strategy: Strategy): String {
        val stepsText = if (strategy.strategy.isEmpty()) {
            "  - (无步骤)"
        } else {
            strategy.strategy.mapIndexed { index, step ->
                "  ${index + 1}. $step"
            }.joinToString("\n")
        }

        return buildString {
            appendLine("规划结果:")
            appendLine("目标: ${strategy.goal}")
            appendLine("应用: ${strategy.app}")
            appendLine("步骤(${strategy.strategy.size}):")
            append(stepsText)
            if (strategy.successCriteria.isNotEmpty()) {
                appendLine()
                appendLine("成功条件:")
                strategy.successCriteria.take(4).forEachIndexed { index, criterion ->
                    appendLine("  ${index + 1}. $criterion")
                }
            }
        }
    }

    private sealed class ReflectionResolution {
        data class Replanned(
            val strategy: Strategy,
            val recoveryBudgetRemaining: Int
        ) : ReflectionResolution()

        data class Aborted(val result: TaskResult.Failure) : ReflectionResolution()
    }

    private fun buildExecutionObservation(): String {
        val state = worldStateManager.getState()
        val app = summarizeObservationField(state.currentApp, "未知应用")
        val resolvedPkg = summarizeObservationField(state.resolvedTargetPackage, "无")
        val capabilities = state.pageCapabilities.take(4).joinToString(",").ifBlank { "无" }
        val lastAction = summarizeObservationField(state.lastAction, "无")
        val lastResult = summarizeObservationField(state.lastActionResult, "无")
        val stuck = state.stuckCount

        return "App=$app, ResolvedPkg=$resolvedPkg, Capabilities=$capabilities, LastAction=$lastAction, LastResult=$lastResult, Stuck=$stuck"
    }

    private fun summarizeObservationField(value: String?, fallback: String): String {
        return value
            ?.replace(Regex("\\s+"), " ")
            ?.replace(", ", " / ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.take(60)
            ?: fallback
    }

    private suspend fun handlePlannerReflection(
        currentStrategy: Strategy,
        reason: String,
        recoveryBudgetRemaining: Int
    ): ReflectionResolution {
        if (recoveryBudgetRemaining <= 0) {
            updateStatus(
                AgentPhase.FAILED,
                "恢复预算已用尽，无法继续重规划: $reason",
                observation = buildExecutionObservation()
            )
            persistTaskExperience(
                strategy = currentStrategy,
                success = false,
                summary = buildTaskSummary(currentStrategy, detail = "恢复预算用尽: $reason")
            )
            worldStateManager.persistTerminalState(markCompleted = false)
            worldStateManager.resetForNewTask()
            FlowTraceLogger.finish(status = "failed", reason = "recovery_budget_exhausted:$reason")
            return ReflectionResolution.Aborted(TaskResult.Failure("恢复预算已用尽: $reason"))
        }
        val decision = planner.reflect(worldStateManager.getState(), reason)
        FlowTraceLogger.event(
            stage = "planner_reflection_decision",
            kv = mapOf(
                "decision" to decision::class.simpleName.orEmpty(),
                "reason" to reason
            )
        )
        return when (decision) {
            is PlannerDecision.Replan -> {
                val replanned = planner.reconcileReplannedStrategy(currentStrategy, decision.newStrategy)
                worldStateManager.setStrategy(replanned)
                val replanSummary = buildPlanSummary(replanned)
                val budgetAfterReplan = recoveryBudgetRemaining - 1
                updateStatus(
                    AgentPhase.PLANNING,
                    "已生成新策略，目标应用: ${replanned.app}",
                    thought = replanSummary,
                    action = "重规划策略",
                    observation = buildExecutionObservation()
                )
                FlowTraceLogger.warn(
                    stage = "planner_replan",
                    kv = mapOf(
                        "goal" to replanned.goal,
                        "app" to replanned.app,
                        "strategy_size" to replanned.strategy.size,
                        "recovery_budget_left" to budgetAfterReplan
                    )
                )
                ReflectionResolution.Replanned(
                    strategy = replanned,
                    recoveryBudgetRemaining = budgetAfterReplan
                )
            }
            is PlannerDecision.Abort -> {
                updateStatus(
                    AgentPhase.FAILED,
                    "任务被迫终止: ${decision.reason}",
                    observation = buildExecutionObservation()
                )
                persistTaskExperience(
                    strategy = currentStrategy,
                    success = false,
                    summary = buildTaskSummary(currentStrategy, detail = decision.reason)
                )
                worldStateManager.persistTerminalState(markCompleted = false)
                worldStateManager.resetForNewTask()
                FlowTraceLogger.finish(status = "failed", reason = "planner_abort:${decision.reason}")
                ReflectionResolution.Aborted(TaskResult.Failure(decision.reason))
            }
        }
    }

    private fun isFinishHandshakeFailure(code: ExecutionFailureCode): Boolean {
        return code == ExecutionFailureCode.FINISH_HANDSHAKE_REJECTED
    }

    private fun shouldEscalateToPlannerReflection(code: ExecutionFailureCode): Boolean {
        return code != ExecutionFailureCode.NONE &&
            code != ExecutionFailureCode.FINISH_HANDSHAKE_REJECTED &&
            code != ExecutionFailureCode.MODEL_EMPTY_RESPONSE
    }

    private fun shouldAttemptLocalExecutorRecovery(code: ExecutionFailureCode): Boolean {
        return code != ExecutionFailureCode.LOOP_DETECTED &&
            code != ExecutionFailureCode.STUCK_THRESHOLD &&
            code != ExecutionFailureCode.PROGRESS_STALLED
    }

    /**
     * Build task-type-aware recovery hint for finish handshake failures.
     * Instead of a generic "finish it" hint, provides specific guidance based
     * on what kind of task the user requested.
     */
    private fun buildFinishRecoveryHint(): String {
        return "保持当前上下文。仅基于可观测业务结果判断是否 finished：" +
            "如果已经出现明确结果证据则直接 finished；如果还差最后一个业务动作或确认动作，先完成它。禁止重复无进展路径。"
    }

    private fun isTransientModelFailure(code: ExecutionFailureCode, reason: String): Boolean {
        return code == ExecutionFailureCode.MODEL_EMPTY_RESPONSE ||
            reason.contains("空响应") ||
            reason.contains("Empty response", ignoreCase = true)
    }

    private fun buildTransientModelRecoveryHint(reason: String): String {
        return "这是模型/供应商响应异常，不代表当前策略错误。请刷新观察，继续同一目标；" +
            "如果当前屏幕已经满足成功条件，直接提出 finished。原因：${reason.take(120)}"
    }
}
