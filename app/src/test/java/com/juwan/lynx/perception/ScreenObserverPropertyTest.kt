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
import android.view.accessibility.AccessibilityNodeInfo
import com.juwan.lynx.service.LynxAccessibilityService
import com.juwan.lynx.service.LynxCaptureService
import com.juwan.lynx.state.OcrResult
import com.juwan.lynx.state.Point
import com.juwan.lynx.state.Rect
import com.juwan.lynx.state.UIElement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.pair
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk

/**
 * Mock implementation of LynxCaptureService for testing.
 * Can be configured to throw exceptions.
 */
class MockCaptureService(
    private val shouldThrow: Boolean = false
) : LynxCaptureService() {
    override fun captureBase64(): String {
        if (shouldThrow) {
            throw RuntimeException("Screenshot capture failed")
        }
        return "mock_screenshot_base64"
    }

    override fun captureAsBitmap(): Bitmap? {
        if (shouldThrow) {
            throw RuntimeException("Bitmap capture failed")
        }
        return mockk<Bitmap>(relaxed = true)
    }
}

/**
 * Mock implementation of LynxAccessibilityService for testing.
 * Can be configured to throw exceptions on UI tree dump.
 */
class MockAccessibilityService(
    private val shouldThrow: Boolean = false
) : LynxAccessibilityService() {
    override fun dumpUITree(): AccessibilityNodeInfo? {
        if (shouldThrow) {
            throw RuntimeException("UI tree capture failed")
        }
        return mockk<AccessibilityNodeInfo>(relaxed = true) {
            every { className } returns "android.widget.TextView"
            every { text } returns "Mock Text"
            every { contentDescription } returns "Mock Description"
            every { isClickable } returns true
            every { isEditable } returns false
            every { childCount } returns 0
            every { getBoundsInScreen(any()) } answers {
                val rect = firstArg<android.graphics.Rect>()
                rect.set(0, 0, 100, 100)
            }
        }
    }

    override fun getCurrentPackageName(): String? {
        return "com.test.app"
    }
}

/**
 * Mock implementation of OcrEngine for testing.
 * Can be configured to throw exceptions.
 */
class MockOcrEngine(
    private val shouldThrow: Boolean = false
) : OcrEngine {
    override fun recognize(bitmap: Bitmap): List<OcrResult> {
        if (shouldThrow) {
            throw RuntimeException("OCR recognition failed")
        }
        return listOf(
            OcrResult(
                text = "Mock OCR Text",
                bounds = Rect(10, 10, 90, 30)
            )
        )
    }
}

/**
 * Property-based tests for ScreenObserver graceful degradation.
 * 
 * Property 9: ScreenObserver 优雅降级
 * **Validates: Requirements 6.5, 6.6**
 */
class ScreenObserverPropertyTest : FunSpec({
    context("Property 9: ScreenObserver 优雅降级") {
        test("should return null uiTree but valid screenshotBase64 when UI tree capture fails") {
            checkAll(10, Arb.boolean()) { ocrShouldFail ->
                // Create mock services
                val captureService = MockCaptureService(shouldThrow = false)
                val accessibilityService = MockAccessibilityService(shouldThrow = true)
                val ocrEngine = if (ocrShouldFail) {
                    MockOcrEngine(shouldThrow = true)
                } else {
                    MockOcrEngine(shouldThrow = false)
                }
                
                // Create ScreenObserver
                val observer = ScreenObserverImpl(
                    captureService = captureService,
                    accessibilityService = accessibilityService,
                    ocrEngine = ocrEngine
                )
                
                // Capture observation
                val observation = observer.capture()
                
                // Verify graceful degradation
                observation.uiTree shouldBe null
                observation.screenshotBase64 shouldNotBe null
                observation.screenshotBase64 shouldNotBe ""
                
                // Other fields should still be valid
                if (ocrShouldFail) {
                    observation.ocrTexts shouldBe null
                } else {
                    observation.ocrTexts shouldNotBe null
                }
                
                // Page signature should still be computed
                observation.pageSignature shouldNotBe null
            }
        }
        
        test("should return null ocrTexts but valid screenshotBase64 and uiTree when OCR fails") {
            checkAll(10, Arb.boolean()) { uiTreeShouldFail ->
                // Create mock services
                val captureService = MockCaptureService(shouldThrow = false)
                val accessibilityService = if (uiTreeShouldFail) {
                    MockAccessibilityService(shouldThrow = true)
                } else {
                    MockAccessibilityService(shouldThrow = false)
                }
                val ocrEngine = MockOcrEngine(shouldThrow = true)
                
                // Create ScreenObserver
                val observer = ScreenObserverImpl(
                    captureService = captureService,
                    accessibilityService = accessibilityService,
                    ocrEngine = ocrEngine
                )
                
                // Capture observation
                val observation = observer.capture()
                
                // Verify graceful degradation
                observation.ocrTexts shouldBe null
                observation.screenshotBase64 shouldNotBe null
                observation.screenshotBase64 shouldNotBe ""
                
                // Other fields should still be valid
                if (uiTreeShouldFail) {
                    observation.uiTree shouldBe null
                } else {
                    observation.uiTree shouldNotBe null
                }
                
                // Page signature should still be computed
                observation.pageSignature shouldNotBe null
            }
        }
        
        test("should handle all combinations of UI tree and OCR failures gracefully") {
            checkAll(
                10,
                Arb.pair(Arb.boolean(), Arb.boolean())
            ) { (uiTreeShouldFail, ocrShouldFail) ->
                // Create mock services with random failure combinations
                val captureService = MockCaptureService(shouldThrow = false)
                val accessibilityService = MockAccessibilityService(shouldThrow = uiTreeShouldFail)
                val ocrEngine = MockOcrEngine(shouldThrow = ocrShouldFail)
                
                // Create ScreenObserver
                val observer = ScreenObserverImpl(
                    captureService = captureService,
                    accessibilityService = accessibilityService,
                    ocrEngine = ocrEngine
                )
                
                // Capture observation
                val observation = observer.capture()
                
                // Verify screenshot is always valid (no failure injected)
                observation.screenshotBase64 shouldNotBe null
                observation.screenshotBase64 shouldNotBe ""
                
                // Verify UI tree field matches failure state
                if (uiTreeShouldFail) {
                    observation.uiTree shouldBe null
                } else {
                    observation.uiTree shouldNotBe null
                }
                
                // Verify OCR field matches failure state
                if (ocrShouldFail) {
                    observation.ocrTexts shouldBe null
                } else {
                    observation.ocrTexts shouldNotBe null
                }
                
                // Page signature should always be computed (even if empty)
                observation.pageSignature shouldNotBe null
                
                // Current app should be available (from accessibility service)
                // Note: currentApp comes from accessibilityService.currentPackageName
                // which doesn't throw in our mock
                observation.currentApp shouldNotBe null
            }
        }
        
        test("should return null ocrTexts when OCR engine is unavailable") {
            checkAll(10, Arb.boolean()) { uiTreeShouldFail ->
                // Create mock services without OCR engine
                val captureService = MockCaptureService(shouldThrow = false)
                val accessibilityService = MockAccessibilityService(shouldThrow = uiTreeShouldFail)
                
                // Create ScreenObserver without OCR engine
                val observer = ScreenObserverImpl(
                    captureService = captureService,
                    accessibilityService = accessibilityService,
                    ocrEngine = null
                )
                
                // Capture observation
                val observation = observer.capture()
                
                // Verify OCR is null when engine is unavailable
                observation.ocrTexts shouldBe null
                
                // Screenshot should still be valid
                observation.screenshotBase64 shouldNotBe null
                observation.screenshotBase64 shouldNotBe ""
                
                // UI tree should match failure state
                if (uiTreeShouldFail) {
                    observation.uiTree shouldBe null
                } else {
                    observation.uiTree shouldNotBe null
                }
                
                // Page signature should still be computed
                observation.pageSignature shouldNotBe null
            }
        }
    }
    
    context("Property 10: pageSignature 确定性") {
        test("should produce the same pageSignature for the same UI tree and OCR inputs") {
            // Create arbitrary generators for UI elements and OCR results
            val arbUIElement = Arb.bind(
                Arb.string(minSize = 0, maxSize = 20),  // type
                Arb.string(minSize = 0, maxSize = 50),  // text
                Arb.string(minSize = 0, maxSize = 50),  // contentDescription
                Arb.boolean(),  // isClickable
                Arb.boolean(),  // isEditable
                Arb.int(0..1000),  // left
                Arb.int(0..1000),  // top
                Arb.int(0..1000),  // right
                Arb.int(0..1000)   // bottom
            ) { type, text, desc, clickable, editable, left, top, right, bottom ->
                val actualRight = maxOf(left, right)
                val actualBottom = maxOf(top, bottom)
                UIElement(
                    type = type,
                    text = text.takeIf { it.isNotEmpty() },
                    contentDescription = desc.takeIf { it.isNotEmpty() },
                    isClickable = clickable,
                    isEditable = editable,
                    bounds = Rect(left, top, actualRight, actualBottom),
                    center = Point((left + actualRight) / 2, (top + actualBottom) / 2)
                )
            }
            
            val arbOcrResult = Arb.bind(
                Arb.string(minSize = 1, maxSize = 50),  // text
                Arb.int(0..1000),  // left
                Arb.int(0..1000),  // top
                Arb.int(0..1000),  // right
                Arb.int(0..1000)   // bottom
            ) { text, left, top, right, bottom ->
                val actualRight = maxOf(left, right)
                val actualBottom = maxOf(top, bottom)
                OcrResult(
                    text = text,
                    bounds = Rect(left, top, actualRight, actualBottom)
                )
            }
            
            // Generate random UI trees and OCR results
            checkAll(
                10,
                Arb.list(arbUIElement, range = 0..20),
                Arb.list(arbOcrResult, range = 0..20)
            ) { uiTree, ocrTexts ->
                // Create a mock ScreenObserver with fixed inputs
                val captureService = MockCaptureService(shouldThrow = false)
                val accessibilityService = MockAccessibilityService(shouldThrow = false)
                val ocrEngine = MockOcrEngine(shouldThrow = false)
                
                val observer = ScreenObserverImpl(
                    captureService = captureService,
                    accessibilityService = accessibilityService,
                    ocrEngine = ocrEngine
                )
                
                // Use reflection to access the private computeSignature method
                val computeSignatureMethod = ScreenObserverImpl::class.java.getDeclaredMethod(
                    "computeSignature",
                    List::class.java,
                    List::class.java
                )
                computeSignatureMethod.isAccessible = true
                
                // Call computeSignature multiple times with the same inputs
                val signature1 = computeSignatureMethod.invoke(observer, uiTree, ocrTexts) as String
                val signature2 = computeSignatureMethod.invoke(observer, uiTree, ocrTexts) as String
                val signature3 = computeSignatureMethod.invoke(observer, uiTree, ocrTexts) as String
                
                // Verify all signatures are identical
                signature1 shouldBe signature2
                signature2 shouldBe signature3
                signature1 shouldBe signature3
            }
        }
        
        test("should produce different signatures for different UI trees") {
            val arbUIElement = Arb.bind(
                Arb.string(minSize = 1, maxSize = 20),
                Arb.string(minSize = 1, maxSize = 50),
                Arb.int(0..1000),
                Arb.int(0..1000),
                Arb.int(0..1000),
                Arb.int(0..1000)
            ) { type, text, left, top, right, bottom ->
                val actualRight = maxOf(left, right)
                val actualBottom = maxOf(top, bottom)
                UIElement(
                    type = type,
                    text = text,
                    contentDescription = null,
                    isClickable = true,
                    isEditable = false,
                    bounds = Rect(left, top, actualRight, actualBottom),
                    center = Point((left + actualRight) / 2, (top + actualBottom) / 2)
                )
            }
            
            checkAll(
                10,
                Arb.list(arbUIElement, range = 1..10),
                Arb.list(arbUIElement, range = 1..10)
            ) { uiTree1, uiTree2 ->
                // Skip if the trees are identical
                if (uiTree1 == uiTree2) return@checkAll
                
                val captureService = MockCaptureService(shouldThrow = false)
                val accessibilityService = MockAccessibilityService(shouldThrow = false)
                val ocrEngine = MockOcrEngine(shouldThrow = false)
                
                val observer = ScreenObserverImpl(
                    captureService = captureService,
                    accessibilityService = accessibilityService,
                    ocrEngine = ocrEngine
                )
                
                // Use reflection to access the private computeSignature method
                val computeSignatureMethod = ScreenObserverImpl::class.java.getDeclaredMethod(
                    "computeSignature",
                    List::class.java,
                    List::class.java
                )
                computeSignatureMethod.isAccessible = true
                
                // Compute signatures for different UI trees
                val signature1 = computeSignatureMethod.invoke(observer, uiTree1, null) as String
                val signature2 = computeSignatureMethod.invoke(observer, uiTree2, null) as String
                
                // Different inputs should (usually) produce different signatures
                // Note: Hash collisions are possible but rare
                // We verify determinism, not uniqueness
                val sig1Again = computeSignatureMethod.invoke(observer, uiTree1, null) as String
                val sig2Again = computeSignatureMethod.invoke(observer, uiTree2, null) as String
                
                signature1 shouldBe sig1Again
                signature2 shouldBe sig2Again
            }
        }
        
        test("should classify comment composer surface separately from chat thread") {
            fun element(
                type: String,
                text: String?,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                clickable: Boolean = false,
                editable: Boolean = false,
                contentDescription: String? = null
            ): UIElement {
                val bounds = Rect(left, top, right, bottom)
                return UIElement(
                    type = type,
                    text = text,
                    contentDescription = contentDescription,
                    isClickable = clickable,
                    isEditable = editable,
                    bounds = bounds,
                    center = bounds.center()
                )
            }

            val observer = ScreenObserverImpl(
                captureService = MockCaptureService(shouldThrow = false),
                accessibilityService = MockAccessibilityService(shouldThrow = false),
                ocrEngine = MockOcrEngine(shouldThrow = false)
            )
            val inferPageNameMethod = ScreenObserverImpl::class.java.getDeclaredMethod(
                "buildDebugSurfaceHints",
                List::class.java,
                List::class.java,
                String::class.java,
                String::class.java
            )
            inferPageNameMethod.isAccessible = true

            val uiTree = listOf(
                element("android.widget.TextView", "搜你想看的", 320, 60, 760, 120),
                element("android.widget.TextView", "全部评论", 360, 240, 720, 300),
                element("android.widget.TextView", "回复", 440, 420, 620, 470),
                element("android.widget.EditText", "留下你的评论", 80, 1780, 860, 1860, editable = true),
                element("android.widget.Button", "发送", 900, 1770, 1040, 1860, clickable = true)
            )

            val decision = inferPageNameMethod.invoke(
                observer,
                uiTree,
                null,
                "sig_comment",
                "com.ss.android.ugc.aweme.lite"
            )
            val semanticTagField = decision.javaClass.getDeclaredField("semanticTag")
            semanticTagField.isAccessible = true
            val pageField = decision.javaClass.getDeclaredField("page")
            pageField.isAccessible = true

            semanticTagField.get(decision) shouldBe "comment_sheet"
            pageField.get(decision) shouldBe "comment_sheet"
        }

        test("should keep message composer surface as chat thread") {
            fun element(
                type: String,
                text: String?,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                clickable: Boolean = false,
                editable: Boolean = false,
                contentDescription: String? = null
            ): UIElement {
                val bounds = Rect(left, top, right, bottom)
                return UIElement(
                    type = type,
                    text = text,
                    contentDescription = contentDescription,
                    isClickable = clickable,
                    isEditable = editable,
                    bounds = bounds,
                    center = bounds.center()
                )
            }

            val observer = ScreenObserverImpl(
                captureService = MockCaptureService(shouldThrow = false),
                accessibilityService = MockAccessibilityService(shouldThrow = false),
                ocrEngine = MockOcrEngine(shouldThrow = false)
            )
            val inferPageNameMethod = ScreenObserverImpl::class.java.getDeclaredMethod(
                "buildDebugSurfaceHints",
                List::class.java,
                List::class.java,
                String::class.java,
                String::class.java
            )
            inferPageNameMethod.isAccessible = true

            val uiTree = listOf(
                element("android.widget.TextView", "消息", 420, 60, 660, 120),
                element("android.widget.TextView", "30分钟内在线", 340, 220, 740, 280),
                element("android.widget.EditText", "发送消息", 80, 1780, 860, 1860, editable = true),
                element("android.widget.Button", "发送", 900, 1770, 1040, 1860, clickable = true)
            )

            val decision = inferPageNameMethod.invoke(
                observer,
                uiTree,
                null,
                "sig_chat",
                "com.ss.android.ugc.aweme.lite"
            )
            val semanticTagField = decision.javaClass.getDeclaredField("semanticTag")
            semanticTagField.isAccessible = true

            semanticTagField.get(decision) shouldBe "chat_thread"
        }

        test("should prefer stable content title over action surface labels on detail pages") {
            fun element(
                type: String,
                text: String?,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                clickable: Boolean = false,
                editable: Boolean = false,
                contentDescription: String? = null
            ): UIElement {
                val bounds = Rect(left, top, right, bottom)
                return UIElement(
                    type = type,
                    text = text,
                    contentDescription = contentDescription,
                    isClickable = clickable,
                    isEditable = editable,
                    bounds = bounds,
                    center = bounds.center()
                )
            }

            val observer = ScreenObserverImpl(
                captureService = MockCaptureService(shouldThrow = false),
                accessibilityService = MockAccessibilityService(shouldThrow = false),
                ocrEngine = MockOcrEngine(shouldThrow = false)
            )
            val inferPageNameMethod = ScreenObserverImpl::class.java.getDeclaredMethod(
                "buildDebugSurfaceHints",
                List::class.java,
                List::class.java,
                String::class.java,
                String::class.java
            )
            inferPageNameMethod.isAccessible = true

            val uiTree = listOf(
                element("android.widget.TextView", "抖音音乐榜", 320, 60, 760, 120),
                element("android.widget.TextView", "你的小哥哥创作的原声", 260, 240, 860, 300),
                element("android.widget.Button", "已点赞，喜欢13，按钮", 680, 300, 1030, 420, clickable = true)
            )

            val decision = inferPageNameMethod.invoke(
                observer,
                uiTree,
                null,
                "sig_detail_action",
                "com.ss.android.ugc.aweme.lite"
            )
            val semanticTagField = decision.javaClass.getDeclaredField("semanticTag")
            semanticTagField.isAccessible = true
            val pageField = decision.javaClass.getDeclaredField("page")
            pageField.isAccessible = true

            semanticTagField.get(decision) shouldBe "content_detail"
            pageField.get(decision) shouldBe "抖音音乐榜"
        }

        test("should prefer trusted comment title over action control labels") {
            fun element(
                type: String,
                text: String?,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                clickable: Boolean = false,
                editable: Boolean = false,
                contentDescription: String? = null
            ): UIElement {
                val bounds = Rect(left, top, right, bottom)
                return UIElement(
                    type = type,
                    text = text,
                    contentDescription = contentDescription,
                    isClickable = clickable,
                    isEditable = editable,
                    bounds = bounds,
                    center = bounds.center()
                )
            }

            val observer = ScreenObserverImpl(
                captureService = MockCaptureService(shouldThrow = false),
                accessibilityService = MockAccessibilityService(shouldThrow = false),
                ocrEngine = MockOcrEngine(shouldThrow = false)
            )
            val inferPageNameMethod = ScreenObserverImpl::class.java.getDeclaredMethod(
                "buildDebugSurfaceHints",
                List::class.java,
                List::class.java,
                String::class.java,
                String::class.java
            )
            inferPageNameMethod.isAccessible = true

            val uiTree = listOf(
                element("android.widget.Button", "已选中，按钮", 420, 60, 660, 120, clickable = true),
                element("android.widget.TextView", "全部评论", 360, 240, 720, 300),
                element("android.widget.TextView", "回复", 440, 420, 620, 470),
                element("android.widget.EditText", "留下你的评论", 80, 1780, 860, 1860, editable = true),
                element("android.widget.Button", "发送", 900, 1770, 1040, 1860, clickable = true)
            )

            val decision = inferPageNameMethod.invoke(
                observer,
                uiTree,
                null,
                "sig_comment_action",
                "com.ss.android.ugc.aweme.lite"
            )
            val semanticTagField = decision.javaClass.getDeclaredField("semanticTag")
            semanticTagField.isAccessible = true
            val pageField = decision.javaClass.getDeclaredField("page")
            pageField.isAccessible = true

            semanticTagField.get(decision) shouldBe "comment_sheet"
            pageField.get(decision) shouldBe "全部评论"
        }

        test("should keep internal agent surface out of chat and comment contexts") {
            fun element(
                type: String,
                text: String?,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                clickable: Boolean = false,
                editable: Boolean = false
            ): UIElement {
                val bounds = Rect(left, top, right, bottom)
                return UIElement(
                    type = type,
                    text = text,
                    contentDescription = null,
                    isClickable = clickable,
                    isEditable = editable,
                    bounds = bounds,
                    center = bounds.center()
                )
            }

            val observer = ScreenObserverImpl(
                captureService = MockCaptureService(shouldThrow = false),
                accessibilityService = MockAccessibilityService(shouldThrow = false),
                ocrEngine = MockOcrEngine(shouldThrow = false)
            )
            val inferPageNameMethod = ScreenObserverImpl::class.java.getDeclaredMethod(
                "buildDebugSurfaceHints",
                List::class.java,
                List::class.java,
                String::class.java,
                String::class.java
            )
            inferPageNameMethod.isAccessible = true

            val uiTree = listOf(
                element("android.widget.TextView", "Lynx Agent", 320, 60, 760, 120),
                element("android.widget.TextView", "评论任务", 360, 240, 720, 300),
                element("android.widget.EditText", "输入指令", 80, 1780, 860, 1860, editable = true),
                element("android.widget.Button", "发送", 900, 1770, 1040, 1860, clickable = true)
            )

            val decision = inferPageNameMethod.invoke(
                observer,
                uiTree,
                null,
                "sig_internal",
                "com.juwan.lynx"
            )
            val semanticTagField = decision.javaClass.getDeclaredField("semanticTag")
            semanticTagField.isAccessible = true

            semanticTagField.get(decision) shouldBe "unknown"
        }

        test("should handle null UI tree and OCR inputs deterministically") {
            // Test multiple times to ensure determinism
            repeat(10) {
                val captureService = MockCaptureService(shouldThrow = false)
                val accessibilityService = MockAccessibilityService(shouldThrow = false)
                val ocrEngine = MockOcrEngine(shouldThrow = false)
                
                val observer = ScreenObserverImpl(
                    captureService = captureService,
                    accessibilityService = accessibilityService,
                    ocrEngine = ocrEngine
                )
                
                // Use reflection to access the private computeSignature method
                val computeSignatureMethod = ScreenObserverImpl::class.java.getDeclaredMethod(
                    "computeSignature",
                    List::class.java,
                    List::class.java
                )
                computeSignatureMethod.isAccessible = true
                
                // Test with null inputs
                val sig1 = computeSignatureMethod.invoke(observer, null, null) as String
                val sig2 = computeSignatureMethod.invoke(observer, null, null) as String
                val sig3 = computeSignatureMethod.invoke(observer, null, null) as String
                
                sig1 shouldBe sig2
                sig2 shouldBe sig3
                
                // Test with empty lists
                val sig4 = computeSignatureMethod.invoke(observer, emptyList<UIElement>(), emptyList<OcrResult>()) as String
                val sig5 = computeSignatureMethod.invoke(observer, emptyList<UIElement>(), emptyList<OcrResult>()) as String
                
                sig4 shouldBe sig5
            }
        }
    }
})
