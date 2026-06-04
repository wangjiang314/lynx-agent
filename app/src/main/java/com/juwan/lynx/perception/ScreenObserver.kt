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
import android.util.Base64
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.juwan.lynx.agent.FlowTraceLogger
import com.juwan.lynx.agent.TraceLogFields
import com.juwan.lynx.service.LynxAccessibilityService
import com.juwan.lynx.service.LynxCaptureService
import com.juwan.lynx.state.CapabilityState
import com.juwan.lynx.state.ElementRole
import com.juwan.lynx.state.Observation
import com.juwan.lynx.state.OcrResult
import com.juwan.lynx.state.PageObservationLogging
import com.juwan.lynx.state.Point
import com.juwan.lynx.state.Rect
import com.juwan.lynx.state.SemanticElement
import com.juwan.lynx.state.UIElement
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * ScreenObserver implementation that captures screen state.
 * Integrates screenshot, UI tree, and OCR data into a unified observation.
 * Provides graceful degradation when UI tree or OCR fails.
 *
 * @param captureService Service for capturing screenshots
 * @param accessibilityService Service for accessing UI tree
 * @param ocrEngine Optional OCR engine for text recognition
 */
class ScreenObserverImpl(
    private val captureService: LynxCaptureService,
    private val accessibilityService: LynxAccessibilityService,
    private val ocrEngine: OcrEngine? = null
) : com.juwan.lynx.state.ScreenObserver {

    companion object {
        private const val TAG = "ScreenObserver"
        private const val SEMANTIC_UNKNOWN = "unknown"
        private const val SEMANTIC_CHAT_THREAD = "chat_thread"
        private const val SEMANTIC_COMMENT_SHEET = "comment_sheet"
        private const val SEMANTIC_MESSAGE_LIST = "message_list"
        private const val SEMANTIC_SEARCH_LIST = "search_list"
        private const val SEMANTIC_FEED = "feed"
        private const val SEMANTIC_CONTENT_DETAIL = "content_detail"
    }

    /**
     * Capture current screen state.
     * Combines screenshot, UI tree, and OCR data.
     * Gracefully handles failures in UI tree or OCR capture.
     *
     * @return Observation containing screenshot, UI tree, OCR, and page signature
     */
    override fun capture(): Observation {
        try {
            // Capture screenshot
            val screenshotBase64 = captureScreenshot()

            // Capture UI tree (graceful degradation if fails)
            val uiTree = try {
                captureUITree()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to capture UI tree: ${e.message}")
                null
            }

            // Capture OCR results (graceful degradation if unavailable or fails)
            val ocrTexts = if (ocrEngine != null) {
                try {
                    captureOCR(screenshotBase64)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to capture OCR: ${e.message}")
                    null
                }
            } else {
                null
            }

            val semanticElements = buildSemanticElements(uiTree, ocrTexts)

            // Get current app and page info
            val currentApp = accessibilityService.currentPackageName

            // Compute page signature first; debug hints may use it as a weak fallback.
            val pageSignature = computeSignature(uiTree, ocrTexts)

            // Build weak debug-only surface hints from UI/OCR signals.
            val debugHints = buildDebugSurfaceHints(
                uiTree = uiTree,
                ocrTexts = ocrTexts,
                pageSignature = pageSignature,
                currentApp = currentApp
            )
            logDebugSurfaceHints(
                currentApp = currentApp,
                pageSignature = pageSignature,
                uiTree = uiTree,
                ocrTexts = ocrTexts,
                hints = debugHints
            )

            return Observation(
                screenshotBase64 = screenshotBase64,
                uiTree = uiTree,
                ocrTexts = ocrTexts,
                pageSignature = pageSignature,
                currentApp = currentApp,
                debugPageLabel = debugHints.page,
                debugPageSemanticTag = debugHints.semanticTag,
                debugPageSemanticConfidence = debugHints.semanticConfidence,
                debugPageTitleHint = debugHints.titleHint,
                pageAffordances = debugHints.affordances,
                pageCapabilities = debugHints.capabilities,
                debugPageSignals = debugHints.signals,
                semanticElements = semanticElements
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing observation: ${e.message}", e)
            // Return minimal observation on critical failure
            return Observation(
                screenshotBase64 = "",
                uiTree = null,
                ocrTexts = null,
                pageSignature = "",
                currentApp = null,
                debugPageLabel = null,
                debugPageSemanticTag = null,
                debugPageSemanticConfidence = 0f,
                debugPageTitleHint = null,
                pageAffordances = emptyList(),
                pageCapabilities = emptyList(),
                debugPageSignals = emptyList()
            )
        }
    }

    /**
     * Capture screenshot and convert to Base64.
     *
     * @return Base64-encoded screenshot
     */
    private fun captureScreenshot(): String {
        return try {
            val refreshed = captureService.refreshScreenshotBlocking(1800L)
            val base64 = captureService.captureBase64()
            if (base64.isNotBlank()) {
                val hash = base64.hashCode().toString(16)
                Log.d(TAG, "Screenshot captured: refreshed=$refreshed, len=${base64.length}, hash=$hash")
            } else {
                Log.w(TAG, "Screenshot captured empty: refreshed=$refreshed")
            }
            base64
        } catch (e: Exception) {
            Log.w(TAG, "Failed to capture screenshot: ${e.message}")
            ""
        }
    }

    /**
     * Capture UI tree from accessibility service.
     *
     * @return List of UI elements from the accessibility tree
     */
    private fun captureUITree(): List<UIElement> {
        val root = accessibilityService.dumpUITree()
            ?: return emptyList()

        val elements = mutableListOf<UIElement>()
        traverseUITree(root, elements)
        return elements
    }

    /**
     * Recursively traverse the accessibility tree and extract UI elements.
     *
     * @param node Current accessibility node
     * @param elements List to accumulate UI elements
     */
    private fun traverseUITree(node: AccessibilityNodeInfo?, elements: MutableList<UIElement>) {
        if (node == null) return

        try {
            // Extract element information
            val boundsRect = android.graphics.Rect()
            node.getBoundsInScreen(boundsRect)
            val rect = Rect(
                left = boundsRect.left,
                top = boundsRect.top,
                right = boundsRect.right,
                bottom = boundsRect.bottom
            )

            val center = Point(
                x = (boundsRect.left + boundsRect.right) / 2,
                y = (boundsRect.top + boundsRect.bottom) / 2
            )

            val element = UIElement(
                type = node.className?.toString() ?: "Unknown",
                text = node.text?.toString(),
                contentDescription = node.contentDescription?.toString(),
                isClickable = node.isClickable,
                isEditable = node.isEditable,
                isFocused = node.isFocused,
                bounds = rect,
                center = center
            )

            elements.add(element)

            // Recursively process children
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                traverseUITree(child, elements)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error processing accessibility node: ${e.message}")
        }
    }

    /**
     * Capture OCR results from screenshot.
     *
     * @param screenshotBase64 Base64-encoded screenshot
     * @return List of OCR results
     */
    private fun captureOCR(screenshotBase64: String): List<OcrResult> {
        if (ocrEngine == null) return emptyList()

        // Never hand OCR the shared bitmap instance from LynxCaptureService directly.
        // OCR owns and recycles only a private copy to avoid double-freeing the service cache.
        val bitmap = try {
            captureService.captureAsBitmap()
                ?.takeIf { !it.isRecycled }
                ?.let { sharedBitmap ->
                    sharedBitmap.copy(sharedBitmap.config ?: Bitmap.Config.ARGB_8888, false)
                }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clone screenshot bitmap for OCR: ${e.message}")
            null
        } ?: run {
            try {
                val decodedBytes = Base64.decode(screenshotBase64, Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
            } catch (e2: Exception) {
                Log.w(TAG, "Failed to get bitmap for OCR: ${e2.message}")
                return emptyList()
            }
        }

        return if (bitmap != null && !bitmap.isRecycled) {
            try {
                ocrEngine.recognize(bitmap)
            } finally {
                bitmap.recycle()
            }
        } else {
            emptyList()
        }
    }

    private fun buildSemanticElements(
        uiTree: List<UIElement>?,
        ocrTexts: List<OcrResult>?
    ): List<SemanticElement> {
        val elements = mutableListOf<SemanticElement>()

        uiTree.orEmpty().forEach { node ->
            elements += SemanticElement(
                id = generateSemanticId(node),
                role = inferSemanticRole(node),
                label = extractSemanticLabel(node),
                actionable = node.isClickable || node.isEditable,
                center = node.center,
                bounds = node.bounds,
                attributes = buildSemanticAttributes(node)
            )
        }

        ocrTexts.orEmpty()
            .filter { ocr -> elements.none { it.bounds.overlaps(ocr.bounds) } }
            .forEach { ocr ->
                elements += SemanticElement(
                    id = "ocr_${ocr.text.hashCode()}",
                    role = ElementRole.TEXT,
                    label = ocr.text,
                    actionable = false,
                    center = ocr.bounds.center(),
                    bounds = ocr.bounds
                )
            }

        return elements
    }

    private fun generateSemanticId(node: UIElement): String {
        return "ui_${node.type}_${node.bounds.left}_${node.bounds.top}_${node.text?.hashCode() ?: 0}"
    }

    private fun inferSemanticRole(node: UIElement): ElementRole {
        val type = node.type.lowercase()
        return when {
            type.contains("button") -> ElementRole.BUTTON
            type.contains("edittext") || type.contains("textinput") -> ElementRole.INPUT
            type.contains("checkbox") -> ElementRole.CHECKBOX
            type.contains("switch") -> ElementRole.SWITCH
            type.contains("tab") -> ElementRole.TAB
            type.contains("listitem") || type.contains("recycler") -> ElementRole.LIST_ITEM
            type.contains("link") || type.contains("hyperlink") -> ElementRole.LINK
            type.contains("image") || type.contains("imageview") -> ElementRole.IMAGE
            type.contains("text") -> ElementRole.TEXT
            type.contains("layout") || type.contains("view") -> ElementRole.CONTAINER
            else -> ElementRole.UNKNOWN
        }
    }

    private fun extractSemanticLabel(node: UIElement): String {
        return node.text?.trim()?.takeIf { it.isNotEmpty() }
            ?: node.contentDescription?.trim()?.takeIf { it.isNotEmpty() }
            ?: ""
    }

    private fun buildSemanticAttributes(node: UIElement): Map<String, String> {
        val attributes = mutableMapOf<String, String>()
        attributes["type"] = node.type
        if (node.isClickable) attributes["clickable"] = "true"
        if (node.isEditable) attributes["editable"] = "true"
        if (node.isFocused) attributes["focused"] = "true"
        node.contentDescription?.let { attributes["contentDescription"] = it }
        return attributes
    }

    /**
     * Compute page signature from UI tree and OCR results.
     * Uses hash of top 10 UI elements and top 10 OCR results.
     *
     * @param uiTree UI tree elements
     * @param ocrTexts OCR results
     * @return Hash-based page signature
     */
    private fun computeSignature(uiTree: List<UIElement>?, ocrTexts: List<OcrResult>?): String {
        val signatureBuilder = StringBuilder()

        // Add top 10 UI tree elements
        uiTree?.take(10)?.forEach { element ->
            signatureBuilder.append("${element.type}:${element.text};")
        }

        // Add top 10 OCR results
        ocrTexts?.take(10)?.forEach { ocr ->
            signatureBuilder.append(ocr.text)
        }

        // Compute hash
        val signature = signatureBuilder.toString()
        return if (signature.isEmpty()) {
            "empty"
        } else {
            signature.hashCode().toString(16)
        }
    }

    /**
     * Build weak debug-only surface hints from UI tree and OCR.
     *
     * @param uiTree UI tree elements
     * @return Weak debug hints; never runtime truth
     */
    private fun buildDebugSurfaceHints(
        uiTree: List<UIElement>?,
        ocrTexts: List<OcrResult>?,
        pageSignature: String,
        currentApp: String?
    ): DebugSurfaceHints {
        // 1) Rank UI-derived semantic labels instead of trusting a single top text.
        val uiCandidates = buildUiPageCandidates(uiTree)
        val chatComposer = detectChatComposer(uiTree)
        val ocrTitleHint = extractOcrTitleHint(ocrTexts)
        val titleHintCandidate = extractPageTitleHint(
            uiTree = uiTree,
            uiCandidates = uiCandidates,
            ocrTitleHint = ocrTitleHint,
            hasChatComposer = chatComposer
        )
        val titleHint = titleHintCandidate.label
        val affordances = buildPageAffordances(
            uiTree = uiTree,
            uiCandidates = uiCandidates,
            hasChatComposer = chatComposer
        )
        val capabilities = buildPageCapabilities(
            uiTree = uiTree,
            uiCandidates = uiCandidates,
            affordances = affordances
        )
        val semanticTag = inferPageSemanticTag(
            currentApp = currentApp,
            uiCandidates = uiCandidates,
            affordances = affordances
        )
        val semanticConfidence = inferPageSemanticConfidence(
            semanticTag = semanticTag,
            affordances = affordances,
            hasChatComposer = chatComposer
        )
        val signals = buildPageSignals(
            uiCandidates = uiCandidates,
            titleHint = titleHint,
            titleHintSource = titleHintCandidate.source,
            ocrTitleHint = ocrTitleHint,
            affordances = affordances,
            semanticTag = semanticTag,
            hasChatComposer = chatComposer
        )
        val strongUi = uiCandidates.firstOrNull { it.score >= 7 && !isNavigationLikeLabel(it.label) }
        val preferredPageLabel = resolvePreferredPageLabel(
            strongUi = strongUi,
            semanticTag = semanticTag,
            titleHint = titleHint
        )
        if (strongUi != null) {
            return DebugSurfaceHints(
                page = preferredPageLabel ?: strongUi.label,
                source = "strong_ui",
                strongUi = strongUi,
                uiCandidates = uiCandidates,
                composedHint = null,
                ocrTitleHint = ocrTitleHint,
                hasChatComposer = chatComposer,
                semanticTag = semanticTag,
                semanticConfidence = semanticConfidence,
                titleHint = titleHint,
                titleHintSource = titleHintCandidate.source,
                affordances = affordances,
                capabilities = capabilities,
                signals = signals
            )
        }

        // 2) Compose a weak text hint from top UI/OCR candidates when available.
        val composedHint = composePageHint(uiCandidates, ocrTexts)
        if (!composedHint.isNullOrBlank()) {
            return DebugSurfaceHints(
                page = composedHint,
                source = "composed",
                strongUi = null,
                uiCandidates = uiCandidates,
                composedHint = composedHint,
                ocrTitleHint = ocrTitleHint,
                hasChatComposer = chatComposer,
                semanticTag = semanticTag,
                semanticConfidence = semanticConfidence,
                titleHint = titleHint,
                titleHintSource = titleHintCandidate.source,
                affordances = affordances,
                capabilities = capabilities,
                signals = signals
            )
        }

        // 3) Stable debug fallback from app + signature.
        val appPart = currentApp
            ?.substringAfterLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: "app"

        val sigPart = pageSignature
            .takeIf { it.isNotBlank() && it != "empty" }
            ?.take(8)
            ?: "no_sig"

        return DebugSurfaceHints(
            page = "${appPart}_$sigPart",
            source = "fallback",
            strongUi = null,
            uiCandidates = uiCandidates,
            composedHint = null,
            ocrTitleHint = ocrTitleHint,
            hasChatComposer = chatComposer,
            semanticTag = semanticTag,
            semanticConfidence = semanticConfidence,
            titleHint = titleHint,
            titleHintSource = titleHintCandidate.source,
            affordances = affordances,
            capabilities = capabilities,
            signals = signals
        )
    }

    private data class PageCandidate(val label: String, val score: Int)
    private data class TitleHintCandidate(val label: String?, val source: String?)
    private data class DebugSurfaceHints(
        val page: String,
        val source: String,
        val strongUi: PageCandidate?,
        val uiCandidates: List<PageCandidate>,
        val composedHint: String?,
        val ocrTitleHint: String?,
        val hasChatComposer: Boolean,
        val semanticTag: String?,
        val semanticConfidence: Float,
        val titleHint: String?,
        val titleHintSource: String?,
        val affordances: List<String>,
        val capabilities: List<String>,
        val signals: List<String>
    )

    private fun buildUiPageCandidates(uiTree: List<UIElement>?): List<PageCandidate> {
        if (uiTree.isNullOrEmpty()) return emptyList()

        val maxRight = uiTree.maxOfOrNull { it.bounds.right }?.coerceAtLeast(1) ?: 1
        val maxBottom = uiTree.maxOfOrNull { it.bounds.bottom }?.coerceAtLeast(1) ?: 1
        val hasChatComposer = hasBottomComposerStructure(
            uiTree = uiTree,
            maxRight = maxRight,
            maxBottom = maxBottom
        )
        val labelScores = linkedMapOf<String, Int>()
        val labelFreq = linkedMapOf<String, Int>()
        val typeBandCounts = linkedMapOf<String, Int>()
        val shortTextBandCounts = linkedMapOf<Int, Int>()

        uiTree.forEach { element ->
            val band = (element.bounds.top / 80).coerceAtLeast(0)
            val typeBandKey = "${element.type.lowercase()}#$band"
            typeBandCounts[typeBandKey] = (typeBandCounts[typeBandKey] ?: 0) + 1

            val raw = extractNodeLabel(element) ?: return@forEach
            val label = normalizePageLabel(raw) ?: return@forEach
            val shortMessageLike = label.length in 2..6 &&
                element.type.contains("Text", ignoreCase = true) &&
                !element.isClickable &&
                !element.isEditable &&
                element.bounds.top in 240..1900
            if (shortMessageLike) {
                shortTextBandCounts[band] = (shortTextBandCounts[band] ?: 0) + 1
            }
        }

        uiTree.forEach { element ->
            val raw = extractNodeLabel(element) ?: return@forEach
            val label = normalizePageLabel(raw) ?: return@forEach
            var score = 0
            val band = (element.bounds.top / 80).coerceAtLeast(0)
            val typeBandKey = "${element.type.lowercase()}#$band"

            // Vertical prior: title/section headers are commonly in upper-middle area.
            score += when {
                element.bounds.top <= 320 -> 4
                element.bounds.top <= 880 -> 2
                else -> 0
            }

            // Penalize pure navigation controls and reward text semantics.
            if (isNavigationLikeLabel(label)) score -= 6
            if (isLikelyActionSurfaceLabel(label)) score -= 6
            if (element.type.contains("Text", ignoreCase = true)) score += 3
            if (element.type.contains("ActionBar", ignoreCase = true) ||
                element.type.contains("Toolbar", ignoreCase = true) ||
                element.type.contains("Title", ignoreCase = true)
            ) {
                score += 4
            }
            if (element.bounds.top <= 220 &&
                element.type.contains("Text", ignoreCase = true) &&
                label.length in 2..16 &&
                !isNavigationLikeLabel(label)
            ) {
                score += 3
            }
            val isTitleBarCandidate = element.bounds.top <= 180 &&
                element.bounds.bottom <= 300 &&
                label.length in 2..16 &&
                !isNavigationLikeLabel(label) &&
                !element.isEditable
            if (isTitleBarCandidate) {
                score += 5
                if (hasChatComposer) score += 3
            }
            val centerX = (element.bounds.left + element.bounds.right) / 2f
            val xRatio = centerX / maxRight.toFloat()
            if (element.bounds.top <= 260 && xRatio in 0.18f..0.82f) {
                score += 2
            }
            if (!element.isClickable) score += 2 else score -= 1
            if (element.isEditable) score -= 7

            val width = (element.bounds.right - element.bounds.left).coerceAtLeast(1)
            val widthRatio = width.toFloat() / maxRight.toFloat()
            if (widthRatio in 0.22f..0.88f) score += 2
            if (element.bounds.top <= 220 && xRatio in 0.28f..0.72f && widthRatio in 0.18f..0.72f) {
                // Top-center labels are typically page titles.
                score += 3
            }
            if (element.bounds.top <= 220 && element.isClickable && xRatio >= 0.72f) {
                // Top-right clickable controls (call/menu/action) are usually not page titles.
                score -= 6
            }
            if (element.bounds.top <= 220 && element.isClickable && widthRatio <= 0.2f) {
                score -= 2
            }
            if (element.bounds.top <= 200) score += 1
            if (element.bounds.top > (maxBottom * 0.45f).toInt()) score -= 2
            if (element.bounds.top > (maxBottom * 0.6f).toInt()) score -= 2

            val denseSameTypeBand = (typeBandCounts[typeBandKey] ?: 0) >= 3
            val denseShortBand = (shortTextBandCounts[band] ?: 0) >= 3
            val chatBubbleLike = element.type.contains("Text", ignoreCase = true) &&
                !element.isClickable &&
                !element.isEditable &&
                label.length in 2..8 &&
                element.bounds.top > 220 &&
                (denseSameTypeBand || denseShortBand)
            if (chatBubbleLike) score -= 5
            if (hasChatComposer) {
                val likelyConversationText = element.type.contains("Text", ignoreCase = true) &&
                    !element.isClickable &&
                    !element.isEditable &&
                    element.bounds.top > 220 &&
                    element.bounds.bottom < (maxBottom * 0.92f).toInt()
                if (likelyConversationText) score -= 6
            }
            if (isLikelyRepeatedChatText(label)) score -= 4
            if (label.contains("@")) score -= 4

            when {
                label.length in 2..12 -> score += 2
                label.length <= 24 -> score += 1
                else -> score -= 2
            }

            labelScores[label] = maxOf(labelScores[label] ?: Int.MIN_VALUE, score)
            labelFreq[label] = (labelFreq[label] ?: 0) + 1
        }

        return labelScores
            .map { (label, baseScore) ->
                val freqBonus = ((labelFreq[label] ?: 1) - 1).coerceAtMost(2)
                PageCandidate(label = label, score = baseScore + freqBonus)
            }
            .sortedByDescending { it.score }
    }

    private fun composePageHint(
        uiCandidates: List<PageCandidate>,
        ocrTexts: List<OcrResult>?
    ): String? {
        val ocrTitleParts = extractOcrTitleHint(ocrTexts)?.let { listOf(it) }.orEmpty()

        val uiParts = uiCandidates
            .asSequence()
            .filter { it.score >= 4 }
            .map { it.label }
            .filterNot { isNavigationLikeLabel(it) }
            .filterNot { isLikelyActionSurfaceLabel(it) }
            .filterNot { isLikelyTimestampLike(it) }
            .distinct()
            .take(2)
            .toList()

        val ocrParts = ocrTexts
            ?.asSequence()
            ?.filter { it.bounds.top > 180 }
            ?.map { it.text.trim() }
            ?.mapNotNull { normalizePageLabel(it) }
            ?.filterNot { isNavigationLikeLabel(it) }
            ?.filterNot { isLikelyActionSurfaceLabel(it) }
            ?.filterNot { isLikelyTimestampLike(it) }
            ?.take(2)
            ?.toList()
            .orEmpty()

        val parts = (ocrTitleParts + uiParts + ocrParts).distinct().take(2)
        if (parts.isEmpty()) return null
        return parts.joinToString("·")
    }

    private fun extractNodeLabel(element: UIElement): String? {
        return element.text?.trim()?.takeIf { it.isNotEmpty() }
            ?: element.contentDescription?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun normalizePageLabel(raw: String): String? {
        val cleaned = raw
            .replace(Regex("\\s+"), " ")
            .replace("|", " ")
            .trim()
            .trim(':', '：', '-', '—', ',', '，', '.', '。')

        if (cleaned.length !in 2..30) return null
        if (cleaned.contains("http", ignoreCase = true)) return null
        if (cleaned.contains("@")) return null
        if (cleaned.all { it.isDigit() }) return null
        if (cleaned.count { it.isDigit() } > cleaned.length / 2) return null
        return cleaned
    }

    private fun isNavigationLikeLabel(label: String): Boolean {
        val lower = label.lowercase()
        if (lower == "back" || lower == "search" || lower == "home" || lower == "menu") return true
        if (label == "返回" || label == "搜索" || label == "首页" || label == "更多") return true
        if (label.startsWith("返回") || label.contains("返回未读")) return true
        if (label.contains("侧边栏")) return true
        if (lower.contains("back") && lower.length <= 10) return true
        return false
    }

    private fun isLikelyRepeatedChatText(label: String): Boolean {
        val compact = label.replace(" ", "")
        if (compact.length < 4) return false
        val uniqueChars = compact.toSet().size
        if (uniqueChars <= 2) return true
        return Regex("""(.{1,2})\1{2,}""").containsMatchIn(compact)
    }

    private fun hasBottomComposerStructure(
        uiTree: List<UIElement>,
        maxRight: Int,
        maxBottom: Int
    ): Boolean {
        val editableBottom = uiTree.any { element ->
            val width = (element.bounds.right - element.bounds.left).coerceAtLeast(1)
            val widthRatio = width.toFloat() / maxRight.toFloat()
            element.isEditable &&
                element.bounds.top >= (maxBottom * 0.62f).toInt() &&
                widthRatio >= 0.24f
        }
        if (!editableBottom) return false
        val rightBottomClickable = uiTree.any { element ->
            val centerX = (element.bounds.left + element.bounds.right) / 2f
            val width = (element.bounds.right - element.bounds.left).coerceAtLeast(1)
            val widthRatio = width.toFloat() / maxRight.toFloat()
            element.isClickable &&
                element.bounds.top >= (maxBottom * 0.62f).toInt() &&
                centerX >= (maxRight * 0.7f) &&
                widthRatio <= 0.24f
        }
        return rightBottomClickable || editableBottom
    }

    private fun isLikelyTimestampLike(text: String): Boolean {
        val compact = text.replace(" ", "")
        if (Regex("""^\d{1,2}[:：]\d{2}$""").matches(compact)) return true
        if (Regex("""^(周[一二三四五六日天]|星期[一二三四五六日天])\d{0,2}[:：]?\d{0,2}$""").matches(compact)) return true
        if (Regex("""^\d{1,2}月\d{1,2}日$""").matches(compact)) return true
        if (compact in setOf("今天", "昨天", "前天")) return true
        return false
    }

    private fun isLikelyStatusLikeLabel(text: String): Boolean {
        val compact = text.replace(" ", "")
        if (compact.isBlank()) return false
        if (isLikelyTimestampLike(compact)) return true
        if (compact.contains("在线")) return true
        if (compact.contains("分钟前")) return true
        if (compact.contains("刚刚")) return true
        if (compact.contains("音视频通话")) return true
        if (compact.contains("头像")) return true
        return false
    }

    private fun isConversationThreadLabel(text: String): Boolean {
        val compact = text.replace(" ", "")
        if (compact.isBlank()) return false
        return listOf("消息", "私信", "发送消息", "输入消息", "说点什么", "音视频通话").any { token ->
            compact.contains(token)
        }
    }

    private fun isCommentInteractionLabel(text: String): Boolean {
        val compact = text.replace(" ", "")
        if (compact.isBlank()) return false
        return listOf("评论", "评论区", "发表评论", "写评论", "全部评论", "留下你的评论", "条评论", "回复").any { token ->
            compact.contains(token)
        }
    }

    private fun isLikelyCommentGenericLabel(text: String): Boolean {
        val compact = text.replace(" ", "")
        if (compact.isBlank()) return false
        if (isLikelyTimestampLike(compact)) return true
        if (compact.contains("搜你想看的")) return true
        if (compact == "搜索" || compact.contains("搜索，按钮")) return true
        if (compact.contains("暂停视频")) return true
        return false
    }

    private fun isLikelyActionSurfaceLabel(text: String): Boolean {
        val compact = text.replace(" ", "")
        if (compact.isBlank()) return false
        if (compact in setOf("发送", "提交", "保存", "完成", "发布", "下一步")) return true
        if (compact.contains("已点赞") || compact.contains("未点赞")) return true
        if (compact.contains("已选中") || compact.contains("未选中")) return true
        if (compact.contains("喜欢") && compact.contains("按钮")) return true
        if (compact.contains("按钮")) {
            val controlTokens = listOf("点赞", "喜欢", "发送", "评论", "私信", "播放", "发布", "提交", "保存", "回复", "关注")
            if (controlTokens.any { token -> compact.contains(token) }) return true
        }
        return false
    }

    private fun detectChatComposer(uiTree: List<UIElement>?): Boolean {
        if (uiTree.isNullOrEmpty()) return false
        val maxRight = uiTree.maxOfOrNull { it.bounds.right }?.coerceAtLeast(1) ?: 1
        val maxBottom = uiTree.maxOfOrNull { it.bounds.bottom }?.coerceAtLeast(1) ?: 1
        return hasBottomComposerStructure(uiTree, maxRight, maxBottom)
    }

    private fun extractOcrTitleHint(ocrTexts: List<OcrResult>?): String? {
        return ocrTexts
            ?.asSequence()
            ?.filter { it.bounds.top <= 180 && it.bounds.bottom <= 320 }
            ?.map { it.text.trim() }
            ?.mapNotNull { normalizePageLabel(it) }
            ?.filterNot { isNavigationLikeLabel(it) || isLikelyTimestampLike(it) || isLikelyActionSurfaceLabel(it) }
            ?.firstOrNull()
    }

    private fun extractPageTitleHint(
        uiTree: List<UIElement>?,
        uiCandidates: List<PageCandidate>,
        ocrTitleHint: String?,
        hasChatComposer: Boolean
    ): TitleHintCandidate {
        val maxRight = uiTree?.maxOfOrNull { it.bounds.right }?.coerceAtLeast(1) ?: 1
        val uiTitle = uiTree
            ?.asSequence()
            ?.filter { element ->
                element.bounds.top <= 220 &&
                    !element.isEditable &&
                    !isNavigationLikeLabel(element.text ?: element.contentDescription ?: "")
            }
            ?.mapNotNull { element -> extractNodeLabel(element)?.let { raw -> element to raw } }
            ?.mapNotNull { (element, raw) ->
                val normalized = normalizePageLabel(raw) ?: return@mapNotNull null
                val likelyChatBody = hasChatComposer &&
                    element.bounds.top > 180 &&
                    element.bounds.top < 1800 &&
                    !element.isClickable
                val centerX = (element.bounds.left + element.bounds.right) / 2f
                val nearCenter = centerX in (maxRight * 0.22f)..(maxRight * 0.78f)
                if (likelyChatBody || isLikelyTimestampLike(normalized)) return@mapNotNull null
                if (hasChatComposer && isLikelyStatusLikeLabel(normalized)) return@mapNotNull null
                if (isLikelyActionSurfaceLabel(normalized)) return@mapNotNull null
                if (hasChatComposer && normalized.length <= 2 && normalized.none { it.code in 0x4E00..0x9FFF }) return@mapNotNull null
                if (hasChatComposer && !nearCenter && normalized.length <= 8) return@mapNotNull null
                if (hasChatComposer && isLikelyChatBodyLikeLabel(normalized)) return@mapNotNull null
                normalized
            }
            ?.firstOrNull()
            ?.let { TitleHintCandidate(it, "ui_top") }

        if (uiTitle != null) return uiTitle

        val fallbackUiTitle = uiCandidates.firstOrNull {
                it.score >= 7 &&
                    !isNavigationLikeLabel(it.label) &&
                    !isLikelyActionSurfaceLabel(it.label) &&
                    !(hasChatComposer && isLikelyStatusLikeLabel(it.label)) &&
                    !(hasChatComposer && isLikelyChatBodyLikeLabel(it.label))
            }?.label
        if (!fallbackUiTitle.isNullOrBlank()) {
            return TitleHintCandidate(fallbackUiTitle, "ui_candidate")
        }

        val ocrTitle = ocrTitleHint?.takeUnless {
                hasChatComposer && (
                    isLikelyStatusLikeLabel(it) ||
                        isLikelyChatBodyLikeLabel(it) ||
                        (it.length <= 2 && it.none { ch -> ch.code in 0x4E00..0x9FFF })
                    )
            }
        return TitleHintCandidate(ocrTitle, if (ocrTitle != null) "ocr_top" else null)
    }

    private fun buildPageAffordances(
        uiTree: List<UIElement>?,
        uiCandidates: List<PageCandidate>,
        hasChatComposer: Boolean
    ): List<String> {
        if (uiTree.isNullOrEmpty()) return emptyList()
        val maxRight = uiTree.maxOfOrNull { it.bounds.right }?.coerceAtLeast(1) ?: 1
        val maxBottom = uiTree.maxOfOrNull { it.bounds.bottom }?.coerceAtLeast(1) ?: 1
        val affordances = linkedSetOf<String>()

        if (hasChatComposer) affordances += "has_bottom_composer"

        val hasSendButton = uiTree.any { element ->
            val label = extractNodeLabel(element).orEmpty()
            val centerX = (element.bounds.left + element.bounds.right) / 2f
            val width = (element.bounds.right - element.bounds.left).coerceAtLeast(1)
            val widthRatio = width.toFloat() / maxRight.toFloat()
            val compactBottomRight = element.isClickable &&
                element.bounds.top >= (maxBottom * 0.62f).toInt() &&
                centerX >= (maxRight * 0.72f) &&
                widthRatio <= 0.22f
            element.isClickable &&
                (
                    label.contains("发送") ||
                        label.contains("send", ignoreCase = true) ||
                        (hasChatComposer && compactBottomRight)
                    )
        }
        if (hasSendButton) affordances += "has_send_button"

        val hasSearchEntry = uiTree.any { element ->
            val label = extractNodeLabel(element).orEmpty()
            val topRegion = element.bounds.top <= 320
            topRegion && (
                element.isEditable ||
                    label.contains("搜索") ||
                    label.contains("查找") ||
                    label.contains("search", ignoreCase = true)
                )
        }
        if (hasSearchEntry) affordances += "has_search_entry"

        val listLikeClickableRows = uiTree.count { element ->
            val label = extractNodeLabel(element).orEmpty()
            element.isClickable &&
                element.bounds.top in 220 until (maxBottom * 0.88f).toInt() &&
                label.isNotBlank()
        }
        if (listLikeClickableRows >= 3) affordances += "has_list_cells"

        val hasBackNav = uiTree.any { element ->
            val label = extractNodeLabel(element).orEmpty()
            val centerX = (element.bounds.left + element.bounds.right) / 2f
            element.isClickable &&
                element.bounds.top <= 220 &&
                centerX <= (maxRight * 0.26f) &&
                (label.contains("返回") || label.contains("back", ignoreCase = true) || label.isBlank())
        }
        if (hasBackNav) affordances += "has_back_nav"

        val hasPrimaryCta = uiTree.any { element ->
            val width = (element.bounds.right - element.bounds.left).coerceAtLeast(1)
            val widthRatio = width.toFloat() / maxRight.toFloat()
            element.isClickable &&
                element.bounds.top >= (maxBottom * 0.6f).toInt() &&
                widthRatio >= 0.28f
        }
        if (hasPrimaryCta) affordances += "has_primary_cta"

        val hasMessageContainer = uiTree.any { element ->
            val label = extractNodeLabel(element).orEmpty()
            val topRegion = element.bounds.top <= 320
            topRegion &&
                !element.isClickable &&
                isMessageContainerLabel(label)
        }
        if (hasMessageContainer) {
            affordances += "has_message_container"
        }

        val hasCommentSurface = uiTree.any { element ->
            isCommentInteractionLabel(extractNodeLabel(element).orEmpty())
        } || uiCandidates.take(5).any { candidate ->
            isCommentInteractionLabel(candidate.label)
        }
        if (hasCommentSurface) {
            affordances += "has_comment_surface"
        }

        return affordances.toList()
    }

    private fun buildPageCapabilities(
        uiTree: List<UIElement>?,
        uiCandidates: List<PageCandidate>,
        affordances: List<String>
    ): List<String> {
        if (uiTree.isNullOrEmpty()) return emptyList()
        val capabilities = linkedSetOf<String>()
        val normalizedAffordances = affordances.toSet()

        if ("has_search_entry" in normalizedAffordances) {
            capabilities += CapabilityState.CAN_SEARCH
        }
        if ("has_list_cells" in normalizedAffordances) {
            capabilities += CapabilityState.CAN_OPEN_CANDIDATE
            capabilities += CapabilityState.CAN_SCROLL
        }
        if ("has_back_nav" in normalizedAffordances) {
            capabilities += CapabilityState.CAN_GO_BACK
        }
        if ("has_bottom_composer" in normalizedAffordances) {
            capabilities += CapabilityState.CAN_INPUT_TEXT
            capabilities += CapabilityState.HAS_BOTTOM_INPUT
        }
        if ("has_send_button" in normalizedAffordances) {
            capabilities += CapabilityState.CAN_SUBMIT_TEXT
            capabilities += CapabilityState.CAN_SUBMIT_COMMENT
        }
        if ("has_comment_surface" in normalizedAffordances) {
            capabilities += CapabilityState.CAN_OPEN_COMMENT
        }
        if ("has_primary_cta" in normalizedAffordances) {
            capabilities += CapabilityState.CAN_SCROLL
        }

        val labels = buildList {
            uiCandidates.take(12).forEach { add(it.label) }
            uiTree.take(24).forEach { element ->
                extractNodeLabel(element)?.let { add(it) }
            }
        }

        labels.forEach { label ->
            val compact = label.replace(" ", "")
            when {
                compact.contains("点赞") || compact.contains("喜欢") || compact.contains("like", ignoreCase = true) -> {
                    capabilities += CapabilityState.CAN_LIKE
                }
                compact.contains("评论") || compact.contains("回复") -> {
                    capabilities += CapabilityState.CAN_OPEN_COMMENT
                }
                compact.contains("发送") || compact.contains("发布") || compact.contains("提交") -> {
                    capabilities += CapabilityState.CAN_SUBMIT_TEXT
                    capabilities += CapabilityState.CAN_SUBMIT_COMMENT
                }
            }

            if (
                compact.contains("视频") ||
                compact.contains("播放") ||
                compact.contains("暂停") ||
                compact.contains("IP属地") ||
                compact.contains("搜你想看的") ||
                compact.contains("作品")
            ) {
                capabilities += CapabilityState.HAS_MEDIA_SURFACE
            }

            if (
                compact.contains("允许") ||
                compact.contains("稍后") ||
                compact.contains("仅在") ||
                compact.contains("知道了")
            ) {
                capabilities += CapabilityState.HAS_OVERLAY
            }
        }

        return capabilities.toList()
    }

    private fun inferPageSemanticTag(
        currentApp: String?,
        uiCandidates: List<PageCandidate>,
        affordances: List<String>
    ): String {
        if (currentApp.equals("com.juwan.lynx", ignoreCase = true)) {
            return SEMANTIC_UNKNOWN
        }
        val topLabel = uiCandidates.firstOrNull()?.label.orEmpty()
        val topLabels = uiCandidates.take(3).map { it.label }
        val hasBottomComposer = affordances.contains("has_bottom_composer")
        val hasSendButton = affordances.contains("has_send_button")
        val hasListCells = affordances.contains("has_list_cells")
        val hasSearchEntry = affordances.contains("has_search_entry")
        val hasMessageContainer = affordances.contains("has_message_container")
        val hasCommentSurface = affordances.contains("has_comment_surface")
        val hasConversationSignals = topLabels.any { label ->
            isLikelyStatusLikeLabel(label) ||
                isLikelyChatBodyLikeLabel(label) ||
                isConversationThreadLabel(label)
        }
        if (hasBottomComposer && hasSendButton && (hasMessageContainer || hasConversationSignals)) {
            return SEMANTIC_CHAT_THREAD
        }
        if (
            hasBottomComposer &&
            (hasSendButton || hasListCells) &&
            (hasCommentSurface || (hasListCells && !hasMessageContainer && !hasConversationSignals))
        ) {
            return SEMANTIC_COMMENT_SHEET
        }
        if (hasListCells && (hasMessageContainer || topLabels.any(::isMessageContainerLabel))) {
            return SEMANTIC_MESSAGE_LIST
        }
        if (hasSearchEntry) {
            return SEMANTIC_SEARCH_LIST
        }
        if (topLabel in setOf("关注", "推荐", "首页")) {
            return SEMANTIC_FEED
        }
        if (affordances.contains("has_primary_cta") && !hasListCells) {
            return SEMANTIC_CONTENT_DETAIL
        }
        return SEMANTIC_UNKNOWN
    }

    private fun inferPageSemanticConfidence(
        semanticTag: String,
        affordances: List<String>,
        hasChatComposer: Boolean
    ): Float {
        return when {
            semanticTag == SEMANTIC_CHAT_THREAD &&
                hasChatComposer &&
                affordances.contains("has_send_button") -> 0.95f
            semanticTag == SEMANTIC_CHAT_THREAD -> 0.82f
            semanticTag == SEMANTIC_COMMENT_SHEET &&
                hasChatComposer &&
                affordances.contains("has_send_button") -> 0.9f
            semanticTag == SEMANTIC_COMMENT_SHEET -> 0.78f
            semanticTag == SEMANTIC_MESSAGE_LIST && affordances.contains("has_list_cells") -> 0.8f
            semanticTag == SEMANTIC_SEARCH_LIST -> 0.72f
            semanticTag == SEMANTIC_FEED -> 0.65f
            semanticTag == SEMANTIC_CONTENT_DETAIL -> 0.62f
            else -> 0.35f
        }
    }

    private fun buildPageSignals(
        uiCandidates: List<PageCandidate>,
        titleHint: String?,
        titleHintSource: String?,
        ocrTitleHint: String?,
        affordances: List<String>,
        semanticTag: String,
        hasChatComposer: Boolean
    ): List<String> {
        val signals = linkedSetOf<String>()
        signals += "semantic:$semanticTag"
        if (hasChatComposer) signals += "bottom_composer"
        if (!titleHint.isNullOrBlank()) signals += "title:$titleHint"
        if (!titleHintSource.isNullOrBlank()) signals += "title_source:$titleHintSource"
        if (!ocrTitleHint.isNullOrBlank()) signals += "ocr_title:$ocrTitleHint"
        affordances.forEach { signals += it }
        uiCandidates.take(2).forEach { candidate ->
            signals += "ui:${candidate.label}:${candidate.score}"
        }
        return signals.toList()
    }

    private fun resolvePreferredPageLabel(
        strongUi: PageCandidate?,
        semanticTag: String,
        titleHint: String?
    ): String? {
        val label = strongUi?.label?.trim().orEmpty()
        if (label.isBlank()) return titleHint
        if (
            semanticTag == SEMANTIC_CHAT_THREAD &&
            (isLikelyStatusLikeLabel(label) || isLikelyChatBodyLikeLabel(label) || isLikelyActionSurfaceLabel(label))
        ) {
            return titleHint?.takeIf { !isLikelyStatusLikeLabel(it) && !isLikelyActionSurfaceLabel(it) }
                ?: SEMANTIC_CHAT_THREAD
        }
        if (
            semanticTag == SEMANTIC_COMMENT_SHEET &&
            (isLikelyStatusLikeLabel(label) || isLikelyChatBodyLikeLabel(label) || isLikelyCommentGenericLabel(label) || isLikelyActionSurfaceLabel(label))
        ) {
            return titleHint?.takeIf { isCommentInteractionLabel(it) } ?: SEMANTIC_COMMENT_SHEET
        }
        if (semanticTag == SEMANTIC_MESSAGE_LIST && !isMessageContainerLabel(label)) {
            return titleHint?.takeIf { isMessageContainerLabel(it) } ?: SEMANTIC_MESSAGE_LIST
        }
        if (semanticTag == SEMANTIC_CONTENT_DETAIL && isLikelyActionSurfaceLabel(label)) {
            return titleHint?.takeIf { !isLikelyStatusLikeLabel(it) && !isLikelyActionSurfaceLabel(it) && !isLikelyCommentGenericLabel(it) }
                ?: SEMANTIC_CONTENT_DETAIL
        }
        return titleHint?.takeIf {
            it.length < label.length &&
                !isLikelyStatusLikeLabel(it) &&
                !isLikelyActionSurfaceLabel(it)
        } ?: label
    }

    private fun isMessageContainerLabel(text: String): Boolean {
        val compact = text.replace(" ", "")
        if (compact.isBlank()) return false
        return compact == "消息" ||
            compact == "聊天" ||
            compact == "私信" ||
            compact == "消息页" ||
            compact == "消息列表"
    }

    private fun isLikelyChatBodyLikeLabel(text: String): Boolean {
        val compact = text.replace(" ", "")
        if (compact.length > 12) return true
        if (compact.contains("？") || compact.contains("?") || compact.contains("！") || compact.contains("!")) {
            return true
        }
        if (compact.contains("视频") || compact.contains("表情包")) return true
        return isLikelyRepeatedChatText(compact)
    }

    private fun logDebugSurfaceHints(
        currentApp: String?,
        pageSignature: String,
        uiTree: List<UIElement>?,
        ocrTexts: List<OcrResult>?,
        hints: DebugSurfaceHints
    ) {
        val loggedPage = PageObservationLogging.fromValues(
            debugPageLabel = hints.page,
            debugPageTitleHint = hints.titleHint,
            debugPageSemanticTag = hints.semanticTag,
            debugPageSignals = hints.signals
        )
        val topUi = hints.uiCandidates
            .take(3)
            .joinToString("/") { "${it.label}:${it.score}" }
            .ifBlank { "none" }
        val titleSource = PageObservationLogging.extractTitleSource(hints.signals)
        val kv = linkedMapOf<String, Any?>(
            "app" to (currentApp ?: "unknown"),
            "fact_affordances" to hints.affordances.joinToString(","),
            "fact_composer" to hints.hasChatComposer,
            "debug_source" to hints.source,
            "debug_semantic_tag" to (hints.semanticTag ?: "unknown"),
            "debug_semantic_conf" to hints.semanticConfidence,
            "debug_title_source" to titleSource,
            "debug_ui_top" to topUi,
            "debug_strong_ui" to (hints.strongUi?.let { "${it.label}:${it.score}" } ?: "none"),
            "debug_composed" to (hints.composedHint ?: "none"),
            "debug_ocr_title" to (hints.ocrTitleHint ?: "none"),
            "debug_sig" to pageSignature.take(8),
            "debug_ui_count" to (uiTree?.size ?: 0),
            "debug_ocr_count" to (ocrTexts?.size ?: 0)
        )
        TraceLogFields.putSurfaceHintFields(kv, loggedPage)
        FlowTraceLogger.event(
            stage = "page_observation",
            kv = kv
        )
    }
}

/**
 * Interface for OCR engine.
 * Implementations can use various OCR libraries (Tesseract, ML Kit, etc.)
 */
interface OcrEngine {
    /**
     * Recognize text in a bitmap image.
     *
     * @param bitmap Image bitmap
     * @return List of OCR results with text and bounds
     */
    fun recognize(bitmap: Bitmap): List<OcrResult>
}
