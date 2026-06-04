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

import android.util.Log
import com.juwan.lynx.agent.FlowTraceLogger
import com.juwan.lynx.api.ApiToolDefinition
import com.juwan.lynx.api.ApiToolParameter
import com.juwan.lynx.api.ApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Custom ReAct loop implementation as a fallback when Koog is unavailable.
 * Implements the ReAct pattern: Reasoning → Action → Observation → Repeat
 *
 * @param apiClient LLM API client for making chat requests
 */
class SimpleReActRuntime(
    private val apiClient: ApiClient
) : AgentRuntime {
    private val TAG = "SimpleReActRuntime"
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val MAX_EMPTY_RESPONSE_ATTEMPTS = 3
        private const val MAX_VISION_EMPTY_RESPONSE_ATTEMPTS = 2
        private const val EMPTY_RESPONSE_RETRY_DELAY_MS = 300L
        private val COORDINATE_TOOL_NAMES = setOf("click", "long_press", "drag", "scroll")
    }

    override suspend fun runAgent(config: AgentConfig, input: String): AgentResult {
        val history = mutableListOf<Pair<ToolCall, String>>()
        var iteration = 0
        val apiTools = buildApiToolDefinitions(config.tools)
        var currentInput = input
        var currentUseVision = config.useVision
        var currentImageBase64 = config.imageBase64

        while (iteration < config.maxIterations) {
            try {
                if (iteration > 0) {
                    val refreshedContext = runCatching {
                        config.iterationContextProvider?.invoke(iteration)
                    }.onFailure {
                        Log.w(TAG, "Iteration context refresh failed at $iteration: ${it.message}")
                    }.getOrNull()
                    if (refreshedContext != null) {
                        currentInput = refreshedContext.input
                        currentUseVision = refreshedContext.useVision
                        currentImageBase64 = refreshedContext.imageBase64
                    }
                }

                // Build messages from history
                val messages = buildMessages(currentInput, history, config.conversationHistory)

                Log.d(TAG, "Calling LLM with systemPrompt length: ${config.systemPrompt.length}")
                Log.d(TAG, "Messages count: ${messages.size}")
                messages.forEachIndexed { index, msg ->
                    Log.d(TAG, "Message $index: role=${msg.role}, content length=${msg.content.length}")
                }

                val modelResponse = requestModelWithEmptyResponseRetries(
                    config = config,
                    messages = messages,
                    useVision = currentUseVision,
                    imageBase64 = currentImageBase64,
                    tools = apiTools,
                    iteration = iteration
                )
                val response = modelResponse.response

                if (response.isEmpty()) {
                    return AgentResult.Error("Empty response from LLM")
                }

                // If no tools are configured, treat model output as final text directly.
                // This avoids misleading function-call parse warnings for planner/reflection JSON.
                if (config.tools.isEmpty()) {
                    return AgentResult.Finished(response)
                }

                // Parse function call:
                // 1) Prefer API-client parser (understands provider envelope formats)
                // 2) Fallback to local parser
                val rawToolCall = apiClient.parseFunctionCall(response)?.let {
                    ToolCall(name = it.name, args = it.args)
                } ?: parseFunctionCall(response)

                if (rawToolCall == null) {
                    // In tool-driven executor mode, plain text without a tool call is a protocol error,
                    // not a successful finish. Planner/reflection paths are already handled above by
                    // the "tools.isEmpty()" branch.
                    return AgentResult.Error(
                        "No tool call in model response: ${smartClip(response, 180)}"
                    )
                }

                val toolCall = rawToolCall
                val allowedToolNames = modelResponse.allowedToolNames
                if (allowedToolNames != null && toolCall.name !in allowedToolNames) {
                    return AgentResult.Error("Tool not allowed in current fallback mode: ${toolCall.name}")
                }

                // Find the tool
                val tool = config.tools.find { it.name == toolCall.name }
                    ?: return AgentResult.Error("Unknown tool: ${toolCall.name}")

                // Execute the tool
                val startedAt = System.currentTimeMillis()
                val result = try {
                    tool.execute(toolCall.args)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Tool execution failed", e)
                    "Error executing tool: ${e.message}"
                }
                val latencyMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
                val resultForLog = summarizeResultForHistory(sanitizeResult(result), maxChars = 140)
                FlowTraceLogger.event(
                    stage = "tool_call",
                    kv = mapOf(
                        "tool" to toolCall.name,
                        "args" to smartClip(
                            toolCall.args.toString().replace(Regex("\\s+"), " ").trim(),
                            maxChars = 100
                        ),
                        "result" to resultForLog,
                        "latency_ms" to latencyMs
                    )
                )
                FlowTraceLogger.recordToolCall()

                // Check if this is the finish tool
                if (toolCall.name == "finished") {
                    return AgentResult.Finished("FINISH_TOOL_EXECUTED: $result")
                }

                // Add to history
                history.add(toolCall to result)

                iteration++
            } catch (e: CancellationException) {
                FlowTraceLogger.warn(
                    stage = "runtime_cancelled",
                    kv = mapOf("reason" to (e.message ?: "cancelled"))
                )
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error in ReAct loop", e)
                return AgentResult.Error("ReAct loop error: ${e.message}")
            }
        }

        return AgentResult.MaxIterationsReached
    }

    private suspend fun requestModelWithEmptyResponseRetries(
        config: AgentConfig,
        messages: List<com.juwan.lynx.api.ChatMessage>,
        useVision: Boolean,
        imageBase64: String?,
        tools: List<ApiToolDefinition>,
        iteration: Int
    ): ModelResponse {
        val primary = requestModelAttempts(
            config = config,
            messages = messages,
            useVision = useVision,
            imageBase64 = imageBase64,
            tools = tools,
            iteration = iteration,
            maxAttempts = if (useVision && !imageBase64.isNullOrBlank()) {
                MAX_VISION_EMPTY_RESPONSE_ATTEMPTS
            } else {
                MAX_EMPTY_RESPONSE_ATTEMPTS
            }
        )
        if (primary.isNotBlank()) {
            return ModelResponse(primary, allowedToolNames = null)
        }

        if (useVision && !imageBase64.isNullOrBlank()) {
            FlowTraceLogger.warn(
                stage = "llm_empty_response_text_fallback",
                kv = mapOf(
                    "iteration" to iteration,
                    "reason" to "vision_empty_response_exhausted"
                )
            )
            val textFallbackTools = tools.filterNot { isCoordinateTool(it) }
            val textFallbackResponse = requestModelAttempts(
                config = config,
                messages = messages,
                useVision = false,
                imageBase64 = null,
                tools = textFallbackTools,
                iteration = iteration,
                maxAttempts = MAX_EMPTY_RESPONSE_ATTEMPTS
            )
            return ModelResponse(
                response = textFallbackResponse,
                allowedToolNames = textFallbackTools.map { it.name }.toSet()
            )
        }

        return ModelResponse("", allowedToolNames = null)
    }

    private suspend fun requestModelAttempts(
        config: AgentConfig,
        messages: List<com.juwan.lynx.api.ChatMessage>,
        useVision: Boolean,
        imageBase64: String?,
        tools: List<ApiToolDefinition>,
        iteration: Int,
        maxAttempts: Int
    ): String {
        var attempt = 0
        var timeoutSeconds = config.model.timeoutSeconds.coerceIn(10, 120)

        while (attempt < maxAttempts) {
            val response = requestModel(
                systemPrompt = config.systemPrompt,
                messages = messages,
                useVision = useVision,
                imageBase64 = imageBase64,
                temperature = config.model.temperature,
                tools = tools,
                timeoutSeconds = timeoutSeconds
            )

            if (response.isNotBlank()) {
                return response
            }

            attempt++
            if (attempt >= maxAttempts) {
                break
            }

            val retryTimeoutSeconds = (timeoutSeconds + 10).coerceAtMost(120)
            FlowTraceLogger.warn(
                stage = "llm_empty_response_retry",
                kv = mapOf(
                    "iteration" to iteration,
                    "attempt" to attempt,
                    "timeout_seconds" to timeoutSeconds,
                    "retry_timeout_seconds" to retryTimeoutSeconds,
                    "use_vision" to useVision
                )
            )
            delay(EMPTY_RESPONSE_RETRY_DELAY_MS * attempt)
            timeoutSeconds = retryTimeoutSeconds
        }

        FlowTraceLogger.warn(
            stage = "llm_empty_response_exhausted",
            kv = mapOf(
                "iteration" to iteration,
                "max_attempts" to maxAttempts,
                "use_vision" to useVision
            )
        )
        return ""
    }

    private suspend fun requestModel(
        systemPrompt: String,
        messages: List<com.juwan.lynx.api.ChatMessage>,
        useVision: Boolean,
        imageBase64: String?,
        temperature: Float,
        tools: List<ApiToolDefinition>,
        timeoutSeconds: Int
    ): String {
        return if (useVision && !imageBase64.isNullOrBlank()) {
            Log.d(
                TAG,
                "Routing request to vision API, image length=${imageBase64.length}, timeout=${timeoutSeconds}s"
            )
            apiClient.chatWithVision(
                systemPrompt = systemPrompt,
                messages = messages,
                imageBase64 = imageBase64,
                temperature = temperature,
                tools = tools,
                timeoutSeconds = timeoutSeconds
            )
        } else {
            apiClient.chat(
                systemPrompt = systemPrompt,
                messages = messages,
                temperature = temperature,
                tools = tools,
                timeoutSeconds = timeoutSeconds
            )
        }
    }

    /**
     * Build chat messages from input and history.
     * Uses compact action log format instead of full conversation history.
     */
    private fun buildMessages(
        input: String,
        history: List<Pair<ToolCall, String>>,
        priorHistory: List<com.juwan.lynx.api.ChatMessage> = emptyList()
    ): List<com.juwan.lynx.api.ChatMessage> {
        val messages = mutableListOf<com.juwan.lynx.api.ChatMessage>()

        // Bring forward curated cross-iteration context from caller (e.g. action/result pairs).
        if (priorHistory.isNotEmpty()) {
            messages.addAll(priorHistory)
        }

        // Add compact action log if history exists
        if (history.isNotEmpty()) {
            val actionLog = buildActionLog(history)
            messages.add(com.juwan.lynx.api.ChatMessage("user", "Action history:\n$actionLog"))
        }

        // Put the latest screen/task context last so it remains the freshest chunk after flattening.
        messages.add(com.juwan.lynx.api.ChatMessage("user", input))

        return messages
    }

    /**
     * Build compact action log from history.
     * Format: [action_name(args)] -> result
     * Only includes last 5 actions with smart truncation (head + tail preservation).
     */
    private fun buildActionLog(history: List<Pair<ToolCall, String>>): String {
        if (history.isEmpty()) return ""

        val maxActionChars = 56
        val maxResultChars = 120
        val maxLineChars = 180
        val recentActions = history.takeLast(4)
        return recentActions.joinToString("\n") { (toolCall, result) ->
            // Extract key args (max 2)
            val keyArgs = toolCall.args.entries.take(2).joinToString(", ") { (k, v) ->
                // Sanitize sensitive parameters
                val sanitizedValue = if (k.lowercase() in setOf("password", "token", "apikey", "secret")) {
                    "***"
                } else {
                    v
                }
                "$k=$sanitizedValue"
            }
            
            val actionStr = if (keyArgs.isNotEmpty()) {
                "${toolCall.name}($keyArgs)"
            } else {
                toolCall.name
            }

            // Sanitize PII from result
            val sanitizedResult = sanitizeResult(result)

            // Use key-anchor-aware summary so status/coordinates are kept under truncation.
            val actionSummary = smartClip(actionStr, maxActionChars)
            val resultSummary = summarizeResultForHistory(sanitizedResult, maxResultChars)

            // Keep each line bounded to avoid ballooning prompt size.
            val logEntry = "[$actionSummary] -> $resultSummary"
            smartClip(logEntry, maxLineChars)
        }
    }

    /**
     * Sanitize PII information from result string.
     */
    private fun sanitizeResult(result: String): String {
        val piiPatterns = listOf(
            Regex("\\d{11}"),           // 手机号
            Regex("\\d{17}[0-9X]"),     // 身份证
            Regex("\\d{16}")            // 银行卡
        )
        var sanitized = result
        piiPatterns.forEach { pattern ->
            sanitized = sanitized.replace(pattern, "***")
        }
        return sanitized
    }

    private fun smartClip(raw: String, maxChars: Int): String {
        val normalized = raw.replace(Regex("\\s+"), " ").trim()
        if (normalized.length <= maxChars) return normalized
        if (maxChars <= 12) return normalized.take(maxChars)

        val head = (maxChars * 0.6).toInt().coerceAtLeast(8)
        val tail = (maxChars - head - 3).coerceAtLeast(4)
        return normalized.take(head) + "..." + normalized.takeLast(tail)
    }

    private fun summarizeResultForHistory(raw: String, maxChars: Int): String {
        val normalized = raw.replace(Regex("\\s+"), " ").trim()
        if (normalized.length <= maxChars) return normalized

        val anchors = collectResultAnchors(normalized)
        if (anchors.isEmpty()) return smartClip(normalized, maxChars)

        val anchorText = smartClip(anchors.joinToString(" | "), (maxChars * 0.55).toInt().coerceAtLeast(24))
        val bodyBudget = (maxChars - anchorText.length - 3).coerceAtLeast(16)
        val body = smartClip(normalized, bodyBudget)
        return smartClip("$body | $anchorText", maxChars)
    }

    private fun collectResultAnchors(text: String): List<String> {
        val patterns = listOf(
            Regex("""Error:\s*[^,，。;；]{1,48}""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:SUCCESS|FAILED|FAIL|ERROR|BLOCKED|TIMEOUT|UNKNOWN)\b""", RegexOption.IGNORE_CASE),
            Regex("""\bblocked_by_[a-z_]+\b""", RegexOption.IGNORE_CASE),
            Regex("""\bresult_code\s*=\s*[A-Z_]+\b""", RegexOption.IGNORE_CASE),
            Regex("""<point>\s*-?\d+(?:\.\d+)?\s+-?\d+(?:\.\d+)?\s*</point>""", RegexOption.IGNORE_CASE),
            Regex("""(?:像素|pixel|坐标)\s*\(\s*-?\d+\s*,\s*-?\d+\s*\)""", RegexOption.IGNORE_CASE),
            Regex("""\(\s*-?\d+\s*,\s*-?\d+\s*\)"""),
            Regex("""(?:未找到|找到|已点击|已输入|已打开|超时|失败|成功)[^,，。;；]{0,24}""")
        )

        val anchors = linkedSetOf<String>()
        patterns.forEach { pattern ->
            pattern.findAll(text).forEach { match ->
                val value = match.value.replace(Regex("\\s+"), " ").trim()
                if (value.isNotBlank()) anchors += value
            }
        }
        return anchors.take(4)
    }

    /**
     * Build JSON representation of a tool call for the LLM.
     */
    private fun buildToolCallJson(toolCall: ToolCall): String {
        val argsJson = toolCall.args.entries.joinToString(",") { (k, v) ->
            "\"$k\":\"$v\""
        }
        return """
            {
                "name": "${toolCall.name}",
                "args": {$argsJson}
            }
        """.trimIndent()
    }

    /**
     * Parse a function call from LLM response.
     * Looks for JSON in the format: {"name": "...", "args": {...}}
     */
    fun parseFunctionCall(response: String): ToolCall? {
        return try {
            // Try to extract JSON from code blocks first
            val jsonMatch = Regex("""```(?:json)?\s*(.*?)\s*```""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
                .find(response)
            val jsonStr = jsonMatch?.groupValues?.get(1)?.trim().orEmpty()
                .ifBlank { response.trim() }

            safeParseToolCallJson(jsonStr)
                ?: run {
                    val candidates = extractJsonObjectCandidates(jsonStr)
                    candidates.asReversed().firstNotNullOfOrNull { candidate ->
                        safeParseToolCallJson(candidate)
                    }
                }
                ?: parseFunctionLikeToolCall(jsonStr)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse function call from response: ${e.message}")
            null
        }
    }

    private fun safeParseToolCallJson(raw: String): ToolCall? {
        return runCatching { parseToolCallJson(raw) }.getOrNull()
    }

    private fun extractJsonObjectCandidates(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val candidates = mutableListOf<String>()
        var inString = false
        var escaped = false
        var depth = 0
        var start = -1

        text.forEachIndexed { index, ch ->
            if (escaped) {
                escaped = false
                return@forEachIndexed
            }
            if (ch == '\\' && inString) {
                escaped = true
                return@forEachIndexed
            }
            if (ch == '"') {
                inString = !inString
                return@forEachIndexed
            }
            if (inString) return@forEachIndexed

            when (ch) {
                '{' -> {
                    if (depth == 0) start = index
                    depth++
                }
                '}' -> {
                    if (depth <= 0) return@forEachIndexed
                    depth--
                    if (depth == 0 && start >= 0 && index > start) {
                        candidates += text.substring(start, index + 1)
                        start = -1
                    }
                }
            }
        }
        return candidates
    }

    private fun parseToolCallJson(raw: String): ToolCall? {
        val root = json.parseToJsonElement(raw).jsonObject
        val name = root["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (name.isBlank()) return null

        val argsObj = root["args"]?.jsonObject ?: JsonObject(emptyMap())
        val args = mutableMapOf<String, String>()
        argsObj.forEach { (key, value) ->
            when (value) {
                is JsonPrimitive -> {
                    args[key] = value.contentOrNull ?: value.toString()
                }
                is JsonObject -> {
                    // Normalize common nested format:
                    // {"name":"click","args":{"position":{"x":922,"y":75}}}
                    if (name == "click" && key == "position") {
                        val x = value["x"]?.jsonPrimitive?.contentOrNull
                        val y = value["y"]?.jsonPrimitive?.contentOrNull
                        if (!x.isNullOrBlank()) args["x"] = x
                        if (!y.isNullOrBlank()) args["y"] = y
                    } else {
                        args[key] = value.toString()
                    }
                }
                else -> {
                    args[key] = value.toString()
                }
            }
        }
        return ToolCall(name = name, args = args)
    }

    private fun parseFunctionLikeToolCall(text: String): ToolCall? {
        if (text.isBlank()) return null
        val candidates = text.lineSequence()
            .map { normalizeFunctionLikeLine(it) }
            .filter { it.isNotBlank() }
            .mapNotNull { parseFunctionLikeLine(it) }
            .toList()
        return candidates.lastOrNull()
    }

    private fun normalizeFunctionLikeLine(raw: String): String {
        val trimmed = raw.trim().trim('`')
        if (trimmed.isBlank()) return ""
        return trimmed
            .removePrefix("Action:")
            .removePrefix("Tool:")
            .removePrefix("Next action:")
            .trim()
    }

    private fun parseFunctionLikeLine(line: String): ToolCall? {
        val match = Regex("""^([A-Za-z_][A-Za-z0-9_]*)\((.*)\)$""").matchEntire(line) ?: return null
        val name = match.groupValues[1].trim()
        val argBlock = match.groupValues[2].trim()
        if (name.isBlank()) return null
        if (argBlock.isBlank()) return ToolCall(name = name, args = emptyMap())

        val args = linkedMapOf<String, String>()
        splitTopLevelArgs(argBlock).forEach { part ->
            val separator = findAssignmentSeparator(part)
            if (separator <= 0) return@forEach
            val key = part.substring(0, separator).trim()
            val rawValue = part.substring(separator + 1).trim()
            if (key.isNotBlank() && rawValue.isNotBlank()) {
                args[key] = normalizePseudoArgValue(rawValue)
            }
        }
        if (args.isEmpty()) return null
        return ToolCall(name = name, args = args)
    }

    private fun splitTopLevelArgs(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inSingleQuote = false
        var inDoubleQuote = false
        var escaped = false
        var roundDepth = 0
        var squareDepth = 0
        var curlyDepth = 0

        raw.forEach { ch ->
            if (escaped) {
                current.append(ch)
                escaped = false
                return@forEach
            }
            if (ch == '\\' && (inSingleQuote || inDoubleQuote)) {
                current.append(ch)
                escaped = true
                return@forEach
            }
            when (ch) {
                '\'' -> {
                    if (!inDoubleQuote) inSingleQuote = !inSingleQuote
                    current.append(ch)
                }
                '"' -> {
                    if (!inSingleQuote) inDoubleQuote = !inDoubleQuote
                    current.append(ch)
                }
                '(' -> {
                    if (!inSingleQuote && !inDoubleQuote) roundDepth++
                    current.append(ch)
                }
                ')' -> {
                    if (!inSingleQuote && !inDoubleQuote && roundDepth > 0) roundDepth--
                    current.append(ch)
                }
                '[' -> {
                    if (!inSingleQuote && !inDoubleQuote) squareDepth++
                    current.append(ch)
                }
                ']' -> {
                    if (!inSingleQuote && !inDoubleQuote && squareDepth > 0) squareDepth--
                    current.append(ch)
                }
                '{' -> {
                    if (!inSingleQuote && !inDoubleQuote) curlyDepth++
                    current.append(ch)
                }
                '}' -> {
                    if (!inSingleQuote && !inDoubleQuote && curlyDepth > 0) curlyDepth--
                    current.append(ch)
                }
                ',' -> {
                    if (!inSingleQuote && !inDoubleQuote && roundDepth == 0 && squareDepth == 0 && curlyDepth == 0) {
                        val part = current.toString().trim()
                        if (part.isNotBlank()) parts += part
                        current.clear()
                    } else {
                        current.append(ch)
                    }
                }
                else -> current.append(ch)
            }
        }

        val tail = current.toString().trim()
        if (tail.isNotBlank()) parts += tail
        return parts
    }

    private fun findAssignmentSeparator(raw: String): Int {
        var inSingleQuote = false
        var inDoubleQuote = false
        var escaped = false
        raw.forEachIndexed { index, ch ->
            if (escaped) {
                escaped = false
                return@forEachIndexed
            }
            if (ch == '\\' && (inSingleQuote || inDoubleQuote)) {
                escaped = true
                return@forEachIndexed
            }
            when (ch) {
                '\'' -> if (!inDoubleQuote) inSingleQuote = !inSingleQuote
                '"' -> if (!inSingleQuote) inDoubleQuote = !inDoubleQuote
                '=' -> if (!inSingleQuote && !inDoubleQuote) return index
            }
        }
        return -1
    }

    private fun normalizePseudoArgValue(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length >= 2) {
            val first = trimmed.first()
            val last = trimmed.last()
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return trimmed.substring(1, trimmed.length - 1)
                    .replace("\\\"", "\"")
                    .replace("\\'", "'")
            }
        }
        return trimmed
    }

    private fun buildApiToolDefinitions(tools: List<Tool>): List<ApiToolDefinition> {
        if (tools.isEmpty()) return emptyList()
        return tools.map { tool ->
            ApiToolDefinition(
                name = tool.name,
                description = tool.description,
                parameters = tool.parameters.map { param ->
                    ApiToolParameter(
                        name = param.name,
                        type = normalizeParamType(param.type),
                        description = param.description,
                        required = param.required
                    )
                }
            )
        }
    }

    private fun isCoordinateTool(tool: ApiToolDefinition): Boolean {
        if (tool.name in COORDINATE_TOOL_NAMES) return true
        val parameterNames = tool.parameters.map { it.name.lowercase() }.toSet()
        return parameterNames.intersect(
            setOf("x", "y", "startx", "starty", "endx", "endy", "pointx", "pointy")
        ).isNotEmpty()
    }

    private fun normalizeParamType(rawType: String): String {
        val normalized = rawType.trim().lowercase()
        return when (normalized) {
            "int" -> "integer"
            "float", "double" -> "number"
            "bool" -> "boolean"
            "string", "integer", "number", "boolean", "array", "object" -> normalized
            else -> "string"
        }
    }
}

/**
 * Represents a tool call with name and arguments.
 *
 * @param name Name of the tool to call
 * @param args Map of argument names to values
 */
@Serializable
data class ToolCall(
    val name: String,
    val args: Map<String, String>
)

private data class ModelResponse(
    val response: String,
    val allowedToolNames: Set<String>?
)
