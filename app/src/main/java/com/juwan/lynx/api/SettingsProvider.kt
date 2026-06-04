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

package com.juwan.lynx.api

/**
 * Interface for providing configurable API settings.
 * Implementations should read from SharedPreferences, SQLite, or other storage.
 */
interface SettingsProvider {
    /**
     * Get API type (standard or other)
     */
    fun getApiType(): String

    /**
     * Get API base URL (e.g., "https://api.openai.com/v1")
     */
    fun getApiBaseUrl(): String

    /**
     * Get API key for authentication
     */
    fun getApiKey(): String

    /**
     * Get text model name for PlannerAgent (e.g., "gpt-4")
     */
    fun getTextModel(): String

    /**
     * Get vision model name for ExecutorAgent (e.g., "gpt-4-vision")
     */
    fun getVisionModel(): String
}

/**
 * Extended interface for OTHER-specific settings.
 */
interface OtherSettingsProvider : SettingsProvider {
    /**
     * Get OTHER API URL
     */
    fun getOtherApiUrl(): String

    /**
     * Get OTHER ID for authentication
     */
    fun getOtherId(): String

    /**
     * Get OTHER API key
     */
    fun getOtherApiKey(): String

    /**
     * Get OTHER company identifier
     */
    fun getOtherCompany(): String

    /**
     * Get OTHER text model name
     */
    fun getOtherTextModel(): String

    /**
     * Get OTHER vision model name
     */
    fun getOtherVisionModel(): String
}

/**
 * Data class for Lynx settings
 */
data class LynxSettings(
    val apiBaseUrl: String,
    val apiKey: String,
    val textModel: String,
    val visionModel: String,
    val screenshotQuality: Int = 80
)
