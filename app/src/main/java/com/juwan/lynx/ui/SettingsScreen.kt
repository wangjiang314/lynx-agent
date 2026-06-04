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

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juwan.lynx.util.PermissionChecker

/**
 * Settings screen for configuring API settings.
 */
@Composable
fun SettingsScreen(
    settingsProvider: SharedPreferencesSettingsProvider,
    onBack: () -> Unit,
    onRequestScreenCapturePermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var apiType by remember { mutableStateOf(settingsProvider.getApiType()) }

    var apiBaseUrl by remember { mutableStateOf(settingsProvider.getApiBaseUrl()) }
    var apiKey by remember { mutableStateOf(settingsProvider.getApiKey()) }
    var textModel by remember { mutableStateOf(settingsProvider.getTextModel()) }
    var visionModel by remember { mutableStateOf(settingsProvider.getVisionModel()) }

    var otherApiUrl by remember { mutableStateOf(settingsProvider.getOtherApiUrl()) }
    var otherId by remember { mutableStateOf(settingsProvider.getOtherId()) }
    var otherApiKey by remember { mutableStateOf(settingsProvider.getOtherApiKey()) }
    var otherCompany by remember { mutableStateOf(settingsProvider.getOtherCompany()) }
    var otherTextModel by remember { mutableStateOf(settingsProvider.getOtherTextModel()) }
    var otherVisionModel by remember { mutableStateOf(settingsProvider.getOtherVisionModel()) }

    var passwordVisible by remember { mutableStateOf(false) }

    // Executor tuning
    var repeatedActionLimit by remember { mutableStateOf(settingsProvider.getExecutorRepeatedActionLimit().toString()) }
    var stuckNoChangeLimit by remember { mutableStateOf(settingsProvider.getExecutorStuckNoChangeLimit().toString()) }
    var progressCheckInterval by remember { mutableStateOf(settingsProvider.getExecutorProgressCheckInterval().toString()) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        SettingsHeader(onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "已预填本地测试 API、账号 ID 与模型配置，可直接用于验证 Agent。",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Text(
                text = "API 类型",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { apiType = SharedPreferencesSettingsProvider.API_TYPE_STANDARD },
                    modifier = Modifier.weight(1f),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = if (apiType == SharedPreferencesSettingsProvider.API_TYPE_STANDARD) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text("标准 API")
                }

                Button(
                    onClick = { apiType = SharedPreferencesSettingsProvider.API_TYPE_OTHER },
                    modifier = Modifier.weight(1f),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = if (apiType == SharedPreferencesSettingsProvider.API_TYPE_OTHER) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text("OTHER API")
                }
            }

            if (apiType == SharedPreferencesSettingsProvider.API_TYPE_STANDARD) {
                OutlinedTextField(
                    value = apiBaseUrl,
                    onValueChange = { apiBaseUrl = it },
                    label = { Text("API Base URL") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key（仅需填写）") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation()
                )
                OutlinedTextField(
                    value = textModel,
                    onValueChange = { textModel = it },
                    label = { Text("文本模型名称") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = visionModel,
                    onValueChange = { visionModel = it },
                    label = { Text("视觉模型名称") },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                OutlinedTextField(
                    value = otherApiUrl,
                    onValueChange = { otherApiUrl = it },
                    label = { Text("OTHER API URL") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = otherId,
                    onValueChange = { otherId = it },
                    label = { Text("OTHER ID") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = otherApiKey,
                    onValueChange = { otherApiKey = it },
                    label = { Text("OTHER API Key") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation()
                )
                OutlinedTextField(
                    value = otherCompany,
                    onValueChange = { otherCompany = it },
                    label = { Text("OTHER Company") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = otherTextModel,
                    onValueChange = { otherTextModel = it },
                    label = { Text("OTHER 文本模型") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = otherVisionModel,
                    onValueChange = { otherVisionModel = it },
                    label = { Text("OTHER 视觉模型") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Text(
                text = "Executor 调优",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = repeatedActionLimit,
                onValueChange = { repeatedActionLimit = it.filter { ch -> ch.isDigit() } },
                label = { Text("重复动作阈值 (2-10)") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            OutlinedTextField(
                value = stuckNoChangeLimit,
                onValueChange = { stuckNoChangeLimit = it.filter { ch -> ch.isDigit() } },
                label = { Text("无变化阈值 (2-10)") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            OutlinedTextField(
                value = progressCheckInterval,
                onValueChange = { progressCheckInterval = it.filter { ch -> ch.isDigit() } },
                label = { Text("进度检查间隔 (2-20)") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Text(
                text = "每次调用步数已固定为 1（单轮单工具执行）",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "设备权限",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (PermissionChecker.isMediaProjectionGranted()) {
                            "屏幕捕获权限已就绪"
                        } else {
                            "当前缺少屏幕捕获权限，Agent 执行任务前会请求授权。"
                        },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = onRequestScreenCapturePermission,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("授予屏幕捕获权限")
                    }
                }
            }

            Button(
                onClick = {
                    if (apiType == SharedPreferencesSettingsProvider.API_TYPE_STANDARD) {
                        val normalizedKey = apiKey.trim()
                        if (normalizedKey.isBlank() || normalizedKey == "your-api-key" || normalizedKey == "sk-placeholder") {
                            Toast.makeText(context, "请填写有效的标准 API Key", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (apiBaseUrl.trim().isBlank() || textModel.trim().isBlank() || visionModel.trim().isBlank()) {
                            Toast.makeText(context, "请完善标准 API 地址与模型配置", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                    } else {
                        if (otherApiKey.trim().isBlank() || otherApiKey.trim() == "your-other-api-key") {
                        Toast.makeText(context, "请填写有效的 OTHER API Key", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                    }

                    if (apiType == SharedPreferencesSettingsProvider.API_TYPE_OTHER) {
                        settingsProvider.saveOtherSettings(otherApiUrl, otherId, otherApiKey, otherCompany, otherTextModel, otherVisionModel)
                    } else {
                        settingsProvider.saveStandardSettings(apiBaseUrl, apiKey, textModel, visionModel)
                    }

                    val repeated = repeatedActionLimit.toIntOrNull() ?: settingsProvider.getExecutorRepeatedActionLimit()
                    val stuck = stuckNoChangeLimit.toIntOrNull() ?: settingsProvider.getExecutorStuckNoChangeLimit()
                    val progress = progressCheckInterval.toIntOrNull() ?: settingsProvider.getExecutorProgressCheckInterval()
                    settingsProvider.saveExecutorTuning(
                        repeatedActionLimit = repeated,
                        stuckNoChangeLimit = stuck,
                        progressCheckInterval = progress,
                        runtimeIterationsPerCall = 1
                    )

                    // 保存成功后直接触发返回跳转逻辑
                    onBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("保存设置")
            }
        }
    }
}

@Composable
fun SettingsHeader(onBack: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        shadowElevation = 4.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 4.dp, vertical = 8.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }

            Text(
                text = "设置",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}
