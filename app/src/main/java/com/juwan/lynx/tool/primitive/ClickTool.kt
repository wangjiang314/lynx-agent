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

import android.content.Context
import com.juwan.lynx.runtime.Tool
import com.juwan.lynx.runtime.ToolParam
import com.juwan.lynx.service.LynxAccessibilityService

/**
 * UI-TARS style click tool.
 * Accepts either:
 * - point: "x y"
 * - x / y: normalized coordinates (0-1000)
 */
class ClickTool(
    private val context: Context,
    private val accessibilityService: LynxAccessibilityService
) : Tool {
    override val name: String = "click"
    override val description: String
        get() = "点击当前截图坐标；使用 0-1000 归一化坐标，${CoordinateArgumentParser.screenBoundsDescription()}；不是设备像素"
    override val parameters: List<ToolParam>
        get() = listOf(
            ToolParam("point", "string", "归一化坐标字符串，如 \"300 900\"", required = false),
            ToolParam("x", "int", "归一化 X 坐标 (0-1000)", required = false),
            ToolParam("y", "int", "归一化 Y 坐标 (0-1000)", required = false)
        )

    override suspend fun execute(args: Map<String, String>): String {
        val normalized = CoordinateArgumentParser.parseSingleNormalizedPoint(args)
            ?: return "错误: 缺少 point 或 x/y 参数，或坐标不在 0-1000 范围内"

        val (x, y) = normalized
        val (px, py) = CoordinateArgumentParser.normalizedToPixels(context, x, y)
        accessibilityService.performClick(px, py)
        return "已点击归一化坐标 ($x, $y) → 像素 ($px, $py)"
    }
}
