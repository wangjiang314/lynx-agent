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

import java.security.MessageDigest

/**
 * SafetyGuard intercepts sensitive operations and classifies them into three categories:
 * - ALLOW: Safe operations that can proceed immediately
 * - CONFIRM: Sensitive operations that require user confirmation
 * - BLOCK: Dangerous system-level operations that are rejected
 *
 * For submission-sensitive actions, confirmation is bound to an action hash to prevent bypass.
 */
class SafetyGuard(
    private val onConfirmRequired: suspend (String) -> Boolean
) {
    enum class Decision {
        ALLOW,
        CONFIRM,
        BLOCK
    }

    sealed interface CheckResult {
        data object Allow : CheckResult
        data class ConfirmRequired(
            val actionHash: String,
            val summary: String,
            val reason: String
        ) : CheckResult

        data class Blocked(
            val reason: String
        ) : CheckResult
    }

    private val paymentKeywords = listOf("支付", "付款", "下单", "购买", "转账")
    private val deleteKeywords = listOf("删除", "卸载", "清除", "格式化", "重置")
    private val sensitiveInputKeywords = listOf(
        "password", "passwd", "pwd", "token", "secret", "apikey", "api_key",
        "验证码", "短信码", "支付密码", "银行卡", "身份证"
    )
    private val submitIntentKeywords = listOf(
        "发送", "提交", "支付", "下单", "确认", "发布", "转账",
        "去支付", "立即支付", "立即下单", "send", "submit", "pay", "confirm"
    )
    private val blockKeywords = listOf("root", "adb", "developer", "开发者选项", "su ")

    private val lowRiskPrimitive = setOf(
        "click",
        "scroll",
        "press_back",
        "press_home",
        "long_press",
        "drag",
        "resolve_installed_app",
        "finished"
    )

    private val lock = Any()
    private val approvedActionHashes = linkedSetOf<String>()

    /**
     * Backward-compatible decision API used by existing tests/callers.
     */
    fun check(action: String, screenText: String): Decision {
        val fallbackContext = PlannedActionContext(
            toolName = action.substringBefore("(").trim().ifBlank { "unknown" },
            args = emptyMap(),
            normalizedTargetLabel = screenText.trim().takeIf { it.isNotBlank() },
            actionText = action,
            pageSignatureBefore = "",
            screenTopTexts = listOf(screenText.trim()).filter { it.isNotBlank() }
        )
        return when (evaluate(fallbackContext, screenText)) {
            is CheckResult.Allow -> Decision.ALLOW
            is CheckResult.ConfirmRequired -> Decision.CONFIRM
            is CheckResult.Blocked -> Decision.BLOCK
        }
    }

    fun evaluate(context: PlannedActionContext, screenText: String): CheckResult {
        val normalizedAction = context.actionText.trim()
        val actionLower = normalizedAction.lowercase()
        val actionName = context.toolName.trim().lowercase()
        val frameworkInternalTools = setOf("finished")
        if (actionName in frameworkInternalTools) {
            return CheckResult.Allow
        }
        val normalizedScreen = screenText.trim().lowercase()
        val normalizedTopTexts = context.screenTopTexts.joinToString(" ").lowercase()
        val targetLabel = context.normalizedTargetLabel?.trim()?.lowercase().orEmpty()
        val intentText = buildString {
            append(actionLower)
            if (targetLabel.isNotBlank()) {
                append(' ')
                append(targetLabel)
            }
            val argText = context.args.values.joinToString(" ").trim().lowercase()
            if (argText.isNotBlank()) {
                append(' ')
                append(argText)
            }
        }
        val combinedText = buildString {
            append(intentText)
            if (normalizedScreen.isNotBlank()) {
                append(' ')
                append(normalizedScreen)
            }
            if (normalizedTopTexts.isNotBlank()) {
                append(' ')
                append(normalizedTopTexts)
            }
        }

        val hasPaymentKeyword = paymentKeywords.any { combinedText.contains(it, ignoreCase = true) }
        val hasPaymentKeywordInIntent = paymentKeywords.any { intentText.contains(it, ignoreCase = true) }
        val hasDeleteKeyword = deleteKeywords.any { combinedText.contains(it, ignoreCase = true) }
        val hasDeleteKeywordInIntent = deleteKeywords.any { intentText.contains(it, ignoreCase = true) }
        val hasSensitiveInputKeyword = sensitiveInputKeywords.any { combinedText.contains(it, ignoreCase = true) }
        val hasSensitiveInputKeywordInIntent = sensitiveInputKeywords.any { intentText.contains(it, ignoreCase = true) }
        val hasSubmitKeyword = submitIntentKeywords.any { combinedText.contains(it, ignoreCase = true) }
        val hasSubmitKeywordInIntent = submitIntentKeywords.any { intentText.contains(it, ignoreCase = true) }

        // System-level block keywords should be based on action intent, not full-screen text.
        if (blockKeywords.any { intentText.contains(it, ignoreCase = true) }) {
            return CheckResult.Blocked("blocked_system_level")
        }

        if (actionName in setOf("type", "type_in", "type_and_enter")) {
            if (hasSensitiveInputKeywordInIntent || hasPaymentKeywordInIntent || hasDeleteKeywordInIntent) {
                return confirmRequired(context, reason = "sensitive_input_or_financial")
            }
            return CheckResult.Allow
        }

        if (actionName in setOf("open_app")) {
            if (hasPaymentKeywordInIntent || hasDeleteKeywordInIntent || hasSensitiveInputKeywordInIntent) {
                return confirmRequired(context, reason = "app_launch_sensitive_target")
            }
            return CheckResult.Allow
        }

        // Low-risk primitives should not be blocked by unrelated screen text.
        if (actionName in lowRiskPrimitive) {
            if (hasPaymentKeywordInIntent || hasDeleteKeywordInIntent || hasSensitiveInputKeywordInIntent) {
                return confirmRequired(context, reason = "submit_or_sensitive_semantics")
            }
            return CheckResult.Allow
        }

        val isPrimitive = actionName in setOf(
            "click",
            "long_press",
            "scroll",
            "drag",
            "press_back",
            "press_home",
            "resolve_installed_app",
            "finished"
        )

        if (isPrimitive &&
            (hasPaymentKeywordInIntent || hasDeleteKeywordInIntent || hasSensitiveInputKeywordInIntent)
        ) {
            return confirmRequired(context, reason = "submit_or_sensitive_semantics")
        }

        if (hasPaymentKeyword || hasDeleteKeyword || hasSensitiveInputKeyword) {
            return confirmRequired(context, reason = "semantic_sensitive_keyword")
        }

        if (hasSubmitKeyword && !hasSubmitKeywordInIntent) {
            return CheckResult.Allow
        }

        return CheckResult.Allow
    }

    private fun confirmRequired(context: PlannedActionContext, reason: String): CheckResult.ConfirmRequired {
        val hash = context.actionHash()
        val summary = context.normalizedTargetLabel
            ?.takeIf { it.isNotBlank() }
            ?.let { "${context.toolName}(target=$it)" }
            ?: context.actionText
        return CheckResult.ConfirmRequired(actionHash = hash, summary = summary, reason = reason)
    }

    suspend fun requestConfirmation(actionDescription: String): Boolean {
        return onConfirmRequired(actionDescription)
    }

    suspend fun requestConfirmation(actionDescription: String, actionHash: String): Boolean {
        val approved = onConfirmRequired(actionDescription)
        synchronized(lock) {
            if (approved) {
                approvedActionHashes += actionHash
            }
        }
        return approved
    }

    fun consumeApprovedAction(actionHash: String): Boolean {
        synchronized(lock) {
            val exists = approvedActionHashes.contains(actionHash)
            if (exists) {
                approvedActionHashes.remove(actionHash)
            }
            return exists
        }
    }
}

data class PlannedActionContext(
    val toolName: String,
    val args: Map<String, String>,
    val normalizedTargetLabel: String?,
    val actionText: String,
    val pageSignatureBefore: String,
    val screenTopTexts: List<String>
) {
    fun actionHash(): String {
        val material = buildString {
            append(toolName.trim().lowercase())
            append('|')
            append(args.toSortedMap().entries.joinToString("&") { (k, v) -> "$k=$v" })
            append('|')
            append(normalizedTargetLabel.orEmpty().trim().lowercase())
            append('|')
            append(pageSignatureBefore)
        }
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(material.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
