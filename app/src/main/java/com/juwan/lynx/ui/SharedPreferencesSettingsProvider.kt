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

package com.juwan.lynx.ui

import android.content.Context
import android.content.SharedPreferences
import com.juwan.lynx.api.OtherSettingsProvider

/**
 * SharedPreferences-based implementation of SettingsProvider.
 */
class SharedPreferencesSettingsProvider(context: Context) : OtherSettingsProvider {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    companion object {
        private const val PREFS_NAME = "lynx_settings"
        private const val KEY_API_TYPE = "api_type"
        private const val KEY_API_BASE_URL = "api_base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_TEXT_MODEL = "text_model"
        private const val KEY_VISION_MODEL = "vision_model"
        private const val KEY_OTHER_API_URL = "other_api_url"
        private const val KEY_OTHER_ID = "other_id"
        private const val KEY_OTHER_API_KEY = "other_api_key"
        private const val KEY_OTHER_COMPANY = "other_company"
        private const val KEY_OTHER_TEXT_MODEL = "other_text_model"
        private const val KEY_OTHER_VISION_MODEL = "other_vision_model"
        private const val KEY_FIRST_LAUNCH = "first_launch"

        // Executor tuning keys
        private const val KEY_EXECUTOR_REPEATED_ACTION_LIMIT = "executor_repeated_action_limit"
        private const val KEY_EXECUTOR_STUCK_NO_CHANGE_LIMIT = "executor_stuck_no_change_limit"
        private const val KEY_EXECUTOR_PROGRESS_CHECK_INTERVAL = "executor_progress_check_interval"
        private const val KEY_EXECUTOR_RUNTIME_ITERATIONS_PER_CALL = "executor_runtime_iterations_per_call"

        const val API_TYPE_STANDARD = "standard"
        const val API_TYPE_OTHER = "other"

        private const val DEFAULT_API_TYPE = API_TYPE_STANDARD
        private const val DEFAULT_API_BASE_URL = "https://api.openai.com/v1"
        private const val DEFAULT_API_KEY = "your-api-key"
        private const val DEFAULT_TEXT_MODEL = "gpt-4.1-mini"
        private const val DEFAULT_VISION_MODEL = "gpt-4.1"

        private const val DEFAULT_OTHER_API_URL = "https://example.com/v1/chat/completions"
        private const val DEFAULT_OTHER_ID = "your-tenant-id"
        private const val DEFAULT_OTHER_API_KEY = "your-other-api-key"
        private const val DEFAULT_OTHER_COMPANY = "your-company"
        private const val DEFAULT_OTHER_TEXT_MODEL = "your-text-model"
        private const val DEFAULT_OTHER_VISION_MODEL = "your-vision-model"
        // Executor tuning defaults
        private const val DEFAULT_EXECUTOR_REPEATED_ACTION_LIMIT = 3
        private const val DEFAULT_EXECUTOR_STUCK_NO_CHANGE_LIMIT = 3
        private const val DEFAULT_EXECUTOR_PROGRESS_CHECK_INTERVAL = 5
        private const val DEFAULT_EXECUTOR_RUNTIME_ITERATIONS_PER_CALL = 1
    }

    override fun getApiType(): String = prefs.getString(KEY_API_TYPE, DEFAULT_API_TYPE) ?: DEFAULT_API_TYPE
    override fun getApiBaseUrl(): String = prefs.getString(KEY_API_BASE_URL, DEFAULT_API_BASE_URL) ?: DEFAULT_API_BASE_URL
    override fun getApiKey(): String = prefs.getString(KEY_API_KEY, DEFAULT_API_KEY) ?: DEFAULT_API_KEY
    override fun getTextModel(): String {
        val raw = (prefs.getString(KEY_TEXT_MODEL, DEFAULT_TEXT_MODEL) ?: DEFAULT_TEXT_MODEL).trim()
        return if (raw.isBlank()) DEFAULT_TEXT_MODEL else raw
    }

    override fun getVisionModel(): String {
        val raw = (prefs.getString(KEY_VISION_MODEL, DEFAULT_VISION_MODEL) ?: DEFAULT_VISION_MODEL).trim()
        return if (raw.isBlank()) DEFAULT_VISION_MODEL else raw
    }
    override fun getOtherApiUrl(): String = prefs.getString(KEY_OTHER_API_URL, DEFAULT_OTHER_API_URL) ?: DEFAULT_OTHER_API_URL
    override fun getOtherId(): String = prefs.getString(KEY_OTHER_ID, DEFAULT_OTHER_ID) ?: DEFAULT_OTHER_ID
    override fun getOtherApiKey(): String = prefs.getString(KEY_OTHER_API_KEY, DEFAULT_OTHER_API_KEY) ?: DEFAULT_OTHER_API_KEY
    override fun getOtherCompany(): String = prefs.getString(KEY_OTHER_COMPANY, DEFAULT_OTHER_COMPANY) ?: DEFAULT_OTHER_COMPANY
    override fun getOtherTextModel(): String = prefs.getString(KEY_OTHER_TEXT_MODEL, DEFAULT_OTHER_TEXT_MODEL) ?: DEFAULT_OTHER_TEXT_MODEL
    override fun getOtherVisionModel(): String = prefs.getString(KEY_OTHER_VISION_MODEL, DEFAULT_OTHER_VISION_MODEL) ?: DEFAULT_OTHER_VISION_MODEL

    /**
     * Executor tuning: max consecutive repeated actions before loop risk triggers.
     */
    fun getExecutorRepeatedActionLimit(): Int =
        prefs.getInt(KEY_EXECUTOR_REPEATED_ACTION_LIMIT, DEFAULT_EXECUTOR_REPEATED_ACTION_LIMIT)
            .coerceIn(2, 10)

    /**
     * Executor tuning: max consecutive no-change actions before stuck risk triggers.
     */
    fun getExecutorStuckNoChangeLimit(): Int =
        prefs.getInt(KEY_EXECUTOR_STUCK_NO_CHANGE_LIMIT, DEFAULT_EXECUTOR_STUCK_NO_CHANGE_LIMIT)
            .coerceIn(2, 10)

    /**
     * Executor tuning: iterations interval for periodic progress checks.
     */
    fun getExecutorProgressCheckInterval(): Int =
        prefs.getInt(KEY_EXECUTOR_PROGRESS_CHECK_INTERVAL, DEFAULT_EXECUTOR_PROGRESS_CHECK_INTERVAL)
            .coerceIn(2, 20)

    /**
     * Executor tuning: fixed to a single runtime iteration to enforce one tool call per round.
     */
    fun getExecutorRuntimeIterationsPerCall(): Int =
        prefs.getInt(
            KEY_EXECUTOR_RUNTIME_ITERATIONS_PER_CALL,
            DEFAULT_EXECUTOR_RUNTIME_ITERATIONS_PER_CALL
        ).coerceIn(1, 1)

    /**
     * Persist executor tuning values.
     */
    fun saveExecutorTuning(
        repeatedActionLimit: Int,
        stuckNoChangeLimit: Int,
        progressCheckInterval: Int,
        runtimeIterationsPerCall: Int
    ) {
        prefs.edit().apply {
            putInt(
                KEY_EXECUTOR_REPEATED_ACTION_LIMIT,
                repeatedActionLimit.coerceIn(2, 10)
            )
            putInt(
                KEY_EXECUTOR_STUCK_NO_CHANGE_LIMIT,
                stuckNoChangeLimit.coerceIn(2, 10)
            )
            putInt(
                KEY_EXECUTOR_PROGRESS_CHECK_INTERVAL,
                progressCheckInterval.coerceIn(2, 20)
            )
            putInt(
                KEY_EXECUTOR_RUNTIME_ITERATIONS_PER_CALL,
                runtimeIterationsPerCall.coerceIn(1, 1)
            )
        }.commit()
    }

    fun isFirstLaunch(): Boolean = prefs.getBoolean(KEY_FIRST_LAUNCH, true)

    /**
     * 判断是否已配置。
     * 放宽判定条件：只要不是初始化的占位符字符串，且不为空，即视为配置成功。
     */
    fun isConfigured(): Boolean {
        val type = getApiType()
        return if (type == API_TYPE_OTHER) {
            val key = getOtherApiKey().trim()
            key.isNotBlank() && key != "your-other-api-key"
        } else {
            val key = getApiKey().trim()
            val baseUrl = getApiBaseUrl().trim()
            val textModel = getTextModel().trim()
            val visionModel = getVisionModel().trim()
            key.isNotBlank() &&
                key != "sk-placeholder" &&
                key != "your-api-key" &&
                baseUrl.isNotBlank() &&
                textModel.isNotBlank() &&
                visionModel.isNotBlank()
        }
    }

    fun saveStandardSettings(apiBaseUrl: String, apiKey: String, textModel: String, visionModel: String) {
        prefs.edit().apply {
            putString(KEY_API_TYPE, API_TYPE_STANDARD)
            putString(KEY_API_BASE_URL, apiBaseUrl.trim())
            putString(KEY_API_KEY, apiKey.trim())
            putString(KEY_TEXT_MODEL, textModel.trim())
            putString(KEY_VISION_MODEL, visionModel.trim())
            putBoolean(KEY_FIRST_LAUNCH, false)
        }.commit() // 使用 commit() 确保同步写入，解决读取延迟问题
    }

    fun saveOtherSettings(otherApiUrl: String, otherId: String, otherApiKey: String, otherCompany: String, otherTextModel: String, otherVisionModel: String) {
        prefs.edit().apply {
            putString(KEY_API_TYPE, API_TYPE_OTHER)
            putString(KEY_OTHER_API_URL, otherApiUrl.trim())
            putString(KEY_OTHER_ID, otherId.trim())
            putString(KEY_OTHER_API_KEY, otherApiKey.trim())
            putString(KEY_OTHER_COMPANY, otherCompany.trim())
            putString(KEY_OTHER_TEXT_MODEL, otherTextModel.trim())
            putString(KEY_OTHER_VISION_MODEL, otherVisionModel.trim())
            putBoolean(KEY_FIRST_LAUNCH, false)
        }.commit() // 使用 commit() 确保同步写入
    }
}
