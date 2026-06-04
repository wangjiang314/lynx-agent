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

package com.juwan.lynx.agent.planner

import com.juwan.lynx.state.ActionRecord
import com.juwan.lynx.state.WorldState
import kotlin.math.abs

internal class ReflectionContextBuilder {
    fun build(worldState: WorldState, failureReason: String): String {
        return buildString {
            appendLine("失败原因: ${failureReason.trim()}")
            appendLine("当前 App: ${worldState.currentApp ?: "桌面"}")
            if (worldState.goal.isNotBlank()) appendLine("目标: ${worldState.goal}")
            if (!worldState.resolvedTargetPackage.isNullOrBlank()) {
                appendLine("目标包名: ${worldState.resolvedTargetPackage}")
            }
            if (worldState.strategy.isNotEmpty()) {
                appendLine("当前策略: ${worldState.strategy.joinToString(" | ")}")
            }
            if (worldState.visibleTexts.isNotEmpty()) {
                appendLine("可见文本样本: ${worldState.visibleTexts.take(8).joinToString(" | ")}")
            }
            appendLine("StuckCount: ${worldState.stuckCount}")
            appendLine()
            appendLine("最近操作:")
            appendLine(buildRecentActionSummary(worldState.recentActions.takeLast(6)))
            appendLine()
            appendLine("最近结果:")
            appendLine(buildRecentOutcomeSummary(worldState))
            appendLine()
            appendLine("动作模式摘要:")
            appendLine(buildActionPatternSummary(worldState))
            appendLine()
            appendLine("请仅基于这些可观测事实决定 replan 或 abort。不要输出低层工具步骤。")
        }
    }

    private fun buildRecentActionSummary(records: List<ActionRecord>): String {
        if (records.isEmpty()) return "none"
        return buildString {
            records.forEach { record ->
                val changed = record.pageSignatureBefore != record.pageSignatureAfter
                val actionText = record.action.replace(Regex("\\s+"), " ").take(120)
                val resultText = record.result.replace(Regex("\\s+"), " ").take(120)
                appendLine("- $actionText ${if (changed) "→变化" else "→未变"} $resultText")
            }
        }
    }

    private fun buildRecentOutcomeSummary(worldState: WorldState): String {
        val outcomes = worldState.recentOutcomes.takeLast(6)
        if (outcomes.isEmpty()) return "none"
        return outcomes.joinToString("\n") { outcome ->
            val pageChanged = outcome.pageSignatureBefore != outcome.pageSignatureAfter
            "- ${outcome.toolName}:${outcome.resultCode.name}${if (pageChanged) " ->变化" else ""}"
        }
    }

    private fun buildActionPatternSummary(worldState: WorldState): String {
        val recent = worldState.recentActions.takeLast(6)
        val historyRecent = worldState.taskHistoryActions.takeLast(8)
        val evidence = if (recent.isNotEmpty()) recent else historyRecent
        if (evidence.isEmpty()) {
            return "recent_actions=0"
        }

        val names = evidence.map { normalizeActionName(it.action) }
        val uniqueActions = names.distinct().size
        val unchangedCount = evidence.count { it.pageSignatureBefore == it.pageSignatureAfter }
        val trailingSameAction = countTrailingSameAction(names)

        val nearClickRepeat = detectTrailingNearClickRepeat(evidence)
        val lastChanged = evidence.lastOrNull()?.let {
            it.pageSignatureBefore != it.pageSignatureAfter
        } ?: false

        return buildString {
            appendLine("recent_actions=${evidence.size}")
            appendLine("unique_actions=$uniqueActions")
            appendLine("unchanged_actions=$unchangedCount")
            appendLine("trailing_same_action=$trailingSameAction")
            appendLine("near_click_repeat=$nearClickRepeat")
            append("last_action_changed=$lastChanged")
        }
    }

    private fun normalizeActionName(action: String): String {
        val compact = action.replace(Regex("\\s+"), " ").trim()
        val withoutArgs = compact.substringBefore("(").trim()
        return withoutArgs.ifBlank {
            compact.substringBefore("{").trim().ifBlank { compact }
        }
    }

    private fun countTrailingSameAction(names: List<String>): Int {
        if (names.isEmpty()) return 0
        val tail = names.last()
        var count = 0
        for (name in names.asReversed()) {
            if (name.equals(tail, ignoreCase = true)) {
                count++
            } else {
                break
            }
        }
        return count
    }

    private fun detectTrailingNearClickRepeat(
        recentActions: List<ActionRecord>,
        threshold: Int = 20
    ): Boolean {
        val last = recentActions.lastOrNull() ?: return false
        val prev = recentActions.dropLast(1).lastOrNull() ?: return false
        if (!normalizeActionName(last.action).equals("click", ignoreCase = true)) return false
        if (!normalizeActionName(prev.action).equals("click", ignoreCase = true)) return false

        val p1 = parsePointFromAction(last.action) ?: return false
        val p2 = parsePointFromAction(prev.action) ?: return false
        val distance = abs(p1.first - p2.first) + abs(p1.second - p2.second)
        return distance <= threshold
    }

    private fun parsePointFromAction(action: String): Pair<Int, Int>? {
        val pattern = Regex("""<point>\s*(-?\d+(?:\.\d+)?)\s+(-?\d+(?:\.\d+)?)\s*</point>""")
        val match = pattern.find(action)
        if (match != null) {
            val x = match.groupValues[1].toFloatOrNull()?.toInt() ?: return null
            val y = match.groupValues[2].toFloatOrNull()?.toInt() ?: return null
            return x to y
        }

        val nums = Regex("""-?\d+(?:\.\d+)?""").findAll(action)
            .mapNotNull { it.value.toFloatOrNull() }
            .take(2)
            .toList()
        if (nums.size < 2) return null
        return nums[0].toInt() to nums[1].toInt()
    }
}
