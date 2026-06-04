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

import com.juwan.lynx.ui.SharedPreferencesSettingsProvider

/**
 * Factory for creating API clients based on configuration.
 */
object ApiClientFactory {
    /**
     * Create an API client based on the configured API type.
     */
    fun createApiClient(settingsProvider: SettingsProvider): ApiClient {
        return when (settingsProvider.getApiType()) {
            SharedPreferencesSettingsProvider.API_TYPE_OTHER -> {
                if (settingsProvider is OtherSettingsProvider) {
                    OtherApiClient(settingsProvider)
                } else {
                    throw IllegalStateException("OTHER API type requires OtherSettingsProvider")
                }
            }
            else -> {
                LynxApiClient(settingsProvider)
            }
        }
    }
}
