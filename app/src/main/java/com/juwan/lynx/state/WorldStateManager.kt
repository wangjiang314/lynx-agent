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

package com.juwan.lynx.state

import android.util.Log
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.juwan.lynx.agent.Strategy
import com.juwan.lynx.agent.FlowTraceLogger
import com.juwan.lynx.agent.TraceLogFields
import com.juwan.lynx.safety.PlannedActionContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * Manages the lifecycle of WorldState.
 * Updates state after each action, tracks stuck detection, and generates LLM context.
 */
class WorldStateManager(
    private val screenObserver: ScreenObserver,
    private val worldStateDao: WorldStateDao? = null
) {
    private val tag = "WorldStateManager"
    private var state = WorldState()
    private var runtimeTaskFacts: List<String> = emptyList()
    private val maxRecentActions = 8
    private val maxTaskHistoryActions = 48
    private val maxRecentOutcomes = 16
    private val maxRecentToolResults = 16
    private val maxVerifierDecisions = 8
    private val maxHumanAssistanceEvents = 16
    private val maxVisibleTextFrames = 4
    private val maxConfirmedActionHashes = 32
    private val defaultStuckThreshold = 3
    private val observationTimeoutMs = 7_000L
    private val actionTransitionListeners = linkedMapOf<Long, suspend (ActionTransitionEvent) -> Unit>()
    private var nextActionTransitionListenerId = 0L

    fun addActionTransitionListener(listener: suspend (ActionTransitionEvent) -> Unit): Long {
        nextActionTransitionListenerId += 1
        val listenerId = nextActionTransitionListenerId
        actionTransitionListeners[listenerId] = listener
        return listenerId
    }

    fun removeActionTransitionListener(listenerId: Long) {
        actionTransitionListeners.remove(listenerId)
    }

    fun setRuntimeTaskFacts(facts: List<String>) {
        runtimeTaskFacts = facts
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        state = state.copy(
            pageCapabilities = mergeRuntimeTaskFacts(
                state.pageCapabilities.filterNot(::isRuntimeTaskFact)
            ),
            timestamp = System.currentTimeMillis()
        )
    }

    fun clearRuntimeTaskFacts() {
        runtimeTaskFacts = emptyList()
        state = state.copy(
            pageCapabilities = state.pageCapabilities.filterNot(::isRuntimeTaskFact),
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Update the world state after an action is executed.
     */
    suspend fun updateAfterAction(
        action: String,
        result: String,
        plannedActionContext: PlannedActionContext? = null,
        actionOrigin: ActionOrigin = ActionOrigin.REACT
    ) {
        val previousState = state
        val previousApp = previousState.currentApp
        val settleDelayMs = postActionSettleDelayMs(action, result)
        if (settleDelayMs > 0L) {
            delay(settleDelayMs)
        }

        var snapshot = buildObservationSnapshot(
            observation = captureObservationOrFallback("post_action", previousState),
            previousState = previousState,
            action = action
        )
        captureSettledSnapshot(
            action = action,
            result = result,
            previousState = previousState,
            initialSnapshot = snapshot
        )?.let { settledSnapshot ->
            FlowTraceLogger.event(
                stage = "post_action_settled_capture",
                kv = mapOf(
                    "action" to normalizeActionName(action),
                    "signature_changed" to (settledSnapshot.newSignature != snapshot.newSignature),
                    "visible_changed" to (settledSnapshot.visibleTexts.take(10) != snapshot.visibleTexts.take(10))
                )
            )
            snapshot = settledSnapshot
        }

        var observation = snapshot.observation
        var semanticElements = snapshot.semanticElements
        var typedActionCandidates = snapshot.typedActionCandidates
        var actionCandidates = snapshot.actionCandidates
        var newSignature = snapshot.newSignature
        var newElements = snapshot.newElements
        var textChannels = snapshot.textChannels
        var nextVisibleTexts = snapshot.visibleTexts
        var pageChanged = snapshot.pageChanged
        var rawSignatureChanged = snapshot.rawSignatureChanged

        // Some submit-like clicks update UI with a short delay. Re-sample once to avoid
        // classifying successful submission as "no change" because of early capture.
        if (shouldPerformDelayedRecheck(action, pageChanged, rawSignatureChanged, result)) {
            delay(260L)
            val recaptured = captureObservationOrFallback("delayed_recheck", previousState)
            val refreshedElementsSemantic = recaptured.semanticElements
            val refreshedTypedCandidates = buildActionCandidates(refreshedElementsSemantic, previousState)
            val refreshedCandidates = refreshedTypedCandidates.map { it.toDisplayString() }
            val refreshedElements = refreshedElementsSemantic.filter { it.actionable }.map { it.label }
            val refreshedTextChannels = collectObservationTextChannels(
                observation = recaptured,
                semanticElements = refreshedElementsSemantic,
                typedActionCandidates = refreshedTypedCandidates
            )
            val refreshedVisibleTexts = refreshedTextChannels.visibleTexts
            val refreshedSignature = recaptured.pageSignature
            val refreshedMeaningfulChange = detectMeaningfulChange(
                oldSignature = previousState.pageSignature,
                newSignature = refreshedSignature,
                oldElements = previousState.interactableElements,
                newElements = refreshedElements,
                oldVisibleTexts = previousState.visibleTexts,
                newVisibleTexts = refreshedVisibleTexts,
                action = action
            )
            val refreshedRawChanged = previousState.pageSignature != refreshedSignature

            observation = recaptured
            semanticElements = refreshedElementsSemantic
            typedActionCandidates = refreshedTypedCandidates
            actionCandidates = refreshedCandidates
            newSignature = refreshedSignature
            newElements = refreshedElements
            textChannels = refreshedTextChannels
            nextVisibleTexts = refreshedVisibleTexts
            pageChanged = refreshedMeaningfulChange
            rawSignatureChanged = refreshedRawChanged
        }

        val outcome = buildActionOutcome(
            action = action,
            result = result,
            pageSignatureBefore = previousState.pageSignature,
            pageSignatureAfter = newSignature,
            targetLabel = plannedActionContext?.normalizedTargetLabel,
            meaningfulChanged = pageChanged,
            rawSignatureChanged = rawSignatureChanged
        )
        val record = ActionRecord(
            action = action,
            result = result,
            pageSignatureBefore = previousState.pageSignature,
            pageSignatureAfter = newSignature,
            timestamp = System.currentTimeMillis(),
            pageAffordancesBefore = previousState.pageAffordances,
            pageAffordancesAfter = observation.pageAffordances,
            visibleTextsBefore = previousState.visibleTexts,
            visibleTextsAfter = nextVisibleTexts,
            actionCandidatesBefore = previousState.actionCandidates,
            resultCode = outcome.resultCode,
            targetLabel = plannedActionContext?.normalizedTargetLabel,
            origin = actionOrigin
        )

        val updatedStrategyHistory = (previousState.strategyRecentActions + record).takeLast(maxRecentActions)
        val updatedTaskHistory = (previousState.taskHistoryActions + record).takeLast(maxTaskHistoryActions)
        val updatedOutcomeHistory = (previousState.recentOutcomes + outcome).takeLast(maxRecentOutcomes)
        val toolResult = buildToolResult(
            outcome = outcome,
            result = result,
            meaningfulChanged = pageChanged,
            rawSignatureChanged = rawSignatureChanged
        )
        val updatedToolResults = (previousState.recentToolResults + toolResult).takeLast(maxRecentToolResults)
        val updatedVisibleTextHistory = (previousState.visibleTextsHistory + listOf(nextVisibleTexts))
            .takeLast(maxVisibleTextFrames)
        val newStuckCount = if (pageChanged) 0 else previousState.stuckCount + 1

        state = previousState.copy(
            currentApp = observation.currentApp,
            pageAffordances = observation.pageAffordances,
            pageCapabilities = mergeRuntimeTaskFacts(observation.pageCapabilities),
            pageSignature = newSignature,
            semanticLabels = textChannels.semanticLabels,
            uiTexts = textChannels.uiTexts,
            contentDescriptions = textChannels.contentDescriptions,
            ocrTexts = textChannels.ocrTexts,
            visibleTexts = nextVisibleTexts,
            visibleTextsHistory = updatedVisibleTextHistory,
            interactableElements = newElements,
            typedActionCandidates = typedActionCandidates,
            actionCandidates = actionCandidates,
            strategyRecentActions = updatedStrategyHistory,
            taskHistoryActions = updatedTaskHistory,
            recentActions = updatedStrategyHistory,
            lastAction = action,
            lastActionResult = result,
            recentOutcomes = updatedOutcomeHistory,
            recentToolResults = updatedToolResults,
            stuckCount = newStuckCount,
            totalSteps = previousState.totalSteps + 1,
            screenshotBase64 = observation.screenshotBase64,
            timestamp = System.currentTimeMillis()
        )

        val loggedPage = PageObservationLogging.fromObservation(observation)
        FlowTraceLogger.event(
            stage = "world_update_after_action",
            kv = linkedMapOf<String, Any?>(
                "action" to action,
                "result_code" to updatedOutcomeHistory.lastOrNull()?.resultCode?.name.orEmpty(),
                "tool_result_status" to toolResult.status.name,
                "tool_result_error_type" to toolResult.errorType.orEmpty(),
                "changed" to pageChanged,
                "raw_sig_changed" to rawSignatureChanged,
                "stuck" to newStuckCount,
                "app" to (observation.currentApp ?: "unknown"),
                "fact_affordances" to observation.pageAffordances.joinToString(","),
                "fact_candidate_count" to actionCandidates.size,
                "fact_candidates" to actionCandidates.take(3).joinToString(" / "),
                "debug_semantic_tag" to (observation.debugPageSemanticTag ?: "unknown"),
                "debug_semantic_total" to semanticElements.size,
                "debug_semantic_actionable" to semanticElements.count { it.actionable },
                "debug_semantic_labeled" to semanticElements.count { it.label.isNotBlank() }
            ).apply {
                TraceLogFields.putSurfaceHintFields(this, loggedPage)
            }
        )

        notifyActionTransitionListeners(
            ActionTransitionEvent(
                action = action,
                result = result,
                previousApp = previousApp,
                currentApp = state.currentApp,
                pageSignatureBefore = previousState.pageSignature,
                pageSignatureAfter = state.pageSignature
            )
        )

        persistState(isTaskInProgress = true)
    }

    private suspend fun notifyActionTransitionListeners(event: ActionTransitionEvent) {
        if (actionTransitionListeners.isEmpty()) return
        val listenersSnapshot = actionTransitionListeners.values.toList()
        listenersSnapshot.forEach { listener ->
            runCatching { listener(event) }
                .onFailure { Log.w(tag, "Action transition listener failed: ${it.message}") }
        }
    }

    private fun postActionSettleDelayMs(action: String, result: String): Long {
        if (result.contains("错误", ignoreCase = true) || result.contains("Error", ignoreCase = true)) {
            return 0L
        }
        return when (normalizeActionName(action)) {
            "open_app" -> 700L
            "press_back", "press_home" -> 500L
            "scroll", "drag" -> 420L
            "click", "long_press" -> 320L
            "type", "type_and_enter" -> 250L
            else -> 180L
        }
    }

    private suspend fun captureSettledSnapshot(
        action: String,
        result: String,
        previousState: WorldState,
        initialSnapshot: ObservationSnapshot
    ): ObservationSnapshot? {
        if (!shouldConfirmSettledObservation(action, result)) return null
        var latest = initialSnapshot
        var changedAfterInitial = false
        repeat(2) { attempt ->
            delay(360L + attempt * 120L)
            val recaptured = buildObservationSnapshot(
                observation = captureObservationOrFallback("post_action_settle_${attempt + 1}", previousState),
                previousState = previousState,
                action = action
            )
            if (stableObservationKey(recaptured) == stableObservationKey(latest)) {
                return if (changedAfterInitial) latest else null
            }
            latest = recaptured
            changedAfterInitial = true
        }
        return if (changedAfterInitial) latest else null
    }

    private fun shouldConfirmSettledObservation(action: String, result: String): Boolean {
        if (result.contains("错误", ignoreCase = true) || result.contains("Error", ignoreCase = true)) {
            return false
        }
        return normalizeActionName(action) in setOf("scroll", "drag")
    }

    private fun stableObservationKey(snapshot: ObservationSnapshot): String {
        val visibleKey = normalizeTexts(snapshot.visibleTexts.asSequence())
            .take(10)
            .joinToString("|")
        return "${snapshot.newSignature}|$visibleKey"
    }

    private fun buildObservationSnapshot(
        observation: Observation,
        previousState: WorldState,
        action: String
    ): ObservationSnapshot {
        val semanticElements = observation.semanticElements
        val typedActionCandidates = buildActionCandidates(semanticElements, previousState)
        val actionCandidates = typedActionCandidates.map { it.toDisplayString() }
        val newSignature = observation.pageSignature
        val newElements = semanticElements.filter { it.actionable }.map { it.label }
        val textChannels = collectObservationTextChannels(
            observation = observation,
            semanticElements = semanticElements,
            typedActionCandidates = typedActionCandidates
        )
        val nextVisibleTexts = textChannels.visibleTexts
        val pageChanged = detectMeaningfulChange(
            oldSignature = previousState.pageSignature,
            newSignature = newSignature,
            oldElements = previousState.interactableElements,
            newElements = newElements,
            oldVisibleTexts = previousState.visibleTexts,
            newVisibleTexts = nextVisibleTexts,
            action = action
        )
        val rawSignatureChanged = previousState.pageSignature != newSignature
        return ObservationSnapshot(
            observation = observation,
            semanticElements = semanticElements,
            typedActionCandidates = typedActionCandidates,
            actionCandidates = actionCandidates,
            newSignature = newSignature,
            newElements = newElements,
            textChannels = textChannels,
            visibleTexts = nextVisibleTexts,
            pageChanged = pageChanged,
            rawSignatureChanged = rawSignatureChanged
        )
    }

    private fun buildActionOutcome(
        action: String,
        result: String,
        pageSignatureBefore: String,
        pageSignatureAfter: String,
        targetLabel: String?,
        meaningfulChanged: Boolean,
        rawSignatureChanged: Boolean
    ): ActionOutcome {
        val toolName = normalizeActionName(action)
        val resultCode = classifyActionResultCode(
            actionName = toolName,
            result = result,
            meaningfulChanged = meaningfulChanged,
            rawSignatureChanged = rawSignatureChanged
        )
        return ActionOutcome(
            toolName = toolName,
            actionText = action,
            resultCode = resultCode,
            targetLabel = targetLabel,
            pageSignatureBefore = pageSignatureBefore,
            pageSignatureAfter = pageSignatureAfter,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun buildToolResult(
        outcome: ActionOutcome,
        result: String,
        meaningfulChanged: Boolean,
        rawSignatureChanged: Boolean
    ): ToolResult {
        return ToolResult(
            toolName = outcome.toolName,
            actionText = outcome.actionText,
            status = outcome.resultCode,
            targetLabel = outcome.targetLabel,
            changed = meaningfulChanged,
            rawSignatureChanged = rawSignatureChanged,
            pageSignatureBefore = outcome.pageSignatureBefore,
            pageSignatureAfter = outcome.pageSignatureAfter,
            message = result.replace(Regex("\\s+"), " ").trim().take(240),
            errorType = classifyToolResultErrorType(outcome.resultCode, result),
            timestamp = outcome.timestamp
        )
    }

    private fun classifyToolResultErrorType(status: ActionResultCode, result: String): String? {
        val lower = result.lowercase()
        return when (status) {
            ActionResultCode.BLOCKED -> when {
                lower.contains("blocked_by_user") -> "blocked_by_user"
                lower.contains("blocked_by_safety") -> "blocked_by_safety"
                else -> "blocked"
            }
            ActionResultCode.ERROR -> "tool_error"
            ActionResultCode.NOT_FOUND -> "not_found"
            ActionResultCode.SUPPRESSED -> "suppressed"
            else -> null
        }
    }

    private fun classifyActionResultCode(
        actionName: String,
        result: String,
        meaningfulChanged: Boolean,
        rawSignatureChanged: Boolean
    ): ActionResultCode {
        val lower = result.lowercase()
        if (lower.contains("blocked_by_user") || lower.contains("blocked_by_safety")) {
            return ActionResultCode.BLOCKED
        }
        if (lower.contains("suppressed", ignoreCase = true) || result.contains("抑制")) {
            return ActionResultCode.SUPPRESSED
        }
        if (lower.contains("未找到") || lower.contains("not found")) {
            return ActionResultCode.NOT_FOUND
        }
        if (lower.contains("error") || result.contains("错误") || result.contains("失败")) {
            return ActionResultCode.ERROR
        }
        if (lower.contains("跳过") || lower.contains("no-op") || lower.contains("noop") || lower.contains("already_in_foreground")) {
            return ActionResultCode.NOOP
        }
        if (!meaningfulChanged && actionName == "open_app") {
            return ActionResultCode.NOOP
        }
        if (meaningfulChanged || lower.contains("success") || result.contains("完成") || result.contains("成功")) {
            return ActionResultCode.SUCCESS
        }
        if (rawSignatureChanged && isSubmissionLikeAction(actionName)) {
            return ActionResultCode.UNKNOWN
        }
        return ActionResultCode.UNKNOWN
    }

    private fun normalizeActionName(action: String): String {
        return canonicalActionName(action)
    }

    private suspend fun persistState(isTaskInProgress: Boolean) = withContext(Dispatchers.IO) {
        try {
            worldStateDao?.insertState(state.copy(isTaskInProgress = isTaskInProgress))
        } catch (e: Exception) {
            Log.e(tag, "Failed to persist world state", e)
        }
    }

    suspend fun persistTerminalState(markCompleted: Boolean) = withContext(Dispatchers.IO) {
        try {
            val terminalState = state.copy(
                isTaskInProgress = false,
                stuckCount = if (markCompleted) 0 else state.stuckCount,
                timestamp = System.currentTimeMillis()
            )
            worldStateDao?.insertState(terminalState)
            state = terminalState
        } catch (e: Exception) {
            Log.e(tag, "Failed to persist terminal world state", e)
        }
    }

    suspend fun restoreState(): Boolean = withContext(Dispatchers.IO) {
        try {
            val savedState = worldStateDao?.getCurrentState()
            if (savedState != null && savedState.isTaskInProgress) {
                state = savedState
                Log.i(tag, "Successfully restored task: ${state.goal}")
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to restore world state", e)
        }
        return@withContext false
    }

    private fun detectMeaningfulChange(
        oldSignature: String,
        newSignature: String,
        oldElements: List<String>,
        newElements: List<String>,
        oldVisibleTexts: List<String>,
        newVisibleTexts: List<String>,
        action: String
    ): Boolean {
        val signatureChanged = oldSignature != newSignature
        val changeRatio = calculateElementChangeRatio(oldElements, newElements)
        val textChangeRatio = calculateElementChangeRatio(oldVisibleTexts, newVisibleTexts)

        if (isNavigationGestureAction(action)) {
            if (changeRatio > 0.12f || textChangeRatio > 0.12f) return true
            val noComparableContent = oldElements.isEmpty() &&
                newElements.isEmpty() &&
                oldVisibleTexts.isEmpty() &&
                newVisibleTexts.isEmpty()
            return signatureChanged && noComparableContent
        }

        if (changeRatio > 0.2f) return true

        if (isTextCommitAction(action) && textChangeRatio > 0f) return true

        // For text input / commit actions, small element diffs (e.g., input text changed or cleared)
        // are still meaningful even when page signature is stable.
        if (isInputOrCommitAction(action) && changeRatio > 0f) return true

        if (signatureChanged && changeRatio < 0.05f && oldElements.isNotEmpty()) {
            Log.d(tag, "Page signature changed but elements are similar. Likely animation.")
            return false
        }

        return signatureChanged
    }

    private fun isNavigationGestureAction(action: String): Boolean {
        val normalized = action.substringBefore("(")
            .substringBefore("{")
            .trim()
            .lowercase()
        return normalized == "scroll" || normalized == "drag"
    }

    private fun isInputOrCommitAction(action: String): Boolean {
        val normalized = action.substringBefore("(")
            .substringBefore("{")
            .trim()
            .lowercase()
        return normalized in setOf(
            "type",
            "type_and_enter",
            "click",
            "long_press"
        )
    }

    private fun isSubmissionLikeAction(action: String): Boolean {
        val normalized = action.substringBefore("(")
            .substringBefore("{")
            .trim()
            .lowercase()
        return normalized in setOf("click", "type_and_enter", "long_press")
    }

    private fun isTextCommitAction(action: String): Boolean {
        val normalized = action.substringBefore("(")
            .substringBefore("{")
            .trim()
            .lowercase()
        return normalized in setOf("type", "type_and_enter")
    }

    private fun shouldPerformDelayedRecheck(
        action: String,
        pageChanged: Boolean,
        rawSignatureChanged: Boolean,
        result: String
    ): Boolean {
        if (result.contains("错误", ignoreCase = true) || result.contains("error", ignoreCase = true)) {
            return false
        }
        val normalized = action.substringBefore("(")
            .substringBefore("{")
            .trim()
            .lowercase()
        return normalized in setOf("click", "type_and_enter") &&
            !pageChanged &&
            rawSignatureChanged
    }

    private fun calculateElementChangeRatio(oldList: List<String>, newList: List<String>): Float {
        if (oldList.isEmpty() && newList.isEmpty()) return 0f
        if (oldList.isEmpty() || newList.isEmpty()) return 1f
        val oldSet = oldList.toSet()
        val newSet = newList.toSet()
        val totalChanges = (newSet - oldSet).size + (oldSet - newSet).size
        return totalChanges.toFloat() / maxOf(oldList.size, newList.size)
    }

    private fun collectObservationTextChannels(
        observation: Observation,
        semanticElements: List<SemanticElement>,
        typedActionCandidates: List<ActionCandidate>
    ): ObservationTextChannels {
        val semanticLabels = normalizeTexts(
            semanticElements.asSequence().map { it.label }
        ).take(40)

        val uiTexts = normalizeTexts(
            observation.uiTree.orEmpty().asSequence().mapNotNull { it.text }
        ).take(40)

        val contentDescriptions = normalizeTexts(
            observation.uiTree.orEmpty().asSequence().mapNotNull { it.contentDescription }
        ).take(32)

        val ocrTexts = normalizeTexts(
            observation.ocrTexts.orEmpty().asSequence().map { it.text }
        ).take(40)

        val candidateLabels = normalizeTexts(
            typedActionCandidates.asSequence().map { it.label }
        ).take(20)

        val visibleTexts = normalizeTexts(
            sequenceOf(
                semanticLabels.asSequence(),
                uiTexts.asSequence(),
                contentDescriptions.asSequence(),
                ocrTexts.asSequence(),
                candidateLabels.asSequence()
            ).flatten()
        ).take(120)

        return ObservationTextChannels(
            semanticLabels = semanticLabels,
            uiTexts = uiTexts,
            contentDescriptions = contentDescriptions,
            ocrTexts = ocrTexts,
            visibleTexts = visibleTexts
        )
    }

    private fun normalizeTexts(values: Sequence<String>): List<String> {
        return values
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .filter { it.length in 1..80 }
            .distinct()
            .toList()
    }

    private fun extractCandidateLabel(raw: String): String? {
        val keyed = Regex("""label=([^|/]+)""")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
        if (!keyed.isNullOrBlank()) return keyed

        val bracketed = Regex("""\[[^\]]+]\s*"([^"]*)"""")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
        return bracketed?.takeIf { it.isNotBlank() }
    }

    fun setStrategy(strategy: Strategy) {
        applyStrategyState(
            goal = strategy.goal,
            targetApp = strategy.app,
            strategySteps = strategy.strategy,
            successCriteria = strategy.successCriteria,
            riskHints = strategy.riskHints
        )
    }

    fun setStrategy(goal: String, strategy: List<String>) {
        applyStrategyState(
            goal = goal,
            targetApp = "",
            strategySteps = strategy,
            successCriteria = emptyList(),
            riskHints = emptyList()
        )
    }

    private fun applyStrategyState(
        goal: String,
        targetApp: String,
        strategySteps: List<String>,
        successCriteria: List<String>,
        riskHints: List<String>
    ) {
        state = state.copy(
            goal = goal,
            targetApp = targetApp,
            strategy = strategySteps,
            successCriteria = successCriteria,
            riskHints = riskHints,
            isTaskInProgress = true,
            strategyRecentActions = emptyList(),
            recentActions = emptyList(),
            recentOutcomes = emptyList(),
            recentToolResults = emptyList(),
            verifierDecisions = emptyList(),
            completionEvidence = emptyList(),
            missingEvidence = emptyList(),
            humanAssistanceEvents = emptyList(),
            pendingConfirmation = null,
            confirmedActionHashes = emptyList(),
            resolvedTargetPackage = null,
            visibleTextsHistory = emptyList(),
            totalSteps = 0,
            lastAction = null,
            lastActionResult = null,
            stuckCount = 0
        )
    }

    suspend fun recordVerifierDecision(decision: VerifierDecision) {
        val normalizedDecision = decision.copy(
            evidence = decision.evidence.map { it.trim() }.filter { it.isNotBlank() }.take(8),
            missing = decision.missing.map { it.trim() }.filter { it.isNotBlank() }.take(8),
            nextHint = decision.nextHint.trim().take(240)
        )
        state = state.copy(
            verifierDecisions = (state.verifierDecisions + normalizedDecision).takeLast(maxVerifierDecisions),
            completionEvidence = normalizedDecision.evidence,
            missingEvidence = normalizedDecision.missing,
            timestamp = System.currentTimeMillis()
        )
        persistState(isTaskInProgress = state.isTaskInProgress)
    }

    suspend fun recordHumanAssistanceEvent(event: HumanAssistanceEvent) {
        val normalizedEvent = event.copy(
            prompt = event.prompt.replace(Regex("\\s+"), " ").trim().take(300),
            responseText = event.responseText?.replace(Regex("\\s+"), " ")?.trim()?.take(120),
            reason = event.reason?.replace(Regex("\\s+"), " ")?.trim()?.take(160)
        )
        state = state.copy(
            humanAssistanceEvents = (state.humanAssistanceEvents + normalizedEvent)
                .takeLast(maxHumanAssistanceEvents),
            timestamp = System.currentTimeMillis()
        )
        persistState(isTaskInProgress = state.isTaskInProgress)
    }

    fun setResolvedTargetPackage(packageName: String?) {
        state = state.copy(
            resolvedTargetPackage = packageName?.trim()?.takeIf { it.isNotBlank() },
            timestamp = System.currentTimeMillis()
        )
    }

    fun getResolvedTargetPackage(): String? = state.resolvedTargetPackage

    fun setPendingConfirmation(pending: PendingConfirmation?) {
        state = state.copy(
            pendingConfirmation = pending,
            timestamp = System.currentTimeMillis()
        )
    }

    fun confirmPendingActionHash(actionHash: String) {
        if (actionHash.isBlank()) return
        val updated = (state.confirmedActionHashes + actionHash).distinct().takeLast(maxConfirmedActionHashes)
        val pending = state.pendingConfirmation
        state = state.copy(
            pendingConfirmation = if (pending?.actionHash == actionHash) null else pending,
            confirmedActionHashes = updated,
            timestamp = System.currentTimeMillis()
        )
    }

    fun consumeConfirmedActionHash(actionHash: String): Boolean {
        if (actionHash.isBlank()) return false
        if (!state.confirmedActionHashes.contains(actionHash)) return false
        state = state.copy(
            confirmedActionHashes = state.confirmedActionHashes.filterNot { it == actionHash },
            timestamp = System.currentTimeMillis()
        )
        return true
    }

    fun toPlannerContext(): String = buildString {
        appendLine("=== 当前状态 ===")
        appendLine("App: ${state.currentApp ?: "桌面"}")
        if (state.pageCapabilities.isNotEmpty()) {
            appendLine("当前能力: ${state.pageCapabilities.joinToString(", ")}")
        }
        if (!state.resolvedTargetPackage.isNullOrBlank()) {
            appendLine("目标包名: ${state.resolvedTargetPackage}")
        }
        if (state.recentActions.isNotEmpty()) {
            appendLine("最近操作:")
            state.recentActions.takeLast(4).forEach { record ->
                val changed = record.pageSignatureBefore != record.pageSignatureAfter
                appendLine("  - ${record.action} ${if (changed) "→变化" else "→未变"} ${record.result}")
            }
        }
        if (state.stuckCount > 0) appendLine("⚠️ 连续 ${state.stuckCount} 步页面无变化")
    }

    fun toPromptContext(): String = toPlannerContext()

    fun getState(): WorldState = state

    /**
     * Lightweight observation refresh API.
     * Captures latest screenshot/UI context and updates in-memory WorldState without recording an action.
     * Useful before vision-model inference to ensure screenshotBase64 is fresh.
     */
    suspend fun refreshObservation() {
        refreshObservationAndReturn()
    }

    suspend fun refreshObservationAndReturn(): Observation? {
        try {
            delay(250L)
            val observation = captureObservationOrFallback("refresh", state)
            applyPassiveObservation(observation)
            return observation
        } catch (e: Exception) {
            Log.w(tag, "Failed to refresh observation", e)
            return null
        }
    }

    private suspend fun captureObservationOrFallback(context: String, fallbackState: WorldState): Observation {
        val captured = withTimeoutOrNull(observationTimeoutMs) {
            withContext(Dispatchers.IO) {
                screenObserver.capture()
            }
        }
        if (captured != null) return captured

        FlowTraceLogger.warn(
            stage = "observation_capture_timeout",
            kv = mapOf(
                "context" to context,
                "timeout_ms" to observationTimeoutMs,
                "fallback_signature" to fallbackState.pageSignature
            )
        )
        Log.w(tag, "Observation capture timed out in $context after ${observationTimeoutMs}ms")
        return Observation(
            screenshotBase64 = fallbackState.screenshotBase64.orEmpty(),
            uiTree = null,
            ocrTexts = null,
            pageSignature = fallbackState.pageSignature.ifBlank { "observation_timeout" },
            currentApp = fallbackState.currentApp,
            debugPageLabel = "observation_timeout",
            debugPageSemanticTag = null,
            debugPageSemanticConfidence = 0f,
            debugPageTitleHint = null,
            pageAffordances = fallbackState.pageAffordances,
            pageCapabilities = fallbackState.pageCapabilities,
            debugPageSignals = listOf("observation_timeout:$context"),
            semanticElements = emptyList()
        )
    }

    fun isStuck(threshold: Int = defaultStuckThreshold): Boolean {
        val normalizedThreshold = threshold.coerceAtLeast(1)
        return state.stuckCount >= normalizedThreshold
    }

    fun resetForNewTask() {
        state = WorldState()
        runtimeTaskFacts = emptyList()
    }

    private fun applyPassiveObservation(observation: Observation) {
        val semanticElements = observation.semanticElements
        val nextTypedActionCandidates = buildActionCandidates(semanticElements, state)
        val nextActionCandidates = nextTypedActionCandidates.map { it.toDisplayString() }
        val textChannels = collectObservationTextChannels(
            observation = observation,
            semanticElements = semanticElements,
            typedActionCandidates = nextTypedActionCandidates
        )
        val nextVisibleTexts = textChannels.visibleTexts

        state = state.copy(
            currentApp = observation.currentApp,
            pageAffordances = observation.pageAffordances,
            pageCapabilities = mergeRuntimeTaskFacts(observation.pageCapabilities),
            pageSignature = observation.pageSignature,
            semanticLabels = textChannels.semanticLabels,
            uiTexts = textChannels.uiTexts,
            contentDescriptions = textChannels.contentDescriptions,
            ocrTexts = textChannels.ocrTexts,
            visibleTexts = nextVisibleTexts,
            visibleTextsHistory = (state.visibleTextsHistory + listOf(nextVisibleTexts))
                .takeLast(maxVisibleTextFrames),
            interactableElements = semanticElements.filter { it.actionable }.map { it.label },
            typedActionCandidates = nextTypedActionCandidates,
            actionCandidates = nextActionCandidates,
            screenshotBase64 = observation.screenshotBase64,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun mergeRuntimeTaskFacts(baseCapabilities: List<String>): List<String> {
        val sanitizedBase = baseCapabilities
            .map { it.trim() }
            .filter { it.isNotBlank() && !isRuntimeTaskFact(it) }
        return (sanitizedBase + runtimeTaskFacts)
            .distinct()
            .take(20)
    }

    private fun isRuntimeTaskFact(value: String): Boolean {
        return value.startsWith("task.")
    }

    private data class RankedActionCandidate(
        val element: SemanticElement,
        val score: Int,
        val kind: CandidateKind,
        val stateHint: String?,
        val editable: Boolean
    )

    private data class CandidateGroupAssignment(
        val groupId: String,
        val indexInGroup: Int
    )

    private data class ObservationTextChannels(
        val semanticLabels: List<String>,
        val uiTexts: List<String>,
        val contentDescriptions: List<String>,
        val ocrTexts: List<String>,
        val visibleTexts: List<String>
    )

    private data class ObservationSnapshot(
        val observation: Observation,
        val semanticElements: List<SemanticElement>,
        val typedActionCandidates: List<ActionCandidate>,
        val actionCandidates: List<String>,
        val newSignature: String,
        val newElements: List<String>,
        val textChannels: ObservationTextChannels,
        val visibleTexts: List<String>,
        val pageChanged: Boolean,
        val rawSignatureChanged: Boolean
    )

    private fun buildActionCandidates(
        semanticElements: List<SemanticElement>,
        planningState: WorldState
    ): List<ActionCandidate> {
        val elements = semanticElements
        if (elements.isEmpty()) return emptyList()

        val maxRight = elements.maxOfOrNull { it.bounds.right }?.coerceAtLeast(1) ?: 1
        val maxBottom = elements.maxOfOrNull { it.bounds.bottom }?.coerceAtLeast(1) ?: 1
        val intentTokens = extractIntentTokens(
            listOf(planningState.goal, planningState.strategy.joinToString(" "))
                .filter { it.isNotBlank() }
                .joinToString(" ")
        )
        val conversationIntent = isConversationIntent(intentTokens)
        val rawConversationSurface = conversationIntent ||
            planningState.pageAffordances.contains("has_message_container") ||
            planningState.pageAffordances.contains("has_bottom_composer") ||
            planningState.pageAffordances.contains("has_send_button")
        val denseContentSurface = isDenseContentSurface(
            planningState = planningState,
            semanticElements = elements,
            conversationSurface = rawConversationSurface
        )
        val conversationSurface = rawConversationSurface && !denseContentSurface

        val ranked = elements
            .asSequence()
            .filter { element ->
                val bottomRegion = element.center.y >= (maxBottom * 0.55f).toInt()
                val hasUsefulLabel = element.label.isNotBlank()
                val likelyInputRegion = element.role == ElementRole.INPUT || parseBoolAttr(element, "editable")
                val compositeConversationContainer = conversationSurface &&
                    conversationIdentitySegments(element.label).size >= 2 &&
                    isWideConversationContainer(
                        element = element,
                        maxRight = maxRight,
                        maxBottom = maxBottom
                    )
                (element.actionable || (hasUsefulLabel && (bottomRegion || likelyInputRegion))) &&
                    !compositeConversationContainer
            }
            .map { element ->
                val editable = element.role == ElementRole.INPUT || parseBoolAttr(element, "editable")
                val kind = inferCandidateKind(
                    element = element,
                    maxRight = maxRight,
                    maxBottom = maxBottom
                )
                var score = 0
                if (element.actionable) score += 120
                if (kind == CandidateKind.INPUT) score += 60
                if (kind == CandidateKind.ACTION) score += 45
                if (kind == CandidateKind.COLLECTION_ITEM) score += 30
                if (kind == CandidateKind.MEDIA) score += 24
                if (kind == CandidateKind.TOGGLE) score += 24
                if (kind == CandidateKind.NAVIGATION) score -= 18
                if (kind == CandidateKind.DISMISS) score -= 12
                if (editable) score += 35
                if (parseBoolAttr(element, "clickable")) score += 20
                if (element.center.y >= (maxBottom * 0.6f).toInt()) score += 16
                if (element.center.y <= (maxBottom * 0.2f).toInt()) score -= 8

                val rawLabel = element.label
                val label = rawLabel.lowercase()
                val labelTokens = extractIntentTokens(label)
                val overlap = intentTokens.intersect(labelTokens).size
                if (overlap > 0) {
                    score += overlap * 16
                } else if (intentTokens.isNotEmpty() && label.isNotBlank()) {
                    val fuzzyHit = intentTokens.any { token ->
                        label.contains(token, ignoreCase = true) || token.contains(label)
                    }
                    if (fuzzyHit) score += 12
                }

                if (conversationSurface) {
                    if (isConversationEntryLabel(label)) score += 28
                    if (looksLikeFeedNavigationLabel(label)) score -= 30
                    val identitySegmentCount = conversationIdentitySegments(rawLabel).size
                    if (identitySegmentCount >= 2) {
                        score -= 56
                    }
                    if (identitySegmentCount >= 2 &&
                        isWideConversationContainer(
                            element = element,
                            maxRight = maxRight,
                            maxBottom = maxBottom
                        )
                    ) {
                        score -= 52
                    }
                    if (element.role == ElementRole.CONTAINER &&
                        isWideConversationContainer(
                            element = element,
                            maxRight = maxRight,
                            maxBottom = maxBottom
                        )
                    ) {
                        score -= 24
                    }
                }

                if (denseContentSurface) {
                    if (element.role == ElementRole.LINK) score += 40
                    if (kind == CandidateKind.COLLECTION_ITEM) score += 28
                    if (kind == CandidateKind.MEDIA) score += 18
                    if (kind == CandidateKind.NAVIGATION) score -= 10
                    if (kind == CandidateKind.DISMISS) score -= 8
                    if (element.actionable && isCollectionSurfaceElement(element, maxRight, maxBottom)) {
                        score += 18
                    }
                    if (looksLikeReferenceSummaryLabel(rawLabel)) {
                        score += 44
                    }
                }

                if (label.contains("返回") || label.contains("back")) score -= 18

                RankedActionCandidate(
                    element = element,
                    score = score,
                    kind = kind,
                    stateHint = candidateStateHint(element),
                    editable = editable
                )
            }
            .sortedWith(
                compareByDescending<RankedActionCandidate> { it.score }
                    .thenBy { it.element.center.y }
                    .thenBy { it.element.center.x }
            )
            .toList()

        val deduped = linkedMapOf<String, RankedActionCandidate>()
        val dedupBucketDivisor = if (denseContentSurface) 18 else 28
        ranked.forEach { candidate ->
            val bucket = "${candidate.element.center.x / dedupBucketDivisor},${candidate.element.center.y / dedupBucketDivisor}"
            deduped.putIfAbsent(bucket, candidate)
        }

        val selected = deduped.values.take(if (denseContentSurface) 24 else 14).toList()
        val groupAssignments = assignCandidateGroups(selected, maxBottom)

        return selected
            .map { candidate ->
                val element = candidate.element
                val nx = (element.center.x.toFloat() * 1000f / maxRight).roundToInt().coerceIn(0, 1000)
                val ny = (element.center.y.toFloat() * 1000f / maxBottom).roundToInt().coerceIn(0, 1000)
                val label = candidateLabel(
                    element = element,
                    conversationSurface = conversationSurface
                )
                val assignment = groupAssignments[element.id]
                ActionCandidate(
                    id = element.id.take(32),
                    kind = candidate.kind,
                    role = element.role.name,
                    label = label,
                    actionable = element.actionable,
                    editable = candidate.editable,
                    pointX = nx,
                    pointY = ny,
                    boundsLeft = element.bounds.left,
                    boundsTop = element.bounds.top,
                    boundsRight = element.bounds.right,
                    boundsBottom = element.bounds.bottom,
                    groupId = assignment?.groupId,
                    indexInGroup = assignment?.indexInGroup,
                    stateHint = candidate.stateHint,
                    confidence = (candidate.score.coerceIn(0, 200) / 200f)
                )
            }
            .toList()
    }

    private fun assignCandidateGroups(
        candidates: List<RankedActionCandidate>,
        maxBottom: Int
    ): Map<String, CandidateGroupAssignment> {
        val eligible = candidates.filter { candidate ->
            (
                candidate.kind in setOf(CandidateKind.COLLECTION_ITEM, CandidateKind.MEDIA) ||
                    (candidate.kind == CandidateKind.ACTION && candidate.element.role == ElementRole.LINK)
                ) &&
                candidate.element.center.y > (maxBottom * 0.14f).toInt() &&
                candidate.element.center.y < (maxBottom * 0.94f).toInt()
        }
        if (eligible.size < 2) return emptyMap()

        val assignments = mutableMapOf<String, CandidateGroupAssignment>()
        eligible.groupBy { it.kind }.forEach { (kind, members) ->
            if (members.size < 2) return@forEach
            val ordered = members.sortedWith(
                compareBy<RankedActionCandidate> { it.element.center.y / 24 }
                    .thenBy { it.element.center.x }
            )
            val groupId = when {
                kind == CandidateKind.ACTION && ordered.firstOrNull()?.element?.role == ElementRole.LINK -> "link_main"
                else -> "${kind.name.lowercase()}_main"
            }
            ordered.forEachIndexed { index, candidate ->
                assignments[candidate.element.id] = CandidateGroupAssignment(
                    groupId = groupId,
                    indexInGroup = index + 1
                )
            }
        }
        return assignments
    }

    private fun extractIntentTokens(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val stopwords = setOf(
            "当前", "页面", "任务", "目标", "状态", "步骤", "完成", "进入", "并", "然后",
            "the", "and", "with", "task", "state", "goal"
        )
        val pieces = Regex("""[\p{IsHan}]{2,}|[a-zA-Z0-9_]{3,}""")
            .findAll(text)
            .map { it.value.trim() }
            .filter { it.isNotBlank() }
            .toList()

        val tokens = linkedSetOf<String>()
        for (piece in pieces) {
            if (piece.all { it.code in 0x4E00..0x9FFF }) {
                val compact = piece.take(10)
                if (compact.length <= 3) {
                    tokens += compact
                } else {
                    for (size in 2..3) {
                        if (compact.length < size) continue
                        for (i in 0..(compact.length - size)) {
                            tokens += compact.substring(i, i + size)
                        }
                    }
                }
            } else {
                tokens += piece.lowercase()
            }
        }
        return tokens
            .map { it.trim().lowercase() }
            .filter { it.length >= 2 && it !in stopwords }
            .toSet()
    }

    private fun parseBoolAttr(element: SemanticElement, key: String): Boolean {
        return element.attributes[key]?.equals("true", ignoreCase = true) == true
    }

    private fun isConversationIntent(intentTokens: Set<String>): Boolean {
        if (intentTokens.isEmpty()) return false
        val conversationTokens = setOf(
            "消息", "发消息", "私信", "聊天", "会话", "好友", "联系人",
            "message", "chat", "friend", "contact", "reply", "send"
        )
        return intentTokens.any { token ->
            conversationTokens.any { marker ->
                token.contains(marker, ignoreCase = true) || marker.contains(token, ignoreCase = true)
            }
        }
    }

    private fun inferCandidateKind(
        element: SemanticElement,
        maxRight: Int,
        maxBottom: Int
    ): CandidateKind {
        val label = element.label.lowercase()
        val editable = element.role == ElementRole.INPUT || parseBoolAttr(element, "editable")
        if (editable) return CandidateKind.INPUT
        if (element.role == ElementRole.CHECKBOX || element.role == ElementRole.SWITCH) {
            return CandidateKind.TOGGLE
        }
        if (labelMatchesAny(label, listOf("返回", "back", "返回上一页"))) {
            return CandidateKind.NAVIGATION
        }
        if (labelMatchesAny(label, listOf("关闭", "取消", "close", "cancel", "跳过", "skip", "以后再说", "稍后"))) {
            return CandidateKind.DISMISS
        }
        if (element.role == ElementRole.LIST_ITEM) {
            return CandidateKind.COLLECTION_ITEM
        }
        if (element.role == ElementRole.IMAGE && isCollectionSurfaceElement(element, maxRight, maxBottom)) {
            return CandidateKind.MEDIA
        }
        if (element.role == ElementRole.CONTAINER && element.actionable && isCollectionSurfaceElement(element, maxRight, maxBottom)) {
            return CandidateKind.COLLECTION_ITEM
        }
        if (element.role in setOf(ElementRole.BUTTON, ElementRole.TAB, ElementRole.LINK)) {
            return CandidateKind.ACTION
        }
        if (element.role == ElementRole.CONTAINER) {
            return CandidateKind.CONTAINER
        }
        return if (element.actionable) CandidateKind.ACTION else CandidateKind.UNKNOWN
    }

    private fun candidateStateHint(element: SemanticElement): String? {
        return when {
            parseBoolAttr(element, "checked") -> "checked"
            parseBoolAttr(element, "selected") -> "selected"
            parseBoolAttr(element, "activated") -> "activated"
            parseBoolAttr(element, "focused") -> "focused"
            else -> null
        }
    }

    private fun isCollectionSurfaceElement(
        element: SemanticElement,
        maxRight: Int,
        maxBottom: Int
    ): Boolean {
        val width = (element.bounds.right - element.bounds.left).coerceAtLeast(0)
        val height = (element.bounds.bottom - element.bounds.top).coerceAtLeast(0)
        val centerY = element.center.y
        val isMiddleRegion = centerY > (maxBottom * 0.16f).toInt() &&
            centerY < (maxBottom * 0.92f).toInt()
        val reasonableSize = width >= (maxRight * 0.08f).toInt() &&
            height >= (maxBottom * 0.05f).toInt()
        return isMiddleRegion && reasonableSize
    }

    private fun isDenseContentSurface(
        planningState: WorldState,
        semanticElements: List<SemanticElement>,
        conversationSurface: Boolean
    ): Boolean {
        val taskHintedDenseSurface = isTaskHintedDenseSurface(planningState)
        if (taskHintedDenseSurface) return true
        if (conversationSurface) return false
        val affordances = planningState.pageAffordances.toSet()
        val capabilities = planningState.pageCapabilities.toSet()
        val linkOrListCount = semanticElements.count { element ->
            element.role == ElementRole.LINK || element.role == ElementRole.LIST_ITEM
        }
        return taskHintedDenseSurface ||
            (affordances.contains("has_list_cells") && linkOrListCount >= 4) ||
            (capabilities.contains(CapabilityState.CAN_OPEN_CANDIDATE) && linkOrListCount >= 6)
    }

    private fun isTaskHintedDenseSurface(planningState: WorldState): Boolean {
        val taskFacts = planningState.pageCapabilities.filter(::isRuntimeTaskFact)
        return taskFacts.any { fact ->
            fact.contains("expected_count") ||
                fact.contains("target_artifact") ||
                fact.contains("coverage_mode")
        }
    }

    private fun labelMatchesAny(label: String, markers: List<String>): Boolean {
        if (label.isBlank()) return false
        return markers.any { marker ->
            label.contains(marker, ignoreCase = true) || marker.contains(label, ignoreCase = true)
        }
    }

    private fun looksLikeNewConversationLabel(label: String): Boolean {
        return labelMatchesAny(
            label,
            listOf("聊聊新话题", "新话题", "新建对话", "新建聊天", "开启新对话", "开始新对话", "new chat", "new conversation")
        )
    }

    private fun looksLikeSubmitActionLabel(label: String): Boolean {
        return labelMatchesAny(
            label,
            listOf("发送", "send", "提交", "搜索")
        )
    }

    private fun looksLikeReferenceSummaryLabel(label: String): Boolean {
        val normalized = label.trim()
        if (normalized.isBlank()) return false
        return (normalized.contains("参考") && (normalized.contains("资料") || normalized.contains("篇"))) ||
            (normalized.contains("搜索") && normalized.contains("关键词"))
    }

    private fun isConversationEntryLabel(label: String): Boolean {
        if (label.isBlank()) return false
        val markers = listOf("消息", "私信", "聊天", "会话", "好友", "联系人", "message", "chat")
        return markers.any { marker -> label.contains(marker, ignoreCase = true) }
    }

    private fun looksLikeFeedNavigationLabel(label: String): Boolean {
        if (label.isBlank()) return false
        val markers = listOf("关注", "推荐", "首页", "团购", "同城", "商城", "短剧", "直播")
        return markers.any { marker -> label.contains(marker, ignoreCase = true) }
    }

    private fun candidateLabel(
        element: SemanticElement,
        conversationSurface: Boolean
    ): String {
        val base = element.label.replace("|", " ").trim()
        if (conversationSurface && base.isNotBlank()) {
            conversationIdentitySegments(base).singleOrNull()?.let { preferred ->
                return preferred.take(24)
            }
        }
        if (base.isNotBlank()) return base.take(24)
        val editable = parseBoolAttr(element, "editable")
        val clickable = parseBoolAttr(element, "clickable")
        return when {
            editable -> "[input]"
            clickable -> "[tap_${element.role.name.lowercase()}]"
            else -> "[${element.role.name.lowercase()}]"
        }
    }

    private fun conversationIdentitySegments(rawLabel: String): List<String> {
        return rawLabel
            .split('·', '|', '/', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter(::looksLikeConversationIdentitySegment)
            .distinct()
    }

    private fun looksLikeConversationIdentitySegment(value: String): Boolean {
        val normalized = value.replace(Regex("\\s+"), "")
        if (normalized.length !in 2..18) return false
        val excludedMarkers = listOf(
            "昨天在线",
            "今天在线",
            "前天",
            "在线",
            "音视频通话",
            "详聊",
            "发送消息",
            "互动消息",
            "消息",
            "私信",
            "回复",
            "搜索",
            "关注",
            "好友",
            "朋友"
        )
        if (excludedMarkers.any { marker ->
                normalized.contains(marker) || marker.contains(normalized)
            }
        ) {
            return false
        }
        if (Regex("""^\d+$""").matches(normalized)) return false
        return normalized.any { it in '\u4E00'..'\u9FFF' } ||
            normalized.any { it.isLetterOrDigit() }
    }

    private fun isWideConversationContainer(
        element: SemanticElement,
        maxRight: Int,
        maxBottom: Int
    ): Boolean {
        val width = (element.bounds.right - element.bounds.left).coerceAtLeast(0)
        val height = (element.bounds.bottom - element.bounds.top).coerceAtLeast(0)
        return width >= (maxRight * 0.55f).toInt() &&
            height >= (maxBottom * 0.12f).toInt()
    }
}

data class ActionTransitionEvent(
    val action: String,
    val result: String,
    val previousApp: String?,
    val currentApp: String?,
    val pageSignatureBefore: String,
    val pageSignatureAfter: String
)

/**
 * DAO for persisting WorldState (Requirement 14.1).
 */
@Dao
interface WorldStateDao {
    @Query("SELECT * FROM world_states WHERE id = 'current_task' LIMIT 1")
    fun getCurrentState(): WorldState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertState(state: WorldState)

    @Query("DELETE FROM world_states")
    fun clear()
}

interface ScreenObserver {
    fun capture(): Observation
}

data class Observation(
    val screenshotBase64: String,
    val uiTree: List<UIElement>?,
    val ocrTexts: List<OcrResult>?,
    val pageSignature: String,
    val currentApp: String?,
    val debugPageLabel: String?,
    val debugPageSemanticTag: String? = null,
    val debugPageSemanticConfidence: Float = 0f,
    val debugPageTitleHint: String? = null,
    val pageAffordances: List<String> = emptyList(),
    val pageCapabilities: List<String> = emptyList(),
    val debugPageSignals: List<String> = emptyList(),
    val semanticElements: List<SemanticElement> = emptyList()
)

data class UIElement(
    val type: String,
    val text: String?,
    val contentDescription: String?,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isFocused: Boolean = false,
    val bounds: Rect,
    val center: Point
)

data class OcrResult(
    val text: String,
    val bounds: Rect
)

data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun center(): Point = Point((left + right) / 2, (top + bottom) / 2)
    fun overlaps(other: Rect): Boolean {
        return !(right < other.left || left > other.right ||
                bottom < other.top || top > other.bottom)
    }
}

data class Point(val x: Int, val y: Int)

data class SemanticElement(
    val id: String,
    val role: ElementRole,
    val label: String,
    val actionable: Boolean,
    val center: Point,
    val bounds: Rect,
    val attributes: Map<String, String> = emptyMap()
)

enum class ElementRole {
    BUTTON, INPUT, CHECKBOX, SWITCH, TAB, LIST_ITEM, LINK, IMAGE, TEXT, CONTAINER, UNKNOWN
}
