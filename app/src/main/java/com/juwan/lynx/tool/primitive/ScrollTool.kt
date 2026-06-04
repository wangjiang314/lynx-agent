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
 * Primitive tool for scrolling/swiping at normalized coordinates.
 */
class ScrollTool(
    private val context: Context,
    private val accessibilityService: LynxAccessibilityService
) : Tool {
    override val name = "scroll"
    override val description: String
        get() = "在指定位置执行滑动手势；使用 0-1000 归一化坐标和归一化距离，${CoordinateArgumentParser.screenBoundsDescription()}；不是设备像素"
    override val parameters: List<ToolParam>
        get() = listOf(
            ToolParam("point", "string", "起始归一化坐标，如 \"500 750\"（可选）", required = false),
            ToolParam("x", "int", "起始归一化 X (0-1000，可选，默认 500)", required = false),
            ToolParam("y", "int", "起始归一化 Y (0-1000，可选，默认 750)", required = false),
            ToolParam("direction", "string", "手指滑动方向: up/down/left/right；查看下面内容用 up，回到上面内容用 down", required = true),
            ToolParam("length", "int", "滑动归一化距离 (0-1000，可选，默认 450)", required = false)
        )

    override suspend fun execute(args: Map<String, String>): String {
        return try {
            val (screenWidth, screenHeight) = CoordinateArgumentParser.getScreenSize(context)
            val parsedPoint = CoordinateArgumentParser.parseSingleNormalizedPoint(
                args = args,
                pointKey = "point",
                xKey = "x",
                yKey = "y"
            )
            val x = parsedPoint?.first ?: 500
            val y = parsedPoint?.second ?: 750
            val direction = args["direction"]?.lowercase()
                ?: return "错误: 缺少 direction 参数"
            val length = args["length"]?.toIntOrNull()
                ?: 450

            // Validate direction
            if (direction !in listOf("up", "down", "left", "right")) {
                return "错误: 方向必须是 up/down/left/right，收到 $direction"
            }

            // Validate length
            if (length < 0 || length > 1000) {
                return "错误: 滑动距离必须在 0-1000 范围内，收到 $length"
            }

            val startX = x * screenWidth / 1000
            val startY = y * screenHeight / 1000
            val lengthPixels = length * screenHeight / 1000

            // Calculate end coordinates based on direction and length
            val (endX, endY) = when (direction) {
                "up" -> Pair(startX, startY - lengthPixels)
                "down" -> Pair(startX, startY + lengthPixels)
                "left" -> Pair(startX - lengthPixels, startY)
                "right" -> Pair(startX + lengthPixels, startY)
                else -> return "错误: 未知方向 $direction"
            }

            // Perform swipe
            val maxX = (screenWidth - 1).coerceAtLeast(0)
            val maxY = (screenHeight - 1).coerceAtLeast(0)
            accessibilityService.performSwipe(
                startX.coerceIn(0, maxX),
                startY.coerceIn(0, maxY),
                endX.coerceIn(0, maxX),
                endY.coerceIn(0, maxY)
            )
            "已在归一化坐标 ($x, $y) 执行 $direction 滑动，距离 $length"
        } catch (e: Exception) {
            "错误: 滑动失败 - ${e.message}"
        }
    }
}
