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

package com.juwan.lynx.api

import android.util.Log
import com.juwan.lynx.agent.FlowTraceLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OTHER-specific API client implementation.
 * Uses custom authentication headers (otherid, apikey, company) instead of Bearer token.
 */
class OtherApiClient(
    private val settingsProvider: OtherSettingsProvider,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) : ApiClient {
    private val tag = "OtherApiClient"
    private val json = Json { ignoreUnknownKeys = true }
    private val maxVisionTextChars = 8_000

    override suspend fun chat(
        systemPrompt: String,
        messages: List<ChatMessage>,
        temperature: Float,
        tools: List<ApiToolDefinition>,
        timeoutSeconds: Int
    ): String = withContext(Dispatchers.IO) {
        try {
            val requestBody = buildTextRequest(systemPrompt, messages, temperature, tools)
            val request = buildRequest(requestBody)
            val requestClient = clientForTimeout(timeoutSeconds)

            Log.d(tag, "Sending text request to OTHER API")
            Log.d(tag, "API URL: ${settingsProvider.getOtherApiUrl()}")
            Log.d(tag, "Text Model: ${settingsProvider.getOtherTextModel()}")

            val response = executeCancellable(requestClient.newCall(request))
            val responseBody = response.body?.string() ?: ""
            recordApiUsage(
                requestType = "text",
                model = settingsProvider.getOtherTextModel(),
                responseBody = responseBody
            )

            Log.d(tag, "Response code: ${response.code}")
            Log.d(tag, "Response body: $responseBody")

            if (response.isSuccessful) {
                // Parse the response and extract content
                parseResponseContent(responseBody)
            } else {
                FlowTraceLogger.warn(
                    stage = "api_http_error",
                    kv = mapOf(
                        "type" to "text",
                        "provider" to "other",
                        "code" to response.code,
                        "message" to response.message.take(80),
                        "body" to responseBody.take(160)
                    )
                )
                Log.e(tag, "API request failed: ${response.code} ${response.message}")
                Log.e(tag, "Response body: $responseBody")
                ""
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            FlowTraceLogger.warn(
                stage = "api_request_exception",
                kv = mapOf(
                    "type" to "text",
                    "provider" to "other",
                    "error" to (e.message ?: e::class.java.simpleName).take(160)
                )
            )
            Log.e(tag, "Error in chat request", e)
            ""
        }
    }

    override suspend fun chatWithVision(
        systemPrompt: String,
        messages: List<ChatMessage>,
        imageBase64: String,
        temperature: Float,
        tools: List<ApiToolDefinition>,
        timeoutSeconds: Int
    ): String = withContext(Dispatchers.IO) {
        try {
            val requestBody = buildVisionRequest(systemPrompt, messages, imageBase64, temperature, tools)
            val request = buildRequest(requestBody)
            val requestClient = clientForTimeout(timeoutSeconds)

            Log.d(tag, "Sending vision request to OTHER API")
            Log.d(tag, "API URL: ${settingsProvider.getOtherApiUrl()}")
            Log.d(tag, "Vision Model: ${settingsProvider.getOtherVisionModel()}")
            Log.d(tag, "Image base64 length: ${imageBase64.length}")

            val response = executeCancellable(requestClient.newCall(request))
            val responseBody = response.body?.string() ?: ""
            recordApiUsage(
                requestType = "vision",
                model = settingsProvider.getOtherVisionModel(),
                responseBody = responseBody
            )

            Log.d(tag, "Response code: ${response.code}")
            Log.d(tag, "Response body: $responseBody")

            if (response.isSuccessful) {
                // Parse the response and extract content
                parseResponseContent(responseBody)
            } else {
                FlowTraceLogger.warn(
                    stage = "api_http_error",
                    kv = mapOf(
                        "type" to "vision",
                        "provider" to "other",
                        "code" to response.code,
                        "message" to response.message.take(80),
                        "body" to responseBody.take(160)
                    )
                )
                Log.e(tag, "API request failed: ${response.code} ${response.message}")
                Log.e(tag, "Response body: $responseBody")
                ""
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            FlowTraceLogger.warn(
                stage = "api_request_exception",
                kv = mapOf(
                    "type" to "vision",
                    "provider" to "other",
                    "error" to (e.message ?: e::class.java.simpleName).take(160)
                )
            )
            Log.e(tag, "Error in vision chat request", e)
            ""
        }
    }

    override fun parseFunctionCall(response: String): FunctionCall? {
        return try {
            parseFunctionCallFromText(response)?.let { return it }
            val root = json.parseToJsonElement(response).jsonObject
            parseFunctionCallFromOpenAiEnvelope(root)?.let { return it }
            val content = extractResponseContent(root) ?: return null
            parseFunctionCallFromText(content)
        } catch (e: Exception) {
            Log.w(tag, "Failed to parse function call: ${e.message}")
            null
        }
    }

    private fun buildRequest(requestBody: okhttp3.RequestBody): Request {
        val url = settingsProvider.getOtherApiUrl()
        val otherId = settingsProvider.getOtherId()
        val apiKey = settingsProvider.getOtherApiKey()
        val company = settingsProvider.getOtherCompany()

        Log.d(tag, "Building request with:")
        Log.d(tag, "  URL: $url")
        Log.d(tag, "  OTHER ID: $otherId")
        Log.d(tag, "  Company: $company")

        return Request.Builder()
            .url(url)
            .addHeader("saicid", otherId)
            .addHeader("apikey", apiKey)
            .addHeader("company", company)
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()
    }

    private fun clientForTimeout(timeoutSeconds: Int): OkHttpClient {
        val boundedTimeout = timeoutSeconds.coerceIn(10, 120).toLong()
        return httpClient.newBuilder()
            .readTimeout(boundedTimeout, TimeUnit.SECONDS)
            .callTimeout((boundedTimeout + 5L).coerceAtMost(125L), TimeUnit.SECONDS)
            .build()
    }

    private suspend fun executeCancellable(call: okhttp3.Call): Response {
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            try {
                val response = call.execute()
                if (continuation.isActive) {
                    continuation.resume(response)
                } else {
                    response.close()
                }
            } catch (e: Exception) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }
        }
    }

    private fun buildTextRequest(
        systemPrompt: String,
        messages: List<ChatMessage>,
        temperature: Float,
        tools: List<ApiToolDefinition>
    ): okhttp3.RequestBody {
        // Build messages with text content only
        val allMessages = mutableListOf<OtherVisionMessage>()
        val promptWithContract = composePromptWithToolContract(systemPrompt, tools)

        // Add system prompt as first message if not empty
        if (promptWithContract.isNotEmpty()) {
            allMessages.add(OtherVisionMessage("user", listOf(OtherContent("text", promptWithContract))))
        }

        // Add user messages
        messages.forEach { msg ->
            // Only add non-empty messages
            if (msg.content.isNotEmpty()) {
                allMessages.add(OtherVisionMessage(msg.role, listOf(OtherContent("text", msg.content))))
            }
        }

        // If no messages at all, add a placeholder
        if (allMessages.isEmpty()) {
            Log.w(tag, "No messages to send, adding placeholder")
            allMessages.add(OtherVisionMessage("user", listOf(OtherContent("text", "Hello"))))
        }

        val request = OtherVisionRequest(
            model = settingsProvider.getOtherTextModel(),
            messages = allMessages,
            temperature = temperature
        )

        val jsonString = json.encodeToString(OtherVisionRequest.serializer(), request)
        Log.d(tag, "Text request JSON: $jsonString")
        return jsonString.toRequestBody("application/json".toMediaType())
    }

    private fun buildVisionRequest(
        systemPrompt: String,
        messages: List<ChatMessage>,
        imageBase64: String,
        temperature: Float,
        tools: List<ApiToolDefinition>
    ): okhttp3.RequestBody {
        // Build a single user message that contains:
        // 1) system prompt + messages merged as single text
        // 2) screenshot image
        val contentList = mutableListOf<OtherContent>()

        // Merge system prompt + messages with a hard text budget.
        val combinedText = buildBudgetedVisionText(systemPrompt, messages, tools)
            .ifBlank { "Please analyze the current screen and decide the next best action." }

        contentList.add(OtherContent("text", text = combinedText))

        // Sanitize and validate image payload
        val sanitizedBase64 = sanitizeBase64Image(imageBase64)
        if (!isValidBase64(sanitizedBase64)) {
            Log.e(tag, "Invalid base64 image after sanitization")
            // Still proceed but log the error
        }

        // Add image content with correct format: data:image/jpeg;base64,{base64}
        contentList.add(OtherContent("image_url", image_url = OtherImageUrl("data:image/jpeg;base64,$sanitizedBase64")))

        val userMessage = OtherVisionMessage("user", contentList)

        val request = OtherVisionRequest(
            model = settingsProvider.getOtherVisionModel(),
            messages = listOf(userMessage),
            temperature = temperature
        )

        val jsonString = json.encodeToString(OtherVisionRequest.serializer(), request)
        val imageHash = sanitizedBase64.hashCode().toString(16)
        Log.d(tag, "Vision request JSON (first 500 chars): ${jsonString.take(500)}...")
        Log.d(tag, "Vision model: ${settingsProvider.getOtherVisionModel()}")
        Log.d(tag, "Vision text length: ${combinedText.length}")
        Log.d(tag, "Image base64 length: ${sanitizedBase64.length}")
        Log.d(tag, "Image base64 hash: $imageHash")
        return jsonString.toRequestBody("application/json".toMediaType())
    }

    private fun buildBudgetedVisionText(
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<ApiToolDefinition>
    ): String {
        val segments = mutableListOf<String>()
        if (systemPrompt.isNotBlank()) {
            segments.add("[system]\n${systemPrompt.trim()}")
        }
        messages.forEach { msg ->
            val content = msg.content.trim()
            if (content.isNotBlank()) {
                segments.add("[${msg.role}]\n$content")
            }
        }
        buildToolOutputContract(tools).takeIf { it.isNotBlank() }?.let {
            // Keep the tool contract as the freshest textual segment so it survives truncation.
            segments.add("[tool_contract]\n$it")
        }
        if (segments.isEmpty()) return ""

        val picked = ArrayDeque<String>()
        var remaining = maxVisionTextChars

        // Keep the most recent context first (tail of segments list),
        // because it contains the latest screen state and outcomes.
        for (segment in segments.asReversed()) {
            if (remaining <= 0) break
            val candidate = if (segment.length <= remaining) {
                segment
            } else {
                segment.takeLast(remaining)
            }
            if (candidate.isNotBlank()) {
                picked.addFirst(candidate)
                remaining -= (candidate.length + 2) // join separator budget
            }
        }

        if (picked.size < segments.size) {
            Log.w(
                tag,
                "Vision text truncated by budget: keptSegments=${picked.size}, totalSegments=${segments.size}, maxChars=$maxVisionTextChars"
            )
        }

        return picked.joinToString("\n\n")
    }

    private fun composePromptWithToolContract(
        systemPrompt: String,
        tools: List<ApiToolDefinition>
    ): String {
        val contract = buildToolOutputContract(tools)
        if (contract.isBlank()) return systemPrompt
        return buildString {
            if (systemPrompt.isNotBlank()) {
                append(systemPrompt.trim())
                append("\n\n")
            }
            append(contract)
        }
    }

    private fun buildToolOutputContract(tools: List<ApiToolDefinition>): String {
        if (tools.isEmpty()) return ""
        val toolList = tools.joinToString("\n") { tool ->
            val params = tool.parameters.joinToString(", ") { param ->
                val suffix = if (param.required) "" else "?"
                "${param.name}:${param.type}$suffix"
            }
            if (params.isBlank()) {
                "- ${tool.name}()"
            } else {
                "- ${tool.name}($params)"
            }
        }
        val coordinateRule = if (tools.any { it.name in setOf("click", "long_press", "drag", "scroll") }) {
            """
            - Coordinate tools use 0-1000 normalized screenshot coordinates, not device pixels.
            - Top-left is (0,0), bottom-right is (1000,1000).
            - For click/long_press, x/y must be the visible target's normalized location in the screenshot.
            """.trimIndent()
        } else {
            ""
        }
        return """
            TOOL OUTPUT CONTRACT:
            If tools are available, you must output exactly two parts:
            1. One short line that starts with "Reason:"
            2. Exactly one JSON object and nothing after it:
            {"name":"tool_name","args":{"arg":"value"}}

            Rules:
            - Use double quotes in JSON.
            - Choose exactly one tool from the allowed list below.
            - Do not output click(...), open_app(...), markdown, XML, or extra prose after the JSON object.
            - If a point is needed, prefer numeric x/y args; otherwise use a plain point string like "921 69".
            $coordinateRule

            Allowed tools:
            $toolList
        """.trimIndent()
    }

    private fun parseFunctionCallFromText(rawText: String): FunctionCall? {
        if (rawText.isBlank()) return null
        val content = rawText.trim()
        val codeBlock = Regex(
            """```(?:json)?\s*(.*?)\s*```""",
            setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)
        ).find(content)?.groupValues?.get(1)?.trim().orEmpty()
        val primary = if (codeBlock.isNotBlank()) codeBlock else content

        parseFunctionCallFromJson(primary)?.let { return it }

        val candidates = extractJsonObjectCandidates(primary)
        candidates.asReversed().firstNotNullOfOrNull { parseFunctionCallFromJson(it) }?.let { return it }

        return parseFunctionLikeCall(primary)
    }

    private fun parseFunctionCallFromJson(raw: String): FunctionCall? {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val name = root["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (name.isBlank()) return null
        val args = parseArgumentsElement(root["args"])
        return FunctionCall(name = name, args = args)
    }

    private fun parseFunctionCallFromOpenAiEnvelope(root: JsonObject): FunctionCall? {
        val firstChoice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val message = firstChoice["message"]?.jsonObject ?: return null

        val toolCalls = message["tool_calls"]?.jsonArray
        val firstTool = toolCalls?.firstOrNull()?.jsonObject
        if (firstTool != null) {
            val functionObj = firstTool["function"]?.jsonObject
            val name = functionObj?.get("name")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (name.isNotBlank()) {
                return FunctionCall(name = name, args = parseArgumentsElement(functionObj?.get("arguments")))
            }
        }

        val legacy = message["function_call"]?.jsonObject
        if (legacy != null) {
            val name = legacy["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (name.isNotBlank()) {
                return FunctionCall(name = name, args = parseArgumentsElement(legacy["arguments"]))
            }
        }
        return null
    }

    private fun parseArgumentsElement(arguments: JsonElement?): Map<String, String> {
        if (arguments == null) return emptyMap()
        return when (arguments) {
            is JsonObject -> arguments.entries.associate { (key, value) ->
                key to (runCatching { value.jsonPrimitive.contentOrNull }.getOrNull() ?: value.toString())
            }
            else -> {
                val raw = runCatching { arguments.jsonPrimitive.contentOrNull?.trim().orEmpty() }
                    .getOrDefault("")
                if (raw.startsWith("{") && raw.endsWith("}")) {
                    val nested = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                    nested?.entries?.associate { (key, value) ->
                        key to (runCatching { value.jsonPrimitive.contentOrNull }.getOrNull() ?: value.toString())
                    } ?: emptyMap()
                } else {
                    emptyMap()
                }
            }
        }
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

    private fun parseFunctionLikeCall(text: String): FunctionCall? {
        val candidates = text.lineSequence()
            .map { it.trim().trim('`') }
            .filter { it.isNotBlank() }
            .map {
                it.removePrefix("Action:")
                    .removePrefix("Tool:")
                    .removePrefix("Next action:")
                    .trim()
            }
            .mapNotNull { parseFunctionLikeLine(it) }
            .toList()
        return candidates.lastOrNull()
    }

    private fun parseFunctionLikeLine(line: String): FunctionCall? {
        val match = Regex("""^([A-Za-z_][A-Za-z0-9_]*)\((.*)\)$""").matchEntire(line) ?: return null
        val name = match.groupValues[1].trim()
        val argBlock = match.groupValues[2].trim()
        if (name.isBlank()) return null
        if (argBlock.isBlank()) return FunctionCall(name = name, args = emptyMap())

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
        return FunctionCall(name = name, args = args)
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

    /**
     * Sanitize Base64 image payload for Other vision API.
     * Removes whitespace/newlines and strips accidental data URL prefix.
     */
    private fun sanitizeBase64Image(base64: String): String {
        var sanitized = base64
            .replace("\n", "")
            .replace("\r", "")
            .replace(" ", "")
            .trim()

        if (sanitized.startsWith("data:image/")) {
            val commaIndex = sanitized.indexOf(',')
            if (commaIndex >= 0 && commaIndex < sanitized.length - 1) {
                sanitized = sanitized.substring(commaIndex + 1)
            }
        }

        return sanitized
    }

    /**
     * Validate base64 string format.
     */
    private fun isValidBase64(base64: String): Boolean {
        if (base64.isBlank()) return false
        // Basic validation: check if it contains only valid base64 characters
        return base64.matches(Regex("^[A-Za-z0-9+/]*={0,2}$"))
    }

    /**
     * Parse response content from Other API response.
     * Extracts the actual content from the JSON response structure.
     */
    private fun parseResponseContent(responseBody: String): String {
        if (responseBody.isBlank()) return ""
        val root = runCatching { json.parseToJsonElement(responseBody).jsonObject }.getOrNull()
            ?: return responseBody.trim()

        extractResponseContent(root)?.let { return it }

        // Some OpenAI-compatible providers return native tool_calls with null/empty content.
        // Preserve the envelope so the runtime can parse the tool call instead of treating it
        // as an empty model response.
        if (parseFunctionCallFromOpenAiEnvelope(root) != null) {
            return responseBody
        }

        FlowTraceLogger.warn(
            stage = "api_empty_content",
            kv = mapOf(
                "provider" to "other",
                "root_keys" to root.keys.joinToString(",").take(120)
            )
        )
        return ""
    }

    private fun extractResponseContent(root: JsonObject): String? {
        extractContentFromChoices(root)?.let { return it }

        val directKeys = listOf("content", "text", "response", "answer", "output", "result")
        directKeys.firstNotNullOfOrNull { key ->
            normalizeContentElement(root[key])
        }?.let { return it }

        val message = root["message"]?.jsonObjectOrNull()
        normalizeContentElement(message?.get("content"))?.let { return it }

        val data = root["data"]?.jsonObjectOrNull()
        if (data != null) {
            directKeys.firstNotNullOfOrNull { key ->
                normalizeContentElement(data[key])
            }?.let { return it }
            val dataMessage = data["message"]?.jsonObjectOrNull()
            normalizeContentElement(dataMessage?.get("content"))?.let { return it }
        }

        return null
    }

    private fun extractContentFromChoices(root: JsonObject): String? {
        val firstChoice = root["choices"]?.jsonArrayOrNull()?.firstOrNull()?.jsonObjectOrNull()
            ?: return null
        val message = firstChoice["message"]?.jsonObjectOrNull()
        normalizeContentElement(message?.get("content"))?.let { return it }
        normalizeContentElement(firstChoice["text"])?.let { return it }
        return null
    }

    private fun normalizeContentElement(element: JsonElement?): String? {
        if (element == null) return null
        return when (element) {
            is JsonArray -> {
                element.joinToString("\n") { item ->
                    val obj = item.jsonObjectOrNull()
                    if (obj != null) {
                        normalizeContentElement(obj["text"])
                            ?: normalizeContentElement(obj["content"])
                            ?: item.toString()
                    } else {
                        runCatching { item.jsonPrimitive.contentOrNull }.getOrNull() ?: item.toString()
                    }
                }.trim().ifBlank { null }
            }
            is JsonObject -> {
                normalizeContentElement(element["text"])
                    ?: normalizeContentElement(element["content"])
                    ?: element.toString().trim().ifBlank { null }
            }
            else -> runCatching { element.jsonPrimitive.contentOrNull?.trim().orEmpty() }
                .getOrDefault("")
                .ifBlank { null }
        }
    }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? {
        return runCatching { this.jsonObject }.getOrNull()
    }

    private fun JsonElement.jsonArrayOrNull(): JsonArray? {
        return runCatching { this.jsonArray }.getOrNull()
    }

    private fun recordApiUsage(requestType: String, model: String, responseBody: String) {
        val usage = extractUsage(responseBody)
        val promptTokens = usage?.promptTokens ?: 0
        val completionTokens = usage?.completionTokens ?: 0
        FlowTraceLogger.recordApiUsage(
            promptTokens = promptTokens,
            completionTokens = completionTokens
        )
        FlowTraceLogger.event(
            stage = "api_usage",
            kv = mapOf(
                "type" to requestType,
                "model" to model,
                "prompt_tokens" to promptTokens,
                "completion_tokens" to completionTokens,
                "total_tokens" to (promptTokens + completionTokens)
            )
        )
    }

    private fun extractUsage(responseBody: String): TokenUsage? {
        if (responseBody.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(responseBody).jsonObject }.getOrNull() ?: return null
        val usage = root["usage"]?.jsonObject ?: return null
        return TokenUsage(
            promptTokens = usage["prompt_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            completionTokens = usage["completion_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        )
    }

    private data class TokenUsage(
        val promptTokens: Int,
        val completionTokens: Int
    )
}

// Other-specific data classes
@Serializable
data class OtherVisionMessage(
    val role: String,
    val content: List<OtherContent>
)

@Serializable
data class OtherContent(
    val type: String,
    val text: String? = null,
    val image_url: OtherImageUrl? = null
)

@Serializable
data class OtherImageUrl(
    val url: String
)

@Serializable
data class OtherVisionRequest(
    val model: String,
    val messages: List<OtherVisionMessage>,
    val temperature: Float = 0.7f
)

@Serializable
data class OtherChatResponse(
    val choices: List<OtherChoice>
)

@Serializable
data class OtherChoice(
    val message: OtherResponseMessage
)

@Serializable
data class OtherResponseMessage(
    val content: String
)
