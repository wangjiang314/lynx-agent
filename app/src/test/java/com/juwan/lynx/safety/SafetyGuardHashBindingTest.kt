package com.juwan.lynx.safety

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking

class SafetyGuardHashBindingTest : FunSpec({

    test("submit-like click should be allowed by default") {
        var prompted = ""
        val guard = SafetyGuard { description ->
            prompted = description
            true
        }

        val context = PlannedActionContext(
            toolName = "click",
            args = mapOf("point" to "<point>500 500</point>"),
            normalizedTargetLabel = "发送",
            actionText = "click(point=<point>500 500</point>)",
            pageSignatureBefore = "sig-1",
            screenTopTexts = listOf("发送", "消息")
        )

        val result = guard.evaluate(context, screenText = "发送消息")
        result.shouldBeInstanceOf<SafetyGuard.CheckResult.Allow>()
        prompted shouldBe ""
    }

    test("hash-bound approval should still work for confirmed actions") {
        var prompted = ""
        val guard = SafetyGuard { description ->
            prompted = description
            true
        }
        val context = PlannedActionContext(
            toolName = "click",
            args = mapOf("point" to "<point>500 500</point>"),
            normalizedTargetLabel = "立即支付",
            actionText = "click(point=<point>500 500</point>)",
            pageSignatureBefore = "sig-1",
            screenTopTexts = listOf("订单支付")
        )

        val result = guard.evaluate(context, screenText = "请确认支付")
        val confirm = result.shouldBeInstanceOf<SafetyGuard.CheckResult.ConfirmRequired>()
        runBlocking {
            guard.requestConfirmation("确认支付", confirm.actionHash)
        } shouldBe true
        prompted.isNotBlank() shouldBe true
        guard.consumeApprovedAction(confirm.actionHash) shouldBe true
        guard.consumeApprovedAction(confirm.actionHash) shouldBe false
    }

    test("system-level action should be blocked") {
        val guard = SafetyGuard { true }
        val context = PlannedActionContext(
            toolName = "type",
            args = mapOf("text" to "adb shell"),
            normalizedTargetLabel = null,
            actionText = "type(text=adb shell)",
            pageSignatureBefore = "sig-1",
            screenTopTexts = listOf("developer")
        )

        val result = guard.evaluate(context, screenText = "打开developer选项")
        result.shouldBeInstanceOf<SafetyGuard.CheckResult.Blocked>()
    }

    test("non-submit click should not require confirm only because screen contains send keyword") {
        val guard = SafetyGuard { true }
        val context = PlannedActionContext(
            toolName = "click",
            args = mapOf("point" to "<point>800 900</point>"),
            normalizedTargetLabel = "关闭",
            actionText = "click(point=<point>800 900</point>)",
            pageSignatureBefore = "sig-1",
            screenTopTexts = listOf("发送", "消息")
        )

        val result = guard.evaluate(context, screenText = "当前页面包含发送按钮")
        result.shouldBeInstanceOf<SafetyGuard.CheckResult.Allow>()
    }

    test("safe search typing should not require confirm only because unrelated screen text is sensitive") {
        val guard = SafetyGuard { true }
        val context = PlannedActionContext(
            toolName = "type_and_enter",
            args = mapOf("text" to "显示和亮度"),
            normalizedTargetLabel = "显示和亮度",
            actionText = "type_and_enter(text=显示和亮度)",
            pageSignatureBefore = "settings_home",
            screenTopTexts = listOf("华为帐号、付款与账单、云空间等", "华为支付未添加银行卡")
        )

        val result = guard.evaluate(
            context,
            screenText = "设置 搜索设置项 华为帐号、付款与账单、云空间等 华为支付未添加银行卡"
        )

        result.shouldBeInstanceOf<SafetyGuard.CheckResult.Allow>()
    }

    test("typing into sensitive target should still require confirm") {
        val guard = SafetyGuard { true }
        val context = PlannedActionContext(
            toolName = "type",
            args = mapOf("text" to "123456"),
            normalizedTargetLabel = "验证码",
            actionText = "type(text=123456)",
            pageSignatureBefore = "verify_code",
            screenTopTexts = listOf("请输入验证码")
        )

        val result = guard.evaluate(context, screenText = "请输入验证码")

        result.shouldBeInstanceOf<SafetyGuard.CheckResult.ConfirmRequired>()
    }
})
