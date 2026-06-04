package com.juwan.lynx.agent

import com.juwan.lynx.runtime.Tool
import com.juwan.lynx.runtime.ToolParam
import com.juwan.lynx.safety.SafetyGuard
import com.juwan.lynx.state.ActionOrigin
import com.juwan.lynx.state.ActionRecord
import com.juwan.lynx.state.CapabilityState
import com.juwan.lynx.state.WorldState
import com.juwan.lynx.state.WorldStateManager
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking

class ConstrainedSafetyWrappedToolTest : FunSpec({

    test("type should suppress duplicate resend when same text already sent in chat") {
        val delegate = mockk<Tool>()
        every { delegate.name } returns "type"
        every { delegate.description } returns "type text"
        every { delegate.parameters } returns listOf(ToolParam("text", "string", "message text"))
        coEvery { delegate.execute(any()) } returns "should_not_run"

        val safetyGuard = SafetyGuard { true }
        val worldStateManager = mockk<WorldStateManager>(relaxed = true)
        val sentText = "刚刚哈哈哈哈哈哈哈哈"
        val state = WorldState(
            currentApp = "com.ss.android.ugc.aweme.lite",
            pageCapabilities = listOf(
                CapabilityState.CAN_INPUT_TEXT,
                CapabilityState.CAN_SUBMIT_TEXT
            ),
            visibleTexts = listOf("发送消息", sentText),
            actionCandidates = listOf(
                "[INPUT] \"发送消息\" -> point=<point>383 961</point>",
                "[BUTTON] \"M\" -> point=<point>897 961</point>"
            ),
            recentActions = listOf(
                ActionRecord(
                    action = "type(text=$sentText)",
                    result = "已输入文本: \"$sentText\"",
                    pageSignatureBefore = "chat_a",
                    pageSignatureAfter = "chat_b",
                    timestamp = 1L
                ),
                ActionRecord(
                    action = "click(point=<point>897 961</point>)",
                    result = "已点击坐标 (897, 961)",
                    pageSignatureBefore = "chat_b",
                    pageSignatureAfter = "chat_c",
                    timestamp = 2L
                )
            )
        )

        every { worldStateManager.getState() } returns state
        coEvery { worldStateManager.updateAfterAction(any(), any(), any(), any()) } returns Unit

        val tool = ConstrainedSafetyWrappedTool(
            delegate = delegate,
            safetyGuard = safetyGuard,
            worldStateManager = worldStateManager
        )

        val result = runBlocking {
            tool.execute(
                mapOf(
                    "text" to sentText
                )
            )
        }

        result.contains("抑制重复发送") shouldBe true
        coVerify(exactly = 0) { delegate.execute(any()) }
    }

    test("type should not auto-focus visible input candidate before a successful type") {
        val delegate = mockk<Tool>()
        every { delegate.name } returns "type"
        every { delegate.description } returns "type text"
        every { delegate.parameters } returns listOf(ToolParam("text", "string", "message text"))
        coEvery { delegate.execute(any()) } returns "ok"

        val clickTool = mockk<Tool>()
        every { clickTool.name } returns "click"
        every { clickTool.description } returns "click point"
        every { clickTool.parameters } returns listOf(ToolParam("point", "string", "target point"))
        coEvery { clickTool.execute(any()) } returns "clicked"

        val safetyGuard = SafetyGuard { true }
        val worldStateManager = mockk<WorldStateManager>(relaxed = true)
        every { worldStateManager.getState() } returns WorldState(
            currentApp = "com.ss.android.ugc.aweme.lite",
            pageCapabilities = listOf(
                CapabilityState.CAN_INPUT_TEXT,
                CapabilityState.CAN_SUBMIT_TEXT
            ),
            actionCandidates = listOf(
                "[INPUT] \"发送消息\" -> point(383, 961) / tap=<point>383 961</point>",
                "[BUTTON] \"M\" -> point(897, 961) / tap=<point>897 961</point>"
            )
        )
        coEvery { worldStateManager.updateAfterAction(any(), any(), any(), any()) } returns Unit

        val tool = ConstrainedSafetyWrappedTool(
            delegate = delegate,
            safetyGuard = safetyGuard,
            worldStateManager = worldStateManager,
            siblingTools = listOf(clickTool)
        )

        runBlocking {
            tool.execute(mapOf("text" to "富太太太多了解了解"))
        } shouldContain "ok"

        coVerify(exactly = 0) { clickTool.execute(any()) }
        coVerify {
            delegate.execute(match { args -> args["text"] == "富太太太多了解了解" })
        }
    }

    test("type_and_enter should not auto-focus visible search candidate before a successful type") {
        val delegate = mockk<Tool>()
        every { delegate.name } returns "type_and_enter"
        every { delegate.description } returns "type and submit text"
        every { delegate.parameters } returns listOf(ToolParam("text", "string", "query text"))
        coEvery { delegate.execute(any()) } returns "已输入文本并触发回车"

        val clickTool = mockk<Tool>()
        every { clickTool.name } returns "click"
        every { clickTool.description } returns "click point"
        every { clickTool.parameters } returns listOf(ToolParam("point", "string", "target point"))
        coEvery { clickTool.execute(any()) } returns "clicked"

        val safetyGuard = SafetyGuard { true }
        val worldStateManager = mockk<WorldStateManager>(relaxed = true)
        every { worldStateManager.getState() } returns WorldState(
            currentApp = "com.android.settings",
            actionCandidates = listOf(
                "[TEXT] \"搜索设置项\" | point(500, 213)",
                "[CONTAINER] \"桌面和壁纸\" | point(300, 900)"
            )
        )
        coEvery { worldStateManager.updateAfterAction(any(), any(), any(), any()) } returns Unit

        val tool = ConstrainedSafetyWrappedTool(
            delegate = delegate,
            safetyGuard = safetyGuard,
            worldStateManager = worldStateManager,
            siblingTools = listOf(clickTool)
        )

        runBlocking {
            tool.execute(mapOf("text" to "显示和亮度"))
        } shouldContain "已输入文本"

        coVerify(exactly = 0) { clickTool.execute(any()) }
        coVerify {
            delegate.execute(match { args -> args["text"] == "显示和亮度" })
        }
    }

    test("type_and_enter should focus visible search candidate only after missing-focus failure") {
        val delegate = mockk<Tool>()
        every { delegate.name } returns "type_and_enter"
        every { delegate.description } returns "type and submit text"
        every { delegate.parameters } returns listOf(ToolParam("text", "string", "query text"))
        coEvery { delegate.execute(any()) } returnsMany listOf(
            "错误: 当前没有已聚焦输入框，请先点击输入框",
            "已输入文本并触发回车"
        )

        val clickTool = mockk<Tool>()
        every { clickTool.name } returns "click"
        every { clickTool.description } returns "click point"
        every { clickTool.parameters } returns listOf(ToolParam("point", "string", "target point"))
        coEvery { clickTool.execute(any()) } returns "clicked"

        val safetyGuard = SafetyGuard { true }
        val worldStateManager = mockk<WorldStateManager>(relaxed = true)
        every { worldStateManager.getState() } returns WorldState(
            currentApp = "com.android.settings",
            actionCandidates = listOf(
                "[TEXT] \"搜索设置项\" | point(500, 213)",
                "[CONTAINER] \"桌面和壁纸\" | point(300, 900)"
            )
        )
        coEvery { worldStateManager.updateAfterAction(any(), any(), any(), any()) } returns Unit

        val tool = ConstrainedSafetyWrappedTool(
            delegate = delegate,
            safetyGuard = safetyGuard,
            worldStateManager = worldStateManager,
            siblingTools = listOf(clickTool)
        )

        runBlocking {
            tool.execute(mapOf("text" to "显示和亮度"))
        } shouldContain "已输入文本"

        coVerify(exactly = 1) {
            clickTool.execute(match { args -> args["point"] == "500 213" })
        }
        coVerify(exactly = 2) {
            delegate.execute(match { args -> args["text"] == "显示和亮度" })
        }
        coVerify {
            worldStateManager.updateAfterAction(
                match { action -> action.startsWith("click(input_focus_recovery") },
                match { result -> result.contains("重试前自动聚焦输入框") },
                any(),
                ActionOrigin.SYSTEM
            )
        }
    }

    test("type should retry after focus recovery when initial input fails") {
        val delegate = mockk<Tool>()
        every { delegate.name } returns "type"
        every { delegate.description } returns "type text"
        every { delegate.parameters } returns listOf(ToolParam("text", "string", "message text"))
        coEvery { delegate.execute(any()) } returnsMany listOf("错误: no focused input", "ok")

        val clickTool = mockk<Tool>()
        every { clickTool.name } returns "click"
        every { clickTool.description } returns "click point"
        every { clickTool.parameters } returns listOf(ToolParam("point", "string", "target point"))
        coEvery { clickTool.execute(any()) } returns "clicked"

        val safetyGuard = SafetyGuard { true }
        val worldStateManager = mockk<WorldStateManager>(relaxed = true)
        every { worldStateManager.getState() } returns WorldState(
            currentApp = "com.ss.android.ugc.aweme.lite",
            pageCapabilities = listOf(
                CapabilityState.CAN_INPUT_TEXT,
                CapabilityState.CAN_SUBMIT_TEXT
            ),
            actionCandidates = listOf(
                "[INPUT] \"发送消息\" -> point(383, 961) / tap=<point>383 961</point>",
                "[BUTTON] \"M\" -> point(897, 961) / tap=<point>897 961</point>"
            )
        )
        coEvery { worldStateManager.updateAfterAction(any(), any(), any(), any()) } returns Unit

        val tool = ConstrainedSafetyWrappedTool(
            delegate = delegate,
            safetyGuard = safetyGuard,
            worldStateManager = worldStateManager,
            siblingTools = listOf(clickTool)
        )

        runBlocking {
            tool.execute(mapOf("text" to "富太太太多了解了解"))
        } shouldContain "ok"

        coVerify(exactly = 1) {
            clickTool.execute(match { args -> args["point"] == "383 961" })
        }
        coVerify {
            worldStateManager.updateAfterAction(
                match { action -> action.startsWith("click(input_focus_recovery") },
                match { result -> result.contains("重试前自动聚焦输入框") },
                any(),
                ActionOrigin.SYSTEM
            )
        }
        coVerify {
            worldStateManager.updateAfterAction(
                match { action -> action.contains("retry_after_focus") },
                "ok",
                any(),
                ActionOrigin.SYSTEM
            )
        }
        coVerify(atLeast = 2) {
            delegate.execute(match { args -> args["text"] == "富太太太多了解了解" })
        }
    }

    test("type should honor action guard before delegate execution") {
        val delegate = mockk<Tool>()
        every { delegate.name } returns "type"
        every { delegate.description } returns "type text"
        every { delegate.parameters } returns listOf(ToolParam("text", "string", "message text"))
        coEvery { delegate.execute(any()) } returns "should_not_run"

        val safetyGuard = SafetyGuard { true }
        val worldStateManager = mockk<WorldStateManager>(relaxed = true)
        every { worldStateManager.getState() } returns WorldState(
            currentApp = "com.ss.android.ugc.aweme.lite"
        )
        coEvery { worldStateManager.updateAfterAction(any(), any(), any(), any()) } returns Unit

        val tool = ConstrainedSafetyWrappedTool(
            delegate = delegate,
            safetyGuard = safetyGuard,
            worldStateManager = worldStateManager,
            actionGuard = { actionName, args ->
                if (actionName == "type" && args["text"] == "过年前一天晚上聊吗丁啉") {
                    "Error: 当前仍在定位阶段，暂不接受消息内容输入。"
                } else {
                    null
                }
            }
        )

        val result = runBlocking {
            tool.execute(
                mapOf(
                    "text" to "过年前一天晚上聊吗丁啉"
                )
            )
        }

        result shouldBe "Error: 当前仍在定位阶段，暂不接受消息内容输入。"
        coVerify(exactly = 0) { delegate.execute(any()) }
    }
})
