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

package com.juwan.lynx.tool.macro

import android.os.Build
import com.juwan.lynx.perception.MlKitOcrEngine
import com.juwan.lynx.perception.OcrEngine
import com.juwan.lynx.runtime.Tool
import com.juwan.lynx.runtime.ToolParam
import com.juwan.lynx.service.LynxAccessibilityService
import com.juwan.lynx.service.LynxCaptureService
import kotlinx.coroutines.delay

/**
 * Macro tool: type text into focused input and trigger IME enter action.
 */
class TypeAndEnterTool(
    private val accessibilityService: LynxAccessibilityService,
    private val captureService: LynxCaptureService? = null,
    private val ocrEngine: OcrEngine = MlKitOcrEngine.shared
) : Tool {
    override val name: String = "type_and_enter"
    override val description: String = "在当前输入框输入文本并触发回车/搜索动作"
    override val parameters: List<ToolParam> = listOf(
        ToolParam("text", "string", "要输入的文本", required = true)
    )

    override suspend fun execute(args: Map<String, String>): String {
        val text = args["text"] ?: args["content"] ?: return "错误: 缺少 text 参数"
        if (text.isEmpty()) return "错误: 文本不能为空"
        if (!accessibilityService.hasFocusedEditableInput()) {
            return "错误: 当前没有已聚焦输入框，请先点击输入框"
        }

        val typeSuccess = accessibilityService.performType(text)
        if (!typeSuccess) {
            return "错误: 输入失败 - 系统未接受输入"
        }

        delay(200)
        if (accessibilityService.performImeAction()) {
            return "已输入文本并触发回车: \"$text\""
        }

        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.P..Build.VERSION_CODES.Q) {
            val fallbackNewline = accessibilityService.performType("$text\n")
            if (fallbackNewline) {
                return "已输入文本，并通过换行触发提交: \"$text\""
            }
        }

        accessibilityService.performBack()
        delay(220)
        val clickedLabel = findAndClickSubmitButtonByOcr()
        if (clickedLabel != null) {
            return "已输入文本，并通过点击「$clickedLabel」完成提交: \"$text\""
        }

        return "错误: 已输入文本但触发回车失败 - 当前输入法不支持，且未识别到可点击提交按钮"
    }

    private fun findAndClickSubmitButtonByOcr(): String? {
        val localCaptureService = captureService ?: return null
        localCaptureService.refreshScreenshotBlocking(1800L)
        val bitmap = localCaptureService.captureAsBitmap() ?: return null
        val results = ocrEngine.recognize(bitmap)
        if (results.isEmpty()) return null

        val priorityKeywords = listOf("搜索", "发送", "确定", "完成", "提交")
        val excluded = listOf("取消", "返回", "关闭")

        val target = results
            .filter { ocr ->
                val label = ocr.text.trim()
                if (label.isBlank()) return@filter false
                if (excluded.any { label.contains(it) }) return@filter false
                priorityKeywords.any { key ->
                    label.contains(key) || key.contains(label)
                }
            }
            .sortedBy { it.bounds.top * 10 + it.bounds.left }
            .firstOrNull()
            ?: return null

        val center = target.bounds.center()
        accessibilityService.performClick(center.x, center.y)
        return target.text.trim()
    }
}
