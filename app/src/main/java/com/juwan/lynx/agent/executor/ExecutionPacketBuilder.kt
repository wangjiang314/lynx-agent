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

package com.juwan.lynx.agent.executor

import com.juwan.lynx.agent.Strategy
import com.juwan.lynx.runtime.Tool
import com.juwan.lynx.state.ActionRecord
import com.juwan.lynx.state.WorldState

internal class ExecutionPacketBuilder(
    private val sanitizeUserInput: (String) -> String,
    private val isTargetAppMatch: (String?, Strategy, String) -> Boolean,
    private val buildBlockedActionSignatures: (List<ActionRecord>) -> List<String>
) {
    fun buildSystemPrompt(
        strategy: Strategy,
        recoveryHint: String?,
        availableTools: List<Tool>,
        iterationIndex: Int
    ): String {
        val goal = strategy.goal.takeIf { it.isNotBlank() } ?: "未指定目标"
        val app = strategy.app.takeIf { it.isNotBlank() } ?: "unknown"
        val tools = availableTools.joinToString("\n") { tool ->
            val desc = tool.description.replace(Regex("\\s+"), " ").trim().take(88)
            "- ${tool.name}: ${desc.ifBlank { "no description" }}"
        }
        val recoveryRule = if (!recoveryHint.isNullOrBlank()) {
            "Recovery hint: $recoveryHint"
        } else {
            "Recovery hint: none"
        }
        val verbosityRules = if (iterationIndex > 8) {
            """
            Focus only on the latest packet state.
            Ignore any guessed page category.
            """
        } else {
            """
            Base your decision on raw facts, capabilities, and recent outcomes.
            Do not invent hidden page names or hidden workflow state.
            """
        }.trimIndent()

        return """
            You are an Android UI automation agent.
            Goal: $goal
            Target app: $app
            $recoveryRule

            Available tools:
            $tools

            Rules:
            - First output one short reasoning sentence starting with "Reason:".
            - Then output exactly one JSON tool call: {"name":"tool_name","args":{...}}
            - Make exactly one tool call per round.
            - Treat the screenshot as the primary source of page understanding.
            - Use the latest Execution Packet JSON as the only source of truth.
            - Prioritize raw observable evidence and recent outcomes.
            - original_instruction is binding. If goal or success_criteria is shorter, preserve the original literal text, numbers, order, line breaks, negative constraints, and requested final screen from original_instruction.
            - Do not rely on guessed page categories.
            - Treat page_capabilities and page_affordances as weak local hints, not hard truth.
            - Use coordinates when the screenshot makes the target location clear.
            - Coordinate tools use 0-1000 normalized screenshot coordinates, not device pixels.
            - Top-left is (0,0), bottom-right is (1000,1000).
            - Scroll direction is finger movement: direction="up" means swipe up and reveals content below; direction="down" means swipe down and reveals content above.
            - If a scroll has no meaningful visual change, do not repeat the same direction; try the opposite direction, click a visible relevant entry/control, use visible search, go back, or choose another on-screen route.
            - For ordered controls such as wheels, pickers, sliders, timers, ratings, or paged lists: use screenshot + history.last_action_effect + history.visible_text_delta to infer whether the last gesture moved toward or away from the target; reverse direction when it moved away, and use a smaller gesture or click the visible target when close.
            - When you are close to the target on an ordered control, prefer a shorter gesture length such as 40-140 for fine adjustment; otherwise use a smaller length before trying a larger correction.
            - Do not start timers, recordings, payments, posts, or submissions unless the user explicitly requested that final action.
            - Do not let text snippets override what is visible in the screenshot.
            - Prefer a dedicated semantic tool over coordinate clicks when a tool directly matches the desired navigation or system action.
            - Prefer business actions over internal state management.
            - Do not repeat the same no-progress path when blocked_action_signatures already covers it.
            - Treat blocked_action_signatures as cross-replan memory; choose a genuinely different region or tool.
            - If recent_outcomes contains result_code SUPPRESSED or ERROR, treat its message as authoritative recovery evidence.
            - After a suppressed click, never click the same coordinate bucket again; choose a clearly different visible coordinate, scroll/drag, or use search if available.
            - If a normally useful tool is absent from Available tools or appears in forbidden_tools, it is intentionally blocked for recovery; choose a different available tool.
            - If search_available is true and approximate list clicks have failed, prefer typing suggested_search_query into search instead of tapping guessed list rows.
            - For goals that ask to stay on a search results page, if the current screen shows the requested query and visible result/list entries, call finished. Do not open a result page or submit the same query again.
            - If in_target_app is false, prioritize resolve_installed_app or open_app.
            - The type tool sets/replaces the focused editable text. For required multi-line text, type the complete content in one call with newline characters instead of typing separate lines and guessing keyboard Enter.
            - If an editable field contains only part of the required final text, replace it with the complete required text rather than appending fragments.
            - Do not collapse required separate lines or ordered quoted phrases into one plain sentence.
            - Call finished only when the user goal is fully completed by observable evidence.
            - Treat success_criteria as the minimum evidence needed before calling finished.
            - If the current screen already satisfies success_criteria, call finished instead of tapping related items.
            - If prior verifier_missing exists, collect that evidence before proposing finished again.
            - Never emit tools that are listed in forbidden_tools.

            Packet reading priority:
            1. screenshot
            2. original_instruction, goal, and current_app
            3. success_criteria
            4. recent_outcomes
            5. verifier_missing and recovery_hint
            6. blocked_action_signatures
            7. short ui/ocr/content text facts
            8. page_capabilities and page_affordances
            9. task_facts

            $verbosityRules
        """.trimIndent()
    }

    fun buildInput(
        strategy: Strategy,
        recoveryHint: String?,
        state: WorldState,
        targetIdentifier: String,
        iterationIndex: Int,
        availableToolNames: List<String>,
        forbiddenToolNames: List<String>
    ): String {
        val profile = packetProfileFor(state)
        val sanitizedGoal = sanitizeUserInput(strategy.goal).take(160)
        val sanitizedPlan = strategy.strategy
            .asSequence()
            .map { sanitizeUserInput(it).take(100) }
            .filter { it.isNotBlank() }
            .take(6)
            .toList()
        val successCriteria = state.successCriteria.ifEmpty { strategy.successCriteria }
            .asSequence()
            .map { sanitizeUserInput(it).take(120) }
            .filter { it.isNotBlank() }
            .take(6)
            .toList()
        val riskHints = state.riskHints.ifEmpty { strategy.riskHints }
            .asSequence()
            .map { sanitizeUserInput(it).take(120) }
            .filter { it.isNotBlank() }
            .take(6)
            .toList()
        val recentOutcomes = state.recentOutcomes.takeLast(3)
        val recentToolResults = state.recentToolResults.takeLast(3)
        val taskHistory = state.taskHistoryActions.takeLast(3)
        val recoveryHintText = recoveryHint?.takeIf { it.isNotBlank() }?.take(180) ?: "none"
        val inTargetApp = isTargetAppMatch(state.currentApp, strategy, targetIdentifier)
        val blockedActionSignatures = buildBlockedActionSignatures(blockedActionEvidence(state)).take(4)
        val uiTextsSample = state.uiTexts
            .asSequence()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .take(profile.uiTextLimit)
            .map { it.take(profile.textValueLength) }
            .toList()
        val contentDescriptionsSample = state.contentDescriptions
            .asSequence()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .take(profile.contentDescriptionLimit)
            .map { it.take(profile.textValueLength) }
            .toList()
        val ocrTextsSample = state.ocrTexts
            .asSequence()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .take(profile.ocrTextLimit)
            .map { it.take(profile.textValueLength) }
            .toList()
        val semanticLabelsSample = state.semanticLabels
            .asSequence()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .take(profile.semanticLabelLimit)
            .map { it.take(profile.textValueLength) }
            .toList()
        val taskFacts = state.pageCapabilities
            .filter { it.startsWith("task.") }
            .take(profile.taskFactLimit)
        val pageCapabilities = state.pageCapabilities
            .filterNot { it.startsWith("task.") }
            .take(profile.pageCapabilityLimit)
        val originalInstruction = strategy.originalInstruction.trim().ifBlank { strategy.goal }.take(180)
        val lastActionChanged = state.recentActions.lastOrNull()?.let {
            it.pageSignatureBefore != it.pageSignatureAfter
        } ?: false
        val searchAvailable = hasSearchAffordance(state)
        val suggestedSearchQuery = if (searchAvailable) {
            suggestedSearchQuery(strategy, successCriteria)
        } else {
            ""
        }
        val evidenceTrace = buildEvidenceTrace(state)

        val recentOutcomesJson = if (recentToolResults.isNotEmpty()) {
            recentToolResults.joinToString(prefix = "[", postfix = "]") { result ->
                val targetLabel = result.targetLabel?.takeIf { it.isNotBlank() }?.let {
                    ",\"target_label\":\"${jsonEscape(it.take(60))}\""
                }.orEmpty()
                val errorType = result.errorType?.takeIf { it.isNotBlank() }?.let {
                    ",\"error_type\":\"${jsonEscape(it.take(60))}\""
                }.orEmpty()
                val message = compactOutcomeMessage(result.status.name, result.message)?.let {
                    ",\"message\":\"${jsonEscape(it)}\""
                }.orEmpty()
                """{"action":"${jsonEscape(result.actionText.take(80))}","result_code":"${result.status.name}","tool":"${jsonEscape(result.toolName)}"$targetLabel,"page_changed":${result.changed},"raw_signature_changed":${result.rawSignatureChanged}$errorType$message}"""
            }
        } else {
            recentOutcomes.joinToString(prefix = "[", postfix = "]") { outcome ->
                val pageChanged = outcome.pageSignatureBefore != outcome.pageSignatureAfter
                val targetLabel = outcome.targetLabel?.takeIf { it.isNotBlank() }?.let {
                    ",\"target_label\":\"${jsonEscape(it.take(60))}\""
                }.orEmpty()
                """{"action":"${jsonEscape(outcome.actionText.take(80))}","result_code":"${outcome.resultCode.name}","tool":"${jsonEscape(outcome.toolName)}"$targetLabel,"page_changed":$pageChanged}"""
            }
        }
        val taskHistoryJson = taskHistory.joinToString(prefix = "[", postfix = "]") { action ->
            val pageChanged = action.pageSignatureBefore != action.pageSignatureAfter
            val visibleDelta = visibleTextDeltaJson(action)
            """{"action":"${jsonEscape(action.action.take(80))}","result":"${jsonEscape(action.result.take(80))}","page_changed":$pageChanged$visibleDelta}"""
        }

        return """
            {
              "context_variant":"vision_minimal",
              "goal":"${jsonEscape(sanitizedGoal)}",
              "original_instruction":"${jsonEscape(originalInstruction)}",
              "target_app":"${jsonEscape(strategy.app)}",
              "target_identifier":"${jsonEscape(targetIdentifier)}",
              "strategy":${stringArray(sanitizedPlan)},
              "success_criteria":${stringArray(successCriteria)},
              "risk_hints":${stringArray(riskHints)},
              "iteration_index":$iterationIndex,
              "state":{
                "current_app":"${jsonEscape(state.currentApp ?: "unknown")}",
                "in_target_app":$inTargetApp,
                "stuck_count":${state.stuckCount},
                "last_action_changed":$lastActionChanged
              },
              "facts":{
                "ui_texts_sample":${stringArray(uiTextsSample)},
                "content_desc_sample":${stringArray(contentDescriptionsSample)},
                "ocr_texts_sample":${stringArray(ocrTextsSample)},
                "semantic_labels_sample":${stringArray(semanticLabelsSample)},
                "search_available":$searchAvailable,
                "suggested_search_query":"${jsonEscape(suggestedSearchQuery)}",
                "page_affordances":${stringArray(state.pageAffordances.take(profile.pageAffordanceLimit))},
                "page_capabilities":${stringArray(pageCapabilities)},
                "task_facts":${stringArray(taskFacts)}
              },
              "constraints":{
                "recovery_hint":"${jsonEscape(recoveryHintText)}",
                "verifier_missing":${stringArray(state.missingEvidence.take(6))},
                "completion_evidence":${stringArray(state.completionEvidence.take(6))}
              },
              "control":{
                "available_tools":${stringArray(availableToolNames)},
                "forbidden_tools":${stringArray(forbiddenToolNames)},
                "blocked_action_signatures":${stringArray(blockedActionSignatures)}
              },
              "history":{
                "history_actions":${evidenceTrace["history_actions"]},
                "visible_delta_count":${evidenceTrace["visible_delta_count"]},
                "last_action_effect":"${jsonEscape(evidenceTrace["last_action_effect"].toString())}",
                "recent_outcomes":$recentOutcomesJson,
                "task_history_recent":$taskHistoryJson
              }
            }
        """.trimIndent()
    }

    fun buildEvidenceTrace(state: WorldState): Map<String, Any> {
        val history = state.taskHistoryActions.takeLast(12)
        val visibleDeltas = history.mapNotNull { visibleTextDelta(it) }
        val lastAction = history.lastOrNull() ?: state.recentActions.lastOrNull()
        return linkedMapOf(
            "history_actions" to history.size,
            "visible_delta_count" to visibleDeltas.size,
            "last_action_changed" to (lastAction?.let {
                it.pageSignatureBefore != it.pageSignatureAfter
            } ?: false),
            "last_action_effect" to describeActionEffect(lastAction)
        )
    }

    private fun blockedActionEvidence(state: WorldState): List<ActionRecord> {
        return (state.taskHistoryActions + state.recentActions)
            .distinctBy { record ->
                "${record.timestamp}|${record.action}|${record.pageSignatureBefore}|${record.pageSignatureAfter}"
            }
            .takeLast(12)
    }

    private fun queryTokens(raw: String): List<String> {
        val normalized = raw.lowercase()
        val tokens = linkedSetOf<String>()
        Regex("""[a-z0-9][a-z0-9_.-]*""").findAll(normalized)
            .map { it.value.trim('.', '_', '-') }
            .filter { it.length >= 2 }
            .forEach { tokens += it }

        val cjkRuns = Regex("""\p{IsHan}+""").findAll(normalized).map { it.value }.toList()
        cjkRuns.forEach { run ->
            if (run.length >= 2) {
                run.windowed(size = 2, step = 1).forEach { tokens += it }
            }
            if (run.length >= 3) {
                run.windowed(size = 3, step = 1).forEach { tokens += it }
            }
        }

        return tokens
            .filterNot { it in genericQueryStopTokens }
            .toList()
    }

    private fun relevanceScore(text: String, tokens: List<String>): Int {
        if (text.isBlank()) return 0
        val normalized = text.lowercase()
        var score = 0
        tokens.forEach { token ->
            if (normalized.contains(token)) {
                score += token.length * token.length
            }
        }
        return score
    }

    private fun compactOutcomeMessage(status: String, message: String): String? {
        val normalized = message.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) return null
        val shouldExpose = status != "SUCCESS" ||
            normalized.startsWith("Error:", ignoreCase = true) ||
            normalized.contains("抑制") ||
            normalized.contains("blocked", ignoreCase = true)
        return if (shouldExpose) normalized.take(120) else null
    }

    private fun visibleTextDeltaJson(action: ActionRecord): String {
        val delta = visibleTextDelta(action) ?: return ""
        return ""","visible_text_delta":{"before":${stringArray(delta.before)},"after":${stringArray(delta.after)}}"""
    }

    private fun compactVisibleTexts(values: List<String>): List<String> {
        return values
            .asSequence()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .filterNot { value -> value.length <= 1 }
            .distinct()
            .take(8)
            .map { it.take(56) }
            .toList()
    }

    private fun visibleTextDelta(action: ActionRecord): VisibleTextDelta? {
        val before = compactVisibleTexts(action.visibleTextsBefore)
        val after = compactVisibleTexts(action.visibleTextsAfter)
        if (before.isEmpty() || after.isEmpty()) return null
        val beforeOnly = before.filterNot { value -> after.any { it == value } }.take(3)
        val afterOnly = after.filterNot { value -> before.any { it == value } }.take(3)
        if (beforeOnly.isEmpty() && afterOnly.isEmpty()) return null
        val effect = buildString {
            append(action.action.take(80))
            append(" => visible text changed: ")
            append(beforeOnly.ifEmpty { listOf("(none)") }.joinToString(" / "))
            append(" -> ")
            append(afterOnly.ifEmpty { listOf("(none)") }.joinToString(" / "))
        }
        return VisibleTextDelta(beforeOnly, afterOnly, effect.take(180))
    }

    private fun describeActionEffect(action: ActionRecord?): String {
        if (action == null) return "none"
        val delta = visibleTextDelta(action)
        if (delta != null) return delta.effectSummary
        return if (action.pageSignatureBefore != action.pageSignatureAfter) {
            "${action.action.take(80)} => page changed"
        } else {
            "${action.action.take(80)} => no visible change"
        }
    }

    private data class RankedValue<T>(
        val value: T,
        val score: Int,
        val index: Int
    )

    private data class VisibleTextDelta(
        val before: List<String>,
        val after: List<String>,
        val effectSummary: String
    )

    private fun packetProfileFor(@Suppress("UNUSED_PARAMETER") state: WorldState): PacketProfile {
        return PacketProfile(
            uiTextLimit = 4,
            contentDescriptionLimit = 3,
            ocrTextLimit = 4,
            semanticLabelLimit = 4,
            textValueLength = 56,
            pageAffordanceLimit = 6,
            pageCapabilityLimit = 4,
            taskFactLimit = 6
        )
    }

    private fun hasSearchAffordance(state: WorldState): Boolean {
        return (state.uiTexts + state.contentDescriptions + state.ocrTexts + state.semanticLabels)
            .any { value ->
                val normalized = value.lowercase()
                normalized.contains("搜索") || normalized.contains("search")
            }
    }

    private fun suggestedSearchQuery(
        strategy: Strategy,
        successCriteria: List<String>
    ): String {
        val source = strategy.goal.ifBlank {
            successCriteria.firstOrNull().orEmpty()
        }
        val cleaned = source
            .replace(Regex("""\b(open|go|enter|navigate|show|find|page|app|settings?)\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""打开|进入|前往|跳转到|找到|搜索|页面|应用|当前屏幕|当前|成功|完成|设置|内容|并|的"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return cleaned
            .ifBlank { strategy.goal.trim() }
            .take(32)
    }

    private fun stringArray(values: List<String>): String {
        return values.joinToString(prefix = "[", postfix = "]") { value ->
            "\"${jsonEscape(value)}\""
        }
    }

    private data class PacketProfile(
        val uiTextLimit: Int,
        val contentDescriptionLimit: Int,
        val ocrTextLimit: Int,
        val semanticLabelLimit: Int,
        val textValueLength: Int,
        val pageAffordanceLimit: Int,
        val pageCapabilityLimit: Int,
        val taskFactLimit: Int
    )

    private val genericQueryStopTokens = setOf(
        "打开",
        "进入",
        "页面",
        "应用",
        "设置",
        "当前",
        "完成",
        "目标",
        "成功",
        "open",
        "page",
        "app",
        "setting",
        "settings"
    )

    private fun jsonEscape(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
    }
}
