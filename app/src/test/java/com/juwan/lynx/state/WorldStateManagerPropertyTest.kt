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

import com.juwan.lynx.agent.Strategy
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

private class MockScreenObserver(private var signatureCounter: Int = 0) : ScreenObserver {
    override fun capture(): Observation {
        signatureCounter++
        return Observation(
            screenshotBase64 = "mock_screenshot_$signatureCounter",
            uiTree = emptyList(),
            ocrTexts = emptyList(),
            pageSignature = "signature_$signatureCounter",
            currentApp = "com.test.app",
            debugPageLabel = "TestPage"
        )
    }
}

private class ControllableMockScreenObserver(
    private val signatures: List<String>
) : ScreenObserver {
    private var captureIndex = 0

    override fun capture(): Observation {
        val signature = signatures.getOrElse(captureIndex) { "signature_overflow_$captureIndex" }
        captureIndex++
        return Observation(
            screenshotBase64 = "mock_screenshot_$captureIndex",
            uiTree = emptyList(),
            ocrTexts = emptyList(),
            pageSignature = signature,
            currentApp = "com.test.app",
            debugPageLabel = "TestPage"
        )
    }
}

class WorldStateManagerPropertyTest : FunSpec({
    context("V4 action semantics") {
        test("classifyActionCategory should distinguish control environment and business actions consistently") {
            classifyActionCategory("finished(result=done)") shouldBe ActionCategory.CONTROL
            classifyActionCategory("open_app(package_name=com.test.app)") shouldBe ActionCategory.ENVIRONMENT
            classifyActionCategory("press_home") shouldBe ActionCategory.ENVIRONMENT
            classifyActionCategory("click(point=<point>1 1</point>)") shouldBe ActionCategory.BUSINESS
            classifyActionCategory("type(text=你好)") shouldBe ActionCategory.BUSINESS
        }

        test("setStrategy should reset execution evidence without seeding hidden target state") {
            val manager = WorldStateManager(MockScreenObserver())
            manager.updateAfterAction("click(point=<point>1 1</point>)", "ok")

            manager.setStrategy(
                Strategy(
                    goal = "给好友发送消息",
                    app = "com.ss.android.ugc.aweme.lite",
                    strategy = listOf("进入消息页", "发送消息")
                )
            )

            val state = manager.getState()
            state.goal shouldBe "给好友发送消息"
            state.strategy shouldBe listOf("进入消息页", "发送消息")
            state.recentActions shouldBe emptyList()
            state.recentOutcomes shouldBe emptyList()
            state.recentToolResults shouldBe emptyList()
            state.stuckCount shouldBe 0
        }
    }

    context("V4 evidence memory") {
        test("recentActions should never exceed 8 entries and should retain the most recent actions") {
            checkAll(10, Arb.int(1..50)) { actionCount ->
                val manager = WorldStateManager(MockScreenObserver())
                val executedActions = mutableListOf<Pair<String, String>>()

                repeat(actionCount) { i ->
                    val action = "action_$i"
                    val result = "result_$i"
                    executedActions += action to result
                    manager.updateAfterAction(action, result)
                }

                val state = manager.getState()
                state.recentActions.size shouldBeLessThanOrEqual 8
                state.recentActions.size shouldBe minOf(actionCount, 8)

                val expected = executedActions.takeLast(8)
                state.recentActions.forEachIndexed { index, record ->
                    record.action shouldBe expected[index].first
                    record.result shouldBe expected[index].second
                }
            }
        }

        test("updateAfterAction should update totalSteps lastAction and recentActions after any action") {
            checkAll(10, Arb.string(1..50), Arb.string(1..50)) { action, result ->
                val manager = WorldStateManager(MockScreenObserver())
                val initialTotalSteps = manager.getState().totalSteps

                manager.updateAfterAction(action, result)

                val updatedState = manager.getState()
                updatedState.totalSteps shouldBe initialTotalSteps + 1
                updatedState.lastAction shouldBe action
                updatedState.lastActionResult shouldBe result
                updatedState.recentActions.last().action shouldBe action
                updatedState.recentActions.last().result shouldBe result
            }
        }

        test("updateAfterAction should append aligned recentOutcome for the latest action") {
            val manager = WorldStateManager(
                ControllableMockScreenObserver(listOf("sig_A"))
            )

            val action = "open_app(package_name=com.test.app)"
            val result = "已打开应用"
            manager.updateAfterAction(action, result)

            val state = manager.getState()
            state.recentActions.last().action shouldBe action
            state.recentOutcomes.last().actionText shouldBe action
            state.recentOutcomes.last().toolName shouldBe canonicalActionName(action)
            state.recentOutcomes.last().pageSignatureAfter shouldBe state.pageSignature
            state.recentToolResults.last().actionText shouldBe action
            state.recentToolResults.last().toolName shouldBe canonicalActionName(action)
            state.recentToolResults.last().status shouldBe ActionResultCode.SUCCESS
            state.recentToolResults.last().changed shouldBe true
            state.recentToolResults.last().rawSignatureChanged shouldBe true
            state.recentToolResults.last().errorType shouldBe null
            state.lastAction shouldBe action
            state.lastActionResult shouldBe result
        }

        test("updateAfterAction should classify structured tool error types") {
            val manager = WorldStateManager(
                ControllableMockScreenObserver(listOf("sig_blocked"))
            )

            manager.updateAfterAction(
                action = "click(point=<point>1 1</point>)",
                result = "Error: blocked_by_safety"
            )

            val result = manager.getState().recentToolResults.last()
            result.status shouldBe ActionResultCode.BLOCKED
            result.errorType shouldBe "blocked_by_safety"
            result.message shouldContain "blocked_by_safety"
        }

        test("scroll should not count signature-only jitter as meaningful progress") {
            val observer = object : ScreenObserver {
                private var index = 0

                override fun capture(): Observation {
                    index += 1
                    return Observation(
                        screenshotBase64 = "mock_$index",
                        uiTree = listOf(
                            UIElement(
                                type = "android.widget.TextView",
                                text = "设置",
                                contentDescription = null,
                                isClickable = false,
                                isEditable = false,
                                bounds = Rect(0, 0, 300, 80),
                                center = Point(150, 40)
                            ),
                            UIElement(
                                type = "android.widget.TextView",
                                text = "华为帐号、付款与账单、云空间等",
                                contentDescription = null,
                                isClickable = false,
                                isEditable = false,
                                bounds = Rect(0, 100, 800, 180),
                                center = Point(400, 140)
                            )
                        ),
                        ocrTexts = emptyList(),
                        pageSignature = "settings_top_jitter_$index",
                        currentApp = "com.android.settings",
                        debugPageLabel = "设置"
                    )
                }
            }
            val manager = WorldStateManager(observer)

            manager.updateAfterAction("open_app(package_name=com.android.settings)", "已启动应用")
            manager.updateAfterAction(
                "scroll(x=500, y=800, direction=down, length=500)",
                "已在归一化坐标 (500, 800) 执行 down 滑动，距离 500"
            )

            val state = manager.getState()
            val result = state.recentToolResults.last()
            result.toolName shouldBe "scroll"
            result.changed shouldBe false
            result.rawSignatureChanged shouldBe true
            result.status shouldBe ActionResultCode.UNKNOWN
            state.stuckCount shouldBe 1
        }

        test("scroll should record settled visible text instead of a transient post-action frame") {
            fun timerObservation(signature: String, value: String, screenshotIndex: Int): Observation {
                return Observation(
                    screenshotBase64 = "mock_$screenshotIndex",
                    uiTree = listOf(
                        UIElement(
                            type = "android.widget.TextView",
                            text = "计时器",
                            contentDescription = null,
                            isClickable = false,
                            isEditable = false,
                            bounds = Rect(0, 0, 300, 80),
                            center = Point(150, 40)
                        ),
                        UIElement(
                            type = "android.widget.TextView",
                            text = value,
                            contentDescription = null,
                            isClickable = false,
                            isEditable = false,
                            bounds = Rect(260, 520, 820, 680),
                            center = Point(540, 600)
                        )
                    ),
                    ocrTexts = emptyList(),
                    pageSignature = signature,
                    currentApp = "com.test.clock",
                    debugPageLabel = "计时器"
                )
            }
            val frames = listOf(
                timerObservation("value_20", "0 小时20分0秒", 1),
                timerObservation("transient_27", "0 小时27分0秒", 2),
                timerObservation("settled_21", "0 小时21分0秒", 3),
                timerObservation("settled_21", "0 小时21分0秒", 4)
            )
            val observer = object : ScreenObserver {
                private var index = 0

                override fun capture(): Observation {
                    return frames.getOrElse(index++) { frames.last() }
                }
            }
            val manager = WorldStateManager(observer)

            manager.updateAfterAction("open_app(package_name=com.test.clock)", "已启动应用")
            manager.updateAfterAction(
                "scroll(x=500, y=665, direction=down, length=200)",
                "已在归一化坐标 (500, 665) 执行 down 滑动，距离 200"
            )

            val state = manager.getState()
            val record = state.recentActions.last()
            record.visibleTextsBefore shouldContain "0 小时20分0秒"
            record.visibleTextsAfter shouldContain "0 小时21分0秒"
            record.visibleTextsAfter.contains("0 小时27分0秒") shouldBe false
            state.pageSignature shouldBe "settled_21"
        }

        test("stuckCount should increment when pageSignature unchanged and reset when changed") {
            checkAll(10, Arb.list(Arb.int(0..1), 5..20)) { changePattern ->
                val signatures = mutableListOf<String>()
                var currentSignature = "sig_0"
                changePattern.forEach {
                    if (it == 1) {
                        currentSignature = "sig_${signatures.size + 1}"
                    }
                    signatures += currentSignature
                }

                val manager = WorldStateManager(
                    ControllableMockScreenObserver(signatures)
                )

                var previousSignature = ""
                var expectedStuckCount = 0
                changePattern.forEachIndexed { index, _ ->
                    manager.updateAfterAction("action_$index", "result_$index")
                    val currentSignatureAfterAction = signatures[index]
                    expectedStuckCount = if (currentSignatureAfterAction == previousSignature) {
                        expectedStuckCount + 1
                    } else {
                        0
                    }
                    manager.getState().stuckCount shouldBe expectedStuckCount
                    previousSignature = currentSignatureAfterAction
                }
            }
        }
    }

    context("V4 fact-first context") {
        test("updateAfterAction should keep page hints out of visible texts") {
            val observer = object : ScreenObserver {
                override fun capture(): Observation {
                    return Observation(
                        screenshotBase64 = "mock",
                        uiTree = emptyList(),
                        ocrTexts = emptyList(),
                        pageSignature = "signature_page_hint",
                        currentApp = "com.test.app",
                        debugPageLabel = "聊天页",
                        debugPageTitleHint = "晓哥"
                    )
                }
            }
            val manager = WorldStateManager(observer)

            manager.updateAfterAction(
                action = "click(point=<point>1 1</point>)",
                result = "SUCCESS"
            )

            val state = manager.getState()
            state.visibleTexts.contains("聊天页") shouldBe false
            state.visibleTexts.contains("晓哥") shouldBe false
        }

        test("toPlannerContext should expose facts and hide deleted contract concepts") {
            val observer = object : ScreenObserver {
                override fun capture(): Observation {
                    return Observation(
                        screenshotBase64 = "mock",
                        uiTree = emptyList(),
                        ocrTexts = emptyList(),
                        pageSignature = "signature_planner_context",
                        currentApp = "com.test.app",
                        debugPageLabel = "聊天页",
                        debugPageTitleHint = "晓哥",
                        pageCapabilities = listOf(CapabilityState.CAN_INPUT_TEXT)
                    )
                }
            }
            val manager = WorldStateManager(observer)

            manager.updateAfterAction(
                action = "click(point=<point>1 1</point>)",
                result = "SUCCESS"
            )

            val context = manager.toPlannerContext()
            context shouldContain "App: com.test.app"
            context shouldContain "当前能力:"
            context shouldContain "最近操作:"
            context shouldNotContain "契约阶段:"
            context shouldNotContain "目标锚点:"
            context shouldNotContain "页面: 聊天页"
            context shouldNotContain "页面标题: 晓哥"
        }

        test("toPromptContext should include stuck warning when stuckCount is positive") {
            val manager = WorldStateManager(
                ControllableMockScreenObserver(List(3) { "same_sig" })
            )

            manager.setStrategy("Test Goal", listOf("Step 1"))
            manager.updateAfterAction("action1", "result1")
            manager.updateAfterAction("action2", "result2")

            val promptContext = manager.toPromptContext()
            promptContext shouldContain "⚠️"
            promptContext shouldContain "连续 1 步页面无变化"
        }

        test("updateAfterAction should merge semantic labels ui labels and ocr text into visibleTexts") {
            val observer = object : ScreenObserver {
                override fun capture(): Observation {
                    return Observation(
                        screenshotBase64 = "mock",
                        uiTree = listOf(
                            UIElement(
                                type = "android.widget.TextView",
                                text = "页面内标题",
                                contentDescription = null,
                                isClickable = false,
                                isEditable = false,
                                bounds = Rect(0, 0, 300, 80),
                                center = Point(150, 40)
                            ),
                            UIElement(
                                type = "android.widget.ImageButton",
                                text = null,
                                contentDescription = "分享按钮",
                                isClickable = true,
                                isEditable = false,
                                bounds = Rect(900, 40, 1040, 180),
                                center = Point(970, 110)
                            )
                        ),
                        ocrTexts = listOf(
                            OcrResult(
                                text = "OCR补充文本",
                                bounds = Rect(40, 220, 320, 280)
                            )
                        ),
                        pageSignature = "signature_merge_sources",
                        currentApp = "com.test.app",
                        debugPageLabel = "详情页",
                        semanticElements = listOf(
                            SemanticElement(
                                id = "link_1",
                                role = ElementRole.LINK,
                                label = "参考资料A",
                                actionable = true,
                                center = Point(480, 360),
                                bounds = Rect(120, 320, 840, 400),
                                attributes = mapOf("clickable" to "true")
                            )
                        )
                    )
                }
            }
            val manager = WorldStateManager(observer)

            manager.updateAfterAction(
                action = "click(point=<point>1 1</point>)",
                result = "SUCCESS"
            )

            val visibleTexts = manager.getState().visibleTexts
            val state = manager.getState()
            state.semanticLabels shouldContain "参考资料A"
            state.uiTexts shouldContain "页面内标题"
            state.contentDescriptions shouldContain "分享按钮"
            state.ocrTexts shouldContain "OCR补充文本"
            visibleTexts shouldContain "参考资料A"
            visibleTexts shouldContain "页面内标题"
            visibleTexts shouldContain "分享按钮"
            visibleTexts shouldContain "OCR补充文本"
        }
    }

    context("V4 typed candidates") {
        test("buildActionCandidates should prefer atomic conversation row candidates over multi-contact containers") {
            val observer = object : ScreenObserver {
                override fun capture(): Observation {
                    return Observation(
                        screenshotBase64 = "mock",
                        uiTree = emptyList(),
                        ocrTexts = emptyList(),
                        pageSignature = "signature_message_list",
                        currentApp = "com.ss.android.ugc.aweme.lite",
                        debugPageLabel = "消息",
                        pageAffordances = listOf("has_list_cells", "has_message_container", "has_primary_cta"),
                        pageCapabilities = listOf(CapabilityState.CAN_OPEN_CANDIDATE),
                        semanticElements = listOf(
                            SemanticElement(
                                id = "container",
                                role = ElementRole.CONTAINER,
                                label = "MrWang, 🍓依楼看江湖🔥, 你的小哥哥",
                                actionable = true,
                                center = Point(510, 480),
                                bounds = Rect(40, 300, 980, 640),
                                attributes = mapOf("clickable" to "true")
                            ),
                            SemanticElement(
                                id = "target_row",
                                role = ElementRole.LIST_ITEM,
                                label = "你的小哥哥,昨天在线前天",
                                actionable = true,
                                center = Point(500, 848),
                                bounds = Rect(120, 760, 900, 920),
                                attributes = mapOf("clickable" to "true")
                            )
                        )
                    )
                }
            }
            val manager = WorldStateManager(observer)
            manager.setStrategy(
                Strategy(
                    goal = "给你的小哥哥发消息",
                    app = "com.ss.android.ugc.aweme.lite",
                    strategy = listOf("打开消息列表", "定位好友你的小哥哥并进入会话")
                )
            )

            manager.updateAfterAction(
                action = "click(point=<point>699 970</point>)",
                result = "SUCCESS"
            )

            val candidates = manager.getState().actionCandidates
            candidates.any { it.contains("你的小哥哥") } shouldBe true
            candidates.filter { it.contains("你的小哥哥") }.first() shouldNotContain "MrWang"
            candidates.first() shouldContain "point("
            candidates.first() shouldNotContain "<point>"
        }

        test("buildActionCandidates should preserve generic group ordering for dense candidates") {
            val observer = object : ScreenObserver {
                override fun capture(): Observation {
                    return Observation(
                        screenshotBase64 = "mock",
                        uiTree = emptyList(),
                        ocrTexts = emptyList(),
                        pageSignature = "signature_media_grid",
                        currentApp = "com.ss.android.ugc.aweme.lite",
                        debugPageLabel = "作品",
                        pageAffordances = listOf("has_list_cells", "has_primary_cta"),
                        pageCapabilities = listOf(CapabilityState.CAN_OPEN_CANDIDATE),
                        semanticElements = listOf(
                            SemanticElement(
                                id = "media_1",
                                role = ElementRole.IMAGE,
                                label = "作品A",
                                actionable = true,
                                center = Point(240, 420),
                                bounds = Rect(80, 260, 400, 580),
                                attributes = mapOf("clickable" to "true")
                            ),
                            SemanticElement(
                                id = "media_2",
                                role = ElementRole.IMAGE,
                                label = "作品B",
                                actionable = true,
                                center = Point(760, 420),
                                bounds = Rect(600, 260, 920, 580),
                                attributes = mapOf("clickable" to "true")
                            )
                        )
                    )
                }
            }
            val manager = WorldStateManager(observer)
            manager.setStrategy(
                Strategy(
                    goal = "打开第二条作品",
                    app = "com.ss.android.ugc.aweme.lite",
                    strategy = listOf("进入作品列表", "打开目标作品")
                )
            )

            manager.updateAfterAction(
                action = "click(point=<point>934 69</point>)",
                result = "SUCCESS"
            )

            val typed = manager.getState().typedActionCandidates
            typed.size shouldBe 2
            typed[0].groupId shouldBe "media_main"
            typed[0].indexInGroup shouldBe 1
            typed[1].groupId shouldBe "media_main"
            typed[1].indexInGroup shouldBe 2
        }

        test("buildActionCandidates should keep dense link lists instead of truncating after the first few") {
            val observer = object : ScreenObserver {
                override fun capture(): Observation {
                    return Observation(
                        screenshotBase64 = "mock",
                        uiTree = emptyList(),
                        ocrTexts = emptyList(),
                        pageSignature = "signature_dense_links",
                        currentApp = "com.test.app",
                        debugPageLabel = "详情",
                        pageAffordances = listOf("has_list_cells"),
                        pageCapabilities = listOf(CapabilityState.CAN_OPEN_CANDIDATE),
                        semanticElements = (1..18).map { index ->
                            SemanticElement(
                                id = "link_$index",
                                role = ElementRole.LINK,
                                label = "链接条目$index",
                                actionable = true,
                                center = Point(500, 220 + index * 48),
                                bounds = Rect(120, 180 + index * 48, 920, 220 + index * 48),
                                attributes = mapOf("clickable" to "true")
                            )
                        }
                    )
                }
            }
            val manager = WorldStateManager(observer)
            manager.setStrategy(
                Strategy(
                    goal = "打开 11 条列表链接",
                    app = "com.test.app",
                    strategy = listOf("进入详情页", "处理全部列表链接")
                )
            )
            manager.setRuntimeTaskFacts(
                listOf(
                    "task.expected_count=11",
                    "task.coverage_mode=full_list",
                    "task.target_artifact_kind=list_link"
                )
            )

            manager.updateAfterAction(
                action = "click(point=<point>934 69</point>)",
                result = "SUCCESS"
            )

            val typed = manager.getState().typedActionCandidates
            typed.size shouldBe 18
            typed.first().groupId shouldBe "link_main"
            (typed.count { it.groupId == "link_main" } >= 8) shouldBe true
            typed.map { it.label } shouldContain "链接条目18"
        }
    }
})
