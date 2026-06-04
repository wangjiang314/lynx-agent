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

import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.juwan.lynx.util.ScreenshotUtil

/**
 * Activity for the Settings screen.
 */
class SettingsActivity : ComponentActivity() {
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            ScreenshotUtil.takeScreenshot(
                this,
                result.resultCode,
                result.data,
                object : ScreenshotUtil.ScreenshotCallback {
                    override fun onScreenshotBase64(base64: String, width: Int, height: Int) {
                        Toast.makeText(
                            this@SettingsActivity,
                            "屏幕捕获权限已就绪",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    override fun onError(reason: String) {
                        Toast.makeText(
                            this@SettingsActivity,
                            "屏幕捕获初始化失败: $reason",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
        } else {
            Toast.makeText(this, "未授予屏幕捕获权限", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val settingsProvider = SharedPreferencesSettingsProvider(this)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SettingsScreen(
                        settingsProvider = settingsProvider,
                        onBack = { finish() },
                        onRequestScreenCapturePermission = { requestMediaProjection() }
                    )
                }
            }
        }
    }

    private fun requestMediaProjection() {
        val mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }
}
