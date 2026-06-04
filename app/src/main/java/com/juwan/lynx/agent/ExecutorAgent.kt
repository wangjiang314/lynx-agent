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
import com.juwan.lynx.agent.executor.ActionPolicy
import com.juwan.lynx.agent.executor.ExecutionPacketBuilder
import com.juwan.lynx.runtime.AgentConfig
import com.juwan.lynx.runtime.AgentIterationContext
import com.juwan.lynx.runtime.AgentResult
import com.juwan.lynx.runtime.AgentRuntime
import com.juwan.lynx.runtime.ModelConfig
import com.juwan.lynx.runtime.Tool
import com.juwan.lynx.runtime.ToolParam
import com.juwan.lynx.safety.SafetyGuard
import com.juwan.lynx.safety.PlannedActionContext
import com.juwan.lynx.state.ActionOrigin
import com.juwan.lynx.state.ActionResultCode
import com.juwan.lynx.state.PendingConfirmation
import com.juwan.lynx.state.CapabilityState
import com.juwan.lynx.state.WorldStateManager
import com.juwan.lynx.state.canonicalActionName
import com.juwan.lynx.state.isUnreliableResultText
import kotlinx.coroutines.CancellationException
import kotlin.math.abs

enum class ExecutionFailureCode {
    NONE,
    UNKNOWN,
    NO_TOOL_CALL,
    UNKNOWN_TOOL,
    LOOP_DETECTED,
    STUCK_THRESHOLD,
    PROGRESS_STALLED,
    MAX_ITERATIONS,
    EXECUTOR_EXCEPTION,
    MODEL_EMPTY_RESPONSE,
    LAUNCH_FAILED,
    APP_NOT_FOUND,
    APP_AMBIGUOUS,
    MISSING_REQUIRED_TOOL,
    FINISH_HANDSHAKE_REJECTED
}

/**
 * Result of strategy execution.
 */
sealed class ExecutionResult {
    data object AllCompleted : ExecutionResult()
    data class FinishProposed(val output: String) : ExecutionResult()
    data object Stuck : ExecutionResult()
    data class InsufficientProgress(
        val reason: String,
        val code: ExecutionFailureCode = ExecutionFailureCode.UNKNOWN
    ) : ExecutionResult()
    data class Failed(
        val reason: String,
        val code: ExecutionFailureCode = ExecutionFailureCode.UNKNOWN
    ) : ExecutionResult()
}

/**
 * ExecutorAgent with Safety Guard Integration.
 *
 * Key upgrades:
 * 1) App launch remains model-decided per round:
 *    - Model chooses `resolve_installed_app` / `open_app` as needed
 *    - No implicit launch outside the execution loop
 * 2) Better launch argument normalization:
 *    - Unifies app/app_name/package/pkg/package_name args
 * 3) Prevents repeated launch attempts when target app is already foreground.
 */
class ExecutorAgent(
    private val runtime: AgentRuntime,
    private val worldStateManager: WorldStateManager,
    private val safetyGuard: SafetyGuard,
    private val visionModelConfig: ModelConfig,
    primitiveTools: List<Tool>,
    repeatedActionLimit: Int = 3,
    stuckNoChangeLimit: Int = 3,
    progressCheckInterval: Int = 5,
    executorRuntimeIterationsPerCall: Int = 1
) {
    private val tag = "ExecutorAgent"
    private val progressCheckInterval = progressCheckInterval.coerceIn(2, 20)
    private val repeatedActionLimit = repeatedActionLimit.coerceIn(2, 10)
    private val stuckNoChangeLimit = stuckNoChangeLimit.coerceIn(2, 10)
    private val executorRuntimeIterationsPerCall = executorRuntimeIterationsPerCall.coerceIn(1, 1)
    private val actionPolicy = ActionPolicy(
        worldStateManager = worldStateManager,
        primitiveTools = primitiveTools,
        repeatedActionLimit = this.repeatedActionLimit
    )
    private val executionPacketBuilder = ExecutionPacketBuilder(
        sanitizeUserInput = ::sanitizeUserInput,
        isTargetAppMatch = ::isTargetAppMatch,
        buildBlockedActionSignatures = actionPolicy::buildBlockedActionSignatures
    )

    private val primitiveTools: List<Tool> = primitiveTools
    private val modelToolWhitelist = setOf(
        "resolve_installed_app",
        "open_app",
        "click",
        "long_press",
        "scroll",
        "drag",
        "type",
        "press_back",
        "press_home",
        "type_and_enter",
        "finished"
    )

    private fun securedToolsForStrategy(strategy: Strategy): List<Tool> {
        return this.primitiveTools
            .map { tool ->
                ConstrainedSafetyWrappedTool(
                    delegate = tool,
                    safetyGuard = safetyGuard,
                    worldStateManager = worldStateManager,
                    siblingTools = primitiveTools,
                    argsTransformer = { requestedArgs ->
                        when (tool.name) {
                            "open_app" -> normalizeLaunchArgsWithStrategy(requestedArgs, strategy)
                            "resolve_installed_app" -> normalizeResolveArgsWithStrategy(requestedArgs, strategy)
                            "scroll" -> normalizeScrollArgsForRuntime(requestedArgs)
                            else -> requestedArgs
                        }
                    },
                    actionGuard = { actionName, requestedArgs ->
                        actionPolicy.suppressInvalidToolArguments(
                            actionName = actionName,
                            args = requestedArgs
                        )
                    }
                )
            }
    }

    suspend fun executeStrategy(strategy: Strategy, recoveryHint: String? = null): ExecutionResult {
        try {
            FlowTraceLogger.event(
                stage = "executor_start",
                kv = mapOf(
                    "goal" to strategy.goal,
                    "app" to strategy.app,
                    "strategy_size" to strategy.strategy.size,
                    "compiled_size" to strategy.strategy.size.toString(),
                    "recovery_hint" to recoveryHint.orEmpty()
                )
            )

            var currentIteration = 0
            val actionHistory = mutableListOf<com.juwan.lynx.api.ChatMessage>()
            val maxHistoryMessages = 3
            var unknownToolErrorStreak = 0
            var emptyLlmResponseErrorStreak = 0
            var noToolCallErrorStreak = 0
            val baseRecoveryHint = recoveryHint?.takeIf { it.isNotBlank() }
            loop@ while (currentIteration < 30) {
                try {
                    worldStateManager.refreshObservation()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(tag, "Failed to refresh observation before iteration $currentIteration", e)
                }

                // Rebuild tools and prompt every iteration to avoid stale open_app availability.
                val securedTools = securedToolsForStrategy(strategy)
                var stateSnapshot = worldStateManager.getState()
                val persistedResolved = runCatching {
                    worldStateManager.getResolvedTargetPackage().orEmpty()
                }.getOrDefault("")
                val inferredResolved = inferResolvedPackageFromRecentActions(stateSnapshot.recentActions)
                val activeId = persistedResolved.ifBlank { inferredResolved }.ifBlank { strategy.app.trim() }
                val appActive = isTargetAppAlreadyActive(strategy, activeId)
                val blockedActionHistory = blockedActionEvidence(stateSnapshot)
                val blockedLoopTool = actionPolicy.detectTemporarilyBlockedLoopTool(blockedActionHistory)
                val blockedInputTools = actionPolicy.detectTemporarilyBlockedInputTools(blockedActionHistory)
                val blockedToolNames = buildSet {
                    blockedLoopTool?.let { add(it) }
                    addAll(blockedInputTools)
                }
                val shouldDisableResolver = actionPolicy.shouldDisableResolverInActiveTarget(
                    appActive = appActive,
                    persistedResolved = persistedResolved,
                    inferredResolved = inferredResolved,
                    recentActions = stateSnapshot.recentActions
                )
                val shouldDisableHome = appActive
                val shouldDisableBack = appActive &&
                    actionPolicy.countTrailingActions(stateSnapshot.recentActions, setOf("press_back")) >= 1
                val candidateTools = if (appActive) {
                    securedTools.filter { tool ->
                        when (tool.name) {
                            "open_app" -> tool.name !in blockedToolNames
                            "resolve_installed_app" -> !shouldDisableResolver
                            "press_home" -> !shouldDisableHome
                            "press_back" -> !shouldDisableBack
                            else -> tool.name !in blockedToolNames
                        }
                    }
                } else {
                    securedTools.filter {
                        it.name !in blockedToolNames
                    }
                }
                val baseAvailableTools = candidateTools.filter {
                    it.name in modelToolWhitelist
                }
                    .ifEmpty { candidateTools }
                val availableTools = baseAvailableTools
                if (blockedToolNames.isNotEmpty()) {
                    Log.w(tag, "Temporarily blocked tools: ${blockedToolNames.joinToString(",")}")
                }
                val availableToolNames = availableTools.map { it.name }
                val forbiddenToolNames = securedTools.map { it.name }
                    .filterNot { it in availableToolNames }
                val blockedActionSignatures = actionPolicy.buildBlockedActionSignatures(blockedActionHistory)
                val blockedToolRecoveryHint = buildBlockedToolRecoveryHint(
                    blockedToolNames = blockedToolNames,
                    availableToolNames = availableToolNames
                )
                val effectiveRecoveryHint = listOfNotNull(
                    baseRecoveryHint,
                    blockedToolRecoveryHint
                ).joinToString(" ").ifBlank { null }
                logIterationContext(
                    iteration = currentIteration + 1,
                    state = stateSnapshot,
                    appActive = appActive,
                    blockedToolNames = blockedToolNames,
                    availableTools = availableToolNames,
                    forbiddenTools = forbiddenToolNames,
                    blockedActionSignatures = blockedActionSignatures,
                    recoveryHint = effectiveRecoveryHint
                )
                val compactHistory = filterHistoryForAvailableTools(actionHistory, availableToolNames)
                val priorHistory = if (compactHistory.isNotEmpty()) {
                    val header = com.juwan.lynx.api.ChatMessage("user", "Previous actions taken in this task:")
                    listOf(header) + compactHistory.takeLast(maxHistoryMessages)
                } else {
                    emptyList()
                }
                val shouldUseVision = shouldUseVisionForExecutorRound(
                    appActive = appActive,
                    state = stateSnapshot
                )
                if (!shouldUseVision && !stateSnapshot.screenshotBase64.isNullOrBlank()) {
                    FlowTraceLogger.event(
                        stage = "executor_text_only_pretarget",
                        kv = mapOf(
                            "iter" to (currentIteration + 1),
                            "app" to (stateSnapshot.currentApp ?: "unknown"),
                            "target" to activeId
                        )
                    )
                }
                val config = AgentConfig(
                    name = "ExecutorAgent",
                    model = visionModelConfig.copy(temperature = 0.0f),
                    systemPrompt = executionPacketBuilder.buildSystemPrompt(
                        strategy = strategy,
                        recoveryHint = effectiveRecoveryHint,
                        availableTools = availableTools,
                        iterationIndex = currentIteration + 1
                    ),
                    tools = availableTools,
                    maxIterations = executorRuntimeIterationsPerCall,
                    useVision = shouldUseVision,
                    conversationHistory = priorHistory
                )

                val input = executionPacketBuilder.buildInput(
                    strategy = strategy,
                    recoveryHint = effectiveRecoveryHint,
                    state = stateSnapshot,
                    targetIdentifier = activeId,
                    iterationIndex = currentIteration + 1,
                    availableToolNames = availableToolNames,
                    forbiddenToolNames = forbiddenToolNames
                )
                val screenshotBase64 = stateSnapshot.screenshotBase64
                val result = runtime.runAgent(
                    config.copy(
                        useVision = shouldUseVision,
                        imageBase64 = screenshotBase64.takeIf { shouldUseVision },
                        iterationContextProvider = buildRuntimeIterationContextProvider(
                            strategy = strategy,
                            recoveryHint = effectiveRecoveryHint,
                            targetIdentifier = activeId,
                            iterationIndex = currentIteration + 1,
                            availableToolNames = availableToolNames,
                            forbiddenToolNames = forbiddenToolNames
                        )
                    ),
                    input
                )
                currentIteration++

                // Accumulate concise action+result history (not full input — too verbose for vision API)
                val postState = worldStateManager.getState()
                val lastAction = postState.lastAction
                val lastResult = postState.lastActionResult
                if (!lastAction.isNullOrBlank()) {
                    appendActionHistoryIfNew(
                        actionHistory = actionHistory,
                        action = lastAction,
                        result = lastResult ?: "unknown"
                    )
                }
                when (result) {
                    is AgentResult.Finished -> {
                        val output = result.output
                        if (isExplicitFinishSignal(output)) {
                            return ExecutionResult.FinishProposed(output)
                        } else {
                            Log.w(tag, "Model returned Finished without explicit finish tool signal: $output")
                            return ExecutionResult.Failed(
                                reason = "执行未显式完成：缺少 finished 工具确认",
                                code = ExecutionFailureCode.FINISH_HANDSHAKE_REJECTED
                            )
                        }
                    }
                    is AgentResult.Error -> {
                        if (isTransientEmptyLlmResponse(result.message)) {
                            emptyLlmResponseErrorStreak += 1
                            unknownToolErrorStreak = 0
                            noToolCallErrorStreak = 0
                            FlowTraceLogger.warn(
                                stage = "executor_empty_llm_response",
                                kv = mapOf(
                                    "streak" to emptyLlmResponseErrorStreak,
                                    "iteration" to currentIteration,
                                    "reason" to result.message
                                )
                            )
                            actionHistory.add(
                                com.juwan.lynx.api.ChatMessage(
                                    "user",
                                    "Action result: Model returned an empty response. Re-read the latest screen state and choose exactly one tool from Available tools."
                                )
                            )
                            if (emptyLlmResponseErrorStreak >= 3) {
                                return ExecutionResult.Failed(
                                    reason = "LLM 连续空响应，执行层重试用尽",
                                    code = ExecutionFailureCode.MODEL_EMPTY_RESPONSE
                                )
                            }
                            continue@loop
                        }
                        emptyLlmResponseErrorStreak = 0
                        if (isMissingToolCallResponse(result.message)) {
                            noToolCallErrorStreak += 1
                            unknownToolErrorStreak = 0
                            FlowTraceLogger.warn(
                                stage = "executor_missing_tool_call",
                                kv = mapOf(
                                    "streak" to noToolCallErrorStreak,
                                    "iteration" to currentIteration,
                                    "reason" to result.message
                                )
                            )
                            actionHistory.add(
                                com.juwan.lynx.api.ChatMessage(
                                    "user",
                                    "Action result: Model did not emit a tool call. Re-read the latest screen state and output exactly one JSON tool call from Available tools."
                                )
                            )
                            if (noToolCallErrorStreak >= 3) {
                                return ExecutionResult.InsufficientProgress(
                                    reason = "模型连续未输出工具调用",
                                    code = ExecutionFailureCode.NO_TOOL_CALL
                                )
                            }
                            continue@loop
                        }
                        noToolCallErrorStreak = 0
                        val unknownTool = extractUnknownToolName(result.message)
                        if (!unknownTool.isNullOrBlank()) {
                            unknownToolErrorStreak++
                            actionHistory.add(com.juwan.lynx.api.ChatMessage("assistant", unknownTool))
                            actionHistory.add(
                                com.juwan.lynx.api.ChatMessage(
                                    "user",
                                    buildUnavailableToolGuidance(
                                        toolName = unknownTool,
                                        availableToolNames = availableToolNames,
                                        forbiddenToolNames = forbiddenToolNames
                                    )
                                )
                            )
                            if (unknownToolErrorStreak >= 2) {
                                FlowTraceLogger.warn(
                                    stage = "executor_fail_unknown_tool",
                                    kv = mapOf("tool" to unknownTool)
                                )
                                return ExecutionResult.InsufficientProgress(
                                    reason = "模型连续选择不可用工具：$unknownTool",
                                    code = mapUnknownToolFailureCode(unknownTool)
                                )
                            }
                            continue@loop
                        }
                        FlowTraceLogger.warn(
                            stage = "executor_failed",
                            kv = mapOf("reason" to result.message)
                        )
                        return ExecutionResult.Failed(
                            reason = result.message,
                            code = ExecutionFailureCode.UNKNOWN
                        )
                    }
                    else -> {
                        // continue
                    }
                }

                val loopRisk = actionPolicy.detectLoopRisk()
                if (loopRisk != null) {
                    FlowTraceLogger.warn(
                        stage = "executor_insufficient_progress",
                        kv = mapOf("reason" to loopRisk)
                    )
                    val loopCode = if (loopRisk.contains("finished", ignoreCase = true)) {
                        ExecutionFailureCode.FINISH_HANDSHAKE_REJECTED
                    } else {
                        ExecutionFailureCode.LOOP_DETECTED
                    }
                    return ExecutionResult.InsufficientProgress(
                        reason = loopRisk,
                        code = loopCode
                    )
                }

                if (worldStateManager.isStuck(stuckNoChangeLimit)) {
                    FlowTraceLogger.warn(
                        stage = "executor_insufficient_progress",
                        kv = mapOf("reason" to "stuck_threshold")
                    )
                    return ExecutionResult.InsufficientProgress(
                        reason = "连续 ${stuckNoChangeLimit} 步页面无变化，疑似卡住",
                        code = ExecutionFailureCode.STUCK_THRESHOLD
                    )
                }

                if (currentIteration % progressCheckInterval == 0 && isProgressInsufficient()) {
                    FlowTraceLogger.warn(
                        stage = "executor_insufficient_progress",
                        kv = mapOf("reason" to "progress_check_failed")
                    )
                    return ExecutionResult.InsufficientProgress(
                        reason = "检测到执行陷入停滞：连续操作未形成有效页面进展",
                        code = ExecutionFailureCode.PROGRESS_STALLED
                    )
                }
            }
            FlowTraceLogger.warn(stage = "executor_failed", kv = mapOf("reason" to "max_iterations"))
            return ExecutionResult.Failed(
                reason = "达到最大迭代次数",
                code = ExecutionFailureCode.MAX_ITERATIONS
            )
        } catch (e: CancellationException) {
            FlowTraceLogger.warn(stage = "executor_cancelled", kv = mapOf("reason" to (e.message ?: "cancelled")))
            throw e
        } catch (e: Exception) {
            FlowTraceLogger.warn(stage = "executor_exception", kv = mapOf("error" to (e.message ?: "unknown")))
            return ExecutionResult.Failed(
                reason = e.message ?: "未知异常",
                code = ExecutionFailureCode.EXECUTOR_EXCEPTION
            )
        }
    }

    private fun isProgressInsufficient(): Boolean {
        val history = worldStateManager.getState().recentActions.takeLast(progressCheckInterval)
        val reliableProgressCount = history.count { it.resultCode == ActionResultCode.SUCCESS }
        return history.size >= progressCheckInterval && reliableProgressCount < 2
    }

    private fun extractUnknownToolName(message: String): String? {
        val match = Regex("""Unknown tool:\s*([a-zA-Z0-9_]+)""")
            .find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()
        return match.ifBlank { null }
    }

    private fun isTransientEmptyLlmResponse(message: String): Boolean {
        val normalized = message.trim()
        return normalized.contains("Empty response from LLM", ignoreCase = true) ||
            normalized.contains("empty response", ignoreCase = true)
    }

    private fun isMissingToolCallResponse(message: String): Boolean {
        return message.trim().contains("No tool call in model response", ignoreCase = true)
    }

    private fun mapUnknownToolFailureCode(toolName: String): ExecutionFailureCode {
        return if (toolName.trim().equals("finished", ignoreCase = true)) {
            ExecutionFailureCode.FINISH_HANDSHAKE_REJECTED
        } else {
            ExecutionFailureCode.UNKNOWN_TOOL
        }
    }

    private fun buildBlockedToolRecoveryHint(
        blockedToolNames: Set<String>,
        availableToolNames: List<String>
    ): String? {
        if (blockedToolNames.isEmpty()) return null
        val available = availableToolNames.joinToString(",")
        return "Temporarily blocked tools: ${blockedToolNames.joinToString(",")}. Choose exactly one available tool from [$available]."
    }

    private fun buildUnavailableToolGuidance(
        toolName: String,
        availableToolNames: List<String>,
        forbiddenToolNames: List<String>
    ): String {
        val available = availableToolNames.joinToString(",")
        val isForbidden = forbiddenToolNames.any { it.equals(toolName, ignoreCase = true) }
        return if (isForbidden) {
            "Action result: $toolName is intentionally unavailable for recovery in this state. Do not call $toolName again. Choose exactly one available tool from [$available]."
        } else {
            "Action result: Tool unavailable in current state. Use one tool strictly from Available tools: [$available]."
        }
    }

    private fun appendActionHistoryIfNew(
        actionHistory: MutableList<com.juwan.lynx.api.ChatMessage>,
        action: String,
        result: String
    ) {
        val lastAssistant = actionHistory.lastOrNull { it.role == "assistant" }?.content
        val lastUser = actionHistory.lastOrNull { it.role == "user" }?.content.orEmpty()
        val sameAction = lastAssistant == action
        val sameResult = lastUser == "Action result: $result"
        if (sameAction && sameResult) return
        actionHistory.add(com.juwan.lynx.api.ChatMessage("assistant", action))
        actionHistory.add(com.juwan.lynx.api.ChatMessage("user", "Action result: $result"))
    }

    private fun filterHistoryForAvailableTools(
        actionHistory: List<com.juwan.lynx.api.ChatMessage>,
        availableToolNames: List<String>
    ): List<com.juwan.lynx.api.ChatMessage> {
        if (actionHistory.isEmpty()) return emptyList()
        val allowed = availableToolNames.map { it.lowercase() }.toSet()
        val filtered = mutableListOf<com.juwan.lynx.api.ChatMessage>()
        var index = 0
        while (index < actionHistory.size) {
            val current = actionHistory[index]
            if (current.role == "assistant") {
                val actionName = normalizeAction(current.content).lowercase()
                val next = actionHistory.getOrNull(index + 1)
                val hasResultPair = next?.role == "user" &&
                    next.content.startsWith("Action result:")
                if (actionName in allowed) {
                    filtered.add(current)
                    if (hasResultPair) next?.let { filtered.add(it) }
                } else if (
                    hasResultPair &&
                    next?.content?.contains("Tool unavailable in current state", ignoreCase = true) == true
                ) {
                    filtered.add(next)
                }
                index += if (hasResultPair) 2 else 1
                continue
            }

            // Keep standalone user guidance, but avoid stray action results without action.
            if (!current.content.startsWith("Action result:")) {
                filtered.add(current)
            }
            index++
        }
        return filtered
    }

    private fun logIterationContext(
        iteration: Int,
        state: com.juwan.lynx.state.WorldState,
        appActive: Boolean,
        blockedToolNames: Set<String>,
        availableTools: List<String>,
        forbiddenTools: List<String>,
        blockedActionSignatures: List<String>,
        recoveryHint: String?
    ) {
        val candidatePreview = state.actionCandidates.take(4).joinToString(" | ")
        val blockedPreview = blockedActionSignatures.take(3).joinToString(" | ")
        val blockedToolPreview = blockedToolNames.joinToString(",").ifBlank { "none" }
        val recoveryShort = recoveryHint?.replace(Regex("\\s+"), " ")?.take(60) ?: "none"
        val capabilityPreview = state.pageCapabilities.take(4).joinToString(",").ifBlank { "none" }
        Log.d(
            tag,
            "Iter#$iteration app=${state.currentApp ?: "unknown"} " +
                "active=$appActive blockedTool=$blockedToolPreview " +
                "caps=$capabilityPreview " +
                "tools=${availableTools.joinToString(",")} forbid=${forbiddenTools.joinToString(",")} " +
                "blockedSig=${if (blockedPreview.isBlank()) "none" else blockedPreview} " +
                "candidates=${if (candidatePreview.isBlank()) "none" else candidatePreview} recovery=$recoveryShort"
        )
        FlowTraceLogger.event(
            stage = "executor_iteration",
            kv = linkedMapOf<String, Any?>(
                "iter" to iteration,
                "app" to (state.currentApp ?: "unknown"),
                "fact_caps" to capabilityPreview,
                "blocked_tool" to blockedToolPreview,
                "blocked_sig" to if (blockedPreview.isBlank()) "none" else blockedPreview,
                "fact_candidates" to if (candidatePreview.isBlank()) "none" else candidatePreview
            )
        )
    }

    private fun blockedActionEvidence(
        state: com.juwan.lynx.state.WorldState
    ): List<com.juwan.lynx.state.ActionRecord> {
        return (state.taskHistoryActions + state.recentActions)
            .distinctBy { record ->
                "${record.timestamp}|${record.action}|${record.pageSignatureBefore}|${record.pageSignatureAfter}"
            }
            .takeLast(12)
    }

    private fun isTargetAppMatch(
        currentApp: String?,
        strategy: Strategy,
        resolvedIdentifier: String? = null
    ): Boolean {
        val current = normalizeAppName(currentApp) ?: return false
        val candidates = buildList {
            normalizeAppName(strategy.app)?.let { add(it) }
            normalizeAppName(resolvedIdentifier)?.let { add(it) }
        }.distinct()
        if (candidates.isEmpty()) return false
        return candidates.any { target ->
            if (looksLikePackageIdentifier(target)) {
                current == target
            } else {
                current == target ||
                    current.startsWith("$target.") ||
                    target.startsWith("$current.")
            }
        }
    }

    private fun normalizeAppName(appName: String?): String? {
        if (appName.isNullOrBlank()) return null
        return appName.trim().lowercase()
    }

    private fun looksLikePackageIdentifier(value: String): Boolean {
        if (value.isBlank()) return false
        return Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+").matches(value)
    }

    private fun isExplicitFinishSignal(output: String): Boolean {
        return output.trim().startsWith("FINISH_TOOL_EXECUTED:")
    }

    private fun strategyAppForConstraint(strategy: Strategy): String {
        return strategy.app.trim().takeIf { it.isNotBlank() } ?: ""
    }

    private fun extractRequestedAppArg(args: Map<String, String>): String {
        return args["app"]?.trim().orEmpty()
            .ifBlank { args["app_name"]?.trim().orEmpty() }
            .ifBlank { args["package_name"]?.trim().orEmpty() }
            .ifBlank { args["package"]?.trim().orEmpty() }
            .ifBlank { args["pkg"]?.trim().orEmpty() }
            .ifBlank { args["name"]?.trim().orEmpty() }
            .ifBlank { args["query"]?.trim().orEmpty() }
    }

    /**
     * Normalize launch arguments while preserving explicit package targets:
     * - if model provided package_name/package/pkg, trust it (typically from resolver)
     * - else use provided app/app_name/name when present
     * - fallback to strategy app only when request is empty
     */
    private fun normalizeLaunchArgsWithStrategy(
        requestedArgs: Map<String, String>,
        strategy: Strategy
    ): Map<String, String> {
        val targetApp = strategyAppForConstraint(strategy)
        if (targetApp.isBlank()) return requestedArgs

        val explicitPackage = requestedArgs["package_name"]?.trim().orEmpty()
            .ifBlank { requestedArgs["package"]?.trim().orEmpty() }
            .ifBlank { requestedArgs["pkg"]?.trim().orEmpty() }

        if (explicitPackage.isNotBlank()) {
            return requestedArgs
                .withoutLaunchAliases()
                .plus(
                    mapOf(
                        "app" to explicitPackage,
                        "package_name" to explicitPackage
                    )
                )
        }

        val explicitApp = requestedArgs["app"]?.trim().orEmpty()
            .ifBlank { requestedArgs["app_name"]?.trim().orEmpty() }
            .ifBlank { requestedArgs["name"]?.trim().orEmpty() }

        if (explicitApp.isNotBlank()) {
            if (!isSameApp(explicitApp, targetApp)) {
                Log.w(tag, "Blocked open_app arg drift. requested=$explicitApp, enforced=$targetApp")
                return requestedArgs
                    .withoutLaunchAliases()
                    .plus(buildLaunchTargetArgs(targetApp))
            }

            return requestedArgs
                .withoutLaunchAliases()
                .plus(buildLaunchTargetArgs(explicitApp))
        }

        return requestedArgs
            .withoutLaunchAliases()
            .plus(buildLaunchTargetArgs(targetApp))
    }

    /**
     * Normalize resolver arguments:
     * - ensure query is always present
     */
    private fun normalizeResolveArgsWithStrategy(
        requestedArgs: Map<String, String>,
        strategy: Strategy
    ): Map<String, String> {
        val targetApp = strategyAppForConstraint(strategy)
        if (targetApp.isBlank()) return requestedArgs
        val requested = extractRequestedAppArg(requestedArgs)
        val query = requested.ifBlank { targetApp }
        return requestedArgs + mapOf("query" to query)
    }

    private fun normalizeScrollArgsForRuntime(
        requestedArgs: Map<String, String>
    ): Map<String, String> {
        return requestedArgs
    }

    private fun isTargetAppAlreadyActive(strategy: Strategy, resolvedIdentifier: String? = null): Boolean {
        val latestState = worldStateManager.getState()
        val current = normalizeAppName(latestState.currentApp) ?: return false

        val candidates = buildList {
            normalizeAppName(strategy.app)?.let { add(it) }
            normalizeAppName(resolvedIdentifier)?.let { add(it) }
        }.distinct()

        if (candidates.isEmpty()) return false

        val directMatch = candidates.any { target ->
            current == target ||
                current.startsWith("$target.") ||
                target.startsWith("$current.")
        }
        return directMatch
    }

    private fun shouldUseVisionForExecutorRound(
        appActive: Boolean,
        state: com.juwan.lynx.state.WorldState
    ): Boolean {
        if (state.screenshotBase64.isNullOrBlank()) return false
        if (appActive) return true

        // The Lynx shell screen mostly shows task status and chat controls. Before
        // the target app is active, sending that screenshot to the vision model
        // adds noise and can spend the whole benchmark budget on vision timeouts.
        // The model still chooses the app-resolution/open-app tool itself.
        return normalizeAppName(state.currentApp) != "com.juwan.lynx"
    }

    private fun isSameApp(requested: String, target: String): Boolean {
        if (requested.isBlank() || target.isBlank()) return false
        val r = requested.trim().lowercase()
        val t = target.trim().lowercase()
        return r == t || r.startsWith("$t.") || t.startsWith("$r.")
    }

    private fun buildRuntimeIterationContextProvider(
        strategy: Strategy,
        recoveryHint: String?,
        targetIdentifier: String,
        iterationIndex: Int,
        availableToolNames: List<String>,
        forbiddenToolNames: List<String>
    ): suspend (Int) -> AgentIterationContext? {
        return { runtimeIteration ->
            runCatching {
                worldStateManager.refreshObservation()
            }.onFailure {
                Log.w(tag, "Failed to refresh observation for runtime iteration $runtimeIteration: ${it.message}")
            }
            val latestState = worldStateManager.getState()
            val latestAppActive = isTargetAppMatch(
                currentApp = latestState.currentApp,
                strategy = strategy,
                resolvedIdentifier = targetIdentifier
            )
            val latestUseVision = shouldUseVisionForExecutorRound(
                appActive = latestAppActive,
                state = latestState
            )
            val latestInput = executionPacketBuilder.buildInput(
                strategy = strategy,
                recoveryHint = recoveryHint,
                state = latestState,
                targetIdentifier = targetIdentifier,
                iterationIndex = iterationIndex + runtimeIteration,
                availableToolNames = availableToolNames,
                forbiddenToolNames = forbiddenToolNames
            )
            val latestScreenshot = latestState.screenshotBase64
            AgentIterationContext(
                input = latestInput,
                useVision = latestUseVision,
                imageBase64 = latestScreenshot.takeIf { latestUseVision }
            )
        }
    }

    /**
     * Sanitize user input to prevent prompt injection.
     */
    private fun sanitizeUserInput(input: String): String {
        return input
            .replace("```", "")  // 移除代码块标记
            .replace("\"", "'")  // 转义引号
            .trim()
    }

    private fun inferResolvedPackageFromRecentActions(
        recentActions: List<com.juwan.lynx.state.ActionRecord>
    ): String {
        val regex = Regex("package_name\\s*=\\s*([a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+)")
        val lastLaunch = recentActions.asReversed().firstOrNull {
            it.action.contains("open_app", ignoreCase = true) ||
                it.action.contains("resolve_installed_app", ignoreCase = true)
        } ?: return ""

        val fromAction = regex.find(lastLaunch.action)?.groupValues?.getOrNull(1).orEmpty()
        if (fromAction.isNotBlank()) return fromAction

        val fromResult = regex.find(lastLaunch.result)?.groupValues?.getOrNull(1).orEmpty()
        return fromResult
    }

    private fun normalizeAction(action: String): String {
        return canonicalActionName(action)
    }

    private fun buildLaunchTargetArgs(target: String): Map<String, String> {
        val trimmed = target.trim()
        if (trimmed.isBlank()) return emptyMap()
        return if (looksLikePackageIdentifier(trimmed)) {
            mapOf(
                "app" to trimmed,
                "package_name" to trimmed
            )
        } else {
            mapOf(
                "app" to trimmed,
                "app_name" to trimmed
            )
        }
    }

    private fun Map<String, String>.withoutLaunchAliases(): Map<String, String> {
        return this.filterKeys { key ->
            key !in setOf("app", "app_name", "name", "package_name", "package", "pkg", "query")
        }
    }

}

class ConstrainedSafetyWrappedTool(
    private val delegate: Tool,
    private val safetyGuard: SafetyGuard,
    private val worldStateManager: WorldStateManager,
    private val siblingTools: List<Tool> = emptyList(),
    private val argsTransformer: (Map<String, String>) -> Map<String, String> = { it },
    private val actionGuard: ((String, Map<String, String>) -> String?)? = null
) : Tool {
    override val name: String = delegate.name
    override val description: String = delegate.description
    override val parameters: List<ToolParam> = delegate.parameters

    override suspend fun execute(args: Map<String, String>): String {
        val constrainedArgs = argsTransformer(args)
        val screenText = worldStateManager.getState().visibleTexts.joinToString(" ")
        val argsText = constrainedArgs.entries.joinToString(", ") { (k, v) -> "$k=$v" }
        val actionText = if (argsText.isBlank()) name else "$name($argsText)"
        val plannedActionContext = buildPlannedActionContext(
            actionName = name,
            args = constrainedArgs,
            actionText = actionText
        )

        val duplicateSuppressed = suppressConsecutiveNearDuplicateClick(
            actionName = name,
            args = constrainedArgs
        )
        if (duplicateSuppressed != null) {
            worldStateManager.updateAfterAction(
                action = actionText,
                result = duplicateSuppressed,
                plannedActionContext = plannedActionContext
            )
            FlowTraceLogger.warn(
                stage = "tool_suppressed",
                kv = mapOf(
                    "tool" to name,
                    "reason" to duplicateSuppressed
                )
            )
            return duplicateSuppressed
        }

        val actionSuppressed = actionGuard?.invoke(name, constrainedArgs)
        if (actionSuppressed != null) {
            worldStateManager.updateAfterAction(
                action = actionText,
                result = actionSuppressed,
                plannedActionContext = plannedActionContext
            )
            FlowTraceLogger.warn(
                stage = "tool_suppressed",
                kv = mapOf(
                    "tool" to name,
                    "reason" to actionSuppressed
                )
            )
            return actionSuppressed
        }

        val duplicateMessageSuppressed = suppressDuplicateMessageResend(
            actionName = name,
            args = constrainedArgs
        )
        if (duplicateMessageSuppressed != null) {
            worldStateManager.updateAfterAction(
                action = actionText,
                result = duplicateMessageSuppressed,
                plannedActionContext = plannedActionContext
            )
            FlowTraceLogger.warn(
                stage = "tool_suppressed",
                kv = mapOf(
                    "tool" to name,
                    "reason" to duplicateMessageSuppressed
                )
            )
            return duplicateMessageSuppressed
        }

        val decision = safetyGuard.evaluate(plannedActionContext, screenText)

        return when (decision) {
            is SafetyGuard.CheckResult.Allow -> {
                if (name == "open_app" && shouldSkipLaunch(constrainedArgs)) {
                    val skipResult = "已在目标应用前台，跳过重复启动"
                    worldStateManager.updateAfterAction(
                        action = actionText,
                        result = skipResult,
                        plannedActionContext = plannedActionContext
                    )
                    FlowTraceLogger.event(
                        stage = "tool_noop",
                        kv = mapOf(
                            "tool" to name,
                            "reason" to "already_in_foreground"
                        )
                    )
                    skipResult
                } else {
                    FlowTraceLogger.event(
                        stage = "tool_execute",
                        kv = mapOf(
                            "tool" to name,
                            "args" to argsText.take(80)
                        )
                    )
                      executeAndRecord(constrainedArgs, actionText, plannedActionContext)
                }
            }
            is SafetyGuard.CheckResult.ConfirmRequired -> {
                worldStateManager.setPendingConfirmation(
                    PendingConfirmation(
                        actionHash = decision.actionHash,
                        actionSummary = decision.summary.take(180),
                        reason = decision.reason
                    )
                )
                val confirmed = safetyGuard.requestConfirmation(
                    actionDescription = "确定要执行操作: ${decision.summary} 吗？",
                    actionHash = decision.actionHash
                )
                val hashBoundApproved = confirmed && safetyGuard.consumeApprovedAction(decision.actionHash)
                if (hashBoundApproved) {
                    worldStateManager.confirmPendingActionHash(decision.actionHash)
                    FlowTraceLogger.event(
                        stage = "tool_execute_after_confirm",
                        kv = mapOf(
                            "tool" to name,
                            "action_hash" to decision.actionHash.take(16)
                        )
                    )
                      executeAndRecord(constrainedArgs, actionText, plannedActionContext)
                } else {
                    worldStateManager.setPendingConfirmation(null)
                    val blockedResult = "Error: blocked_by_user"
                    worldStateManager.updateAfterAction(
                        action = actionText,
                        result = blockedResult,
                        plannedActionContext = plannedActionContext
                    )
                    FlowTraceLogger.warn(
                        stage = "tool_blocked_by_user",
                        kv = mapOf(
                            "tool" to name,
                            "action_hash" to decision.actionHash.take(16)
                        )
                    )
                    blockedResult
                }
            }
            is SafetyGuard.CheckResult.Blocked -> {
                val blockedResult = "Error: blocked_by_safety"
                worldStateManager.setPendingConfirmation(null)
                worldStateManager.updateAfterAction(
                    action = actionText,
                    result = blockedResult,
                    plannedActionContext = plannedActionContext
                )
                FlowTraceLogger.warn(
                    stage = "tool_blocked_by_safety",
                    kv = mapOf(
                        "tool" to name,
                        "reason" to decision.reason,
                        "action" to actionText.take(100)
                    )
                )
                blockedResult
            }
        }
    }

    private fun shouldSkipLaunch(args: Map<String, String>): Boolean {
        val currentApp = normalizeAppName(worldStateManager.getState().currentApp) ?: return false
        val targetCandidates = listOf(
            args["package_name"],
            args["package"],
            args["pkg"],
            args["app"],
            args["app_name"]
        )
            .mapNotNull { normalizeAppName(it) }
            .distinct()
        if (targetCandidates.isEmpty()) return false
        return targetCandidates.any { candidate -> isSameFamily(currentApp, candidate) }
    }

    private fun buildPlannedActionContext(
        actionName: String,
        args: Map<String, String>,
        actionText: String
    ): PlannedActionContext {
        val state = worldStateManager.getState()
        val targetLabel = inferTargetLabel(actionName = actionName, args = args, candidates = state.actionCandidates)
        return PlannedActionContext(
            toolName = actionName,
            args = args,
            normalizedTargetLabel = targetLabel,
            actionText = actionText,
            pageSignatureBefore = state.pageSignature,
            screenTopTexts = state.visibleTexts.take(8)
        )
    }

    private data class CandidatePoint(
        val role: String,
        val label: String,
        val point: Pair<Int, Int>
    )

    private fun parseCandidatePoints(rawCandidates: List<String>): List<CandidatePoint> {
        if (rawCandidates.isEmpty()) return emptyList()
        return rawCandidates.mapNotNull { raw ->
            val role = Regex("""role=([^|/]+)""")
                .find(raw)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.uppercase()
                ?: Regex("""^\s*\[([A-Z_]+)]""")
                    .find(raw)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()
                    ?.uppercase()
                ?: ""
            val label = Regex("""label=([^|]+)""")
                .find(raw)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: Regex("""\[[^\]]+]\s*"([^"]*)"""")
                    .find(raw)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()
                    .orEmpty()

            val pointMatch = Regex("""point=<point>\s*(-?\d+)\s+(-?\d+)\s*</point>""")
                .find(raw)
                ?: Regex("""point\(\s*(-?\d+)\s*,\s*(-?\d+)\s*\)""")
                    .find(raw)
                ?: Regex("""tap=<point>\s*(-?\d+)\s+(-?\d+)\s*</point>""")
                    .find(raw)
                ?: return@mapNotNull null

            val x = pointMatch.groupValues.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            val y = pointMatch.groupValues.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null
            CandidatePoint(role = role, label = label, point = x to y)
        }
    }

    private fun inferTargetLabel(
        actionName: String,
        args: Map<String, String>,
        candidates: List<String>
    ): String? {
        val explicit = args["label"]?.trim()
            .orEmpty()
            .ifBlank { args["text"]?.trim().orEmpty() }
            .ifBlank { args["name"]?.trim().orEmpty() }
        if (explicit.isNotBlank()) return explicit.take(60)
        if (!actionName.equals("click", ignoreCase = true) && !actionName.equals("long_press", ignoreCase = true)) {
            return null
        }
        val point = parsePointFromArgs(args) ?: return null
        val nearest = parseCandidatePoints(candidates).minByOrNull { candidate ->
            manhattanDistance(candidate.point, point)
        } ?: return null
        return nearest.label.trim().ifBlank { null }?.take(60)
    }

    private fun normalizeAppName(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return value.trim().lowercase()
    }

    private fun isSameFamily(current: String, target: String): Boolean {
        if (current == target) return true
        if (looksLikePackageIdentifier(current) && looksLikePackageIdentifier(target)) {
            return current == target
        }
        if (current.startsWith("$target.") || target.startsWith("$current.")) return true

        // Handle package-vs-app-name mixed observations.
        val currentTail = current.substringAfterLast('.')
        val targetTail = target.substringAfterLast('.')
        return currentTail == targetTail
    }

    private fun looksLikePackageIdentifier(value: String): Boolean {
        if (value.isBlank()) return false
        return Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+").matches(value)
    }

    private suspend fun executeAndRecord(
        args: Map<String, String>,
        actionText: String,
        plannedActionContext: PlannedActionContext
    ): String {
        val result = delegate.execute(args)
        updateResolvedTargetPackage(actionText, args, result)
        worldStateManager.updateAfterAction(
            action = actionText,
            result = result,
            plannedActionContext = plannedActionContext
        )

        // #4A: If type failed, try clicking nearest INPUT and retrying once.
        if (isTextEntryTool(name) && isTypeFailureResult(result)) {
            val retryResult = retryTypeAfterFocusRecovery(args)
            if (retryResult != null) {
                val retryActionText = "$actionText (retry_after_focus)"
                worldStateManager.updateAfterAction(
                    action = retryActionText,
                    result = retryResult,
                    plannedActionContext = plannedActionContext,
                    actionOrigin = ActionOrigin.SYSTEM
                )
                return retryResult
            }
        }

        return result
    }

    private fun updateResolvedTargetPackage(
        actionText: String,
        args: Map<String, String>,
        result: String
    ) {
        val packageName = when {
            name.equals("resolve_installed_app", ignoreCase = true) -> {
                extractPackageName(actionText)
                    ?: extractPackageName(result)
            }
            name.equals("open_app", ignoreCase = true) -> {
                args["package_name"]?.trim()?.takeIf { it.isNotBlank() && looksLikePackageName(it) }
                    ?: extractPackageName(actionText)
                    ?: extractPackageName(result)
            }
            else -> null
        } ?: return

        worldStateManager.setResolvedTargetPackage(packageName)
    }

    private fun extractPackageName(text: String): String? {
        return Regex("""package_name\s*=\s*([a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)+)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun looksLikePackageName(value: String): Boolean {
        if (value.isBlank()) return false
        return Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+").matches(value)
    }

    private fun isTypeFailureResult(result: String): Boolean {
        val lower = result.lowercase()
        return lower.contains("error") ||
            result.contains("失败") ||
            result.contains("错误") ||
            lower.contains("no focused") ||
            lower.contains("no input")
    }

    private suspend fun retryTypeAfterFocusRecovery(args: Map<String, String>): String? {
        val state = worldStateManager.getState()
        val inputCandidate = findInputCandidate(state.actionCandidates) ?: return null

        return try {
            val focused = performInputFocusClick(
                inputCandidate = inputCandidate,
                reason = "input_focus_recovery",
                resultPrefix = "重试前自动聚焦输入框"
            )
            if (!focused) return null
            kotlinx.coroutines.delay(200)
            delegate.execute(args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun performInputFocusClick(
        inputCandidate: String,
        reason: String,
        resultPrefix: String
    ): Boolean {
        val point = extractPointFromCandidate(inputCandidate) ?: return false
        val clickTool = findClickTool() ?: return false
        val label = extractLabelFromCandidate(inputCandidate).take(60)
        val pointArg = "${point.first} ${point.second}"
        val focusArgs = buildMap {
            put("point", pointArg)
            if (label.isNotBlank()) put("label", label)
        }
        val actionText = if (label.isBlank()) {
            "click($reason)"
        } else {
            "click($reason: $label)"
        }
        val plannedActionContext = buildPlannedActionContext(
            actionName = "click",
            args = focusArgs,
            actionText = actionText
        )
        return try {
            val clickResult = clickTool.execute(mapOf("point" to pointArg))
            val recordedResult = "$resultPrefix: ${clickResult.replace(Regex("\\s+"), " ").trim().take(160)}"
            worldStateManager.updateAfterAction(
                action = actionText,
                result = recordedResult,
                plannedActionContext = plannedActionContext,
                actionOrigin = ActionOrigin.SYSTEM
            )
            !isUnreliableResult(recordedResult)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("ConstrainedSafetyWrappedTool", "$reason click failed: ${e.message}")
            false
        }
    }

    private fun findInputCandidate(candidates: List<String>): String? {
        return candidates.firstOrNull { raw ->
            raw.contains("role=INPUT", ignoreCase = true) ||
                raw.contains("[input]", ignoreCase = true) ||
                raw.contains("editable", ignoreCase = true) ||
                isSearchLikeCandidate(raw)
        }
    }

    private fun isTextEntryTool(actionName: String): Boolean {
        return actionName.equals("type", ignoreCase = true) ||
            actionName.equals("type_and_enter", ignoreCase = true)
    }

    private fun isSearchLikeCandidate(raw: String): Boolean {
        if (extractPointFromCandidate(raw) == null) return false
        val label = extractLabelFromCandidate(raw)
            .ifBlank { raw }
            .lowercase()
        return label.contains("搜索") ||
            label.contains("search")
    }

    private fun findClickTool(): Tool? {
        return siblingTools.firstOrNull { it.name.equals("click", ignoreCase = true) }
    }

    private fun extractLabelFromCandidate(raw: String): String {
        return Regex("""label=([^|]+)""")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: Regex("""\[[^\]]+]\s*"([^"]*)"""")
                .find(raw)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
            .orEmpty()
    }

    private fun extractPointFromCandidate(raw: String): Pair<Int, Int>? {
        val match = Regex("""point=<point>\s*(-?\d+)\s+(-?\d+)\s*</point>""").find(raw)
            ?: Regex("""tap=<point>\s*(-?\d+)\s+(-?\d+)\s*</point>""").find(raw)
            ?: Regex("""point\(\s*(-?\d+)\s*,\s*(-?\d+)\s*\)""").find(raw)
            ?: return null
        val x = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val y = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        return x to y
    }

    private fun suppressConsecutiveNearDuplicateClick(
        actionName: String,
        args: Map<String, String>
    ): String? {
        if (!actionName.equals("click", ignoreCase = true)) return null

        val state = worldStateManager.getState()
        val currentPoint = parsePointFromArgs(args) ?: return null
        val lastRecord = state.recentActions.lastOrNull() ?: return null
        if (isSuppressedResult(lastRecord.result)) return null

        val lastActionName = normalizeActionName(lastRecord.action)
        if (!lastActionName.equals("click", ignoreCase = true)) return null

        val previousPoint = parsePointFromAction(lastRecord.action) ?: return null
        val distance = manhattanDistance(currentPoint, previousPoint)
        val previousMadeProgress = lastRecord.resultCode == ActionResultCode.SUCCESS

        if (distance <= 12 && !previousMadeProgress) {
            return "Error: 连续同点点击且无进展已抑制。请尝试不同区域或其他工具。"
        }
        return null
    }

    private fun suppressDuplicateMessageResend(
        actionName: String,
        args: Map<String, String>
    ): String? {
        if (!actionName.equals("type", ignoreCase = true) &&
            !actionName.equals("type_and_enter", ignoreCase = true)
        ) {
            return null
        }

        val requestedText = listOf("text", "content", "message")
            .firstNotNullOfOrNull { key -> args[key]?.trim()?.takeIf { it.isNotBlank() } }
            ?: return null
        val normalizedText = normalizeForDuplicateMessageCheck(requestedText)
        if (normalizedText.isBlank()) return null

        val state = worldStateManager.getState()
        val messageReady = state.pageCapabilities.contains(CapabilityState.CAN_INPUT_TEXT) &&
            state.pageCapabilities.contains(CapabilityState.CAN_SUBMIT_TEXT)
        if (!messageReady) return null
        if (!state.visibleTexts.any { normalizeForDuplicateMessageCheck(it) == normalizedText }) return null
        if (state.actionCandidates.any { candidateContainsSubmittedText(it = it, normalizedText = normalizedText) }) {
            return null
        }

        val recent = state.recentActions.takeLast(8)
        val inputIndex = recent.indexOfLast { record ->
            normalizeActionName(record.action) in setOf("type", "type_and_enter") &&
                !isUnreliableResult(record.result) &&
                normalizeForDuplicateMessageCheck(record.action).contains(normalizedText)
        }
        if (inputIndex < 0) return null

        val submittedAfter = recent.drop(inputIndex + 1).any { record ->
            val action = normalizeActionName(record.action)
            !isUnreliableResult(record.result) &&
                action in setOf("click", "type_and_enter", "press_enter")
        }
        if (!submittedAfter) return null

        return "Error: 检测到相同消息内容已成功发送并出现在当前会话中，已抑制重复发送。"
    }

    private fun candidateContainsSubmittedText(it: String, normalizedText: String): Boolean {
        val candidateText = normalizeForDuplicateMessageCheck(it)
        if (!candidateText.contains(normalizedText)) return false
        return it.contains("[INPUT]", ignoreCase = true) ||
            it.contains("role=INPUT", ignoreCase = true) ||
            it.contains("editable", ignoreCase = true)
    }

    private fun isSuppressedResult(result: String): Boolean {
        return result.contains("suppressed", ignoreCase = true) ||
            result.contains("抑制") ||
            result.contains("已触发临时脱环")
    }

    private fun normalizeForDuplicateMessageCheck(text: String): String {
        return text.replace(Regex("\\s+"), "")
            .replace("“", "")
            .replace("”", "")
            .replace("\"", "")
            .trim()
            .lowercase()
    }

    private fun isUnreliableResult(result: String): Boolean {
        return isUnreliableResultText(result)
    }

    private fun normalizeActionName(action: String): String {
        return canonicalActionName(action)
    }

    private fun parsePointFromArgs(args: Map<String, String>): Pair<Int, Int>? {
        val pointRaw = args["point"]
        if (!pointRaw.isNullOrBlank()) {
            parsePoint(pointRaw)?.let { return it }
        }
        val x = args["x"]?.toFloatOrNull()
        val y = args["y"]?.toFloatOrNull()
        if (x == null || y == null) return null
        return x.toInt() to y.toInt()
    }

    private fun parsePointFromAction(action: String): Pair<Int, Int>? {
        return parsePoint(action)
    }

    private fun parsePoint(raw: String): Pair<Int, Int>? {
        val pointPattern = Regex("""<point>\s*(-?\d+(?:\.\d+)?)\s+(-?\d+(?:\.\d+)?)\s*</point>""")
        pointPattern.find(raw)?.let { match ->
            val x = match.groupValues[1].toFloatOrNull()?.toInt() ?: return null
            val y = match.groupValues[2].toFloatOrNull()?.toInt() ?: return null
            return x to y
        }

        val nums = Regex("""-?\d+(?:\.\d+)?""").findAll(raw)
            .mapNotNull { it.value.toFloatOrNull() }
            .take(2)
            .toList()
        if (nums.size < 2) return null
        return nums[0].toInt() to nums[1].toInt()
    }

    private fun manhattanDistance(a: Pair<Int, Int>, b: Pair<Int, Int>): Int {
        return abs(a.first - b.first) + abs(a.second - b.second)
    }
}
