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

package com.juwan.lynx.agent

import com.juwan.lynx.state.ActionResultCode
import com.juwan.lynx.state.HumanAssistanceEvent
import com.juwan.lynx.state.HumanAssistanceRequestType
import com.juwan.lynx.state.HumanAssistanceStatus
import com.juwan.lynx.state.WorldState
import com.juwan.lynx.state.WorldStateManager

data class HumanAssistanceRequest(
    val type: HumanAssistanceRequestType,
    val prompt: String,
    val reason: String
)

data class HumanAssistanceResponse(
    val completed: Boolean,
    val text: String? = null,
    val reason: String? = null
)

class HumanAssistanceGate(
    private val worldStateManager: WorldStateManager,
    private val onAssistanceRequired: suspend (HumanAssistanceRequest) -> HumanAssistanceResponse
) {
    fun detect(reason: String): HumanAssistanceRequest? {
        return detectRequest(worldStateManager.getState(), reason)
    }

    suspend fun maybeAssist(reason: String): Boolean {
        val request = detect(reason) ?: return false
        return assist(request)
    }

    suspend fun assist(request: HumanAssistanceRequest): Boolean {
        worldStateManager.recordHumanAssistanceEvent(
            HumanAssistanceEvent(
                type = request.type,
                prompt = request.prompt,
                status = HumanAssistanceStatus.REQUESTED,
                reason = request.reason
            )
        )
        val response = onAssistanceRequired(request)
        worldStateManager.recordHumanAssistanceEvent(
            HumanAssistanceEvent(
                type = request.type,
                prompt = request.prompt,
                status = if (response.completed) {
                    HumanAssistanceStatus.COMPLETED
                } else {
                    HumanAssistanceStatus.CANCELLED
                },
                responseText = response.text,
                reason = response.reason ?: request.reason
            )
        )
        if (!response.text.isNullOrBlank()) {
            worldStateManager.setRuntimeTaskFacts(
                listOf("task.user_text=${response.text.trim().take(80)}")
            )
        }
        if (response.completed) {
            worldStateManager.refreshObservation()
        }
        return response.completed
    }

    private fun detectRequest(state: WorldState, reason: String): HumanAssistanceRequest? {
        state.pendingConfirmation?.let { pending ->
            return HumanAssistanceRequest(
                type = HumanAssistanceRequestType.USER_CONFIRMATION,
                prompt = "请确认或处理当前敏感动作：${pending.actionSummary}",
                reason = pending.reason
            )
        }

        val joinedTexts = (state.visibleTexts + state.uiTexts + state.ocrTexts + state.contentDescriptions)
            .joinToString(" ")
            .lowercase()
        val recentBlocked = state.recentOutcomes.takeLast(3).any {
            it.resultCode == ActionResultCode.BLOCKED
        }
        val hasStrongExternalBlocker = STRONG_EXTERNAL_BLOCKER_KEYWORDS.any {
            joinedTexts.contains(it, ignoreCase = true)
        }
        val hasLoginWall = LOGIN_WALL_PHRASES.any {
            joinedTexts.contains(it, ignoreCase = true)
        }
        if (!recentBlocked && !hasStrongExternalBlocker && !hasLoginWall) return null

        val requestType = if (TEXT_CODE_KEYWORDS.any { joinedTexts.contains(it, ignoreCase = true) }) {
            HumanAssistanceRequestType.USER_TEXT
        } else {
            HumanAssistanceRequestType.USER_ACTION
        }
        val prompt = when (requestType) {
            HumanAssistanceRequestType.USER_TEXT ->
                "检测到验证码、短信码或账号输入阻塞。请在手机上处理，或在可用输入处填入所需信息后确认完成。"
            HumanAssistanceRequestType.USER_CONFIRMATION ->
                "请确认当前阻塞动作后返回。"
            HumanAssistanceRequestType.USER_ACTION ->
                "检测到登录、权限、验证码或风控阻塞。请在手机上手动处理，完成后确认继续。"
        }
        return HumanAssistanceRequest(
            type = requestType,
            prompt = prompt,
            reason = reason
        )
    }

    companion object {
        private val STRONG_EXTERNAL_BLOCKER_KEYWORDS = listOf(
            "验证码", "短信码", "滑块", "安全验证", "身份验证", "captcha",
            "权限", "授权", "风险", "风控", "risk", "verification"
        )
        private val LOGIN_WALL_PHRASES = listOf(
            "请登录", "立即登录", "登录后", "账号登录", "密码登录", "输入密码",
            "未登录", "重新登录", "授权登录", "login required", "sign in"
        )
        private val TEXT_CODE_KEYWORDS = listOf(
            "验证码", "短信码", "code", "verification"
        )
    }
}
