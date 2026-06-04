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

import android.util.Log
import com.juwan.lynx.agent.FlowTraceLogger
import com.juwan.lynx.agent.Strategy
import com.juwan.lynx.runtime.Tool
import com.juwan.lynx.state.ActionRecord
import com.juwan.lynx.state.ActionOrigin
import com.juwan.lynx.state.ActionResultCode
import com.juwan.lynx.state.WorldStateManager
import com.juwan.lynx.state.canonicalActionName
import kotlin.math.abs

/**
 * Encapsulates action-level policy checks that previously lived inside ExecutorAgent.
 *
 * This module is intentionally stateful and adapter-like for now:
 * it keeps behavior stable while moving loop/block/suppression/popup rules
 * out of the executor main loop.
 */
internal class ActionPolicy(
    private val worldStateManager: WorldStateManager,
    private val primitiveTools: List<Tool>,
    private val repeatedActionLimit: Int
) {
    fun suppressInvalidToolArguments(
        actionName: String,
        args: Map<String, String>
    ): String? {
        return when (actionName.trim().lowercase()) {
            "click", "long_press" -> validatePointActionArgs(actionName, args)
            "scroll" -> validateScrollArgs(actionName, args)
            "drag" -> validateDragArgs(actionName, args)
            "type", "type_and_enter" -> validateTextInputArgs(actionName, args)
            else -> null
        }
    }

    private fun validatePointActionArgs(actionName: String, args: Map<String, String>): String? {
        val requestedPoint = parsePointFromClickArgs(args)
            ?: return "Error: 缺少 point 或 x/y 参数"
        if (requestedPoint.first < 0 || requestedPoint.second < 0) {
            return "Error: 坐标不能为负数"
        }
        suppressKnownDeadEndActionRoute(actionName, args)?.let { return it }
        suppressKnownNoProgressClickBucket(actionName, requestedPoint)?.let { return it }
        return null
    }

    private fun parsePointFromClickArgs(args: Map<String, String>): Pair<Int, Int>? {
        val pointArg = args["point"]?.trim()
            ?.takeIf { it.isNotBlank() }
        if (pointArg != null) {
            parsePoint(pointArg)?.let { return it }
        }

        val x = args["x"]?.trim()?.toFloatOrNull()?.toInt()
        val y = args["y"]?.trim()?.toFloatOrNull()?.toInt()
        if (x != null && y != null) return x to y

        return null
    }

    private fun validateScrollArgs(actionName: String, args: Map<String, String>): String? {
        val direction = args["direction"]?.trim()?.lowercase()
        if (direction.isNullOrBlank()) {
            return "Error: 缺少 direction 参数"
        }
        if (direction !in setOf("up", "down", "left", "right")) {
            return "Error: 方向必须是 up/down/left/right，收到 $direction"
        }
        val rawLength = args["length"]?.trim()
        if (!rawLength.isNullOrBlank()) {
            val length = rawLength.toFloatOrNull()?.toInt()
                ?: return "Error: length 必须是 0-1000 的数字，收到 $rawLength"
            if (length !in 0..1000) {
                return "Error: length 必须在 0-1000 范围内，收到 $length"
            }
            if (length in 0 until 30) {
                return "Error: scroll length=$length 太小，通常不会产生可靠变化。请使用至少 30 的微调距离，或对页面滚动使用更大的距离。"
            }
        }
        suppressKnownDeadEndActionRoute(actionName, args)?.let { return it }
        suppressKnownNoProgressScrollDirection(direction)?.let { return it }
        return null
    }

    private fun validateDragArgs(actionName: String, args: Map<String, String>): String? {
        if (parseDragPoints(args) == null) {
            return "Error: 缺少 start_point/end_point 或 x1/y1/x2/y2 参数"
        }
        suppressKnownDeadEndActionRoute(actionName, args)?.let { return it }
        return null
    }

    private fun validateTextInputArgs(actionName: String, args: Map<String, String>): String? {
        val rawText = firstPresentArg(args, "text", "content", "message")
            ?: return "Error: 缺少 text 参数"
        if (rawText.trim().isEmpty()) {
            return "Error: 文本不能为空"
        }
        suppressKnownDeadEndActionRoute(actionName, args)?.let { return it }
        suppressIncompleteStructuredLiteralInput(rawText)?.let { return it }
        return null
    }

    private fun suppressIncompleteStructuredLiteralInput(rawText: String): String? {
        val state = worldStateManager.getState()
        val requirementText = buildString {
            append(state.goal)
            if (state.successCriteria.isNotEmpty()) {
                append('\n')
                append(state.successCriteria.joinToString("\n"))
            }
        }
        val quotedLiterals = extractQuotedLiterals(requirementText)
            .filter { it.isNotBlank() }
            .distinct()
        if (quotedLiterals.size < 2 || !requiresLineStructure(requirementText)) return null

        val requested = rawText.replace("\\n", "\n")
        val missing = quotedLiterals.filterNot { literal ->
            requested.contains(literal, ignoreCase = true)
        }
        val outOfOrder = !containsInOrder(requested, quotedLiterals)
        val lacksLineBreak = !requested.contains('\n')

        if (missing.isEmpty() && !outOfOrder && !lacksLineBreak) return null

        val reason = when {
            missing.isNotEmpty() -> "缺少必要字面文本：${missing.joinToString(" / ") { "“$it”" }}"
            outOfOrder -> "字面文本顺序不符合任务要求"
            else -> "缺少任务要求的换行结构"
        }
        return "Error: 当前任务要求多个字面内容和分行结构；type/type_and_enter 会设置或替换文本，请一次性输入完整最终文本并包含换行。$reason。"
    }

    private fun extractQuotedLiterals(text: String): List<String> {
        val pattern = Regex("[“\"]([^”\"]+)[”\"]|'([^']+)'")
        return pattern.findAll(text).mapNotNull { match ->
            match.groups[1]?.value ?: match.groups[2]?.value
        }.toList()
    }

    private fun requiresLineStructure(text: String): Boolean {
        return Regex("(第[一二三四五六七八九十0-9]+行|两行|多行|分行|换行|每行|line|lines|newline)", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)
    }

    private fun containsInOrder(text: String, literals: List<String>): Boolean {
        var startIndex = 0
        for (literal in literals) {
            val index = text.indexOf(literal, startIndex, ignoreCase = true)
            if (index < 0) return false
            startIndex = index + literal.length
        }
        return true
    }

    private fun firstPresentArg(
        args: Map<String, String>,
        vararg keys: String
    ): String? {
        return keys.firstNotNullOfOrNull { key ->
            args[key]
        }
    }

    fun suppressRepeatedFinishedWithoutEvidence(actionName: String): String? {
        if (!actionName.equals("finished", ignoreCase = true)) return null

        val state = worldStateManager.getState()
        val repeatedFinished = state.recentActions
            .takeLast(3)
            .count { record ->
                normalizeAction(record.action).equals("finished", ignoreCase = true)
            } >= 1
        if (!repeatedFinished) return null
        val hasReliableBusinessOutcome = state.recentOutcomes
            .takeLast(6)
            .any { outcome ->
                outcome.resultCode.name == "SUCCESS" &&
                    outcome.toolName != "finished" &&
                    !outcome.toolName.equals("open_app", ignoreCase = true) &&
                    !outcome.toolName.equals("resolve_installed_app", ignoreCase = true) &&
                    !outcome.toolName.equals("press_home", ignoreCase = true)
            }
        if (hasReliableBusinessOutcome) return null

        return "Error: 当前缺少可观测完成证据，重复 finished 已抑制；请先完成业务动作或观察结果。"
    }

    fun detectLoopRisk(): String? {
        val history = worldStateManager.getState().recentActions
        if (history.size < repeatedActionLimit) return null

        val tail = history.takeLast(repeatedActionLimit)
        val actionNames = tail.map { normalizeAction(it.action).lowercase() }
        val noReliableProgressTail = tail.filterNot(::hasReliableProgress)

        if (actionNames.distinct().size == 1 &&
            actionNames.first() == "click" &&
            isNearClickCluster(tail) &&
            noReliableProgressTail.size >= repeatedActionLimit - 1
        ) {
            return buildLoopRiskReason(
                prefix = "检测到可能循环：连续 $repeatedActionLimit 次 click 近邻点击且无可靠进展",
                tail = tail
            )
        }

        if (noReliableProgressTail.size < repeatedActionLimit - 1) return null

        val signatures = tail.map { buildLoopActionSignature(it) }
        if (signatures.distinct().size == 1) {
            val actionName = normalizeAction(tail.last().action)
            return buildLoopRiskReason(
                prefix = "检测到可能循环：连续 $repeatedActionLimit 次重复动作签名「$actionName」且无可靠进展",
                tail = tail
            )
        }

        return null
    }

    private fun buildLoopRiskReason(prefix: String, tail: List<ActionRecord>): String {
        val suppressed = tail.lastOrNull(::isSuppressedRecord)
        val suppressedHint = suppressed?.result
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(160)
            ?.takeIf { it.isNotBlank() }
        val point = tail.lastOrNull()?.let { parsePoint(it.action) }
        val pointHint = point?.let { "blocked_point_bucket=${it.first / 20},${it.second / 20}" }
        val lastActionName = tail.lastOrNull()?.let { normalizeAction(it.action).lowercase() }.orEmpty()
        val recoveryHint = when (lastActionName) {
            "scroll", "drag" -> "下一步不要继续同一路径滑动；请尝试点击可见的相关入口/控件、使用可见搜索、返回上级，或换一个屏幕区域操作。"
            else -> "下一步必须避开同一点击区域；如果目标不可见，请选择明显不同坐标、滚动/拖动，或使用可见搜索入口。"
        }
        return listOfNotNull(prefix, pointHint, suppressedHint, recoveryHint)
            .joinToString("；")
    }

    fun shouldDisableResolverInActiveTarget(
        appActive: Boolean,
        persistedResolved: String,
        inferredResolved: String,
        recentActions: List<ActionRecord>
    ): Boolean {
        if (!appActive) return false
        if (persistedResolved.isNotBlank() || inferredResolved.isNotBlank()) return true
        val recentResolveCount = recentActions.takeLast(5).count {
            normalizeAction(it.action).equals("resolve_installed_app", ignoreCase = true)
        }
        return recentResolveCount >= 1
    }

    fun detectTemporarilyBlockedLoopTool(
        recentActions: List<ActionRecord>
    ): String? {
        if (recentActions.size < repeatedActionLimit) return null
        val tail = recentActions.takeLast(repeatedActionLimit)
        val normalized = tail.map { normalizeAction(it.action) }
        if (normalized.distinct().size != 1) return null
        val noReliableProgress = tail.all { !hasReliableProgress(it) }
        if (!noReliableProgress) return null
        val candidate = normalized.first().lowercase()
        if (candidate == "finished") return null
        if (candidate == "click") {
            val suppressedCount = tail.count(::isSuppressedRecord)
            if (suppressedCount >= repeatedActionLimit - 1) {
                FlowTraceLogger.event(
                    stage = "loop_tool_block_check",
                    kv = mapOf(
                        "tool" to candidate,
                        "tail_size" to tail.size,
                        "decision" to "keep_click_available_with_blocked_signature",
                        "reason" to "repeated_suppressed_click"
                    )
                )
                return null
            }
            FlowTraceLogger.event(
                stage = "loop_tool_block_check",
                kv = mapOf(
                    "candidate" to candidate,
                    "decision" to "allow_click_with_blocked_signature",
                    "tail_size" to tail.size
                )
            )
            return null
        }
        if (candidate == "scroll" || candidate == "drag") {
            FlowTraceLogger.event(
                stage = "loop_tool_block_check",
                kv = mapOf(
                    "candidate" to candidate,
                    "decision" to "keep_gesture_available_with_direction_block",
                    "tail_size" to tail.size
                )
            )
            return null
        }
        FlowTraceLogger.warn(
            stage = "loop_tool_blocked",
            kv = mapOf(
                "tool" to candidate,
                "tail_size" to tail.size
            )
        )
        return candidate
    }

    private fun isSuppressedRecord(record: ActionRecord): Boolean {
        return record.resultCode == ActionResultCode.SUPPRESSED ||
            record.result.contains("suppressed", ignoreCase = true) ||
            record.result.contains("抑制") ||
            record.result.contains("无进展")
    }

    fun detectTemporarilyBlockedInputTools(
        recentActions: List<ActionRecord>
    ): Set<String> {
        val last = recentActions.lastOrNull() ?: return emptySet()
        val lastName = normalizeAction(last.action).lowercase()
        if (lastName !in inputToolNames) return emptySet()
        return if (isNoFocusedInputFailure(last)) {
            inputToolNames
        } else {
            emptySet()
        }
    }

    fun countTrailingActions(
        recentActions: List<ActionRecord>,
        actionNames: Set<String>
    ): Int {
        if (actionNames.isEmpty()) return 0
        val lowered = actionNames.map { it.lowercase() }.toSet()
        var count = 0
        for (record in recentActions.asReversed()) {
            val normalized = normalizeAction(record.action).lowercase()
            if (normalized in lowered) {
                count++
            } else {
                break
            }
        }
        return count
    }

    suspend fun tryAutoDismissPopup(): Boolean {
        val state = worldStateManager.getState()
        val candidates = state.actionCandidates
        if (candidates.isEmpty()) return false

        val recent = state.recentActions.takeLast(3)
        val isStuck = recent.size >= 2 && recent.all { it.pageSignatureBefore == it.pageSignatureAfter }

        val highConfidenceDismiss = listOf(
            "我知道了", "知道了", "以后再说", "以后", "不再提示",
            "跳过", "暂不", "下次再说", "不了谢谢", "不了",
            "skip", "got it", "not now", "dismiss", "later", "no thanks"
        )
        val lowConfidenceDismiss = listOf(
            "关闭", "取消", "close", "cancel", "×", "✕", "x"
        )

        val activeDismissLabels = if (isStuck) {
            highConfidenceDismiss + lowConfidenceDismiss
        } else {
            highConfidenceDismiss
        }

        for (raw in candidates) {
            val label = extractLabelFromCandidate(raw)
            if (label.isBlank()) continue
            if (isInputLikeCandidate(raw)) continue
            val lowerLabel = label.lowercase()
            val matched = activeDismissLabels.any { dismiss ->
                isDismissLabelMatch(lowerLabel, dismiss)
            }
            if (!matched) continue

            val point = extractPointFromCandidate(raw) ?: continue
            val clickTool = primitiveTools.firstOrNull {
                it.name.equals("click", ignoreCase = true)
            } ?: return false

            val pointArg = "${point.first} ${point.second}"
            val actionText = "click(点击弹窗关闭: $label)"
            try {
                clickTool.execute(mapOf("point" to pointArg))
                worldStateManager.updateAfterAction(
                    action = actionText,
                    result = "自动关闭弹窗: $label",
                    actionOrigin = ActionOrigin.SYSTEM
                )
                return true
            } catch (e: Exception) {
                Log.w("ActionPolicy", "Failed to auto-dismiss popup: ${e.message}")
                return false
            }
        }
        return false
    }

    fun buildBlockedActionSignatures(
        recentActions: List<ActionRecord>
    ): List<String> {
        if (recentActions.isEmpty()) return emptyList()
        val noProgress = recentActions
            .takeLast(6)
            .filterNot(::hasReliableProgress)
            .mapNotNull { record ->
                val actionName = normalizeAction(record.action).lowercase()
                when (actionName) {
                    "click" -> {
                        val point = parsePoint(record.action) ?: return@mapNotNull "click|point=unknown"
                        val bucketX = point.first / 20
                        val bucketY = point.second / 20
                        "click|point_bucket=$bucketX,$bucketY"
                    }
                    "scroll", "drag" -> {
                        val direction = extractDirection(record.action)
                        if (direction == null) actionName else "$actionName|direction=$direction"
                    }
                    "type" -> "type"
                    else -> actionName
                }
            }
        val deadEndRoutes = detectDeadEndActionRoutes(recentActions)
            .map { route ->
                "dead_end_route|action=${route.actionName}|key=${route.actionKey.take(32)}|source=${route.sourceKey.take(28)}|opened=${route.destinationKey.take(28)}|recovery=${route.recoveryAction}"
            }
        return (noProgress + deadEndRoutes).distinct()
    }

    private fun suppressKnownDeadEndActionRoute(
        actionName: String,
        args: Map<String, String>
    ): String? {
        val state = runCatching { worldStateManager.getState() }.getOrNull() ?: return null
        val currentSignature = state.pageSignature
        if (currentSignature.isBlank()) return null
        val normalizedAction = actionName.trim().lowercase()
        val requestedKey = actionRouteKey(normalizedAction, args) ?: return null

        val evidence = (state.taskHistoryActions + state.recentActions)
            .distinctBy { record ->
                "${record.timestamp}|${record.action}|${record.pageSignatureBefore}|${record.pageSignatureAfter}"
            }
            .takeLast(12)
        val currentPageKey = pageKey(state.visibleTexts, currentSignature)
        val deadEndRoute = detectDeadEndActionRoutes(evidence)
            .lastOrNull { route ->
                route.sourceKey == currentPageKey &&
                    route.actionName == normalizedAction &&
                    route.actionKey == requestedKey
            }

        if (deadEndRoute != null) {
            FlowTraceLogger.warn(
                stage = "dead_end_route_action_suppressed",
                kv = mapOf(
                    "action" to deadEndRoute.actionName,
                    "action_key" to deadEndRoute.actionKey.take(80),
                    "source" to deadEndRoute.sourceKey.take(80),
                    "opened" to deadEndRoute.destinationKey.take(80),
                    "recovery_action" to deadEndRoute.recoveryAction
                )
            )
            return "Error: 该动作路线此前进入了无法继续推进的页面，并需要通过 ${deadEndRoute.recoveryAction} 恢复，说明它不是当前有效下一步，已抑制重复执行。请改用不同可见区域、真实搜索框、其他工具，或重新规划。"
        }
        return null
    }

    private fun suppressKnownNoProgressClickBucket(actionName: String, point: Pair<Int, Int>): String? {
        val state = runCatching { worldStateManager.getState() }.getOrNull() ?: return null
        val currentSignature = state.pageSignature
        if (currentSignature.isBlank()) return null

        val bucketX = point.first / 20
        val bucketY = point.second / 20
        val evidence = (state.taskHistoryActions + state.recentActions)
            .distinctBy { record ->
                "${record.timestamp}|${record.action}|${record.pageSignatureBefore}|${record.pageSignatureAfter}"
            }
            .takeLast(12)
        val normalizedAction = actionName.trim().lowercase()

        val sameBucketNoProgress = evidence.count { record ->
            if (hasReliableProgress(record)) return@count false
            if (!normalizeAction(record.action).equals(normalizedAction, ignoreCase = true)) return@count false
            val actionPoint = parsePoint(record.action) ?: return@count false
            val sameBucket = actionPoint.first / 20 == bucketX && actionPoint.second / 20 == bucketY
            val samePage = record.pageSignatureBefore == currentSignature || record.pageSignatureAfter == currentSignature
            sameBucket && samePage
        }

        return if (sameBucketNoProgress >= 2) {
            "Error: 该区域已多次无进展，已抑制重复 $normalizedAction。请改用不同区域或其他工具。"
        } else {
            null
        }
    }

    private fun suppressKnownNoProgressScrollDirection(direction: String): String? {
        val state = runCatching { worldStateManager.getState() }.getOrNull() ?: return null
        val evidence = (state.taskHistoryActions + state.recentActions)
            .distinctBy { record ->
                "${record.timestamp}|${record.action}|${record.pageSignatureBefore}|${record.pageSignatureAfter}"
            }
            .takeLast(12)

        val sameDirectionNoProgress = evidence.count { record ->
            normalizeAction(record.action).equals("scroll", ignoreCase = true) &&
                extractDirection(record.action) == direction &&
                !hasReliableProgress(record)
        }
        if (sameDirectionNoProgress < 2) return null

        val opposite = oppositeScrollDirection(direction)
        return if (opposite != null) {
            "Error: scroll direction=$direction 已连续无可观测进展，已抑制同方向重复滚动。要查看当前屏幕下面的内容通常使用 direction=up；要回到上面的内容使用 direction=down。请改用 direction=$opposite，或点击可见的相关入口/控件、使用可见搜索、返回上级、换区域操作。"
        } else {
            "Error: scroll direction=$direction 已连续无可观测进展，已抑制同方向重复滚动。请改用相反方向，或点击可见的相关入口/控件、使用可见搜索、返回上级、换区域操作。"
        }
    }

    private fun hasReliableProgress(record: ActionRecord): Boolean {
        if (record.resultCode != ActionResultCode.SUCCESS) return false
        val actionName = normalizeAction(record.action).lowercase()
        if (actionName == "scroll" || actionName == "drag") {
            return hasReliableGestureProgress(record)
        }
        return true
    }

    private fun hasReliableGestureProgress(record: ActionRecord): Boolean {
        val beforeKey = pageKey(record.visibleTextsBefore, record.pageSignatureBefore)
        val afterKey = pageKey(record.visibleTextsAfter, record.pageSignatureAfter)
        if (beforeKey.isNotBlank() && afterKey.isNotBlank()) {
            return beforeKey != afterKey
        }
        return record.pageSignatureBefore != record.pageSignatureAfter
    }

    private fun detectDeadEndActionRoutes(records: List<ActionRecord>): List<DeadEndActionRoute> {
        if (records.size < 2) return emptyList()
        val routes = mutableListOf<DeadEndActionRoute>()
        records.forEachIndexed { index, record ->
            val actionName = normalizeAction(record.action).lowercase()
            if (actionName !in routeSourceActionNames) return@forEachIndexed
            val actionKey = actionRouteKey(record) ?: return@forEachIndexed
            val sourceKey = beforePageKey(record)
            val destinationKey = afterPageKey(record)
            if (sourceKey.isBlank() || destinationKey.isBlank() || sourceKey == destinationKey) {
                return@forEachIndexed
            }

            val recovery = records
                .drop(index + 1)
                .take(5)
                .firstOrNull { next ->
                    val recoveryAction = normalizeAction(next.action).lowercase()
                    if (recoveryAction == "finished") return@firstOrNull false
                    val startsFromDestination = beforePageKey(next) == destinationKey
                    val leavesDestination = afterPageKey(next).isNotBlank() &&
                        afterPageKey(next) != destinationKey
                    val isRecoveryAction = recoveryAction in routeRecoveryActionNames
                    startsFromDestination && leavesDestination && isRecoveryAction
                }
            if (recovery != null) {
                routes += DeadEndActionRoute(
                    actionName = actionName,
                    actionKey = actionKey,
                    sourceKey = sourceKey,
                    destinationKey = destinationKey,
                    recoveryAction = normalizeAction(recovery.action).lowercase()
                )
            }
        }
        return routes.distinct()
    }

    private fun actionRouteKey(record: ActionRecord): String? {
        return actionRouteKey(
            actionName = normalizeAction(record.action).lowercase(),
            actionText = record.action
        )
    }

    private fun actionRouteKey(actionName: String, args: Map<String, String>): String? {
        val normalized = actionName.trim().lowercase()
        return when (normalized) {
            "click", "long_press" -> {
                val point = parsePointFromClickArgs(args) ?: return null
                "region:${routeRegionBucket(point)}"
            }
            "scroll" -> {
                val direction = args["direction"]?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                    ?: return null
                "direction:$direction"
            }
            "drag" -> {
                val points = parseDragPoints(args) ?: return null
                "drag:${dragDirection(points.first, points.second)}"
            }
            "type", "type_and_enter" -> {
                val text = firstPresentArg(args, "text", "content", "message")
                    ?.let(::normalizeTextRouteValue)
                    ?.takeIf { it.isNotBlank() }
                    ?: return null
                "text:$text"
            }
            else -> null
        }
    }

    private fun actionRouteKey(actionName: String, actionText: String): String? {
        val normalized = actionName.trim().lowercase()
        return when (normalized) {
            "click", "long_press" -> {
                val point = parsePoint(actionText) ?: return null
                "region:${routeRegionBucket(point)}"
            }
            "scroll" -> extractDirection(actionText)?.let { "direction:$it" }
            "drag" -> parseDragPoints(actionText)?.let { points ->
                "drag:${dragDirection(points.first, points.second)}"
            }
            "type", "type_and_enter" -> {
                extractTextArg(actionText)
                    ?.let(::normalizeTextRouteValue)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "text:$it" }
            }
            else -> null
        }
    }

    private fun beforePageKey(record: ActionRecord): String {
        return pageKey(record.visibleTextsBefore, record.pageSignatureBefore)
    }

    private fun afterPageKey(record: ActionRecord): String {
        return pageKey(record.visibleTextsAfter, record.pageSignatureAfter)
    }

    private fun pageKey(texts: List<String>, signature: String): String {
        val normalizedTexts = texts
            .asSequence()
            .map(::normalizePageKeyText)
            .filter { it.isNotBlank() }
            .filterNot(::isNoisyPageKeyText)
            .distinct()
            .take(4)
            .toList()
        return normalizedTexts.joinToString("/").ifBlank { signature.take(48) }
    }

    private fun normalizePageKeyText(value: String): String {
        return value.replace(Regex("\\s+"), "")
            .replace(Regex("\\d+"), "#")
            .trim()
            .take(24)
    }

    private fun isNoisyPageKeyText(value: String): Boolean {
        if (value.isBlank()) return true
        if (value.length <= 1) return true
        if (value.all { it.isDigit() || it in setOf(':', '%', '/', '-', '.', ' ') }) return true
        return value in setOf("返回", "向上导航", "back", "Back")
    }

    private fun rowBucket(point: Pair<Int, Int>): Int {
        return point.second / 80
    }

    private fun routeRegionBucket(point: Pair<Int, Int>): String {
        val xBucket = point.first / 250
        return "$xBucket,${rowBucket(point)}"
    }

    private fun isNoFocusedInputFailure(record: ActionRecord): Boolean {
        val normalized = normalizeAction(record.action).lowercase()
        if (normalized !in inputToolNames) return false
        if (record.resultCode == ActionResultCode.SUCCESS) return false
        val lower = record.result.lowercase()
        return lower.contains("no focused input") ||
            lower.contains("editable input") ||
            record.result.contains("没有已聚焦输入框") ||
            record.result.contains("未找到可编辑输入框") ||
            record.result.contains("请先点击输入框")
    }

    private fun buildLoopActionSignature(record: ActionRecord): String {
        val actionName = normalizeAction(record.action).lowercase()
        val pageChanged = record.pageSignatureBefore != record.pageSignatureAfter
        val point = parsePointFromAction(record.action)
        val pointBucket = if (point != null) {
            "${point.first / 30},${point.second / 30}"
        } else {
            "none"
        }
        val direction = extractDirection(record.action) ?: "none"
        return "$actionName|point=$pointBucket|dir=$direction|changed=$pageChanged"
    }

    private fun extractDirection(action: String): String? {
        return Regex("""direction\s*=\s*([a-zA-Z_]+)""")
            .find(action)
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase()
    }

    private fun oppositeScrollDirection(direction: String): String? {
        return when (direction) {
            "up" -> "down"
            "down" -> "up"
            "left" -> "right"
            "right" -> "left"
            else -> null
        }
    }

    private fun isNearClickCluster(
        tail: List<ActionRecord>,
        threshold: Int = 25
    ): Boolean {
        val points = tail.mapNotNull { parsePointFromAction(it.action) }
        if (points.size < 2) return false
        val base = points.first()
        return points.all { p ->
            abs(p.first - base.first) + abs(p.second - base.second) <= threshold
        }
    }

    private fun normalizeAction(action: String): String {
        return canonicalActionName(action)
    }

    private fun parsePointFromAction(action: String): Pair<Int, Int>? {
        return parsePoint(action)
    }

    private fun parsePoint(raw: String): Pair<Int, Int>? {
        val pattern = Regex("""<point>\s*(-?\d+(?:\.\d+)?)\s+(-?\d+(?:\.\d+)?)\s*</point>""")
        val match = pattern.find(raw)
        if (match != null) {
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

    private fun parseDragPoints(args: Map<String, String>): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
        val startPoint = firstPresentArg(args, "start_point", "from", "start")
            ?.let(::parsePoint)
        val endPoint = firstPresentArg(args, "end_point", "to", "end")
            ?.let(::parsePoint)
        if (startPoint != null && endPoint != null) {
            return startPoint to endPoint
        }

        val x1 = firstPresentArg(args, "x1", "start_x")?.trim()?.toFloatOrNull()?.toInt()
        val y1 = firstPresentArg(args, "y1", "start_y")?.trim()?.toFloatOrNull()?.toInt()
        val x2 = firstPresentArg(args, "x2", "end_x")?.trim()?.toFloatOrNull()?.toInt()
        val y2 = firstPresentArg(args, "y2", "end_y")?.trim()?.toFloatOrNull()?.toInt()
        if (x1 != null && y1 != null && x2 != null && y2 != null) {
            return (x1 to y1) to (x2 to y2)
        }

        return null
    }

    private fun parseDragPoints(actionText: String): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
        val taggedPoints = Regex("""<point>\s*(-?\d+(?:\.\d+)?)\s+(-?\d+(?:\.\d+)?)\s*</point>""")
            .findAll(actionText)
            .mapNotNull { match ->
                val x = match.groupValues.getOrNull(1)?.toFloatOrNull()?.toInt()
                val y = match.groupValues.getOrNull(2)?.toFloatOrNull()?.toInt()
                if (x != null && y != null) x to y else null
            }
            .take(2)
            .toList()
        if (taggedPoints.size >= 2) {
            return taggedPoints[0] to taggedPoints[1]
        }

        val x1 = Regex("""(?:^|[,(]\s*)(?:x1|start_x)\s*=\s*(-?\d+(?:\.\d+)?)""")
            .find(actionText)
            ?.groupValues
            ?.getOrNull(1)
            ?.toFloatOrNull()
            ?.toInt()
        val y1 = Regex("""(?:^|[,(]\s*)(?:y1|start_y)\s*=\s*(-?\d+(?:\.\d+)?)""")
            .find(actionText)
            ?.groupValues
            ?.getOrNull(1)
            ?.toFloatOrNull()
            ?.toInt()
        val x2 = Regex("""(?:^|[,(]\s*)(?:x2|end_x)\s*=\s*(-?\d+(?:\.\d+)?)""")
            .find(actionText)
            ?.groupValues
            ?.getOrNull(1)
            ?.toFloatOrNull()
            ?.toInt()
        val y2 = Regex("""(?:^|[,(]\s*)(?:y2|end_y)\s*=\s*(-?\d+(?:\.\d+)?)""")
            .find(actionText)
            ?.groupValues
            ?.getOrNull(1)
            ?.toFloatOrNull()
            ?.toInt()
        if (x1 != null && y1 != null && x2 != null && y2 != null) {
            return (x1 to y1) to (x2 to y2)
        }

        return null
    }

    private fun dragDirection(start: Pair<Int, Int>, end: Pair<Int, Int>): String {
        val dx = end.first - start.first
        val dy = end.second - start.second
        if (abs(dx) < 20 && abs(dy) < 20) return "short"
        return if (abs(dx) > abs(dy)) {
            if (dx > 0) "right" else "left"
        } else {
            if (dy > 0) "down" else "up"
        }
    }

    private fun extractTextArg(actionText: String): String? {
        val match = Regex("""(?:^|[,(]\s*)(?:text|content|message)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^,)]+))""")
            .find(actionText)
            ?: return null
        return (match.groups[1]?.value ?: match.groups[2]?.value ?: match.groups[3]?.value)
            ?.trim()
    }

    private fun normalizeTextRouteValue(value: String): String {
        val normalized = value
            .replace("\\n", "\n")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()
        if (normalized.isBlank()) return ""
        val hash = normalized.hashCode().toUInt().toString(16)
        return "len${normalized.length}:h$hash"
    }

    private fun extractLabelFromCandidate(raw: String): String {
        val marker = "label="
        val start = raw.indexOf(marker, ignoreCase = true)
        if (start >= 0) {
            val from = start + marker.length
            val endCandidates = listOf(
                raw.indexOf("/role=", from, ignoreCase = true),
                raw.indexOf("/point=", from, ignoreCase = true),
                raw.indexOf("|", from),
                raw.indexOf(" / id=", from, ignoreCase = true)
            ).filter { it >= 0 }
            val end = endCandidates.minOrNull() ?: raw.length
            return raw.substring(from, end).trim().trim('"')
        }
        return Regex("""\[[A-Z_]+\]\s*"([^"]*)"""")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()
    }

    private fun isInputLikeCandidate(raw: String): Boolean {
        val role = parseCandidateRole(raw)
        if (role.contains("input") || role.contains("edit")) return true
        return raw.contains("editable", ignoreCase = true)
    }

    private fun parseCandidateRole(raw: String): String {
        return Regex("""(?:^|[\/|])role=([A-Za-z_]+)""")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.lowercase()
            ?: Regex("""^\s*\[([A-Z_]+)]""")
                .find(raw)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.lowercase()
            ?: ""
    }

    private fun isDismissLabelMatch(label: String, dismiss: String): Boolean {
        if (label.isBlank() || dismiss.isBlank()) return false
        val normalizedLabel = label.trim().lowercase()
        val normalizedDismiss = dismiss.trim().lowercase()

        if (normalizedDismiss in setOf("×", "✕", "x")) {
            return normalizedLabel == normalizedDismiss
        }
        if (normalizedLabel.length <= 2 || normalizedDismiss.length <= 2) {
            return normalizedLabel == normalizedDismiss
        }
        return normalizedLabel == normalizedDismiss || normalizedLabel.contains(normalizedDismiss)
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

    private val inputToolNames = setOf("type", "type_and_enter")
    private val routeSourceActionNames = setOf("click", "long_press", "type", "type_and_enter", "scroll", "drag")
    private val routeRecoveryActionNames = setOf("press_back", "press_home", "open_app", "back", "home")

    private data class DeadEndActionRoute(
        val actionName: String,
        val actionKey: String,
        val sourceKey: String,
        val destinationKey: String,
        val recoveryAction: String
    )

}
