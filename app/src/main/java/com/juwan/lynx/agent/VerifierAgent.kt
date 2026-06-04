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

package com.juwan.lynx.agent

import android.util.Log
import com.juwan.lynx.api.ChatMessage
import com.juwan.lynx.runtime.AgentConfig
import com.juwan.lynx.runtime.AgentResult
import com.juwan.lynx.runtime.AgentRuntime
import com.juwan.lynx.runtime.ModelConfig
import com.juwan.lynx.state.VerifierDecision
import com.juwan.lynx.state.WorldState
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class VerifierAgent(
    private val runtime: AgentRuntime,
    private val modelConfig: ModelConfig
) {
    private val tag = "VerifierAgent"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun verify(
        strategy: Strategy,
        state: WorldState,
        finishProposal: String
    ): VerifierDecision {
        val input = buildVerificationInput(strategy, state, finishProposal)
        val config = AgentConfig(
            name = "VerifierAgent",
            model = modelConfig.copy(temperature = 0.0f),
            systemPrompt = SYSTEM_PROMPT,
            tools = emptyList(),
            maxIterations = 1,
            useVision = !state.screenshotBase64.isNullOrBlank(),
            imageBase64 = state.screenshotBase64,
            conversationHistory = emptyList<ChatMessage>()
        )
        return try {
            when (val result = runtime.runAgent(config, input)) {
                is AgentResult.Finished -> parseDecision(result.output)
                is AgentResult.Error -> incomplete(
                    reason = "verifier_model_error:${result.message}",
                    rawOutput = result.message
                )
                is AgentResult.MaxIterationsReached -> incomplete(
                    reason = "verifier_max_iterations",
                    rawOutput = "max_iterations"
                )
                is AgentResult.ToolCallResult -> incomplete(
                    reason = "verifier_unexpected_tool_result:${result.toolName}",
                    rawOutput = result.result
                )
            }
        } catch (e: CancellationException) {
            FlowTraceLogger.warn(stage = "verifier_cancelled", kv = mapOf("reason" to (e.message ?: "cancelled")))
            throw e
        } catch (e: Exception) {
            Log.w(tag, "Verifier failed: ${e.message}", e)
            incomplete(reason = "verifier_exception:${e.message}", rawOutput = e.message.orEmpty())
        }
    }

    private fun parseDecision(output: String): VerifierDecision {
        val candidates = buildJsonCandidates(output)
        for (candidate in candidates) {
            try {
                val obj = json.parseToJsonElement(candidate).jsonObject
                return VerifierDecision(
                    complete = obj.booleanValue("complete"),
                    confidence = obj.floatValue("confidence").coerceIn(0.0f, 1.0f),
                    evidence = obj.stringArray("evidence").take(8),
                    missing = obj.stringArray("missing").take(8),
                    nextHint = obj.stringValue("next_hint").take(240),
                    rawOutput = output.take(2000)
                )
            } catch (_: Exception) {
                // Try next candidate.
            }
        }
        return incomplete(
            reason = "verifier_json_parse_failed",
            rawOutput = output.take(2000)
        )
    }

    private fun buildVerificationInput(
        strategy: Strategy,
        state: WorldState,
        finishProposal: String
    ): String {
        return buildString {
            appendLine("Original user goal: ${strategy.originalInstruction.ifBlank { strategy.goal }}")
            appendLine("Normalized goal: ${strategy.goal}")
            appendLine("Target app: ${strategy.app.ifBlank { state.targetApp }}")
            appendLine("Current app: ${state.currentApp ?: "unknown"}")
            appendLine("Finish proposal: ${finishProposal.take(300)}")
            appendSection("Success criteria", state.successCriteria.ifEmpty { strategy.successCriteria })
            appendSection("Visible texts", state.visibleTexts)
            appendSection("UI texts", state.uiTexts)
            appendSection("OCR texts", state.ocrTexts)
            appendSection("Content descriptions", state.contentDescriptions)
            appendSection("Recent actions", state.recentActions.takeLast(6).map { record ->
                "${record.action} => ${record.result} [${record.resultCode ?: "UNKNOWN"}]"
            })
            val structuredToolResults = state.recentToolResults.takeLast(6).map { result ->
                "${result.toolName}:${result.status} changed=${result.changed} target=${result.targetLabel ?: ""} error=${result.errorType ?: ""} before=${result.pageSignatureBefore.take(40)} after=${result.pageSignatureAfter.take(40)}"
            }.ifEmpty {
                state.recentOutcomes.takeLast(6).map { outcome ->
                    "${outcome.toolName}:${outcome.resultCode} target=${outcome.targetLabel ?: ""} before=${outcome.pageSignatureBefore.take(40)} after=${outcome.pageSignatureAfter.take(40)}"
                }
            }
            appendSection("Recent structured tool results", structuredToolResults)
            if (state.pendingConfirmation != null) {
                appendLine("Pending confirmation: ${state.pendingConfirmation.actionSummary} reason=${state.pendingConfirmation.reason}")
            }
            if (state.missingEvidence.isNotEmpty()) {
                appendSection("Previously missing evidence", state.missingEvidence)
            }
        }.trim()
    }

    private fun StringBuilder.appendSection(title: String, values: List<String>) {
        appendLine("$title:")
        if (values.isEmpty()) {
            appendLine("- none")
        } else {
            values.take(12).forEach { value ->
                appendLine("- ${value.replace(Regex("\\s+"), " ").trim().take(180)}")
            }
        }
    }

    private fun buildJsonCandidates(response: String): List<String> {
        val stripped = stripCodeFence(response).trim()
        val candidates = mutableListOf<String>()
        if (stripped.isNotBlank()) candidates.add(stripped)
        extractFirstJsonObject(stripped)?.let { candidates.add(it) }
        return candidates.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }

    private fun stripCodeFence(text: String): String {
        val codeBlock = Regex(
            """```(?:json)?\s*(.*?)\s*```""",
            setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)
        ).find(text)
        return codeBlock?.groupValues?.get(1) ?: text
    }

    private fun extractFirstJsonObject(text: String): String? {
        var start = -1
        var depth = 0
        var inString = false
        var escaped = false
        for (index in text.indices) {
            val char = text[index]
            if (escaped) {
                escaped = false
                continue
            }
            if (char == '\\') {
                escaped = true
                continue
            }
            if (char == '"') {
                inString = !inString
                continue
            }
            if (inString) continue
            if (char == '{') {
                if (depth == 0) start = index
                depth++
            } else if (char == '}') {
                if (depth > 0) {
                    depth--
                    if (depth == 0 && start >= 0) {
                        return text.substring(start, index + 1)
                    }
                }
            }
        }
        return null
    }

    private fun incomplete(reason: String, rawOutput: String): VerifierDecision {
        return VerifierDecision(
            complete = false,
            confidence = 0.0f,
            evidence = emptyList(),
            missing = listOf(reason),
            nextHint = "继续收集可观察完成证据后再提出 finished",
            rawOutput = rawOutput.take(2000)
        )
    }

    private fun JsonObject.booleanValue(key: String): Boolean {
        return this[key]?.jsonPrimitive?.contentOrNull?.equals("true", ignoreCase = true) == true
    }

    private fun JsonObject.floatValue(key: String): Float {
        return this[key]?.jsonPrimitive?.floatOrNull ?: 0.0f
    }

    private fun JsonObject.stringValue(key: String): String {
        return this[key]?.jsonPrimitive?.contentOrNull
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
    }

    private fun JsonObject.stringArray(key: String): List<String> {
        val array = this[key] as? JsonArray ?: return emptyList()
        return array.mapNotNull { item: JsonElement ->
            item.jsonPrimitive.contentOrNull
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }
    }

    companion object {
        private const val SYSTEM_PROMPT = """
You are VerifierAgent, a read-only mobile task completion judge.

Your job is to decide whether the user's task is complete using only observable evidence in the current screenshot/text/state.

Hard rules:
- Do not call tools.
- Do not invent hidden state.
- Do not accept completion from the executor's claim alone.
- If evidence is missing, mark complete=false and give one concise next_hint.
- Ignore irrelevant UI details that are not implied by the user goal.

Return exactly one JSON object:
{"complete":false,"confidence":0.0,"evidence":[],"missing":[],"next_hint":""}
"""
    }
}
