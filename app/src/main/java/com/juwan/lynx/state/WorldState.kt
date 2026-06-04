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

package com.juwan.lynx.state

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

/**
 * Immutable data class representing the runtime state of the agent.
 * Tracks environment state, task state, and operation history.
 *
 * Now persistent via Room to support task resumption (Requirement 14.1).
 */
@Entity(tableName = "world_states")
@Serializable
data class WorldState(
    @PrimaryKey val id: String = "current_task",

    // Environment state
    val currentApp: String? = null,
    val pageAffordances: List<String> = emptyList(),
    val pageCapabilities: List<String> = emptyList(),
    val pageSignature: String = "",
    val semanticLabels: List<String> = emptyList(),
    val uiTexts: List<String> = emptyList(),
    val contentDescriptions: List<String> = emptyList(),
    val ocrTexts: List<String> = emptyList(),
    val visibleTexts: List<String> = emptyList(),
    val visibleTextsHistory: List<List<String>> = emptyList(),
    val interactableElements: List<String> = emptyList(),
    val typedActionCandidates: List<ActionCandidate> = emptyList(),
    val actionCandidates: List<String> = emptyList(),

    // Task state
    val goal: String = "",
    val targetApp: String = "",
    val strategy: List<String> = emptyList(),
    val successCriteria: List<String> = emptyList(),
    val riskHints: List<String> = emptyList(),
    val isTaskInProgress: Boolean = false,
    val resolvedTargetPackage: String? = null,

    // Operation history
    val strategyRecentActions: List<ActionRecord> = emptyList(),
    val taskHistoryActions: List<ActionRecord> = emptyList(),
    val recentActions: List<ActionRecord> = emptyList(),
    val totalSteps: Int = 0,
    val lastAction: String? = null,
    val lastActionResult: String? = null,
    val recentOutcomes: List<ActionOutcome> = emptyList(),
    val recentToolResults: List<ToolResult> = emptyList(),
    val verifierDecisions: List<VerifierDecision> = emptyList(),
    val completionEvidence: List<String> = emptyList(),
    val missingEvidence: List<String> = emptyList(),
    val humanAssistanceEvents: List<HumanAssistanceEvent> = emptyList(),
    val pendingConfirmation: PendingConfirmation? = null,
    val confirmedActionHashes: List<String> = emptyList(),
    val stuckCount: Int = 0,
    val screenshotBase64: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Record of a single action execution.
 */
@Serializable
data class ActionRecord(
    val action: String,
    val result: String,
    val pageSignatureBefore: String,
    val pageSignatureAfter: String,
    val timestamp: Long,
    val pageAffordancesBefore: List<String> = emptyList(),
    val pageAffordancesAfter: List<String> = emptyList(),
    val visibleTextsBefore: List<String> = emptyList(),
    val visibleTextsAfter: List<String> = emptyList(),
    val actionCandidatesBefore: List<String> = emptyList(),
    val resultCode: ActionResultCode? = null,
    val targetLabel: String? = null,
    val origin: ActionOrigin = ActionOrigin.REACT
)

@Serializable
enum class CandidateKind {
    INPUT,
    TOGGLE,
    COLLECTION_ITEM,
    ACTION,
    NAVIGATION,
    DISMISS,
    MEDIA,
    CONTAINER,
    UNKNOWN
}

@Serializable
data class ActionCandidate(
    val id: String,
    val kind: CandidateKind,
    val role: String,
    val label: String,
    val actionable: Boolean,
    val editable: Boolean,
    val pointX: Int,
    val pointY: Int,
    val boundsLeft: Int,
    val boundsTop: Int,
    val boundsRight: Int,
    val boundsBottom: Int,
    val groupId: String? = null,
    val indexInGroup: Int? = null,
    val stateHint: String? = null,
    val confidence: Float = 0f
)

fun ActionCandidate.toDisplayString(): String {
    val safeLabel = label.replace("|", " ").replace("\"", "'").trim().take(32)
    val displayRole = role.trim().ifBlank { kind.name }.uppercase()
    val fallbackLabel = when {
        safeLabel.isNotBlank() -> safeLabel
        editable -> "[input]"
        actionable -> "[tap_${kind.name.lowercase()}]"
        else -> "[${kind.name.lowercase()}]"
    }
    return "[${displayRole}] \"$fallbackLabel\" | point($pointX, $pointY)"
}

@Serializable
enum class ActionOrigin {
    REACT,
    SYSTEM
}

enum class ActionCategory {
    CONTROL,
    ENVIRONMENT,
    BUSINESS
}

private val controlActionNames = setOf("finished")
private val environmentActionNames = setOf("open_app", "resolve_installed_app", "press_home")

fun canonicalActionName(action: String): String {
    val compact = action.replace(Regex("\\s+"), " ").trim()
    val withoutArgs = compact.substringBefore("(").trim()
    return withoutArgs.ifBlank {
        compact.substringBefore("{").trim().ifBlank { compact }
    }.lowercase()
}

fun classifyActionCategory(action: String): ActionCategory {
    return when (canonicalActionName(action)) {
        in controlActionNames -> ActionCategory.CONTROL
        in environmentActionNames -> ActionCategory.ENVIRONMENT
        else -> ActionCategory.BUSINESS
    }
}

fun isControlAction(action: String): Boolean = classifyActionCategory(action) == ActionCategory.CONTROL

fun isEnvironmentAction(action: String): Boolean = classifyActionCategory(action) == ActionCategory.ENVIRONMENT

fun isBusinessAction(action: String): Boolean = classifyActionCategory(action) == ActionCategory.BUSINESS

/**
 * Standardized action outcome used by evidence engine.
 */
@Serializable
data class ActionOutcome(
    val toolName: String,
    val actionText: String,
    val resultCode: ActionResultCode,
    val targetLabel: String? = null,
    val pageSignatureBefore: String,
    val pageSignatureAfter: String,
    val timestamp: Long
)

@Serializable
enum class ActionResultCode {
    SUCCESS,
    NOOP,
    NOT_FOUND,
    BLOCKED,
    ERROR,
    SUPPRESSED,
    UNKNOWN
}

/**
 * Structured primitive-tool result for WorldState, verifier evidence, recovery,
 * replay, and benchmark analysis.
 */
@Serializable
data class ToolResult(
    val toolName: String,
    val actionText: String,
    val status: ActionResultCode,
    val targetLabel: String? = null,
    val changed: Boolean,
    val rawSignatureChanged: Boolean,
    val pageSignatureBefore: String,
    val pageSignatureAfter: String,
    val message: String = "",
    val errorType: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

fun ActionResultCode.isUnreliableForControlProgress(): Boolean {
    return this in setOf(
        ActionResultCode.BLOCKED,
        ActionResultCode.ERROR,
        ActionResultCode.SUPPRESSED,
        ActionResultCode.NOT_FOUND
    )
}

fun isUnreliableResultText(result: String): Boolean {
    val lower = result.lowercase()
    return lower.contains("error") ||
        result.contains("错误") ||
        result.contains("抑制") ||
        result.contains("无进展") ||
        result.contains("不可用工具")
}

/**
 * Pending confirmation request bound to an action hash.
 */
@Serializable
data class PendingConfirmation(
    val actionHash: String,
    val actionSummary: String,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Read-only completion judgment produced by VerifierAgent.
 */
@Serializable
data class VerifierDecision(
    val complete: Boolean,
    val confidence: Float,
    val evidence: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
    @SerialName("next_hint")
    val nextHint: String = "",
    val rawOutput: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
enum class HumanAssistanceRequestType {
    USER_ACTION,
    USER_TEXT,
    USER_CONFIRMATION
}

@Serializable
enum class HumanAssistanceStatus {
    REQUESTED,
    COMPLETED,
    CANCELLED,
    FAILED
}

/**
 * Structured record of a user-assisted unblock attempt.
 */
@Serializable
data class HumanAssistanceEvent(
    val type: HumanAssistanceRequestType,
    val prompt: String,
    val status: HumanAssistanceStatus,
    val responseText: String? = null,
    val reason: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Type converters for Room to handle complex lists in WorldState.
 */
class WorldStateConverters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromStringList(value: List<String>): String = json.encodeToString(value)

    @TypeConverter
    fun toStringList(value: String): List<String> = json.decodeFromString(value)

    @TypeConverter
    fun fromNestedStringList(value: List<List<String>>): String = json.encodeToString(value)

    @TypeConverter
    fun toNestedStringList(value: String): List<List<String>> = json.decodeFromString(value)

    @TypeConverter
    fun fromActionRecordList(value: List<ActionRecord>): String = json.encodeToString(value)

    @TypeConverter
    fun toActionRecordList(value: String): List<ActionRecord> = json.decodeFromString(value)

    @TypeConverter
    fun fromActionOutcomeList(value: List<ActionOutcome>): String = json.encodeToString(value)

    @TypeConverter
    fun toActionOutcomeList(value: String): List<ActionOutcome> = json.decodeFromString(value)

    @TypeConverter
    fun fromToolResultList(value: List<ToolResult>): String = json.encodeToString(value)

    @TypeConverter
    fun toToolResultList(value: String): List<ToolResult> = json.decodeFromString(value)

    @TypeConverter
    fun fromActionCandidateList(value: List<ActionCandidate>): String = json.encodeToString(value)

    @TypeConverter
    fun toActionCandidateList(value: String): List<ActionCandidate> = json.decodeFromString(value)

    @TypeConverter
    fun fromVerifierDecisionList(value: List<VerifierDecision>): String = json.encodeToString(value)

    @TypeConverter
    fun toVerifierDecisionList(value: String): List<VerifierDecision> = json.decodeFromString(value)

    @TypeConverter
    fun fromHumanAssistanceEventList(value: List<HumanAssistanceEvent>): String = json.encodeToString(value)

    @TypeConverter
    fun toHumanAssistanceEventList(value: String): List<HumanAssistanceEvent> = json.decodeFromString(value)

    @TypeConverter
    fun fromPendingConfirmation(value: PendingConfirmation?): String? {
        return value?.let { json.encodeToString(it) }
    }

    @TypeConverter
    fun toPendingConfirmation(value: String?): PendingConfirmation? {
        return value?.takeIf { it.isNotBlank() }?.let { json.decodeFromString(it) }
    }

}
