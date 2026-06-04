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

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import com.juwan.lynx.agent.FlowTraceLogger
import com.juwan.lynx.runtime.AgentExecutionFacade
import com.juwan.lynx.service.LynxAccessibilityService
import com.juwan.lynx.util.PermissionChecker
import com.juwan.lynx.util.ScreenshotUtil

/**
 * Main Chat Panel Activity for user interaction with Lynx Agent.
 */
class ChatPanelActivity : ComponentActivity() {
    private lateinit var settingsProvider: SharedPreferencesSettingsProvider
    private lateinit var viewModel: ChatViewModel
    private val tag = "ChatPanelActivity"
    private var hasTriggeredAccessibilityReadyRecreate = false
    private var shouldAutoRecreateWhenAccessibilityReady = false
    private var shouldRecreateAfterSettings = false
    private var debugAutoInstructionConsumed = false

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            ScreenshotUtil.takeScreenshot(
                this,
                result.resultCode,
                result.data,
                object : ScreenshotUtil.ScreenshotCallback {
                    override fun onScreenshotBase64(base64: String, width: Int, height: Int) {
                        viewModel.onPermissionRequestHandled()
                    }

                    override fun onError(reason: String) {
                        viewModel.onPermissionRequestHandled()
                    }
                }
            )
        } else {
            viewModel.onPermissionRequestHandled()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        hasTriggeredAccessibilityReadyRecreate = savedInstanceState?.getBoolean(
            KEY_ACCESSIBILITY_READY_RECREATE_DONE,
            false
        ) ?: false
        shouldAutoRecreateWhenAccessibilityReady = savedInstanceState?.getBoolean(
            KEY_SHOULD_AUTO_RECREATE_ON_ACCESSIBILITY_READY,
            false
        ) ?: false
        debugAutoInstructionConsumed = savedInstanceState?.getBoolean(
            KEY_DEBUG_AUTO_INSTRUCTION_CONSUMED,
            false
        ) ?: false

        settingsProvider = SharedPreferencesSettingsProvider(this)
        FlowTraceLogger.configureFileSink(applicationContext)

        if (!settingsProvider.isConfigured()) {
            Log.i(tag, "onCreate: settings not configured, redirecting to SettingsActivity")
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        Log.i(tag, "onCreate: settings configured, initializing main UI")
        initializeMainUi()
        handleDebugAutoInstruction()
        checkCriticalPermissionsOnStart()
    }

    override fun onResume() {
        super.onResume()

        if (!::viewModel.isInitialized) {
            settingsProvider = SharedPreferencesSettingsProvider(this)
            if (settingsProvider.isConfigured()) {
                Log.i(tag, "onResume: settings configured after return, initializing main UI")
                initializeMainUi()
                checkCriticalPermissionsOnStart()
                viewModel.promptForPendingTaskPermissions()
            } else {
                Log.i(tag, "onResume: settings still not configured, redirecting to SettingsActivity")
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            return
        }

        if (shouldRecreateAfterSettings) {
            shouldRecreateAfterSettings = false
            recreate()
            return
        }

        settingsProvider = SharedPreferencesSettingsProvider(this)

        if (
            shouldAutoRecreateWhenAccessibilityReady &&
            !hasTriggeredAccessibilityReadyRecreate &&
            PermissionChecker.isAccessibilityServiceEnabled() &&
            LynxAccessibilityService.getInstance() != null
        ) {
            hasTriggeredAccessibilityReadyRecreate = true
            shouldAutoRecreateWhenAccessibilityReady = false
            shouldRecreateAfterSettings = true
            recreate()
            return
        }

        viewModel.onPermissionRequestHandled()
        viewModel.promptForPendingTaskPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        debugAutoInstructionConsumed = false
        if (::viewModel.isInitialized) {
            handleDebugCancelActiveTask()
            handleDebugAutoInstruction()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(
            KEY_ACCESSIBILITY_READY_RECREATE_DONE,
            hasTriggeredAccessibilityReadyRecreate
        )
        outState.putBoolean(
            KEY_SHOULD_AUTO_RECREATE_ON_ACCESSIBILITY_READY,
            shouldAutoRecreateWhenAccessibilityReady
        )
        outState.putBoolean(
            KEY_DEBUG_AUTO_INSTRUCTION_CONSUMED,
            debugAutoInstructionConsumed
        )
    }

    private fun initializeMainUi() {
        Log.i(tag, "initializeMainUi: creating ViewModel and binding Compose content")
        viewModel = createChatViewModel()

        setContent {
            MaterialTheme {
                LaunchedEffect(Unit) {
                    viewModel.permissionRequest.collect { request ->
                        request?.let { handlePermissionRequest(it) }
                    }
                }

                LaunchedEffect(Unit) {
                    handleDebugCancelActiveTask()
                    handleDebugAutoInstruction()
                }

                ChatPanel(
                    viewModel = viewModel,
                    onOpenSettings = {
                        shouldRecreateAfterSettings = true
                        startActivity(Intent(this, SettingsActivity::class.java))
                    }
                )
            }
        }
    }

    private fun handleDebugAutoInstruction() {
        consumeDebugAutoInstruction()?.let { request ->
            Log.i(
                tag,
                "Submitting debug auto instruction scenario=${request.scenarioId ?: "none"} run=${request.runId ?: "none"}"
            )
            FlowTraceLogger.event(
                stage = "debug_auto_instruction_submitted",
                kv = mapOf(
                    "run_id" to request.runId.orEmpty(),
                    "scenario_id" to request.scenarioId.orEmpty()
                )
            )
            viewModel.sendMessage(
                userInstruction = request.instruction,
                traceRunId = request.runId,
                traceScenarioId = request.scenarioId
            )
        }
    }

    private fun handleDebugCancelActiveTask() {
        val reason = consumeDebugCancelReason() ?: return
        Log.i(tag, "Cancelling active debug task: $reason")
        viewModel.cancelActiveTask(reason)
    }

    private fun createChatViewModel(): ChatViewModel {
        val accessibilityService = LynxAccessibilityService.getInstance()
        shouldAutoRecreateWhenAccessibilityReady = accessibilityService == null
        var vm: ChatViewModel? = null
        val facade = AgentExecutionFacade.create(
            context = applicationContext,
            settingsProvider = settingsProvider,
            onConfirmRequired = { actionDescription ->
                vm?.requestConfirmation(actionDescription) ?: false
            },
            onStatusUpdate = { status ->
                vm?.onAgentStatusUpdate(status)
            }
        )

        vm = ChatViewModel(
            context = applicationContext,
            agentOrchestrator = facade.agentOrchestrator,
            safetyGuard = facade.safetyGuard
        )

        return vm
    }

    private fun consumeDebugAutoInstruction(): DebugAutoInstruction? {
        if (!isDebuggableBuild() || debugAutoInstructionConsumed) return null
        val rawInstruction = intent.getStringExtra(EXTRA_DEBUG_INSTRUCTION_B64)
            ?.let { encoded -> decodeDebugInstruction(encoded) }
            ?: intent.getStringExtra(EXTRA_DEBUG_INSTRUCTION)
            ?: return null
        val instruction = rawInstruction.trim().takeIf { it.isNotBlank() } ?: return null
        val runId = intent.getStringExtra(EXTRA_DEBUG_RUN_ID)?.trim()?.takeIf { it.isNotBlank() }
        val scenarioId = intent.getStringExtra(EXTRA_DEBUG_SCENARIO_ID)?.trim()?.takeIf { it.isNotBlank() }
        debugAutoInstructionConsumed = true
        clearDebugAutoInstructionExtras()
        return DebugAutoInstruction(
            instruction = instruction,
            runId = runId,
            scenarioId = scenarioId
        )
    }

    private fun clearDebugAutoInstructionExtras() {
        intent.removeExtra(EXTRA_DEBUG_INSTRUCTION)
        intent.removeExtra(EXTRA_DEBUG_INSTRUCTION_B64)
        intent.removeExtra(EXTRA_DEBUG_RUN_ID)
        intent.removeExtra(EXTRA_DEBUG_SCENARIO_ID)
    }

    private fun consumeDebugCancelReason(): String? {
        if (!isDebuggableBuild()) return null
        if (!intent.getBooleanExtra(EXTRA_DEBUG_CANCEL_ACTIVE_TASK, false)) return null
        val reason = intent.getStringExtra(EXTRA_DEBUG_CANCEL_REASON)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "debug_cancel_active_task"
        intent.removeExtra(EXTRA_DEBUG_CANCEL_ACTIVE_TASK)
        intent.removeExtra(EXTRA_DEBUG_CANCEL_REASON)
        return reason
    }

    private fun isDebuggableBuild(): Boolean {
        return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun decodeDebugInstruction(encoded: String): String? {
        return runCatching {
            String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun checkCriticalPermissionsOnStart() {
        if (!PermissionChecker.isAccessibilityServiceEnabled()) {
            showAccessibilityDialog()
            return
        }
        if (!PermissionChecker.canDrawOverlays(this)) {
            showOverlayPermissionDialog()
        }
    }

    private fun handlePermissionRequest(request: PermissionRequest) {
        when (request) {
            is PermissionRequest.AccessibilityService -> showAccessibilityDialog()
            is PermissionRequest.MediaProjection -> requestMediaProjection()
            is PermissionRequest.OverlayWindow -> showOverlayPermissionDialog()
        }
    }

    private fun showAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要无障碍服务权限")
            .setMessage(
                "Lynx Agent 需要无障碍服务权限来执行屏幕操作（点击、滑动、输入等）。\n\n" +
                    "点击\"去设置\"将跳转到系统设置页面，请找到 Lynx Agent 并开启服务。"
            )
            .setPositiveButton("去设置") { _, _ -> openAccessibilitySettings() }
            .setNegativeButton("取消") { _, _ -> viewModel.onPermissionRequestHandled() }
            .setCancelable(false)
            .show()
    }

    private fun showOverlayPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要悬浮窗权限")
            .setMessage(
                "Lynx Agent 的敏感操作确认已切换为系统全屏悬浮层。\n\n" +
                    "请开启“显示在其他应用上层”，否则无法进行任何确认操作。"
            )
            .setPositiveButton("去设置") { _, _ -> openOverlaySettings() }
            .setNegativeButton("取消") { _, _ -> viewModel.onPermissionRequestHandled() }
            .setCancelable(false)
            .show()
    }

    private fun openAccessibilitySettings() {
        shouldRecreateAfterSettings = true
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }.onFailure {
            startActivity(
                Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    private fun openOverlaySettings() {
        shouldRecreateAfterSettings = true
        runCatching {
            PermissionChecker.openOverlaySettings(this)
        }.onFailure {
            startActivity(
                Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    private fun requestMediaProjection() {
        val mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    companion object {
        private const val KEY_ACCESSIBILITY_READY_RECREATE_DONE =
            "key_accessibility_ready_recreate_done"
        private const val KEY_SHOULD_AUTO_RECREATE_ON_ACCESSIBILITY_READY =
            "key_should_auto_recreate_on_accessibility_ready"
        private const val KEY_DEBUG_AUTO_INSTRUCTION_CONSUMED =
            "key_debug_auto_instruction_consumed"
        const val EXTRA_DEBUG_INSTRUCTION = "lynx.debug_instruction"
        const val EXTRA_DEBUG_INSTRUCTION_B64 = "lynx.debug_instruction_b64"
        const val EXTRA_DEBUG_RUN_ID = "lynx.debug_run_id"
        const val EXTRA_DEBUG_SCENARIO_ID = "lynx.debug_scenario_id"
        const val EXTRA_DEBUG_CANCEL_ACTIVE_TASK = "lynx.debug_cancel_active_task"
        const val EXTRA_DEBUG_CANCEL_REASON = "lynx.debug_cancel_reason"
    }
}

private data class DebugAutoInstruction(
    val instruction: String,
    val runId: String?,
    val scenarioId: String?
)
