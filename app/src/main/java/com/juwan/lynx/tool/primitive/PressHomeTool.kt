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
 * UI-TARS style system home tool.
 */
class PressHomeTool(
    private val accessibilityService: LynxAccessibilityService
) : Tool {
    override val name: String = "press_home"
    override val description: String = "执行系统主屏手势（UI-TARS 风格）"
    override val parameters: List<ToolParam> = emptyList()

    override suspend fun execute(args: Map<String, String>): String {
        return try {
            accessibilityService.performHome()
            "已执行主屏手势"
        } catch (e: Exception) {
            "错误: 主屏手势失败 - ${e.message}"
        }
    }
}
