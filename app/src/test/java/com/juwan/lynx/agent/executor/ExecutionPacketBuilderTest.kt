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
import com.juwan.lynx.state.ActionOutcome
import com.juwan.lynx.state.ActionRecord
import com.juwan.lynx.state.ActionResultCode
import com.juwan.lynx.state.ActionCandidate
import com.juwan.lynx.state.CapabilityState
import com.juwan.lynx.state.CandidateKind
import com.juwan.lynx.state.ToolResult
import com.juwan.lynx.state.WorldState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class ExecutionPacketBuilderTest : FunSpec({
    test("buildInput should emit vision-minimal packet without contract or target anchor") {
        val builder = ExecutionPacketBuilder(
            sanitizeUserInput = { it.trim() },
            isTargetAppMatch = { currentApp, strategy, _ -> currentApp == strategy.app },
            buildBlockedActionSignatures = { listOf("sig_1", "sig_2", "sig_3", "sig_4", "sig_5") }
        )

        val strategy = Strategy(
            goal = "给好友发送消息",
            app = "com.test.app",
            strategy = listOf("定位好友", "完成消息发送"),
            successCriteria = listOf("会话中可见已发送消息"),
            riskHints = listOf("验证码时请求用户协助"),
            originalInstruction = "给好友发送消息"
        )
        val state = WorldState(
            currentApp = "com.test.app",
            pageAffordances = (1..8).map { "affordance_$it" },
            pageCapabilities = listOf(
                CapabilityState.CAN_OPEN_CANDIDATE,
                CapabilityState.CAN_INPUT_TEXT,
                CapabilityState.CAN_SUBMIT_TEXT
            ),
            semanticLabels = (1..8).map { "semantic_$it" },
            uiTexts = (1..8).map { "ui_$it" },
            contentDescriptions = (1..6).map { "desc_$it" },
            ocrTexts = (1..8).map { "ocr_$it" },
            visibleTexts = (1..10).map { "visible_$it" },
            typedActionCandidates = (1..12).map {
                ActionCandidate(
                    id = "candidate_$it",
                    kind = CandidateKind.ACTION,
                    role = "BUTTON",
                    label = "candidate_$it",
                    actionable = true,
                    editable = false,
                    pointX = it,
                    pointY = it,
                    boundsLeft = it,
                    boundsTop = it,
                    boundsRight = it + 1,
                    boundsBottom = it + 1
                )
            },
            actionCandidates = (1..12).map { "[BUTTON] \"candidate_$it\" | point($it, $it)" },
            strategy = strategy.strategy,
            recentOutcomes = (1..5).map {
                ActionOutcome(
                    toolName = "tool_$it",
                    actionText = "outcome_$it",
                    resultCode = ActionResultCode.SUCCESS,
                    pageSignatureBefore = "before_$it",
                    pageSignatureAfter = "after_$it",
                    timestamp = it.toLong()
                )
            },
            taskHistoryActions = (1..5).map {
                ActionRecord(
                    action = "history_$it",
                    result = "ok_$it",
                    pageSignatureBefore = "before_$it",
                    pageSignatureAfter = "after_$it",
                    timestamp = it.toLong()
                )
            }
        )

        val input = builder.buildInput(
            strategy = strategy,
            recoveryHint = "recover",
            state = state,
            targetIdentifier = "com.test.app",
            iterationIndex = 3,
            availableToolNames = listOf("click", "type"),
            forbiddenToolNames = listOf("press_home")
        )

        input shouldContain "\"context_variant\":\"vision_minimal\""
        input shouldContain "\"facts\""
        input shouldContain "\"ui_texts_sample\""
        input shouldContain "\"content_desc_sample\""
        input shouldContain "\"ocr_texts_sample\""
        input shouldContain "\"semantic_labels_sample\""
        input shouldContain "\"page_capabilities\""
        input shouldContain "\"page_affordances\""
        input shouldContain "\"control\""
        input shouldContain "\"recent_outcomes\""
        input shouldContain "\"task_history_recent\""
        input shouldContain "\"blocked_action_signatures\""
        input shouldContain "\"success_criteria\""
        input shouldContain "会话中可见已发送消息"
        input shouldContain "\"risk_hints\""
        input shouldContain "\"verifier_missing\""
        input shouldContain "\"completion_evidence\""

        input shouldContain "ui_4"
        input shouldNotContain "ui_5"
        input shouldContain "desc_3"
        input shouldNotContain "desc_4"
        input shouldContain "ocr_4"
        input shouldNotContain "ocr_5"
        input shouldContain "semantic_4"
        input shouldNotContain "semantic_5"
        input shouldNotContain "\"candidate_actions\""
        input shouldNotContain "\"typed_candidates_sample\""
        input shouldNotContain "[BUTTON] \\\"candidate_1\\\""
        input shouldNotContain "<point>8 8</point>"
        input shouldNotContain "\"label\":\"candidate_1\""
        input shouldContain "outcome_5"
        input shouldNotContain "outcome_2"
        input shouldContain "history_5"
        input shouldNotContain "history_2"
        input shouldContain "sig_4"
        input shouldNotContain "sig_5"

        input shouldNotContain "\"target_anchor\""
        input shouldNotContain "\"contract_stage\""
        input shouldNotContain "\"interaction_context\""
        input shouldNotContain "\"milestone\""
        input shouldNotContain "\"visible_texts_sample\""
    }

    test("buildSystemPrompt should include ordered-control and irreversible-action guidance") {
        val builder = ExecutionPacketBuilder(
            sanitizeUserInput = { it.trim() },
            isTargetAppMatch = { currentApp, strategy, _ -> currentApp == strategy.app },
            buildBlockedActionSignatures = { emptyList() }
        )

        val prompt = builder.buildSystemPrompt(
            strategy = Strategy(
                goal = "设置一个计时器但不要启动",
                app = "clock",
                strategy = listOf("进入计时器", "调整数值"),
                originalInstruction = "设置一个计时器但不要启动"
            ),
            recoveryHint = null,
            availableTools = emptyList(),
            iterationIndex = 1
        )

        prompt shouldContain "ordered controls"
        prompt shouldContain "history.last_action_effect"
        prompt shouldContain "history.visible_text_delta"
        prompt shouldContain "reverse direction when it moved away"
        prompt shouldContain "40-140 for fine adjustment"
        prompt shouldContain "use a smaller length"
        prompt shouldContain "original_instruction is binding"
        prompt shouldContain "click a visible relevant entry/control"
        prompt shouldContain "type the complete content in one call with newline characters"
        prompt shouldContain "Do not collapse required separate lines"
        prompt shouldContain "Do not start timers, recordings, payments, posts, or submissions"
        prompt shouldContain "stay on a search results page"
        prompt shouldContain "Do not open a result page or submit the same query again"
    }

    test("buildInput should not expand dense list surfaces in vision-minimal mode") {
        val builder = ExecutionPacketBuilder(
            sanitizeUserInput = { it.trim() },
            isTargetAppMatch = { currentApp, strategy, _ -> currentApp == strategy.app },
            buildBlockedActionSignatures = { emptyList() }
        )

        val strategy = Strategy(
            goal = "处理 11 条列表链接",
            app = "com.test.app",
            strategy = listOf("进入详情页", "处理全部列表链接"),
            originalInstruction = "处理列表链接"
        )
        val state = WorldState(
            currentApp = "com.test.app",
            pageAffordances = listOf("has_list_cells"),
            pageCapabilities = listOf(
                CapabilityState.CAN_OPEN_CANDIDATE,
                "task.expected_count=11",
                "task.coverage_mode=full_list",
                "task.target_artifact_kind=list_link"
            ),
            semanticLabels = (1..20).map { "semantic_$it" },
            uiTexts = (1..20).map { "ui_$it" },
            contentDescriptions = (1..12).map { "desc_$it" },
            ocrTexts = (1..20).map { "ocr_$it" },
            typedActionCandidates = (1..20).map {
                ActionCandidate(
                    id = "link_$it",
                    kind = CandidateKind.ACTION,
                    role = "LINK",
                    label = "参考资料_$it",
                    actionable = true,
                    editable = false,
                    pointX = it,
                    pointY = it,
                    boundsLeft = it,
                    boundsTop = it,
                    boundsRight = it + 1,
                    boundsBottom = it + 1,
                    groupId = "link_main",
                    indexInGroup = it
                )
            },
            actionCandidates = (1..20).map { "[LINK] \"candidate_$it\" | point($it, $it)" }
        )

        val input = builder.buildInput(
            strategy = strategy,
            recoveryHint = null,
            state = state,
            targetIdentifier = "com.test.app",
            iterationIndex = 1,
            availableToolNames = listOf("click", "scroll"),
            forbiddenToolNames = emptyList()
        )

        input shouldNotContain "\"candidate_actions\""
        input shouldNotContain "[LINK] \\\"candidate_1\\\""
        input shouldContain "ui_4"
        input shouldNotContain "ui_5"
        input shouldContain "ocr_4"
        input shouldNotContain "ocr_5"
        input shouldNotContain "\"group_id\":\"link_main\""
        input shouldNotContain "\"index_in_group\""
    }

    test("buildInput should keep candidates out of the default packet even when goal-relevant") {
        val builder = ExecutionPacketBuilder(
            sanitizeUserInput = { it.trim() },
            isTargetAppMatch = { currentApp, strategy, _ -> currentApp == strategy.app },
            buildBlockedActionSignatures = { emptyList() }
        )
        val strategy = Strategy(
            goal = "进入 WLAN 页面",
            app = "com.test.settings",
            strategy = listOf("找到目标设置入口"),
            successCriteria = listOf("屏幕显示 WLAN 相关设置"),
            originalInstruction = "打开设置并进入 WLAN 页面"
        )
        val state = WorldState(
            currentApp = "com.test.settings",
            typedActionCandidates = (1..10).map { index ->
                ActionCandidate(
                    id = "candidate_$index",
                    kind = CandidateKind.ACTION,
                    role = "TEXT",
                    label = if (index == 10) "WLAN" else "普通候选_$index",
                    actionable = true,
                    editable = false,
                    pointX = index,
                    pointY = index,
                    boundsLeft = index,
                    boundsTop = index,
                    boundsRight = index + 1,
                    boundsBottom = index + 1
                )
            },
            actionCandidates = (1..10).map { index ->
                val label = if (index == 10) "WLAN" else "普通候选_$index"
                "[TEXT] \"$label\" | point($index, $index)"
            }
        )

        val input = builder.buildInput(
            strategy = strategy,
            recoveryHint = null,
            state = state,
            targetIdentifier = "com.test.settings",
            iterationIndex = 1,
            availableToolNames = listOf("click"),
            forbiddenToolNames = emptyList()
        )

        input shouldNotContain "\"id\":\"candidate_10\""
        input shouldNotContain "[TEXT] \\\"WLAN\\\""
    }

    test("buildInput should prefer structured tool results over legacy outcomes") {
        val builder = ExecutionPacketBuilder(
            sanitizeUserInput = { it.trim() },
            isTargetAppMatch = { currentApp, strategy, _ -> currentApp == strategy.app },
            buildBlockedActionSignatures = { emptyList() }
        )

        val strategy = Strategy(
            goal = "发送消息",
            app = "com.test.app",
            strategy = listOf("进入会话", "发送消息"),
            originalInstruction = "发送消息"
        )
        val state = WorldState(
            currentApp = "com.test.app",
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

        val input = builder.buildInput(
            strategy = strategy,
            recoveryHint = null,
            state = state,
            targetIdentifier = "com.test.app",
            iterationIndex = 1,
            availableToolNames = listOf("click"),
            forbiddenToolNames = emptyList()
        )

        input shouldContain "structured_action"
        input shouldContain "\"result_code\":\"ERROR\""
        input shouldContain "\"error_type\":\"tool_error\""
        input shouldContain "\"raw_signature_changed\":false"
        input shouldNotContain "legacy_action"
    }

    test("buildInput should carry blocked action memory from task history after replan") {
        val builder = ExecutionPacketBuilder(
            sanitizeUserInput = { it.trim() },
            isTargetAppMatch = { currentApp, strategy, _ -> currentApp == strategy.app },
            buildBlockedActionSignatures = { records ->
                records.map { it.action }
            }
        )

        val strategy = Strategy(
            goal = "进入目标页面",
            app = "com.test.app",
            strategy = listOf("换一种路径定位目标"),
            originalInstruction = "进入目标页面"
        )
        val state = WorldState(
            currentApp = "com.test.app",
            recentActions = emptyList(),
            taskHistoryActions = listOf(
                ActionRecord(
                    action = "click(x=400, y=465)",
                    result = "stuck",
                    pageSignatureBefore = "same",
                    pageSignatureAfter = "same",
                    timestamp = 1L,
                    resultCode = ActionResultCode.UNKNOWN
                )
            )
        )

        val input = builder.buildInput(
            strategy = strategy,
            recoveryHint = null,
            state = state,
            targetIdentifier = "com.test.app",
            iterationIndex = 1,
            availableToolNames = listOf("click", "scroll"),
            forbiddenToolNames = emptyList()
        )

        input shouldContain "click(x=400, y=465)"
    }

    test("buildInput should expose compact visible text delta for gesture reasoning") {
        val builder = ExecutionPacketBuilder(
            sanitizeUserInput = { it.trim() },
            isTargetAppMatch = { currentApp, strategy, _ -> currentApp == strategy.app },
            buildBlockedActionSignatures = { emptyList() }
        )
        val strategy = Strategy(
            goal = "设置为 1 分钟",
            app = "clock",
            strategy = listOf("调整数值"),
            originalInstruction = "设置为 1 分钟"
        )
        val state = WorldState(
            currentApp = "clock",
            taskHistoryActions = listOf(
                ActionRecord(
                    action = "scroll(x=500, y=660, direction=up, length=200)",
                    result = "changed",
                    pageSignatureBefore = "before",
                    pageSignatureAfter = "after",
                    timestamp = 1L,
                    visibleTextsBefore = listOf("计时器", "0 小时5分0秒", "开始"),
                    visibleTextsAfter = listOf("计时器", "0 小时12分0秒", "开始")
                )
            )
        )

        val input = builder.buildInput(
            strategy = strategy,
            recoveryHint = null,
            state = state,
            targetIdentifier = "clock",
            iterationIndex = 2,
            availableToolNames = listOf("scroll", "drag", "click"),
            forbiddenToolNames = emptyList()
        )

        input shouldContain "\"visible_text_delta\""
        input shouldContain "\"last_action_effect\":\"scroll(x=500, y=660, direction=up, length=200) => visible text changed: 0 小时5分0秒 -> 0 小时12分0秒"
        input shouldContain "0 小时5分0秒"
        input shouldContain "0 小时12分0秒"
        input shouldContain "scroll(x=500, y=660, direction=up, length=200)"

        val trace = builder.buildEvidenceTrace(state)
        trace["history_actions"] shouldBe 1
        trace["visible_delta_count"] shouldBe 1
        trace["last_action_changed"] shouldBe true
        trace["last_action_effect"].toString() shouldContain "visible text changed: 0 小时5分0秒 -> 0 小时12分0秒"
    }

    test("buildInput should expose compact search affordance without candidate expansion") {
        val builder = ExecutionPacketBuilder(
            sanitizeUserInput = { it.trim() },
            isTargetAppMatch = { currentApp, strategy, _ -> currentApp == strategy.app },
            buildBlockedActionSignatures = { listOf("click_returned_route|row=11") }
        )
        val strategy = Strategy(
            goal = "打开设置并进入显示和亮度页面",
            app = "设置",
            strategy = listOf("定位目标页面"),
            successCriteria = listOf("当前屏幕显示显示和亮度页面的内容"),
            originalInstruction = "打开设置并进入显示和亮度页面"
        )
        val state = WorldState(
            currentApp = "com.android.settings",
            uiTexts = listOf("设置", "搜索设置项", "华为帐号"),
            actionCandidates = listOf("[TEXT] \"搜索设置项\" | point(500, 213)")
        )

        val input = builder.buildInput(
            strategy = strategy,
            recoveryHint = null,
            state = state,
            targetIdentifier = "com.android.settings",
            iterationIndex = 1,
            availableToolNames = listOf("type_and_enter", "scroll"),
            forbiddenToolNames = listOf("click")
        )

        input shouldContain "\"search_available\":true"
        input shouldContain "\"suggested_search_query\":\"显示和亮度"
        input shouldContain "click_returned_route"
        input shouldNotContain "[TEXT] \\\"搜索设置项\\\""
    }

    test("buildInput should expose compact suppressed outcome message for recovery") {
        val builder = ExecutionPacketBuilder(
            sanitizeUserInput = { it.trim() },
            isTargetAppMatch = { currentApp, strategy, _ -> currentApp == strategy.app },
            buildBlockedActionSignatures = { listOf("click|point_bucket=15,45") }
        )
        val strategy = Strategy(
            goal = "打开设置并进入显示和亮度页面",
            app = "设置",
            strategy = listOf("定位目标页面"),
            successCriteria = listOf("当前屏幕显示目标页面"),
            originalInstruction = "打开设置并进入显示和亮度页面"
        )
        val state = WorldState(
            currentApp = "com.android.settings",
            recentToolResults = listOf(
                ToolResult(
                    toolName = "click",
                    actionText = "click(x=300, y=900)",
                    status = ActionResultCode.SUPPRESSED,
                    changed = false,
                    rawSignatureChanged = false,
                    pageSignatureBefore = "settings_home",
                    pageSignatureAfter = "settings_home",
                    message = "Error: 该列表行此前打开后又返回原页，疑似错误路线，请改用不同区域、滚动或搜索。"
                )
            )
        )

        val input = builder.buildInput(
            strategy = strategy,
            recoveryHint = null,
            state = state,
            targetIdentifier = "com.android.settings",
            iterationIndex = 4,
            availableToolNames = listOf("click", "scroll", "type_and_enter"),
            forbiddenToolNames = emptyList()
        )

        input shouldContain "\"result_code\":\"SUPPRESSED\""
        input shouldContain "\"message\":\"Error: 该列表行此前打开后又返回原页"
        input shouldContain "click|point_bucket=15,45"
    }

    test("buildSystemPrompt should emphasize raw facts and one-tool execution") {
        val builder = ExecutionPacketBuilder(
            sanitizeUserInput = { it.trim() },
            isTargetAppMatch = { currentApp, strategy, _ -> currentApp == strategy.app },
            buildBlockedActionSignatures = { emptyList() }
        )
        val strategy = Strategy(
            goal = "在高德地图中导航到阜宁县射滨村",
            app = "com.ss.android.ugc.aweme.lite",
            strategy = listOf("进入目标应用", "定位目标对象并进入可操作状态", "完成关键提交并确认结果"),
            originalInstruction = "打开高德地图并导航到阜宁县射滨村"
        )

        val prompt = builder.buildSystemPrompt(
            strategy = strategy,
            recoveryHint = null,
            availableTools = emptyList(),
            iterationIndex = 1
        )

        prompt shouldContain "Make exactly one tool call per round"
        prompt shouldContain "Treat the screenshot as the primary source of page understanding"
        prompt shouldContain "Use the latest Execution Packet JSON as the only source of truth"
        prompt shouldContain "Do not rely on guessed page categories"
        prompt shouldContain "Treat page_capabilities and page_affordances as weak local hints"
        prompt shouldContain "Use coordinates when the screenshot makes the target location clear"
        prompt shouldContain "cross-replan memory"
        prompt shouldContain "search_available is true"
        prompt shouldNotContain "raw coordinate clicks without supplied handles may be suppressed"
        prompt shouldNotContain "Use raw coordinates only when no suitable typed candidate exists"
    }
})
