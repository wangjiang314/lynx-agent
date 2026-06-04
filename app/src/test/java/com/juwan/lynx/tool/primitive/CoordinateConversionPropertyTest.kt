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
import android.util.DisplayMetrics
import android.view.WindowManager
import com.juwan.lynx.service.LynxAccessibilityService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify

class CoordinateConversionPropertyTest : FunSpec({
    context("Normalized coordinate contract") {
        test("ClickTool should convert 0-1000 normalized coordinates to pixels") {
            checkAll(
                10,
                Arb.int(0..1000),
                Arb.int(0..1000),
                Arb.int(720..2560),
                Arb.int(1280..3840)
            ) { x, y, screenWidth, screenHeight ->
                val mockContext = mockScreenContext(screenWidth, screenHeight)
                val mockAccessibilityService = mockk<LynxAccessibilityService>(relaxed = true)
                val clickXSlot = slot<Int>()
                val clickYSlot = slot<Int>()
                every {
                    mockAccessibilityService.performClick(capture(clickXSlot), capture(clickYSlot))
                } returns Unit

                val clickTool = ClickTool(mockContext, mockAccessibilityService)
                val result = clickTool.execute(
                    mapOf(
                        "x" to x.toString(),
                        "y" to y.toString()
                    )
                )

                result shouldContain "已点击归一化坐标"
                verify { mockAccessibilityService.performClick(any(), any()) }
                clickXSlot.captured shouldBe (x * screenWidth / 1000).coerceIn(0, screenWidth - 1)
                clickYSlot.captured shouldBe (y * screenHeight / 1000).coerceIn(0, screenHeight - 1)
            }
        }

        test("ClickTool should use full real display metrics instead of app resource metrics") {
            val mockContext = mockScreenContext(
                screenWidth = 1236,
                screenHeight = 2676,
                resourceWidth = 1236,
                resourceHeight = 2562
            )
            val mockAccessibilityService = mockk<LynxAccessibilityService>(relaxed = true)
            val clickXSlot = slot<Int>()
            val clickYSlot = slot<Int>()
            every {
                mockAccessibilityService.performClick(capture(clickXSlot), capture(clickYSlot))
            } returns Unit

            val clickTool = ClickTool(mockContext, mockAccessibilityService)
            val result = clickTool.execute(mapOf("x" to "300", "y" to "900"))

            result shouldContain "已点击归一化坐标"
            clickXSlot.captured shouldBe 370
            clickYSlot.captured shouldBe 2408
        }

        test("ClickTool should reject pixel-like coordinates outside 0-1000 instead of guessing") {
            val mockContext = mockScreenContext(screenWidth = 1236, screenHeight = 2562)
            val mockAccessibilityService = mockk<LynxAccessibilityService>(relaxed = true)

            val clickTool = ClickTool(mockContext, mockAccessibilityService)
            val result = clickTool.execute(mapOf("x" to "657", "y" to "2305"))

            result shouldContain "坐标不在 0-1000"
            verify(exactly = 0) { mockAccessibilityService.performClick(any(), any()) }
        }

        test("ScrollTool should convert normalized start coordinates and distance to pixels") {
            val screenWidth = 1200
            val screenHeight = 2400
            val mockContext = mockScreenContext(screenWidth, screenHeight)
            val mockAccessibilityService = mockk<LynxAccessibilityService>(relaxed = true)
            val startXSlot = slot<Int>()
            val startYSlot = slot<Int>()
            val endXSlot = slot<Int>()
            val endYSlot = slot<Int>()
            every {
                mockAccessibilityService.performSwipe(
                    capture(startXSlot),
                    capture(startYSlot),
                    capture(endXSlot),
                    capture(endYSlot)
                )
            } returns Unit

            val scrollTool = ScrollTool(mockContext, mockAccessibilityService)
            val result = scrollTool.execute(
                mapOf(
                    "x" to "500",
                    "y" to "750",
                    "direction" to "up",
                    "length" to "450"
                )
            )

            result shouldContain "已在归一化坐标"
            startXSlot.captured shouldBe 600
            startYSlot.captured shouldBe 1800
            endXSlot.captured shouldBe 600
            endYSlot.captured shouldBe 720
        }
    }
})

private fun mockScreenContext(
    screenWidth: Int,
    screenHeight: Int,
    resourceWidth: Int = screenWidth,
    resourceHeight: Int = screenHeight
): Context {
    val mockContext = mockk<Context>(relaxed = true)
    val mockWindowManager = mockk<WindowManager>(relaxed = true)
    val mockDisplay = mockk<android.view.Display>(relaxed = true)
    val mockMetrics = DisplayMetrics().apply {
        widthPixels = resourceWidth
        heightPixels = resourceHeight
    }

    every { mockContext.getSystemService(Context.WINDOW_SERVICE) } returns mockWindowManager
    every { mockContext.resources.displayMetrics } returns mockMetrics
    every { mockWindowManager.defaultDisplay } returns mockDisplay
    every { mockDisplay.getRealMetrics(any()) } answers {
        val metrics = firstArg<DisplayMetrics>()
        metrics.widthPixels = screenWidth
        metrics.heightPixels = screenHeight
    }

    return mockContext
}
