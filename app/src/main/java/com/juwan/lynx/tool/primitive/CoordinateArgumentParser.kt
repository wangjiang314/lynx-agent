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
import android.os.Build
import android.view.WindowManager
import kotlin.math.roundToInt

internal object CoordinateArgumentParser {
    private val numberRegex = Regex("""-?\d+(?:\.\d+)?""")

    fun screenBoundsDescription(): String {
        return "x=0..1000, y=0..1000"
    }

    fun parseSingleNormalizedPoint(
        args: Map<String, String>,
        pointKey: String = "point",
        xKey: String = "x",
        yKey: String = "y"
    ): Pair<Int, Int>? {
        val pointRaw = args[pointKey]
        if (!pointRaw.isNullOrBlank()) {
            val parsed = parsePointValue(pointRaw) ?: return null
            return toNormalized(parsed.first, parsed.second)
        }

        val xRaw = args[xKey]?.toFloatOrNull()
        val yRaw = args[yKey]?.toFloatOrNull()
        if (xRaw == null || yRaw == null) return null
        return toNormalized(xRaw, yRaw)
    }

    fun parseTwoNormalizedPoints(
        args: Map<String, String>,
        startPointKey: String = "start_point",
        endPointKey: String = "end_point"
    ): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
        val start = args[startPointKey]?.let { parsePointValue(it) }
        val end = args[endPointKey]?.let { parsePointValue(it) }
        if (start != null && end != null) {
            val normalizedStart = toNormalized(start.first, start.second) ?: return null
            val normalizedEnd = toNormalized(end.first, end.second) ?: return null
            return normalizedStart to normalizedEnd
        }

        val x1 = args["x1"]?.toFloatOrNull()
        val y1 = args["y1"]?.toFloatOrNull()
        val x2 = args["x2"]?.toFloatOrNull()
        val y2 = args["y2"]?.toFloatOrNull()
        if (x1 == null || y1 == null || x2 == null || y2 == null) return null
        val normalizedStart = toNormalized(x1, y1) ?: return null
        val normalizedEnd = toNormalized(x2, y2) ?: return null
        return normalizedStart to normalizedEnd
    }

    fun normalizedToPixels(context: Context, x: Int, y: Int): Pair<Int, Int> {
        val (screenWidth, screenHeight) = getScreenSize(context)
        val maxX = (screenWidth - 1).coerceAtLeast(0)
        val maxY = (screenHeight - 1).coerceAtLeast(0)
        val px = (x * screenWidth / 1000).coerceIn(0, maxX)
        val py = (y * screenHeight / 1000).coerceIn(0, maxY)
        return px to py
    }

    private fun parsePointValue(raw: String): Pair<Float, Float>? {
        val nums = numberRegex.findAll(raw).mapNotNull { it.value.toFloatOrNull() }.take(2).toList()
        if (nums.size < 2) return null
        return nums[0] to nums[1]
    }

    private fun toNormalized(x: Float, y: Float): Pair<Int, Int>? {
        if (x !in 0f..1000f || y !in 0f..1000f) {
            return null
        }
        return x.roundToInt() to y.roundToInt()
    }

    fun getScreenSize(context: Context): Pair<Int, Int> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Keep action coordinates in the same frame as screenshots. The capture
        // service uses Display#getRealMetrics, while resources.displayMetrics
        // can exclude system/navigation areas on some devices.
        val realMetrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        val fallbackDisplay = windowManager.defaultDisplay
        @Suppress("DEPRECATION")
        fallbackDisplay?.getRealMetrics(realMetrics)
        if (realMetrics.widthPixels > 0 && realMetrics.heightPixels > 0) {
            return realMetrics.widthPixels to realMetrics.heightPixels
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = runCatching { windowManager.currentWindowMetrics.bounds }.getOrNull()
            val width = bounds?.width() ?: 0
            val height = bounds?.height() ?: 0
            if (width > 0 && height > 0) {
                return width to height
            }
        }

        val metrics = context.resources.displayMetrics
        if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
            return metrics.widthPixels to metrics.heightPixels
        }

        return metrics.widthPixels.coerceAtLeast(1) to metrics.heightPixels.coerceAtLeast(1)
    }
}
