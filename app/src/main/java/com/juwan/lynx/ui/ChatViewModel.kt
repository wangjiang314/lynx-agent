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
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juwan.lynx.agent.AgentOrchestrator
import com.juwan.lynx.agent.AgentStatus
import com.juwan.lynx.agent.FlowTraceLogger
import com.juwan.lynx.agent.TaskResult
import com.juwan.lynx.safety.SafetyGuard
import com.juwan.lynx.service.LynxAccessibilityService
import com.juwan.lynx.util.PermissionChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Represents a message in the chat interface.
 *
 * @param id Unique identifier for the message
 * @param content The message text content
 * @param isUserMessage True if sent by user, false if from agent
 * @param timestamp When the message was created
 * @param messageType Type of message (normal, result, confirmation)
 */
data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val content: String,
    val isUserMessage: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val messageType: MessageType = MessageType.NORMAL
)

/**
 * Types of messages that can appear in the chat.
 */
enum class MessageType {
    /** Regular message */
    NORMAL,
    /** Task result message (success or failure) */
    RESULT,
    /** Confirmation request from SafetyGuard */
    CONFIRMATION
}

/**
 * Represents a permission request that requires user action.
 */
sealed class PermissionRequest {
    /** Request to enable Accessibility Service */
    object AccessibilityService : PermissionRequest()
    /** Request to grant MediaProjection permission */
    object MediaProjection : PermissionRequest()
    /** Request to grant SYSTEM_ALERT_WINDOW permission */
    object OverlayWindow : PermissionRequest()
}

/**
 * ViewModel for the ChatPanel UI.
 * Manages message history, user input, and coordinates with AgentOrchestrator.
 *
 * Main responsibilities:
 * - Manages message list and input state
 * - Runs tasks through AgentOrchestrator (planning -> execution -> reflection)
 * - Exposes structured AgentStatus updates for UI visualization
 * - Handles SafetyGuard confirmation requests
 * - Checks required permissions before task execution
 *
 * @param context Application context for permission checking
 * @param agentOrchestrator The orchestrator coordinating planner+executor flow
 * @param safetyGuard The safety guard for confirmation handling
 */
class ChatViewModel(
    private val context: Context,
    private val agentOrchestrator: AgentOrchestrator,
    private val safetyGuard: SafetyGuard
) : ViewModel() {
    private val TAG = "ChatViewModel"

    // Message history
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // Current user input
    private val _userInput = MutableStateFlow("")
    val userInput: StateFlow<String> = _userInput.asStateFlow()

    // Loading state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Permission request event (one-time event)
    private val _permissionRequest = MutableStateFlow<PermissionRequest?>(null)
    val permissionRequest: StateFlow<PermissionRequest?> = _permissionRequest.asStateFlow()

    // Structured agent status for UI visualization
    private val _agentStatus = MutableStateFlow<AgentStatus?>(null)
    val agentStatus: StateFlow<AgentStatus?> = _agentStatus.asStateFlow()

    // Pending task (to execute after permissions are granted)
    private var pendingTask: String? = null
    private var pendingTaskTraceRunId: String? = null
    private var pendingTaskTraceScenarioId: String? = null
    private var activeTaskJob: Job? = null
    private var activeTaskTraceRunId: String? = null
    private var activeTaskTraceScenarioId: String? = null

    // Last status fingerprint to avoid duplicate spam in chat
    private var lastStatusFingerprint: String? = null

    // System-level overlay confirmation (唯一确认通道)
    private val confirmOverlay = FullScreenConfirmOverlay(context)
    private val pendingTaskPrefs = context.getSharedPreferences(PENDING_TASK_PREFS, Context.MODE_PRIVATE)

    /**
     * Update the user input text.
     *
     * @param text New input text
     */
    fun updateUserInput(text: String) {
        _userInput.value = text
    }

    /**
     * Send a user message and execute the task.
     *
     * @param userInstruction The user's natural language instruction
     */
    fun sendMessage(
        userInstruction: String,
        traceRunId: String? = null,
        traceScenarioId: String? = null
    ) {
        if (userInstruction.isBlank()) return
        clearPendingTask()

        // Add user message to history
        val userMessage = ChatMessage(
            content = userInstruction,
            isUserMessage = true,
            messageType = MessageType.NORMAL
        )
        addMessage(userMessage)

        // Clear input
        _userInput.value = ""

        // Execute the task
        executeTask(
            userInstruction = userInstruction,
            traceRunId = traceRunId,
            traceScenarioId = traceScenarioId
        )
    }

    /**
     * Receive and expose orchestrator status updates for UI.
     * Also emits concise status messages to chat (deduplicated).
     */
    fun onAgentStatusUpdate(status: AgentStatus) {
        _agentStatus.value = status
        val displayMessage = when {
            status.message.isNotBlank() -> status.message
            else -> "${status.phase}"
        }

        val thoughtLine = status.thought
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { "\n🧠 $it" }
            ?: ""

        val actionLine = status.action
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { "\n🛠️ 动作: $it" }
            ?: ""

        val observationLine = status.observation
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { "\n👀 观察: $it" }
            ?: ""

        val statusText = "🤖 $displayMessage$thoughtLine$actionLine$observationLine"
        val fingerprint = buildString {
            append(status.phase)
            append("|")
            append(status.message)
            append("|")
            append(status.thought.orEmpty())
            append("|")
            append(status.action.orEmpty())
            append("|")
            append(status.observation.orEmpty())
        }

        if (fingerprint != lastStatusFingerprint) {
            lastStatusFingerprint = fingerprint
            addMessage(
                ChatMessage(
                    content = statusText,
                    isUserMessage = false,
                    messageType = MessageType.NORMAL
                )
            )
        }
    }

    /**
     * Execute a task through AgentOrchestrator after permission checks.
     *
     * IMPORTANT: Checks required permissions before execution:
     * - MediaProjection (for screen capture) - checked at execution time
     *
     * Note: Accessibility Service is checked at app startup, not here.
     *
     * @param userInstruction The user's instruction
     */
    private fun executeTask(
        userInstruction: String,
        traceRunId: String? = null,
        traceScenarioId: String? = null
    ) {
        val previousJob = activeTaskJob?.takeIf { it.isActive }
        val launchedJob = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                _isLoading.value = true
                if (previousJob != null) {
                    cancelPreviousTaskForNewRun(
                        previousJob = previousJob,
                        traceRunId = traceRunId,
                        traceScenarioId = traceScenarioId
                    )
                }

                // Check Accessibility permission before executing task
                if (!PermissionChecker.isAccessibilityServiceEnabled() || LynxAccessibilityService.getInstance() == null) {
                    logPreflightBlockedRun(
                        userInstruction = userInstruction,
                        traceRunId = traceRunId,
                        traceScenarioId = traceScenarioId,
                        reason = "missing_accessibility_permission"
                    )
                    storePendingTask(userInstruction, traceRunId, traceScenarioId)
                    _permissionRequest.value = PermissionRequest.AccessibilityService
                    _isLoading.value = false
                    return@launch
                }

                // Check MediaProjection permission before executing task
                if (!PermissionChecker.isMediaProjectionGranted()) {
                    logPreflightBlockedRun(
                        userInstruction = userInstruction,
                        traceRunId = traceRunId,
                        traceScenarioId = traceScenarioId,
                        reason = "missing_media_projection_permission"
                    )
                    storePendingTask(userInstruction, traceRunId, traceScenarioId)
                    _permissionRequest.value = PermissionRequest.MediaProjection
                    _isLoading.value = false
                    return@launch
                }

                // Overlay permission is mandatory because all confirmations are system overlay.
                if (!PermissionChecker.canDrawOverlays(context)) {
                    logPreflightBlockedRun(
                        userInstruction = userInstruction,
                        traceRunId = traceRunId,
                        traceScenarioId = traceScenarioId,
                        reason = "missing_overlay_permission"
                    )
                    storePendingTask(userInstruction, traceRunId, traceScenarioId)
                    _permissionRequest.value = PermissionRequest.OverlayWindow
                    _isLoading.value = false
                    return@launch
                }

                // All permissions granted, execute task through orchestrator
                doExecuteTask(
                    userInstruction = userInstruction,
                    traceRunId = traceRunId,
                    traceScenarioId = traceScenarioId
                )
            } catch (e: CancellationException) {
                Log.i(TAG, "Task execution cancelled: ${e.message}")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Task execution error", e)
                addMessage(
                    ChatMessage(
                        content = "❌ 执行出错: ${e.message}",
                        isUserMessage = false,
                        messageType = MessageType.RESULT
                    )
                )
                _isLoading.value = false
            } finally {
                if (activeTaskJob === coroutineContext[Job]) {
                    activeTaskJob = null
                    activeTaskTraceRunId = null
                    activeTaskTraceScenarioId = null
                    _isLoading.value = false
                }
            }
        }
        activeTaskJob = launchedJob
        activeTaskTraceRunId = traceRunId
        activeTaskTraceScenarioId = traceScenarioId
        launchedJob.start()
    }

    fun cancelActiveTask(reason: String = "cancel_requested") {
        val job = activeTaskJob?.takeIf { it.isActive } ?: return
        FlowTraceLogger.warn(
            stage = "active_task_cancel_requested",
            kv = mapOf(
                "reason" to reason,
                "run_id" to activeTaskTraceRunId.orEmpty(),
                "scenario_id" to activeTaskTraceScenarioId.orEmpty()
            )
        )
        clearPendingTask()
        job.cancel(CancellationException(reason))
        _isLoading.value = false
    }

    private suspend fun cancelPreviousTaskForNewRun(
        previousJob: Job,
        traceRunId: String?,
        traceScenarioId: String?
    ) {
        FlowTraceLogger.warn(
            stage = "active_task_cancelled_for_new_run",
            kv = mapOf(
                "new_run_id" to traceRunId.orEmpty(),
                "new_scenario_id" to traceScenarioId.orEmpty()
            )
        )
        previousJob.cancel(CancellationException("superseded_by_new_run"))
        val joined = withTimeoutOrNull(ACTIVE_TASK_CANCEL_JOIN_TIMEOUT_MS) {
            previousJob.join()
            true
        } == true
        if (!joined) {
            FlowTraceLogger.warn(
                stage = "active_task_cancel_join_timeout",
                kv = mapOf(
                    "new_run_id" to traceRunId.orEmpty(),
                    "new_scenario_id" to traceScenarioId.orEmpty()
                )
            )
        }
    }

    private fun logPreflightBlockedRun(
        userInstruction: String,
        traceRunId: String?,
        traceScenarioId: String?,
        reason: String
    ) {
        FlowTraceLogger.start(
            userInstruction = userInstruction,
            runId = traceRunId,
            scenarioId = traceScenarioId
        )
        FlowTraceLogger.warn(
            stage = "task_preflight_blocked",
            kv = mapOf("reason" to reason)
        )
        FlowTraceLogger.finish(status = "failed", reason = "preflight_blocked:$reason")
    }

    /**
     * Actually execute the task through orchestrator (called after permissions are verified).
     */
    private suspend fun doExecuteTask(
        userInstruction: String,
        traceRunId: String? = null,
        traceScenarioId: String? = null
    ) {
        try {
            addMessage(
                ChatMessage(
                    content = "正在规划并执行任务: $userInstruction",
                    isUserMessage = false,
                    messageType = MessageType.NORMAL
                )
            )

            val result = agentOrchestrator.run(
                userInstruction = userInstruction,
                traceRunId = traceRunId,
                traceScenarioId = traceScenarioId
            )

            when (result) {
                is TaskResult.Success -> {
                    addMessage(
                        ChatMessage(
                            content = "✅ 任务完成: ${result.summary}",
                            isUserMessage = false,
                            messageType = MessageType.RESULT
                        )
                    )
                }

                is TaskResult.Failure -> {
                    addMessage(
                        ChatMessage(
                            content = "❌ 任务失败: ${result.reason}",
                            isUserMessage = false,
                            messageType = MessageType.RESULT
                        )
                    )
                }
            }
        } catch (e: CancellationException) {
            FlowTraceLogger.warn(
                stage = "task_cancelled",
                kv = mapOf("reason" to (e.message ?: "cancelled"))
            )
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Task execution error", e)
            addMessage(
                ChatMessage(
                    content = "❌ 执行出错: ${e.message}",
                    isUserMessage = false,
                    messageType = MessageType.RESULT
                )
            )
        }
    }

    /**
     * Called when permission request is handled (user returned from settings or granted permission).
     */
    fun onPermissionRequestHandled() {
        _permissionRequest.value = null

        // If there's a pending task and all required permissions are now granted, execute it
        val pending = currentPendingTask()
        val task = pending?.instruction
        if (
            task != null &&
            PermissionChecker.isAccessibilityServiceEnabled() &&
            LynxAccessibilityService.getInstance() != null &&
            PermissionChecker.isMediaProjectionGranted() &&
            PermissionChecker.canDrawOverlays(context)
        ) {
            clearPendingTask()
            executeTask(
                userInstruction = task,
                traceRunId = pending.traceRunId,
                traceScenarioId = pending.traceScenarioId
            )
        }
    }

    fun promptForPendingTaskPermissions() {
        val pending = currentPendingTask() ?: return
        if (_isLoading.value || _permissionRequest.value != null) return

        val missingRequest = nextMissingPermissionRequest()
        if (missingRequest != null) {
            FlowTraceLogger.event(
                stage = "pending_task_permission_request",
                kv = mapOf(
                    "request" to permissionRequestName(missingRequest),
                    "has_trace_run_id" to (!pending.traceRunId.isNullOrBlank())
                )
            )
            _permissionRequest.value = missingRequest
            return
        }

        clearPendingTask()
        executeTask(
            userInstruction = pending.instruction,
            traceRunId = pending.traceRunId,
            traceScenarioId = pending.traceScenarioId
        )
    }

    /**
     * Retry pending task after permissions are granted.
     */
    fun retryPendingTask() {
        val pending = currentPendingTask()
        if (pending != null) {
            clearPendingTask()
            sendMessage(
                userInstruction = pending.instruction,
                traceRunId = pending.traceRunId,
                traceScenarioId = pending.traceScenarioId
            )
        }
    }

    private fun storePendingTask(
        instruction: String,
        traceRunId: String?,
        traceScenarioId: String?
    ) {
        pendingTask = instruction
        pendingTaskTraceRunId = traceRunId
        pendingTaskTraceScenarioId = traceScenarioId
        pendingTaskPrefs.edit()
            .putString(KEY_PENDING_TASK, instruction)
            .putString(KEY_PENDING_TRACE_RUN_ID, traceRunId.orEmpty())
            .putString(KEY_PENDING_TRACE_SCENARIO_ID, traceScenarioId.orEmpty())
            .apply()
    }

    private fun currentPendingTask(): PendingTaskSnapshot? {
        val instruction = pendingTask
            ?: pendingTaskPrefs.getString(KEY_PENDING_TASK, null)
                ?.takeIf { it.isNotBlank() }
            ?: return null
        val traceRunId = pendingTaskTraceRunId
            ?: pendingTaskPrefs.getString(KEY_PENDING_TRACE_RUN_ID, null)
                ?.takeIf { it.isNotBlank() }
        val traceScenarioId = pendingTaskTraceScenarioId
            ?: pendingTaskPrefs.getString(KEY_PENDING_TRACE_SCENARIO_ID, null)
                ?.takeIf { it.isNotBlank() }
        return PendingTaskSnapshot(instruction, traceRunId, traceScenarioId)
    }

    private fun clearPendingTask() {
        pendingTask = null
        pendingTaskTraceRunId = null
        pendingTaskTraceScenarioId = null
        pendingTaskPrefs.edit().clear().apply()
    }

    private fun nextMissingPermissionRequest(): PermissionRequest? {
        return when {
            !PermissionChecker.isAccessibilityServiceEnabled() ||
                LynxAccessibilityService.getInstance() == null -> PermissionRequest.AccessibilityService
            !PermissionChecker.isMediaProjectionGranted() -> PermissionRequest.MediaProjection
            !PermissionChecker.canDrawOverlays(context) -> PermissionRequest.OverlayWindow
            else -> null
        }
    }

    private fun permissionRequestName(request: PermissionRequest): String {
        return when (request) {
            is PermissionRequest.AccessibilityService -> "accessibility"
            is PermissionRequest.MediaProjection -> "media_projection"
            is PermissionRequest.OverlayWindow -> "overlay"
        }
    }

    /**
     * Add a message to the chat history.
     *
     * @param message The message to add
     */
    private fun addMessage(message: ChatMessage) {
        val currentMessages = _messages.value.toMutableList()
        currentMessages.add(message)
        _messages.value = currentMessages
    }

    /**
     * Request user confirmation for a sensitive action.
     * Uses full-screen system overlay only (no in-app / notification fallback).
     *
     * @param actionDescription Description of the action requiring confirmation
     * @return True if user approves, false if user rejects
     */
    suspend fun requestConfirmation(actionDescription: String): Boolean {
        return try {
            if (!PermissionChecker.canDrawOverlays(context)) {
                Log.e(
                    "LynxFlow",
                    "stage=confirm_request_blocked reason=missing_overlay_permission action=${actionDescription.take(200)}"
                )
                return false
            }
            val approved = confirmOverlay.request(actionDescription, timeoutMs = 60_000L)
            Log.i(
                "LynxFlow",
                "stage=confirm_request_result approved=$approved action=${actionDescription.take(200)}"
            )
            approved
        } catch (e: Exception) {
            Log.e(TAG, "Confirmation error", e)
            false
        }
    }

    /**
     * Clear all messages from the chat history.
     */
    fun clearMessages() {
        _messages.value = emptyList()
    }

    private data class PendingTaskSnapshot(
        val instruction: String,
        val traceRunId: String?,
        val traceScenarioId: String?
    )

    private companion object {
        const val PENDING_TASK_PREFS = "lynx_pending_task"
        const val KEY_PENDING_TASK = "pending_task"
        const val KEY_PENDING_TRACE_RUN_ID = "pending_trace_run_id"
        const val KEY_PENDING_TRACE_SCENARIO_ID = "pending_trace_scenario_id"
        const val ACTIVE_TASK_CANCEL_JOIN_TIMEOUT_MS = 5_000L
    }
}
