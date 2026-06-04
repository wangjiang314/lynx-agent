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

import com.juwan.lynx.agent.FlowTraceLogger
import com.juwan.lynx.agent.Strategy

internal class StrategyNormalizer {
    fun normalize(strategy: Strategy): Strategy {
        val normalizedGoal = strategy.goal.trim().ifBlank { "执行用户任务" }
        val normalizedApp = strategy.app.trim().ifBlank { "unknown" }
        val normalizedSteps = normalizeSteps(strategy.strategy, normalizedGoal)
        val finalSteps = if (normalizedSteps.isEmpty()) defaultMilestones(normalizedGoal) else normalizedSteps
        val result = strategy.copy(goal = normalizedGoal, app = normalizedApp, strategy = finalSteps)
        if (result.strategy != strategy.strategy) {
            FlowTraceLogger.event(
                stage = "planner_strategy_normalized",
                kv = mapOf(
                    "before_strategy" to strategy.strategy.size,
                    "after_strategy" to result.strategy.size,
                    "sample_step" to result.strategy.firstOrNull().orEmpty().take(80)
                )
            )
        }
        return result
    }

    private fun normalizeSteps(steps: List<String>, goal: String): List<String> {
        return steps
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .map { sanitizeStepText(it) }
            .map { thinSanitizeStep(it, goal) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(8)
    }

    private fun sanitizeStepText(step: String): String {
        return step
            .replace(Regex("^\\d+[\\.、]\\s*"), "")
            .replace(Regex("^[-*]\\s*"), "")
            .replace("->", "→")
            .trim()
    }

    private fun containsForbiddenExecutionDetail(text: String): Boolean {
        if (text.isBlank()) return false
        if (TOOL_NAME_PATTERN.containsMatchIn(text)) return true
        if (COORDINATE_PATTERNS.any { it.containsMatchIn(text) }) return true

        val compact = text.replace(Regex("\\s+"), " ").trim()
        val verbCount = SCRIPT_VERBS.sumOf { verb ->
            Regex(Regex.escape(verb), RegexOption.IGNORE_CASE).findAll(compact).count()
        }
        val hasConnector = SCRIPT_CONNECTORS.any { connector ->
            compact.contains(connector, ignoreCase = true)
        }
        return verbCount >= 3 && hasConnector
    }

    private fun thinSanitizeStep(raw: String, goal: String): String {
        if (raw.isBlank()) return ""
        val cleaned = raw
            .replace(TOOL_NAME_PATTERN, " ")
            .replace(Regex("""<point>.*?</point>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("""-?\d+\s*,\s*-?\d+"""), " ")
            .replace(Regex("""\(\s*-?\d+\s*,\s*-?\d+\s*\)"""), " ")
            .replace(Regex("""\bx\s*=\s*-?\d+(?:\.\d+)?\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\by\s*=\s*-?\d+(?:\.\d+)?\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isBlank()) return ""
        if (!containsForbiddenExecutionDetail(raw)) return cleaned

        val goalSnippet = goal.trim().ifBlank { "当前任务" }.take(20)
        return "围绕“$goalSnippet”推进到下一可验证状态"
    }

    private fun defaultMilestones(goal: String): List<String> {
        val summarizedGoal = goal.trim().ifBlank { "用户目标" }.take(30)
        return listOf(
            "进入目标应用并到达任务入口",
            "围绕“$summarizedGoal”推进核心流程",
            "确认目标已达成并完成任务"
        )
    }

    companion object {
        private val COORDINATE_PATTERNS = listOf(
            Regex("""<point>.*?</point>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
            Regex("""\bx\s*=\s*-?\d+(?:\.\d+)?\b""", RegexOption.IGNORE_CASE),
            Regex("""\by\s*=\s*-?\d+(?:\.\d+)?\b""", RegexOption.IGNORE_CASE),
            Regex("""-?\d+\s*,\s*-?\d+"""),
            Regex("""\(\s*-?\d+\s*,\s*-?\d+\s*\)""")
        )
        private val TOOL_NAME_PATTERN = Regex(
            """\b(click|type|scroll|drag|long_press|open_app|press_back|press_home|resolve_installed_app|type_and_enter|finished)\b""",
            RegexOption.IGNORE_CASE
        )
        private val SCRIPT_VERBS = listOf(
            "点击", "输入", "返回", "滚动", "滑动", "长按", "拖拽", "打开", "启动", "press_back", "press_home"
        )
        private val SCRIPT_CONNECTORS = listOf("然后", "再", "接着", "随后", "并且", "并", "之后", "->", "→", ";", "；")
    }
}
