package com.juwan.lynx.perception

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.juwan.lynx.state.OcrResult
import com.juwan.lynx.state.Point
import com.juwan.lynx.state.Rect
import java.util.concurrent.TimeUnit

/**
 * Concrete implementation of OcrEngine using Google ML Kit.
 * Configured with ChineseTextRecognizerOptions to support both Chinese and English text.
 */
class MlKitOcrEngine : OcrEngine {

    companion object {
        private const val TAG = "MlKitOcrEngine"
        // Generous timeout for synchronous OCR block
        private const val TIMEOUT_SECONDS = 5L
        val shared: MlKitOcrEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            MlKitOcrEngine()
        }
    }

    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    override fun recognize(bitmap: Bitmap): List<OcrResult> {
        val image = InputImage.fromBitmap(bitmap, 0)
        
        return try {
            val task = recognizer.process(image)
            // Await synchronously because capture() pipeline is synchronous with timeout protection
            val mlKitText = Tasks.await(task, TIMEOUT_SECONDS, TimeUnit.SECONDS)
            
            val results = mutableListOf<OcrResult>()
            
            for (block in mlKitText.textBlocks) {
                for (line in block.lines) {
                    val text = line.text
                    val boundingBox = line.boundingBox
                    
                    if (text.isNotBlank() && boundingBox != null) {
                        results.add(
                            OcrResult(
                                text = text,
                                bounds = Rect(
                                    left = boundingBox.left,
                                    top = boundingBox.top,
                                    right = boundingBox.right,
                                    bottom = boundingBox.bottom
                                )
                            )
                        )
                    }
                }
            }
            results
        } catch (e: Exception) {
            Log.e(TAG, "ML Kit OCR failed: ${e.message}", e)
            emptyList()
        }
    }
}
