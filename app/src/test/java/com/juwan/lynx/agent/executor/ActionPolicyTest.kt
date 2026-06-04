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

import com.juwan.lynx.state.ActionOutcome
import com.juwan.lynx.state.ActionRecord
import com.juwan.lynx.state.ActionResultCode
import com.juwan.lynx.state.WorldState
import com.juwan.lynx.state.WorldStateManager
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk

class ActionPolicyTest : FunSpec({
    test("detectLoopRisk should flag repeated unchanged click loop") {
        val worldStateManager = mockStateManager(
            WorldState(
                recentActions = listOf(
                    actionRecord("click(<point>100 200</point>)", changed = false),
                    actionRecord("click(<point>100 200</point>)", changed = false),
                    actionRecord("click(<point>100 200</point>)", changed = false)
                )
            )
        )
        val policy = ActionPolicy(
            worldStateManager = worldStateManager,
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val loopRisk = policy.detectLoopRisk()

        loopRisk shouldContain "循环"
        loopRisk shouldContain "click"
        loopRisk shouldContain "避开同一点击区域"
    }

    test("suppressRepeatedFinishedWithoutEvidence should reject repeated finished without business evidence") {
        val worldStateManager = mockStateManager(
            WorldState(
                recentActions = listOf(
                    actionRecord("finished", changed = false),
                    actionRecord("click(<point>1 1</point>)", changed = true)
                )
            )
        )
        val policy = ActionPolicy(
            worldStateManager = worldStateManager,
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val rejection = policy.suppressRepeatedFinishedWithoutEvidence("finished")

        rejection shouldContain "可观测完成证据"
    }

    test("suppressRepeatedFinishedWithoutEvidence should allow repeated finished after reliable business outcome") {
        val worldStateManager = mockStateManager(
            WorldState(
                recentActions = listOf(
                    actionRecord("finished", changed = false),
                    actionRecord("click(<point>1 1</point>)", changed = true)
                ),
                recentOutcomes = listOf(
                    ActionOutcome(
                        toolName = "click",
                        actionText = "click(<point>1 1</point>)",
                        resultCode = ActionResultCode.SUCCESS,
                        pageSignatureBefore = "before",
                        pageSignatureAfter = "after",
                        timestamp = 1L
                    )
                )
            )
        )
        val policy = ActionPolicy(
            worldStateManager = worldStateManager,
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val rejection = policy.suppressRepeatedFinishedWithoutEvidence("finished")

        rejection shouldBe null
    }

    test("buildBlockedActionSignatures should keep unchanged buckets only") {
        val policy = ActionPolicy(
            worldStateManager = mockk(),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val blocked = policy.buildBlockedActionSignatures(
            listOf(
                actionRecord("click(<point>100 100</point>)", changed = false),
                actionRecord("click(<point>100 100</point>)", changed = true),
                actionRecord("scroll(direction=down)", changed = false),
                actionRecord("type(text=hello)", changed = false)
            )
        )

        blocked shouldContain "click|point_bucket=5,5"
        blocked shouldContain "scroll|direction=down"
        blocked shouldContain "type"
    }

    test("suppressInvalidToolArguments should reject scroll without direction") {
        val policy = ActionPolicy(
            worldStateManager = mockk(),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val rejection = policy.suppressInvalidToolArguments(
            actionName = "scroll",
            args = emptyMap()
        )

        rejection shouldBe "Error: 缺少 direction 参数"
    }

    test("suppressInvalidToolArguments should suppress repeated no-progress scroll direction only") {
        val policy = ActionPolicy(
            worldStateManager = mockStateManager(
                WorldState(
                    recentActions = listOf(
                        actionRecord("scroll(x=500, y=800, direction=down, length=500)", changed = false, timestamp = 1L),
                        actionRecord("scroll(x=500, y=800, direction=down, length=500)", changed = false, timestamp = 2L)
                    )
                )
            ),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val repeated = policy.suppressInvalidToolArguments(
            actionName = "scroll",
            args = mapOf("direction" to "down")
        )
        val opposite = policy.suppressInvalidToolArguments(
            actionName = "scroll",
            args = mapOf("direction" to "up")
        )

        repeated shouldContain "direction=down"
        repeated shouldContain "direction=up"
        repeated shouldContain "点击可见的相关入口/控件"
        opposite shouldBe null
    }

    test("suppressInvalidToolArguments should treat stable visible text as no-progress scroll") {
        val stableTexts = listOf("设置", "搜索设置项", "华为帐号、付款与账单、云空间等")
        val policy = ActionPolicy(
            worldStateManager = mockStateManager(
                WorldState(
                    recentActions = listOf(
                        actionRecord(
                            "scroll(x=500, y=500, direction=down, length=500)",
                            changed = true,
                            signature = "noise_a",
                            afterSignature = "noise_b",
                            timestamp = 1L,
                            beforeTexts = stableTexts,
                            afterTexts = stableTexts
                        ),
                        actionRecord(
                            "scroll(x=500, y=500, direction=down, length=500)",
                            changed = true,
                            signature = "noise_c",
                            afterSignature = "noise_d",
                            timestamp = 2L,
                            beforeTexts = stableTexts,
                            afterTexts = stableTexts
                        )
                    )
                )
            ),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val rejection = policy.suppressInvalidToolArguments(
            actionName = "scroll",
            args = mapOf("direction" to "down")
        )

        rejection shouldContain "连续无可观测进展"
        rejection shouldContain "direction=up"
    }

    test("suppressInvalidToolArguments should allow repeated scroll after visible text changes") {
        val policy = ActionPolicy(
            worldStateManager = mockStateManager(
                WorldState(
                    recentActions = listOf(
                        actionRecord(
                            "scroll(x=500, y=500, direction=down, length=500)",
                            changed = true,
                            timestamp = 1L,
                            beforeTexts = listOf("设置", "搜索设置项", "华为帐号"),
                            afterTexts = listOf("设置", "通知和状态栏", "声音和振动")
                        ),
                        actionRecord(
                            "scroll(x=500, y=500, direction=down, length=500)",
                            changed = true,
                            timestamp = 2L,
                            beforeTexts = listOf("设置", "通知和状态栏", "声音和振动"),
                            afterTexts = listOf("设置", "显示和亮度", "桌面和壁纸")
                        )
                    )
                )
            ),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val rejection = policy.suppressInvalidToolArguments(
            actionName = "scroll",
            args = mapOf("direction" to "down")
        )

        rejection shouldBe null
    }

    test("suppressInvalidToolArguments should reject click without point") {
        val policy = ActionPolicy(
            worldStateManager = mockk(),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val rejection = policy.suppressInvalidToolArguments(
            actionName = "click",
            args = emptyMap()
        )

        rejection shouldContain "缺少 point 或 x/y"
    }

    test("suppressInvalidToolArguments should allow raw coordinate click on candidate-rich surfaces") {
        val policy = ActionPolicy(
            worldStateManager = mockStateManager(candidateRichState()),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val rejection = policy.suppressInvalidToolArguments(
            actionName = "click",
            args = mapOf("point" to "105 205")
        )

        rejection shouldBe null
    }

    test("suppressInvalidToolArguments should allow raw coordinate click on dense generic surfaces") {
        val denseState = WorldState(
            pageAffordances = listOf("has_primary_cta"),
            actionCandidates = (1..6).map { index ->
                "[BUTTON] \"候选$index\" | point(${80 * index}, ${120 * index})"
            }
        )
        val policy = ActionPolicy(
            worldStateManager = mockStateManager(denseState),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val rejection = policy.suppressInvalidToolArguments(
            actionName = "click",
            args = mapOf("point" to "480 720")
        )

        rejection shouldBe null
    }

    test("suppressInvalidToolArguments should reject click metadata without coordinates") {
        val policy = ActionPolicy(
            worldStateManager = mockStateManager(candidateRichState()),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val rejection = policy.suppressInvalidToolArguments(
            actionName = "click",
            args = mapOf("label" to "目标一")
        )

        rejection shouldContain "缺少 point 或 x/y"
    }

    test("suppressInvalidToolArguments should allow raw click on sparse surfaces") {
        val policy = ActionPolicy(
            worldStateManager = mockStateManager(WorldState()),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val rejection = policy.suppressInvalidToolArguments(
            actionName = "click",
            args = mapOf("point" to "105 205")
        )

        rejection shouldBe null
    }

    test("suppressInvalidToolArguments should suppress known no-progress click bucket across replans") {
        val state = WorldState(
            pageSignature = "settings_home",
            taskHistoryActions = listOf(
                actionRecord("click(x=400, y=465)", changed = false, signature = "settings_home", timestamp = 1L),
                actionRecord("click(x=405, y=468)", changed = false, signature = "settings_home", timestamp = 2L)
            )
        )
        val policy = ActionPolicy(
            worldStateManager = mockStateManager(state),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val rejection = policy.suppressInvalidToolArguments(
            actionName = "click",
            args = mapOf("x" to "410", "y" to "462")
        )

        rejection shouldContain "已多次无进展"
    }

    test("suppressInvalidToolArguments should suppress known dead-end click route across replans") {
        val state = WorldState(
            pageSignature = "home_sig_new",
            visibleTexts = listOf("设置", "搜索设置项", "账号"),
            taskHistoryActions = listOf(
                actionRecord(
                    action = "click(x=300, y=900)",
                    changed = true,
                    signature = "home_sig_old",
                    afterSignature = "detail_sig_old",
                    timestamp = 1L,
                    beforeTexts = listOf("设置", "搜索设置项", "账号"),
                    afterTexts = listOf("桌面和壁纸", "壁纸")
                ),
                actionRecord(
                    action = "press_back",
                    changed = true,
                    signature = "detail_sig_old",
                    afterSignature = "home_sig_new",
                    timestamp = 2L,
                    beforeTexts = listOf("桌面和壁纸", "壁纸"),
                    afterTexts = listOf("设置", "搜索设置项", "账号")
                )
            )
        )
        val policy = ActionPolicy(
            worldStateManager = mockStateManager(state),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val rejection = policy.suppressInvalidToolArguments(
            actionName = "click",
            args = mapOf("x" to "450", "y" to "900")
        )

        rejection shouldContain "无法继续推进"
    }

    test("suppressInvalidToolArguments should not let extra metadata bypass wrong-route coordinate suppression") {
        val state = WorldState(
            pageSignature = "home_sig_new",
            visibleTexts = listOf("设置", "搜索设置项", "账号"),
            taskHistoryActions = listOf(
                actionRecord(
                    action = "click(x=300, y=900, label=显示和亮度)",
                    changed = true,
                    signature = "home_sig_old",
                    afterSignature = "detail_sig_old",
                    timestamp = 1L,
                    beforeTexts = listOf("设置", "搜索设置项", "账号"),
                    afterTexts = listOf("桌面和壁纸", "壁纸")
                ),
                actionRecord(
                    action = "press_back",
                    changed = true,
                    signature = "detail_sig_old",
                    afterSignature = "home_sig_new",
                    timestamp = 2L,
                    beforeTexts = listOf("桌面和壁纸", "壁纸"),
                    afterTexts = listOf("设置", "搜索设置项", "账号")
                )
            )
        )
        val policy = ActionPolicy(
            worldStateManager = mockStateManager(state),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val rejection = policy.suppressInvalidToolArguments(
            actionName = "click",
            args = mapOf(
                "x" to "450",
                "y" to "900",
                "label" to "显示和亮度"
            )
        )

        rejection shouldContain "无法继续推进"
    }

    test("suppressInvalidToolArguments should suppress click region that required recovery") {
        val state = WorldState(
            pageSignature = "browser_home_new",
            visibleTexts = listOf("浏览器", "热门内容", "跳过 3"),
            taskHistoryActions = listOf(
                actionRecord(
                    action = "click(x=871, y=58)",
                    changed = true,
                    signature = "browser_home_old",
                    afterSignature = "external_app",
                    timestamp = 1L,
                    beforeTexts = listOf("浏览器", "热门内容", "跳过 5"),
                    afterTexts = listOf("外部应用", "商品首页")
                ),
                actionRecord(
                    action = "open_app(app=浏览器, package_name=com.huawei.browser)",
                    changed = true,
                    signature = "external_app",
                    afterSignature = "browser_home_new",
                    timestamp = 2L,
                    beforeTexts = listOf("外部应用", "商品首页"),
                    afterTexts = listOf("浏览器", "热门内容", "跳过 3"),
                    resultCode = ActionResultCode.SUCCESS
                )
            )
        )
        val policy = ActionPolicy(
            worldStateManager = mockStateManager(state),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val rejection = policy.suppressInvalidToolArguments(
            actionName = "click",
            args = mapOf("x" to "868", "y" to "60")
        )

        rejection shouldContain "无法继续推进"
    }

    test("suppressInvalidToolArguments should not suppress route when current source page differs") {
        val state = WorldState(
            pageSignature = "browser_search",
            visibleTexts = listOf("OpenAI", "搜索"),
            taskHistoryActions = listOf(
                actionRecord(
                    action = "click(x=871, y=58)",
                    changed = true,
                    signature = "browser_home_old",
                    afterSignature = "external_app",
                    timestamp = 1L,
                    beforeTexts = listOf("浏览器", "热门内容", "跳过 5"),
                    afterTexts = listOf("外部应用", "商品首页")
                ),
                actionRecord(
                    action = "open_app(app=浏览器, package_name=com.huawei.browser)",
                    changed = true,
                    signature = "external_app",
                    afterSignature = "browser_search",
                    timestamp = 2L,
                    beforeTexts = listOf("外部应用", "商品首页"),
                    afterTexts = listOf("OpenAI", "搜索"),
                    resultCode = ActionResultCode.SUCCESS
                )
            )
        )
        val policy = ActionPolicy(
            worldStateManager = mockStateManager(state),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val rejection = policy.suppressInvalidToolArguments(
            actionName = "click",
            args = mapOf("x" to "868", "y" to "60")
        )

        rejection shouldBe null
    }

    test("buildBlockedActionSignatures should include dead-end route memory") {
        val policy = ActionPolicy(
            worldStateManager = mockk(),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val blocked = policy.buildBlockedActionSignatures(
            listOf(
                actionRecord(
                    action = "click(x=300, y=900)",
                    changed = true,
                    signature = "home",
                    afterSignature = "detail",
                    timestamp = 1L,
                    beforeTexts = listOf("Home", "Search"),
                    afterTexts = listOf("Wrong Detail")
                ),
                actionRecord(
                    action = "press_back",
                    changed = true,
                    signature = "detail",
                    afterSignature = "home",
                    timestamp = 2L,
                    beforeTexts = listOf("Wrong Detail"),
                    afterTexts = listOf("Home", "Search")
                )
            )
        )

        blocked shouldContain "dead_end_route|action=click|key=region:1,11|source=Home/Search|opened=WrongDetail|recovery=press_back"
    }

    test("suppressInvalidToolArguments should suppress known dead-end text input route") {
        val state = WorldState(
            pageSignature = "search_page_new",
            visibleTexts = listOf("Search", "Input"),
            taskHistoryActions = listOf(
                actionRecord(
                    action = "type_and_enter(text=OpenAI)",
                    changed = true,
                    signature = "search_page_old",
                    afterSignature = "bad_results",
                    timestamp = 1L,
                    beforeTexts = listOf("Search", "Input"),
                    afterTexts = listOf("Unhelpful page", "No next step")
                ),
                actionRecord(
                    action = "press_back",
                    changed = true,
                    signature = "bad_results",
                    afterSignature = "search_page_new",
                    timestamp = 2L,
                    beforeTexts = listOf("Unhelpful page", "No next step"),
                    afterTexts = listOf("Search", "Input")
                )
            )
        )
        val policy = ActionPolicy(
            worldStateManager = mockStateManager(state),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val rejection = policy.suppressInvalidToolArguments(
            actionName = "type_and_enter",
            args = mapOf("text" to "OpenAI")
        )

        rejection shouldContain "无法继续推进"
    }

    test("suppressInvalidToolArguments should suppress known dead-end scroll direction") {
        val state = WorldState(
            pageSignature = "list_page_new",
            visibleTexts = listOf("List", "Visible item"),
            taskHistoryActions = listOf(
                actionRecord(
                    action = "scroll(direction=down)",
                    changed = true,
                    signature = "list_page_old",
                    afterSignature = "dead_end_page",
                    timestamp = 1L,
                    beforeTexts = listOf("List", "Visible item"),
                    afterTexts = listOf("No useful content")
                ),
                actionRecord(
                    action = "press_back",
                    changed = true,
                    signature = "dead_end_page",
                    afterSignature = "list_page_new",
                    timestamp = 2L,
                    beforeTexts = listOf("No useful content"),
                    afterTexts = listOf("List", "Visible item")
                )
            )
        )
        val policy = ActionPolicy(
            worldStateManager = mockStateManager(state),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val repeated = policy.suppressInvalidToolArguments(
            actionName = "scroll",
            args = mapOf("direction" to "down")
        )
        val opposite = policy.suppressInvalidToolArguments(
            actionName = "scroll",
            args = mapOf("direction" to "up")
        )

        repeated shouldContain "无法继续推进"
        opposite shouldBe null
    }

    test("suppressInvalidToolArguments should suppress known dead-end drag direction") {
        val state = WorldState(
            pageSignature = "canvas_page_new",
            visibleTexts = listOf("Canvas", "Tools"),
            taskHistoryActions = listOf(
                actionRecord(
                    action = "drag(x1=500, y1=800, x2=500, y2=100)",
                    changed = true,
                    signature = "canvas_page_old",
                    afterSignature = "dead_end_panel",
                    timestamp = 1L,
                    beforeTexts = listOf("Canvas", "Tools"),
                    afterTexts = listOf("Blocked panel")
                ),
                actionRecord(
                    action = "press_back",
                    changed = true,
                    signature = "dead_end_panel",
                    afterSignature = "canvas_page_new",
                    timestamp = 2L,
                    beforeTexts = listOf("Blocked panel"),
                    afterTexts = listOf("Canvas", "Tools")
                )
            )
        )
        val policy = ActionPolicy(
            worldStateManager = mockStateManager(state),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val rejection = policy.suppressInvalidToolArguments(
            actionName = "drag",
            args = mapOf("x1" to "500", "y1" to "800", "x2" to "500", "y2" to "100")
        )

        rejection shouldContain "无法继续推进"
    }

    test("detectTemporarilyBlockedLoopTool should keep click available after repeated suppressed clicks") {
        val policy = ActionPolicy(
            worldStateManager = mockk(),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val blocked = policy.detectTemporarilyBlockedLoopTool(
            listOf(
                actionRecord(
                    action = "click(x=300, y=900)",
                    changed = false,
                    result = "Error: 点击区域已抑制",
                    resultCode = ActionResultCode.SUPPRESSED
                ),
                actionRecord(
                    action = "click(x=300, y=900)",
                    changed = false,
                    result = "Error: 点击区域已抑制",
                    resultCode = ActionResultCode.SUPPRESSED
                ),
                actionRecord(
                    action = "click(x=300, y=900)",
                    changed = false,
                    result = "Error: 点击区域已抑制",
                    resultCode = ActionResultCode.SUPPRESSED
                )
            )
        )

        blocked shouldBe null
    }

    test("detectTemporarilyBlockedLoopTool should keep click available after no-progress non-click action") {
        val policy = ActionPolicy(
            worldStateManager = mockk(),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val blocked = policy.detectTemporarilyBlockedLoopTool(
            listOf(
                actionRecord(
                    action = "click(x=300, y=900)",
                    changed = false,
                    signature = "home",
                    result = "Error: 点击区域已抑制",
                    resultCode = ActionResultCode.SUPPRESSED
                ),
                actionRecord(
                    action = "click(x=300, y=900)",
                    changed = false,
                    signature = "home",
                    result = "Error: 点击区域已抑制",
                    resultCode = ActionResultCode.SUPPRESSED
                ),
                actionRecord(
                    action = "long_press(x=300, y=900)",
                    changed = false,
                    signature = "home",
                    result = "no progress"
                )
            )
        )

        blocked shouldBe null
    }

    test("detectTemporarilyBlockedLoopTool should unblock click after non-click page progress") {
        val policy = ActionPolicy(
            worldStateManager = mockk(),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val blocked = policy.detectTemporarilyBlockedLoopTool(
            listOf(
                actionRecord(
                    action = "click(x=300, y=900)",
                    changed = false,
                    signature = "home",
                    result = "Error: 点击区域已抑制",
                    resultCode = ActionResultCode.SUPPRESSED
                ),
                actionRecord(
                    action = "click(x=300, y=900)",
                    changed = false,
                    signature = "home",
                    result = "Error: 点击区域已抑制",
                    resultCode = ActionResultCode.SUPPRESSED
                ),
                actionRecord(
                    action = "scroll(direction=down)",
                    changed = true,
                    signature = "home",
                    afterSignature = "home_scrolled",
                    result = "scrolled"
                )
            )
        )

        blocked shouldBe null
    }

    test("detectTemporarilyBlockedLoopTool should keep scroll available after repeated no-progress scrolls") {
        val policy = ActionPolicy(
            worldStateManager = mockk(),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val blocked = policy.detectTemporarilyBlockedLoopTool(
            listOf(
                actionRecord("scroll(direction=down)", changed = false),
                actionRecord("scroll(direction=down)", changed = false),
                actionRecord("scroll(direction=down)", changed = false)
            )
        )

        blocked shouldBe null
    }

    test("detectTemporarilyBlockedInputTools should block input tools after missing focus failure") {
        val policy = ActionPolicy(
            worldStateManager = mockk(),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val blocked = policy.detectTemporarilyBlockedInputTools(
            listOf(
                actionRecord(
                    action = "type(text=电池)",
                    changed = false,
                    result = "错误: 当前没有已聚焦输入框，请先点击输入框"
                )
            )
        )

        blocked shouldBe setOf("type", "type_and_enter")
    }

    test("detectTemporarilyBlockedInputTools should unblock after a non-input action") {
        val policy = ActionPolicy(
            worldStateManager = mockk(),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val blocked = policy.detectTemporarilyBlockedInputTools(
            listOf(
                actionRecord(
                    action = "type(text=电池)",
                    changed = false,
                    result = "错误: 未找到可编辑输入框"
                ),
                actionRecord("click(x=120, y=197)", changed = true)
            )
        )

        blocked shouldBe emptySet()
    }

    test("suppressInvalidToolArguments should reject negative click coordinates") {
        val policy = ActionPolicy(
            worldStateManager = mockk(),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val rejection = policy.suppressInvalidToolArguments(
            actionName = "click",
            args = mapOf("point" to "-1 200")
        )

        rejection shouldContain "坐标不能为负数"
    }

    test("suppressInvalidToolArguments should reject invalid scroll direction") {
        val policy = ActionPolicy(
            worldStateManager = mockk(),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val rejection = policy.suppressInvalidToolArguments(
            actionName = "scroll",
            args = mapOf("direction" to "diag")
        )

        rejection shouldContain "方向必须是 up/down/left/right"
    }

    test("suppressInvalidToolArguments should reject tiny scroll length") {
        val policy = ActionPolicy(
            worldStateManager = mockk(),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val rejection = policy.suppressInvalidToolArguments(
            actionName = "scroll",
            args = mapOf("direction" to "up", "length" to "5")
        )

        rejection shouldContain "length=5 太小"
    }

    test("suppressInvalidToolArguments should reject blank text input") {
        val policy = ActionPolicy(
            worldStateManager = mockk(),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val rejection = policy.suppressInvalidToolArguments(
            actionName = "type",
            args = mapOf("text" to "   ")
        )

        rejection shouldBe "Error: 文本不能为空"
    }

    test("suppressInvalidToolArguments should reject missing text for type and type_and_enter") {
        val policy = ActionPolicy(
            worldStateManager = mockk(),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        policy.suppressInvalidToolArguments(
            actionName = "type",
            args = emptyMap()
        ) shouldBe "Error: 缺少 text 参数"

        policy.suppressInvalidToolArguments(
            actionName = "type_and_enter",
            args = emptyMap()
        ) shouldBe "Error: 缺少 text 参数"
    }

    test("suppressInvalidToolArguments should reject partial text for required multiline literals") {
        val policy = ActionPolicy(
            worldStateManager = mockStateManager(multilineLiteralState()),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val rejection = policy.suppressInvalidToolArguments(
            actionName = "type",
            args = mapOf("text" to "Lynx agent test")
        )

        rejection shouldContain "多个字面内容"
        rejection shouldContain "cross app challenge"
    }

    test("suppressInvalidToolArguments should allow complete multiline literal text") {
        val policy = ActionPolicy(
            worldStateManager = mockStateManager(multilineLiteralState()),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val rejection = policy.suppressInvalidToolArguments(
            actionName = "type",
            args = mapOf("text" to "Lynx agent test\ncross app challenge")
        )

        rejection shouldBe null
    }

    test("suppressInvalidToolArguments should reject collapsed text when line structure is required") {
        val policy = ActionPolicy(
            worldStateManager = mockStateManager(multilineLiteralState()),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val rejection = policy.suppressInvalidToolArguments(
            actionName = "type_and_enter",
            args = mapOf("text" to "Lynx agent test cross app challenge")
        )

        rejection shouldContain "换行"
    }

    test("suppressInvalidToolArguments should allow single literal text input") {
        val policy = ActionPolicy(
            worldStateManager = mockStateManager(
                WorldState(goal = "在浏览器搜索“Lynx agent test”")
            ),
            primitiveTools = emptyList(),
            repeatedActionLimit = 3
        )

        val rejection = policy.suppressInvalidToolArguments(
            actionName = "type",
            args = mapOf("text" to "Lynx agent test")
        )

        rejection shouldBe null
    }
})

private fun mockStateManager(state: WorldState): WorldStateManager {
    val manager = mockk<WorldStateManager>()
    every { manager.getState() } returns state
    return manager
}

private fun multilineLiteralState(): WorldState {
    return WorldState(
        goal = "新建两行草稿，第一行是“Lynx agent test”，第二行是“cross app challenge”",
        successCriteria = listOf(
            "最终可见结果必须包含原始指令中的字面内容：“Lynx agent test” / “cross app challenge”",
            "必须保留多行/分行结构"
        )
    )
}

private fun actionRecord(
    action: String,
    changed: Boolean,
    signature: String = "before",
    afterSignature: String = if (changed) "${signature}_after" else signature,
    timestamp: Long = 1L,
    result: String? = null,
    beforeTexts: List<String> = emptyList(),
    afterTexts: List<String> = emptyList(),
    resultCode: ActionResultCode = if (changed) ActionResultCode.SUCCESS else ActionResultCode.UNKNOWN
): ActionRecord {
    return ActionRecord(
        action = action,
        result = result ?: if (changed) "changed" else "stuck",
        pageSignatureBefore = signature,
        pageSignatureAfter = afterSignature,
        timestamp = timestamp,
        visibleTextsBefore = beforeTexts,
        visibleTextsAfter = afterTexts,
        resultCode = resultCode
    )
}

private fun candidateRichState(): WorldState {
    return WorldState(
        pageAffordances = listOf("has_list_cells"),
        actionCandidates = listOf(
            "[BUTTON] \"目标一\" | point(100, 200)",
            "[BUTTON] \"目标二\" | point(300, 400)"
        )
    )
}
