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
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.juwan.lynx.R

/**
 * StatusOverlay displays a floating window showing real-time agent status.
 * 
 * This overlay window uses TYPE_APPLICATION_OVERLAY to remain visible while
 * the Agent operates other apps. It displays:
 * - Agent's current thinking process (prompt summary)
 * - WorldState key information (current app, page, stuckCount)
 * - Operation execution status
 * - Cancel button for user to stop the agent
 *
 * Requirements: 15.2, 15.5, 15.7
 */
class StatusOverlay(private val context: Context) {
    
    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    
    private var overlayView: View? = null
    private var thinkingTextView: TextView? = null
    private var worldStateTextView: TextView? = null
    private var actionStatusTextView: TextView? = null
    private var cancelButton: Button? = null
    
    private var onCancelCallback: (() -> Unit)? = null
    
    /**
     * Show the status overlay window.
     * Creates and displays the floating window with initial empty state.
     */
    fun show() {
        if (overlayView != null) {
            // Already showing
            return
        }
        
        // Create layout parameters for overlay window
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 100
        }
        
        // Create overlay view
        overlayView = createOverlayView()
        
        // Add view to window manager
        windowManager.addView(overlayView, params)
    }
    
    /**
     * Update the status information displayed in the overlay.
     *
     * @param thinking Current agent thinking process or prompt summary
     * @param worldStateSummary Summary of WorldState (app, page, stuckCount)
     * @param actionStatus Current operation execution status
     */
    fun updateStatus(thinking: String, worldStateSummary: String, actionStatus: String) {
        thinkingTextView?.text = thinking
        worldStateTextView?.text = worldStateSummary
        actionStatusTextView?.text = actionStatus
    }
    
    /**
     * Show the cancel button and set its callback.
     *
     * @param onCancel Callback to invoke when user clicks cancel
     */
    fun showCancelButton(onCancel: () -> Unit) {
        onCancelCallback = onCancel
        cancelButton?.visibility = View.VISIBLE
    }
    
    /**
     * Hide the cancel button.
     */
    fun hideCancelButton() {
        cancelButton?.visibility = View.GONE
        onCancelCallback = null
    }
    
    /**
     * Dismiss the overlay window and clean up resources.
     */
    fun dismiss() {
        overlayView?.let { view ->
            windowManager.removeView(view)
        }
        overlayView = null
        thinkingTextView = null
        worldStateTextView = null
        actionStatusTextView = null
        cancelButton = null
        onCancelCallback = null
    }
    
    /**
     * Create the overlay view with all UI components.
     *
     * @return The created overlay view
     */
    private fun createOverlayView(): View {
        // Create container layout
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0xE0FFFFFF.toInt()) // Semi-transparent white
            elevation = 8f
        }
        
        // Title
        val titleView = TextView(context).apply {
            text = "🐱 Lynx Agent"
            textSize = 16f
            setTextColor(0xFF000000.toInt())
            setPadding(0, 0, 0, 16)
        }
        container.addView(titleView)
        
        // Thinking section
        val thinkingLabel = TextView(context).apply {
            text = "💭 思考中:"
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, 4)
        }
        container.addView(thinkingLabel)
        
        thinkingTextView = TextView(context).apply {
            text = "等待中..."
            textSize = 14f
            setTextColor(0xFF000000.toInt())
            setPadding(0, 0, 0, 16)
            maxWidth = 300
        }
        container.addView(thinkingTextView)
        
        // WorldState section
        val worldStateLabel = TextView(context).apply {
            text = "📱 状态:"
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, 4)
        }
        container.addView(worldStateLabel)
        
        worldStateTextView = TextView(context).apply {
            text = "未初始化"
            textSize = 14f
            setTextColor(0xFF000000.toInt())
            setPadding(0, 0, 0, 16)
            maxWidth = 300
        }
        container.addView(worldStateTextView)
        
        // Action status section
        val actionLabel = TextView(context).apply {
            text = "⚡ 操作:"
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, 4)
        }
        container.addView(actionLabel)
        
        actionStatusTextView = TextView(context).apply {
            text = "无"
            textSize = 14f
            setTextColor(0xFF000000.toInt())
            setPadding(0, 0, 0, 16)
            maxWidth = 300
        }
        container.addView(actionStatusTextView)
        
        // Cancel button (initially hidden)
        cancelButton = Button(context).apply {
            text = "取消任务"
            textSize = 14f
            visibility = View.GONE
            setOnClickListener {
                onCancelCallback?.invoke()
            }
        }
        container.addView(cancelButton)
        
        return container
    }
}
