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

package com.juwan.lynx.runtime

import android.content.Context
import android.util.Log
import com.juwan.lynx.agent.AgentOrchestrator
import com.juwan.lynx.agent.AgentStatus
import com.juwan.lynx.agent.CompletionGate
import com.juwan.lynx.agent.ExecutorAgent
import com.juwan.lynx.agent.HumanAssistanceGate
import com.juwan.lynx.agent.HumanAssistanceResponse
import com.juwan.lynx.agent.PlannerAgent
import com.juwan.lynx.agent.TaskResult
import com.juwan.lynx.agent.VerifierAgent
import com.juwan.lynx.api.ApiClientFactory
import com.juwan.lynx.api.ChatMessage
import com.juwan.lynx.database.LynxDatabase
import com.juwan.lynx.memory.AgentMemory
import com.juwan.lynx.perception.MlKitOcrEngine
import com.juwan.lynx.perception.ScreenObserverImpl
import com.juwan.lynx.safety.SafetyGuard
import com.juwan.lynx.service.LynxAccessibilityService
import com.juwan.lynx.service.LynxCaptureService
import com.juwan.lynx.state.Observation
import com.juwan.lynx.state.PendingConfirmation
import com.juwan.lynx.state.ScreenObserver
import com.juwan.lynx.state.ActionTransitionEvent
import com.juwan.lynx.state.WorldStateManager
import com.juwan.lynx.state.WorldState
import com.juwan.lynx.tool.macro.TypeAndEnterTool
import com.juwan.lynx.tool.primitive.ClickTool
import com.juwan.lynx.tool.primitive.DragTool
import com.juwan.lynx.tool.primitive.FinishedTool
import com.juwan.lynx.tool.primitive.LongPressTool
import com.juwan.lynx.tool.primitive.OpenAppTool
import com.juwan.lynx.tool.primitive.PressBackTool
import com.juwan.lynx.tool.primitive.PressHomeTool
import com.juwan.lynx.tool.primitive.ResolveInstalledAppTool
import com.juwan.lynx.tool.primitive.ScrollTool
import com.juwan.lynx.tool.primitive.TypeTool
import com.juwan.lynx.ui.SharedPreferencesSettingsProvider
import com.juwan.lynx.util.PermissionChecker
import java.util.concurrent.atomic.AtomicReference

data class TaskSnapshot(
    val latestStatus: AgentStatus?,
    val currentApp: String?,
    val currentPage: String?,
    val pendingConfirmation: PendingConfirmation?
)

class AgentExecutionFacade private constructor(
    val agentOrchestrator: AgentOrchestrator,
    val safetyGuard: SafetyGuard,
    private val worldStateManager: WorldStateManager,
    private val latestStatus: AtomicReference<AgentStatus?>,
    private val visionApiClient: com.juwan.lynx.api.ApiClient
) {
    fun getLatestStatus(): AgentStatus? = latestStatus.get()

    suspend fun runTask(instruction: String): TaskResult = agentOrchestrator.run(instruction)

    suspend fun captureCurrentObservation(): Observation? =
        worldStateManager.refreshObservationAndReturn()

    fun addActionTransitionListener(listener: suspend (ActionTransitionEvent) -> Unit): Long =
        worldStateManager.addActionTransitionListener(listener)

    fun removeActionTransitionListener(listenerId: Long) {
        worldStateManager.removeActionTransitionListener(listenerId)
    }

    fun getCurrentSnapshot(): TaskSnapshot {
        val state = worldStateManager.getState()
        return TaskSnapshot(
            latestStatus = latestStatus.get(),
            currentApp = state.currentApp,
            currentPage = state.pageSignature.ifBlank { null },
            pendingConfirmation = state.pendingConfirmation
        )
    }

    fun getCurrentWorldState(): WorldState = worldStateManager.getState()

    fun setRuntimeTaskFacts(facts: List<String>) {
        worldStateManager.setRuntimeTaskFacts(facts)
    }

    fun clearRuntimeTaskFacts() {
        worldStateManager.clearRuntimeTaskFacts()
    }

    suspend fun confirmWithVision(
        observation: Observation,
        instruction: String,
        timeoutSeconds: Int = 20
    ): Boolean {
        val screenshot = observation.screenshotBase64
        if (screenshot.isNullOrBlank()) {
            return false
        }
        val labels = buildSet {
            observation.uiTree.orEmpty().forEach { element ->
                element.text?.trim()?.takeIf(String::isNotBlank)?.let(::add)
                element.contentDescription?.trim()?.takeIf(String::isNotBlank)?.let(::add)
            }
        }.take(12)
        val systemPrompt = """
            You are a strict mobile UI checkpoint validator.
            Determine whether the screenshot satisfies the requested checkpoint.
            Reply with only one token: YES or NO.
            Ignore top-bar icon descriptions and accessibility-only chrome labels unless they clearly define the whole page state.
        """.trimIndent()
        val userPrompt = buildString {
            appendLine(instruction.trim())
            appendLine()
            appendLine("Current app: ${observation.currentApp ?: "unknown"}")
            if (labels.isNotEmpty()) {
                appendLine("Visible UI labels sample: ${labels.joinToString(" | ")}")
            }
        }.trim()
        return runCatching {
            val response = visionApiClient.chatWithVision(
                systemPrompt = systemPrompt,
                messages = listOf(ChatMessage(role = "user", content = userPrompt)),
                imageBase64 = screenshot,
                temperature = 0.0f,
                tools = emptyList(),
                timeoutSeconds = timeoutSeconds
            ).trim()
            response.lineSequence().firstOrNull()?.trim()?.equals("YES", ignoreCase = true) == true
        }.onFailure {
            Log.w(TAG, "Vision checkpoint confirm failed: ${it.message}", it)
        }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "AgentExecutionFacade"

        fun create(
            context: Context,
            settingsProvider: SharedPreferencesSettingsProvider,
            onConfirmRequired: suspend (String) -> Boolean,
            onStatusUpdate: (AgentStatus) -> Unit = {}
        ): AgentExecutionFacade {
            val appContext = context.applicationContext
            val apiClient = ApiClientFactory.createApiClient(settingsProvider)
            val runtime = SimpleReActRuntime(apiClient)

            val db = LynxDatabase.getInstance(appContext)
            val agentMemory = AgentMemory(db.memoryDao())

            val accessibilityService = LynxAccessibilityService.getInstance()
            val captureService = LynxCaptureService.getInstance()
            val sharedOcrEngine = MlKitOcrEngine.shared
            val dynamicScreenObserver = object : ScreenObserver {
                private var cachedAccessibilityService: LynxAccessibilityService? = null
                private var cachedCaptureService: LynxCaptureService? = null
                private var cachedObserver: ScreenObserverImpl? = null

                override fun capture(): Observation {
                    val currentAccessibilityService = LynxAccessibilityService.getInstance()
                    val currentCaptureService = LynxCaptureService.getInstance()

                    if (currentAccessibilityService != null && currentCaptureService != null) {
                        if (
                            cachedObserver == null ||
                            cachedAccessibilityService !== currentAccessibilityService ||
                            cachedCaptureService !== currentCaptureService
                        ) {
                            cachedObserver = ScreenObserverImpl(
                                captureService = currentCaptureService,
                                accessibilityService = currentAccessibilityService,
                                ocrEngine = sharedOcrEngine
                            )
                            cachedAccessibilityService = currentAccessibilityService
                            cachedCaptureService = currentCaptureService
                        }
                        return cachedObserver?.capture() ?: Observation(
                            screenshotBase64 = "",
                            uiTree = null,
                            ocrTexts = null,
                            pageSignature = "observer_unavailable",
                            currentApp = currentAccessibilityService.currentPackageName,
                            debugPageLabel = "observer_unavailable_page"
                        )
                    }

                    if (currentAccessibilityService != null) {
                        cachedObserver = null
                        cachedCaptureService = null
                        cachedAccessibilityService = currentAccessibilityService
                        val fallbackApp = currentAccessibilityService.currentPackageName
                        return Observation(
                            screenshotBase64 = "",
                            uiTree = null,
                            ocrTexts = null,
                            pageSignature = fallbackApp ?: "no_capture_accessibility_only",
                            currentApp = fallbackApp,
                            debugPageLabel = fallbackApp
                                ?.substringAfterLast('.')
                                ?.takeIf { it.isNotBlank() }
                                ?: "accessibility_fallback_page"
                        )
                    }

                    cachedObserver = null
                    cachedAccessibilityService = null
                    cachedCaptureService = null
                    return Observation(
                        screenshotBase64 = "",
                        uiTree = null,
                        ocrTexts = null,
                        pageSignature = "no_accessibility_no_capture",
                        currentApp = null,
                        debugPageLabel = "system_unknown_page"
                    )
                }
            }

            val worldStateManager = WorldStateManager(
                screenObserver = dynamicScreenObserver,
                worldStateDao = db.worldStateDao()
            )

            val primitiveTools = mutableListOf(
                ResolveInstalledAppTool(appContext),
                OpenAppTool(appContext),
                FinishedTool()
            ).apply {
                if (accessibilityService != null) {
                    add(ClickTool(appContext, accessibilityService))
                    add(LongPressTool(appContext, accessibilityService))
                    add(ScrollTool(appContext, accessibilityService))
                    add(DragTool(appContext, accessibilityService))
                    add(TypeTool(accessibilityService))
                    add(PressBackTool(accessibilityService))
                    add(PressHomeTool(accessibilityService))
                    add(TypeAndEnterTool(accessibilityService, captureService))
                }
            }

            Log.i(
                TAG,
                "Service readiness: accessibility=${accessibilityService != null}, capture=${captureService != null}, " +
                    "permission.accessibility=${PermissionChecker.isAccessibilityServiceEnabled()}, " +
                    "permission.capture=${PermissionChecker.isMediaProjectionGranted()}"
            )
            Log.i(
                TAG,
                "Registered runtime tools (${primitiveTools.size}): ${primitiveTools.joinToString(", ") { it.name }}"
            )

            val safetyGuard = SafetyGuard(onConfirmRequired)
            val plannerAgent = PlannerAgent(
                runtime = runtime,
                worldStateManager = worldStateManager,
                modelConfig = createPlannerModelConfig(settingsProvider)
            )
            val executorAgent = ExecutorAgent(
                runtime = runtime,
                worldStateManager = worldStateManager,
                safetyGuard = safetyGuard,
                visionModelConfig = createExecutorModelConfig(settingsProvider),
                primitiveTools = primitiveTools,
                repeatedActionLimit = settingsProvider.getExecutorRepeatedActionLimit(),
                stuckNoChangeLimit = settingsProvider.getExecutorStuckNoChangeLimit(),
                progressCheckInterval = settingsProvider.getExecutorProgressCheckInterval(),
                executorRuntimeIterationsPerCall = settingsProvider.getExecutorRuntimeIterationsPerCall()
            )
            val verifierAgent = VerifierAgent(
                runtime = runtime,
                modelConfig = createExecutorModelConfig(settingsProvider)
            )

            val latestStatus = AtomicReference<AgentStatus?>(null)
            val humanAssistanceGate = HumanAssistanceGate(
                worldStateManager = worldStateManager
            ) { request ->
                val approved = safetyGuard.requestConfirmation(request.prompt)
                HumanAssistanceResponse(
                    completed = approved,
                    reason = if (approved) request.reason else "user_cancelled_or_timeout"
                )
            }
            val orchestrator = AgentOrchestrator(
                planner = plannerAgent,
                executor = executorAgent,
                verifierAgent = verifierAgent,
                completionGate = CompletionGate(),
                humanAssistanceGate = humanAssistanceGate,
                worldStateManager = worldStateManager,
                taskResultArchive = agentMemory
            ) { status ->
                latestStatus.set(status)
                onStatusUpdate(status)
            }

            return AgentExecutionFacade(
                agentOrchestrator = orchestrator,
                safetyGuard = safetyGuard,
                worldStateManager = worldStateManager,
                latestStatus = latestStatus,
                visionApiClient = apiClient
            )
        }

        private fun createPlannerModelConfig(
            settingsProvider: SharedPreferencesSettingsProvider
        ): ModelConfig {
            val isOther =
                settingsProvider.getApiType() == SharedPreferencesSettingsProvider.API_TYPE_OTHER
            return if (isOther) {
                ModelConfig(
                    baseUrl = settingsProvider.getOtherApiUrl(),
                    modelName = settingsProvider.getOtherTextModel(),
                    temperature = 0.1f,
                    timeoutSeconds = 60
                )
            } else {
                ModelConfig(
                    baseUrl = settingsProvider.getApiBaseUrl(),
                    modelName = settingsProvider.getTextModel(),
                    temperature = 0.1f,
                    timeoutSeconds = 60
                )
            }
        }

        private fun createExecutorModelConfig(
            settingsProvider: SharedPreferencesSettingsProvider
        ): ModelConfig {
            val isOther =
                settingsProvider.getApiType() == SharedPreferencesSettingsProvider.API_TYPE_OTHER
            return if (isOther) {
                ModelConfig(
                    baseUrl = settingsProvider.getOtherApiUrl(),
                    modelName = settingsProvider.getOtherVisionModel(),
                    temperature = 0.0f,
                    timeoutSeconds = 35
                )
            } else {
                ModelConfig(
                    baseUrl = settingsProvider.getApiBaseUrl(),
                    modelName = settingsProvider.getVisionModel(),
                    temperature = 0.0f,
                    timeoutSeconds = 35
                )
            }
        }
    }
}
