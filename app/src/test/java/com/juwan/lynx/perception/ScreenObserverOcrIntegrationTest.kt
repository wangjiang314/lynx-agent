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

package com.juwan.lynx.perception

import android.graphics.Bitmap
import android.util.Log
import com.juwan.lynx.service.LynxAccessibilityService
import com.juwan.lynx.service.LynxCaptureService
import com.juwan.lynx.state.OcrResult
import com.juwan.lynx.state.Rect
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * Test suite for OCR integration in ScreenObserver.
 * Validates Requirements 6.4: OCR engine integration with optional dependency.
 */
class ScreenObserverOcrIntegrationTest {
    
    private lateinit var mockCaptureService: LynxCaptureService
    private lateinit var mockAccessibilityService: LynxAccessibilityService
    
    @Before
    fun setup() {
        mockCaptureService = mockk()
        mockAccessibilityService = mockk()
        
        // Mock Android Log class
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }
    
    @Test
    fun `should capture OCR results when OCR engine is available`() {
        // Arrange
        val mockOcrEngine = mockk<OcrEngine>()
        val mockBitmap = mockk<Bitmap>()
        
        every { mockCaptureService.captureBase64() } returns "base64screenshot"
        every { mockCaptureService.captureAsBitmap() } returns mockBitmap
        every { mockAccessibilityService.dumpUITree() } returns null
        every { mockAccessibilityService.currentPackageName } returns "com.example.app"
        
        val ocrResults = listOf(
            OcrResult("Hello World", Rect(10, 20, 100, 50)),
            OcrResult("Test Text", Rect(10, 60, 100, 90))
        )
        every { mockOcrEngine.recognize(mockBitmap) } returns ocrResults
        every { mockBitmap.recycle() } returns Unit
        
        val screenObserver = ScreenObserverImpl(
            captureService = mockCaptureService,
            accessibilityService = mockAccessibilityService,
            ocrEngine = mockOcrEngine
        )
        
        // Act
        val observation = screenObserver.capture()
        
        // Assert
        assertNotNull(observation.ocrTexts)
        assertEquals(2, observation.ocrTexts?.size)
        assertEquals("Hello World", observation.ocrTexts?.get(0)?.text)
        assertEquals("Test Text", observation.ocrTexts?.get(1)?.text)
        
        verify { mockOcrEngine.recognize(mockBitmap) }
        verify { mockBitmap.recycle() }
    }
    
    @Test
    fun `should gracefully handle missing OCR engine`() {
        // Arrange
        every { mockCaptureService.captureBase64() } returns "base64screenshot"
        every { mockAccessibilityService.dumpUITree() } returns null
        every { mockAccessibilityService.currentPackageName } returns "com.example.app"
        
        val screenObserver = ScreenObserverImpl(
            captureService = mockCaptureService,
            accessibilityService = mockAccessibilityService,
            ocrEngine = null  // No OCR engine
        )
        
        // Act
        val observation = screenObserver.capture()
        
        // Assert
        assertNull(observation.ocrTexts)
        assertEquals("base64screenshot", observation.screenshotBase64)
    }
    
    @Test
    fun `should gracefully handle OCR engine failure`() {
        // Arrange
        val mockOcrEngine = mockk<OcrEngine>()
        val mockBitmap = mockk<Bitmap>()
        
        every { mockCaptureService.captureBase64() } returns "base64screenshot"
        every { mockCaptureService.captureAsBitmap() } returns mockBitmap
        every { mockAccessibilityService.dumpUITree() } returns null
        every { mockAccessibilityService.currentPackageName } returns "com.example.app"
        every { mockOcrEngine.recognize(mockBitmap) } throws RuntimeException("OCR failed")
        every { mockBitmap.recycle() } returns Unit
        
        val screenObserver = ScreenObserverImpl(
            captureService = mockCaptureService,
            accessibilityService = mockAccessibilityService,
            ocrEngine = mockOcrEngine
        )
        
        // Act
        val observation = screenObserver.capture()
        
        // Assert
        assertNull(observation.ocrTexts)
        assertEquals("base64screenshot", observation.screenshotBase64)
    }
    
    @Test
    fun `should expose OCR-only semantic elements directly from observation`() {
        // Arrange
        val mockOcrEngine = mockk<OcrEngine>()
        val mockBitmap = mockk<Bitmap>()
        every { mockCaptureService.captureBase64() } returns "base64screenshot"
        every { mockCaptureService.captureAsBitmap() } returns mockBitmap
        every { mockAccessibilityService.dumpUITree() } returns null
        every { mockAccessibilityService.currentPackageName } returns "com.example.app"

        val ocrResults = listOf(
            OcrResult("Button Text", Rect(10, 20, 100, 50)),
            OcrResult("Non-overlapping Text", Rect(200, 200, 300, 230))
        )
        every { mockOcrEngine.recognize(mockBitmap) } returns ocrResults
        every { mockBitmap.recycle() } returns Unit

        val screenObserver = ScreenObserverImpl(
            captureService = mockCaptureService,
            accessibilityService = mockAccessibilityService,
            ocrEngine = mockOcrEngine
        )

        // Act
        val observation = screenObserver.capture()

        // Assert
        assertEquals(2, observation.semanticElements.size)
        assertTrue(observation.semanticElements.any { it.label == "Button Text" })
        assertTrue(observation.semanticElements.any { it.label == "Non-overlapping Text" })
    }
}
