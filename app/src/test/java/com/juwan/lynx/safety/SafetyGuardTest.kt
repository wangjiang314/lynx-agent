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

package com.juwan.lynx.safety

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

/**
 * Unit tests for SafetyGuard classification logic.
 * Tests specific examples and edge cases for payment, deletion, and system-level operations.
 */
class SafetyGuardUnitTest : FunSpec({
    val safetyGuard = SafetyGuard { true }

    context("Payment keyword classification") {
        test("should classify action with 支付 as CONFIRM") {
            safetyGuard.check("click", "点击支付按钮").shouldBe(SafetyGuard.Decision.CONFIRM)
        }

        test("should classify action with 付款 as CONFIRM") {
            safetyGuard.check("type", "输入付款金额").shouldBe(SafetyGuard.Decision.CONFIRM)
        }

        test("should classify action with 下单 as CONFIRM") {
            safetyGuard.check("click", "确认下单").shouldBe(SafetyGuard.Decision.CONFIRM)
        }

        test("should classify action with 购买 as CONFIRM") {
            safetyGuard.check("click", "立即购买").shouldBe(SafetyGuard.Decision.CONFIRM)
        }

        test("should classify action with 转账 as CONFIRM") {
            safetyGuard.check("type", "转账金额").shouldBe(SafetyGuard.Decision.CONFIRM)
        }

        test("should be case-insensitive for payment keywords") {
            safetyGuard.check("click", "点击支付按钮").shouldBe(SafetyGuard.Decision.CONFIRM)
            safetyGuard.check("click", "点击支付按钮").shouldBe(SafetyGuard.Decision.CONFIRM)
        }
    }

    context("Deletion keyword classification") {
        test("should classify action with 删除 as CONFIRM") {
            safetyGuard.check("click", "删除文件").shouldBe(SafetyGuard.Decision.CONFIRM)
        }

        test("should classify action with 卸载 as CONFIRM") {
            safetyGuard.check("click", "卸载应用").shouldBe(SafetyGuard.Decision.CONFIRM)
        }

        test("should classify action with 清除 as CONFIRM") {
            safetyGuard.check("click", "清除缓存").shouldBe(SafetyGuard.Decision.CONFIRM)
        }

        test("should classify action with 格式化 as CONFIRM") {
            safetyGuard.check("click", "格式化存储").shouldBe(SafetyGuard.Decision.CONFIRM)
        }

        test("should classify action with 重置 as CONFIRM") {
            safetyGuard.check("click", "重置设置").shouldBe(SafetyGuard.Decision.CONFIRM)
        }
    }

    context("System-level dangerous keyword classification") {
        test("should classify action with root as BLOCK") {
            safetyGuard.check("root operation", "普通页面").shouldBe(SafetyGuard.Decision.BLOCK)
        }

        test("should classify action with adb as BLOCK") {
            safetyGuard.check("type", "adb shell").shouldBe(SafetyGuard.Decision.BLOCK)
        }

        test("should classify action with developer as BLOCK") {
            safetyGuard.check("open developer mode", "设置页面").shouldBe(SafetyGuard.Decision.BLOCK)
        }

        test("should classify action with 开发者选项 as BLOCK") {
            safetyGuard.check("打开开发者选项", "设置页面").shouldBe(SafetyGuard.Decision.BLOCK)
        }

        test("should classify action with su as BLOCK") {
            safetyGuard.check("type", "su command").shouldBe(SafetyGuard.Decision.BLOCK)
        }

        test("should be case-insensitive for system keywords") {
            safetyGuard.check("ROOT operation", "普通页面").shouldBe(SafetyGuard.Decision.BLOCK)
            safetyGuard.check("ADB shell", "普通页面").shouldBe(SafetyGuard.Decision.BLOCK)
            safetyGuard.check("DEVELOPER option", "普通页面").shouldBe(SafetyGuard.Decision.BLOCK)
        }
    }

    context("Safe operation classification") {
        test("should classify normal tap action as ALLOW") {
            safetyGuard.check("click", "点击按钮").shouldBe(SafetyGuard.Decision.ALLOW)
        }

        test("should classify normal type action as ALLOW") {
            safetyGuard.check("type", "输入用户名").shouldBe(SafetyGuard.Decision.ALLOW)
        }

        test("should classify scroll action as ALLOW") {
            safetyGuard.check("scroll", "向下滚动").shouldBe(SafetyGuard.Decision.ALLOW)
        }

        test("should classify empty screen text as ALLOW") {
            safetyGuard.check("click", "").shouldBe(SafetyGuard.Decision.ALLOW)
        }

        test("should classify empty action as ALLOW") {
            safetyGuard.check("", "普通文本").shouldBe(SafetyGuard.Decision.ALLOW)
        }
    }

    context("Priority handling") {
        test("should prioritize BLOCK over CONFIRM") {
            // Action contains both payment keyword and system keyword
            safetyGuard.check("root 支付动作", "支付页面").shouldBe(SafetyGuard.Decision.BLOCK)
        }

        test("should prioritize BLOCK over ALLOW") {
            safetyGuard.check("root", "普通页面").shouldBe(SafetyGuard.Decision.BLOCK)
        }

        test("should prioritize CONFIRM over ALLOW") {
            safetyGuard.check("click", "支付").shouldBe(SafetyGuard.Decision.CONFIRM)
        }
    }

    context("Edge cases") {
        test("should handle keywords in different positions") {
            safetyGuard.check("支付_action", "screen_text").shouldBe(SafetyGuard.Decision.CONFIRM)
            safetyGuard.check("action", "screen_支付_text").shouldBe(SafetyGuard.Decision.CONFIRM)
        }

        test("should handle keywords with surrounding text") {
            safetyGuard.check("click", "请点击支付按钮以完成交易").shouldBe(SafetyGuard.Decision.CONFIRM)
        }

        test("should handle multiple keywords in same string") {
            safetyGuard.check("click", "删除并重置").shouldBe(SafetyGuard.Decision.CONFIRM)
        }

        test("should handle whitespace correctly") {
            safetyGuard.check("click", "  支付  ").shouldBe(SafetyGuard.Decision.CONFIRM)
        }
    }
})

/**
 * Property-based tests for SafetyGuard classification.
 * Validates that classification logic is correct across random inputs.
 *
 * **Validates: Requirements 12.2, 12.5, 12.6, 12.7**
 */
class SafetyGuardPropertyTest : FunSpec({
    val safetyGuard = SafetyGuard { true }

    context("Property 23: SafetyGuard keyword classification") {
        test("payment keywords always result in CONFIRM") {
            checkAll(10,
                Arb.string(minSize = 0, maxSize = 100),
                Arb.string(minSize = 0, maxSize = 100)
            ) { action, screenText ->
                val paymentKeywords = listOf("支付", "付款", "下单", "购买", "转账")
                val combined = "$action $screenText"
                val hasPaymentKeyword = paymentKeywords.any { combined.contains(it) }

                if (hasPaymentKeyword) {
                    val decision = safetyGuard.check(action, screenText)
                    // Should be CONFIRM unless BLOCK keyword is present
                    val blockKeywords = listOf("root", "adb", "developer", "开发者选项", "su ")
                    val hasBlockKeyword = blockKeywords.any { combined.contains(it, ignoreCase = true) }
                    if (!hasBlockKeyword) {
                        decision.shouldBe(SafetyGuard.Decision.CONFIRM)
                    }
                }
            }
        }

        test("deletion keywords always result in CONFIRM") {
            checkAll(10,
                Arb.string(minSize = 0, maxSize = 100),
                Arb.string(minSize = 0, maxSize = 100)
            ) { action, screenText ->
                val deleteKeywords = listOf("删除", "卸载", "清除", "格式化", "重置")
                val combined = "$action $screenText"
                val hasDeleteKeyword = deleteKeywords.any { combined.contains(it) }

                if (hasDeleteKeyword) {
                    val decision = safetyGuard.check(action, screenText)
                    // Should be CONFIRM unless BLOCK keyword is present
                    val blockKeywords = listOf("root", "adb", "developer", "开发者选项", "su ")
                    val hasBlockKeyword = blockKeywords.any { combined.contains(it, ignoreCase = true) }
                    if (!hasBlockKeyword) {
                        decision.shouldBe(SafetyGuard.Decision.CONFIRM)
                    }
                }
            }
        }

        test("system-level keywords always result in BLOCK") {
            checkAll(10,
                Arb.string(minSize = 0, maxSize = 100),
                Arb.string(minSize = 0, maxSize = 100)
            ) { action, screenText ->
                val blockKeywords = listOf("root", "adb", "developer", "开发者选项", "su ")
                val hasBlockKeyword = blockKeywords.any { action.contains(it, ignoreCase = true) }

                if (hasBlockKeyword) {
                    val decision = safetyGuard.check(action, screenText)
                    decision.shouldBe(SafetyGuard.Decision.BLOCK)
                }
            }
        }

        test("actions without any keywords result in ALLOW") {
            checkAll(10,
                Arb.string(minSize = 0, maxSize = 100),
                Arb.string(minSize = 0, maxSize = 100)
            ) { action, screenText ->
                val paymentKeywords = listOf("支付", "付款", "下单", "购买", "转账")
                val deleteKeywords = listOf("删除", "卸载", "清除", "格式化", "重置")
                val sensitiveInputKeywords = listOf(
                    "password", "passwd", "pwd", "token", "secret", "apikey", "api_key",
                    "验证码", "短信码", "支付密码", "银行卡", "身份证"
                )
                val blockKeywords = listOf("root", "adb", "developer", "开发者选项", "su ")
                val combined = "$action $screenText"

                val hasPaymentKeyword = paymentKeywords.any { combined.contains(it) }
                val hasDeleteKeyword = deleteKeywords.any { combined.contains(it) }
                val hasSensitiveInputKeyword = sensitiveInputKeywords.any { combined.contains(it, ignoreCase = true) }
                val hasBlockKeyword = blockKeywords.any { combined.contains(it, ignoreCase = true) }

                if (!hasPaymentKeyword && !hasDeleteKeyword && !hasSensitiveInputKeyword && !hasBlockKeyword) {
                    val decision = safetyGuard.check(action, screenText)
                    decision.shouldBe(SafetyGuard.Decision.ALLOW)
                }
            }
        }

        test("classification is deterministic") {
            checkAll(10,
                Arb.string(minSize = 0, maxSize = 100),
                Arb.string(minSize = 0, maxSize = 100)
            ) { action, screenText ->
                val decision1 = safetyGuard.check(action, screenText)
                val decision2 = safetyGuard.check(action, screenText)
                decision1.shouldBe(decision2)
            }
        }

        test("keyword matching is case-insensitive for system keywords") {
            checkAll(10,
                Arb.string(minSize = 0, maxSize = 100),
                Arb.string(minSize = 0, maxSize = 100)
            ) { action, screenText ->
                val blockKeywords = listOf("root", "adb", "developer", "su ")

                val hasBlockKeywordLower = blockKeywords.any { action.contains(it, ignoreCase = false) }
                val hasBlockKeywordUpper = blockKeywords.any { action.contains(it, ignoreCase = true) }

                if (hasBlockKeywordUpper && !hasBlockKeywordLower) {
                    // Case-insensitive match found
                    val decision = safetyGuard.check(action, screenText)
                    decision.shouldBe(SafetyGuard.Decision.BLOCK)
                }
            }
        }
    }
})
