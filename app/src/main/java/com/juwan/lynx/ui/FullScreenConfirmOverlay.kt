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

package com.juwan.lynx.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Full-screen system overlay confirmation.
 *
 * This is the only confirmation channel: no in-app card and no notification fallback.
 */
class FullScreenConfirmOverlay(
    context: Context
) {
    private val tag = "LynxFlow"
    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var overlayView: View? = null

    suspend fun request(actionDescription: String, timeoutMs: Long = 60_000L): Boolean {
        if (!canDrawOverlays()) {
            Log.w(tag, "stage=confirm_overlay_skip reason=missing_overlay_permission")
            return false
        }
        Log.i(tag, "stage=confirm_overlay_request timeout_ms=$timeoutMs action=${actionDescription.take(200)}")
        val decision = CompletableDeferred<Boolean>()

        mainHandler.post {
            showOverlay(
                actionDescription = actionDescription,
                onApprove = { resolveAndDismiss(decision, true) },
                onReject = { resolveAndDismiss(decision, false) }
            )
        }

        val result = withTimeoutOrNull(timeoutMs) { decision.await() } ?: false
        if (!result && !decision.isCompleted) {
            Log.w(tag, "stage=confirm_overlay_timeout timeout_ms=$timeoutMs")
        }
        mainHandler.post { dismissOverlay() }
        return result
    }

    private fun resolveAndDismiss(
        decision: CompletableDeferred<Boolean>,
        approved: Boolean
    ) {
        if (!decision.isCompleted) {
            decision.complete(approved)
            Log.i(
                tag,
                "stage=confirm_overlay_decision decision=${if (approved) "approve" else "reject"}"
            )
        }
        dismissOverlay()
    }

    private fun showOverlay(
        actionDescription: String,
        onApprove: () -> Unit,
        onReject: () -> Unit
    ) {
        dismissOverlay()

        val root = FrameLayout(appContext).apply {
            setBackgroundColor(Color.parseColor("#A6000000"))
            isClickable = true
            isFocusable = true
        }

        val card = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(
                fillColor = Color.parseColor("#FAFAFC"),
                cornerRadiusDp = 24
            )
            setPadding(dp(20), dp(20), dp(20), dp(18))
            elevation = 20f
        }

        val header = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(
                fillColor = Color.parseColor("#ECE5FF"),
                cornerRadiusDp = 16
            )
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        val badge = TextView(appContext).apply {
            text = "安全确认"
            textSize = 12f
            setTextColor(Color.parseColor("#6750A4"))
            setTypeface(typeface, Typeface.BOLD)
        }

        val title = TextView(appContext).apply {
            text = "Lynx Agent 操作确认"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#2A1E4A"))
            setPadding(0, dp(4), 0, 0)
        }

        val hint = TextView(appContext).apply {
            text = "敏感操作请求，请确认是否执行"
            textSize = 14f
            setTextColor(Color.parseColor("#6A5D85"))
            setPadding(0, dp(4), 0, 0)
        }

        val content = TextView(appContext).apply {
            text = actionDescription
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#1E1B27"))
            setLineSpacing(dp(4).toFloat(), 1.15f)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedRect(
                fillColor = Color.parseColor("#F2EEF9"),
                cornerRadiusDp = 14,
                strokeColor = Color.parseColor("#E4DAF6"),
                strokeWidthDp = 1
            )
        }

        val scroll = ScrollView(appContext).apply {
            isVerticalScrollBarEnabled = true
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val buttonRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        }

        val rejectButton = Button(appContext).apply {
            text = "拒绝"
            textSize = 16f
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#B3261E"))
            background = roundedRect(
                fillColor = Color.parseColor("#FDECEA"),
                cornerRadiusDp = 999,
                strokeColor = Color.parseColor("#F3C7C3"),
                strokeWidthDp = 1
            )
            minHeight = dp(48)
            setOnClickListener { onReject() }
        }

        val approveButton = Button(appContext).apply {
            text = "确认"
            textSize = 16f
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            background = roundedRect(
                fillColor = Color.parseColor("#6750A4"),
                cornerRadiusDp = 999
            )
            setTextColor(Color.WHITE)
            minHeight = dp(48)
            setOnClickListener { onApprove() }
        }

        val buttonParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            weight = 1f
        }
        val gapParams = LinearLayout.LayoutParams(dp(10), 1)

        buttonRow.addView(rejectButton, buttonParams)
        buttonRow.addView(View(appContext), gapParams)
        buttonRow.addView(approveButton, buttonParams)

        header.addView(
            badge,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        header.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        header.addView(
            hint,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        card.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        card.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(220)
            ).apply {
                topMargin = dp(12)
            }
        )
        card.addView(
            buttonRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val cardWidth = (appContext.resources.displayMetrics.widthPixels * 0.9f).toInt()
            .coerceAtMost(dp(520))
        root.addView(
            card,
            FrameLayout.LayoutParams(
                cardWidth,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        try {
            windowManager.addView(root, params)
            overlayView = root
            Log.i(tag, "stage=confirm_overlay_shown")
        } catch (_: Exception) {
            overlayView = null
            Log.e(tag, "stage=confirm_overlay_show_failed")
            onReject()
        }
    }

    private fun dismissOverlay() {
        val view = overlayView ?: return
        overlayView = null
        Log.i(tag, "stage=confirm_overlay_dismiss")
        runCatching { windowManager.removeViewImmediate(view) }
    }

    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(appContext)
        } else {
            true
        }
    }

    private fun dp(value: Int): Int {
        val density = appContext.resources.displayMetrics.density
        return (value * density).toInt()
    }

    private fun roundedRect(
        fillColor: Int,
        cornerRadiusDp: Int,
        strokeColor: Int = Color.TRANSPARENT,
        strokeWidthDp: Int = 0
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = dp(cornerRadiusDp).toFloat()
            if (strokeWidthDp > 0) {
                setStroke(dp(strokeWidthDp), strokeColor)
            }
        }
    }
}
