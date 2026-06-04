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

package com.juwan.lynx.tool.primitive

import com.juwan.lynx.runtime.Tool
import com.juwan.lynx.runtime.ToolParam
import com.juwan.lynx.service.LynxAccessibilityService

/**
 * Primitive tool for text input.
 * Performs text input via LynxAccessibilityService.
 *
 * @param accessibilityService Service for performing text input
 */
class TypeTool(
    private val accessibilityService: LynxAccessibilityService
) : Tool {
    override val name = "type"
    override val description = "设置当前可编辑输入框文本；通常会替换当前文本。多行内容可在 text 中包含换行"
    override val parameters = listOf(
        ToolParam("text", "string", "要设置的文本；多行内容请一次性包含完整文本和换行", required = true)
    )

    override suspend fun execute(args: Map<String, String>): String {
        return try {
            val text = args["text"] ?: args["content"]
                ?: return "错误: 缺少 text 参数"

            if (text.isEmpty()) {
                return "错误: 文本不能为空"
            }

            // Perform text input
            val success = accessibilityService.performType(text)
            if (success) {
                "已输入文本: \"$text\""
            } else {
                "错误: 输入失败 - 未找到可编辑输入框或系统拒绝输入"
            }
        } catch (e: Exception) {
            "错误: 输入失败 - ${e.message}"
        }
    }
}
