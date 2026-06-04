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
 * UI-TARS style drag tool.
 * Accepts either:
 * - start_point/end_point: "x y"
 * - x1/y1/x2/y2: normalized coordinates
 */
class DragTool(
    private val context: Context,
    private val accessibilityService: LynxAccessibilityService
) : Tool {
    override val name: String = "drag"
    override val description: String
        get() = "从起点拖拽到终点；使用 0-1000 归一化坐标，${CoordinateArgumentParser.screenBoundsDescription()}；不是设备像素"
    override val parameters: List<ToolParam>
        get() = listOf(
            ToolParam("start_point", "string", "起点归一化坐标，如 \"500 800\"", required = false),
            ToolParam("end_point", "string", "终点归一化坐标，如 \"500 300\"", required = false),
            ToolParam("x1", "int", "起点归一化 X (0-1000)", required = false),
            ToolParam("y1", "int", "起点归一化 Y (0-1000)", required = false),
            ToolParam("x2", "int", "终点归一化 X (0-1000)", required = false),
            ToolParam("y2", "int", "终点归一化 Y (0-1000)", required = false)
        )

    override suspend fun execute(args: Map<String, String>): String {
        val points = CoordinateArgumentParser.parseTwoNormalizedPoints(args)
            ?: return "错误: 缺少 start_point/end_point 或 x1/y1/x2/y2 参数，或坐标不在 0-1000 范围内"

        val (start, end) = points
        val (sx, sy) = start
        val (ex, ey) = end
        val (startPx, startPy) = CoordinateArgumentParser.normalizedToPixels(context, sx, sy)
        val (endPx, endPy) = CoordinateArgumentParser.normalizedToPixels(context, ex, ey)

        accessibilityService.performSwipe(startPx, startPy, endPx, endPy)
        return "已拖拽归一化坐标 ($sx, $sy)->($ex, $ey) → 像素 ($startPx, $startPy)->($endPx, $endPy)"
    }
}
