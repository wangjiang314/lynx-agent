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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for SharedPreferencesSettingsProvider.
 */
class SharedPreferencesSettingsProviderTest {

    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var settingsProvider: SharedPreferencesSettingsProvider

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        sharedPreferences = mockk(relaxed = true)
        editor = mockk(relaxed = true)

        every { context.getSharedPreferences(any(), any()) } returns sharedPreferences
        every { sharedPreferences.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.putInt(any(), any()) } returns editor
        every { editor.apply() } returns Unit

        settingsProvider = SharedPreferencesSettingsProvider(context)
    }

    @Test
    fun `getApiBaseUrl returns default value when not set`() {
        every { sharedPreferences.getString("api_base_url", any()) } returns "https://api.openai.com/v1"

        val result = settingsProvider.getApiBaseUrl()

        assertEquals("https://api.openai.com/v1", result)
    }

    @Test
    fun `getApiKey returns default empty string when not set`() {
        every { sharedPreferences.getString("api_key", any()) } returns ""

        val result = settingsProvider.getApiKey()

        assertEquals("", result)
    }

    @Test
    fun `getTextModel returns default value when not set`() {
        every { sharedPreferences.getString("text_model", any()) } returns "gpt-4"

        val result = settingsProvider.getTextModel()

        assertEquals("gpt-4", result)
    }

    @Test
    fun `getVisionModel returns default value when not set`() {
        every { sharedPreferences.getString("vision_model", any()) } returns "gpt-4-vision-preview"

        val result = settingsProvider.getVisionModel()

        assertEquals("gpt-4-vision-preview", result)
    }

    @Test
    fun `isFirstLaunch returns true by default`() {
        every { sharedPreferences.getBoolean("first_launch", true) } returns true

        val result = settingsProvider.isFirstLaunch()

        assertTrue(result)
    }

    @Test
    fun `isConfigured returns false when API key is empty`() {
        every { sharedPreferences.getString("api_key", any()) } returns ""

        val result = settingsProvider.isConfigured()

        assertFalse(result)
    }

    @Test
    fun `isConfigured returns true when API key is set`() {
        every { sharedPreferences.getString("api_key", any()) } returns "sk-test-key"

        val result = settingsProvider.isConfigured()

        assertTrue(result)
    }

    @Test
    fun `saveStandardSettings stores all values in SharedPreferences`() {
        every { editor.commit() } returns true

        settingsProvider.saveStandardSettings(
            apiBaseUrl = "https://api.example.com/v1",
            apiKey = "test-key",
            textModel = "gpt-4-turbo",
            visionModel = "gpt-4-vision"
        )

        verify {
            editor.putString("api_type", "standard")
            editor.putString("api_base_url", "https://api.example.com/v1")
            editor.putString("api_key", "test-key")
            editor.putString("text_model", "gpt-4-turbo")
            editor.putString("vision_model", "gpt-4-vision")
            editor.putBoolean("first_launch", false)
            editor.commit()
        }
    }

    @Test
    fun `saveStandardSettings marks first launch as false`() {
        every { editor.commit() } returns true

        settingsProvider.saveStandardSettings(
            apiBaseUrl = "https://api.example.com/v1",
            apiKey = "test-key",
            textModel = "gpt-4",
            visionModel = "gpt-4-vision"
        )

        verify {
            editor.putBoolean("first_launch", false)
            editor.commit()
        }
    }

    @Test
    fun `executor tuning getters return stored values within valid ranges`() {
        every { sharedPreferences.getInt("executor_repeated_action_limit", 3) } returns 4
        every { sharedPreferences.getInt("executor_stuck_no_change_limit", 3) } returns 6
        every { sharedPreferences.getInt("executor_progress_check_interval", 5) } returns 8
        every { sharedPreferences.getInt("executor_runtime_iterations_per_call", 1) } returns 3

        assertEquals(4, settingsProvider.getExecutorRepeatedActionLimit())
        assertEquals(6, settingsProvider.getExecutorStuckNoChangeLimit())
        assertEquals(8, settingsProvider.getExecutorProgressCheckInterval())
        assertEquals(1, settingsProvider.getExecutorRuntimeIterationsPerCall())
    }

    @Test
    fun `executor tuning getters clamp out-of-range values`() {
        every { sharedPreferences.getInt("executor_repeated_action_limit", 3) } returns 100
        every { sharedPreferences.getInt("executor_stuck_no_change_limit", 3) } returns 1
        every { sharedPreferences.getInt("executor_progress_check_interval", 5) } returns 0
        every { sharedPreferences.getInt("executor_runtime_iterations_per_call", 1) } returns 99

        assertEquals(10, settingsProvider.getExecutorRepeatedActionLimit())
        assertEquals(2, settingsProvider.getExecutorStuckNoChangeLimit())
        assertEquals(2, settingsProvider.getExecutorProgressCheckInterval())
        assertEquals(1, settingsProvider.getExecutorRuntimeIterationsPerCall())
    }

    @Test
    fun `saveExecutorTuning persists clamped values`() {
        every { editor.commit() } returns true

        settingsProvider.saveExecutorTuning(
            repeatedActionLimit = 100,
            stuckNoChangeLimit = 1,
            progressCheckInterval = 999,
            runtimeIterationsPerCall = 0
        )

        verify {
            editor.putInt("executor_repeated_action_limit", 10)
            editor.putInt("executor_stuck_no_change_limit", 2)
            editor.putInt("executor_progress_check_interval", 20)
            editor.putInt("executor_runtime_iterations_per_call", 1)
            editor.commit()
        }
    }

    @Test
    fun `executor tuning getters return defaults when not set`() {
        every { sharedPreferences.getInt("executor_repeated_action_limit", 3) } returns 3
        every { sharedPreferences.getInt("executor_stuck_no_change_limit", 3) } returns 3
        every { sharedPreferences.getInt("executor_progress_check_interval", 5) } returns 5
        every { sharedPreferences.getInt("executor_runtime_iterations_per_call", 1) } returns 1

        assertEquals(3, settingsProvider.getExecutorRepeatedActionLimit())
        assertEquals(3, settingsProvider.getExecutorStuckNoChangeLimit())
        assertEquals(5, settingsProvider.getExecutorProgressCheckInterval())
        assertEquals(1, settingsProvider.getExecutorRuntimeIterationsPerCall())
    }
}
