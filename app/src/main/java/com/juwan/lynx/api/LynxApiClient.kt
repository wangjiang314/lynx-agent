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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * Standard OpenAI-compatible API client implementation.
 * Uses Bearer token authentication.
 */
class LynxApiClient(
    private val settingsProvider: SettingsProvider,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) : ApiClient {
    private val tag = "LynxApiClient"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun chat(
        systemPrompt: String,
        messages: List<ChatMessage>,
        temperature: Float,
        tools: List<ApiToolDefinition>,
        timeoutSeconds: Int
    ): String = withContext(Dispatchers.IO) {
        chatInternal(
            baseUrl = settingsProvider.getApiBaseUrl(),
            model = settingsProvider.getTextModel(),
            systemPrompt = systemPrompt,
            messages = messages,
            temperature = temperature,
            tools = tools,
            timeoutSeconds = timeoutSeconds
        )
    }

    override suspend fun chatWithVision(
        systemPrompt: String,
        messages: List<ChatMessage>,
        imageBase64: String,
        temperature: Float,
        tools: List<ApiToolDefinition>,
        timeoutSeconds: Int
    ): String = withContext(Dispatchers.IO) {
        chatVisionInternal(
            baseUrl = settingsProvider.getApiBaseUrl(),
            model = settingsProvider.getVisionModel(),
            systemPrompt = systemPrompt,
            messages = messages,
            imageBase64 = imageBase64,
            temperature = temperature,
            tools = tools,
            timeoutSeconds = timeoutSeconds
        )
    }

    /**
     * Internal chat implementation with configurable parameters
     */
    private suspend fun chatInternal(
        baseUrl: String,
        model: String,
        systemPrompt: String,
        messages: List<ChatMessage>,
        temperature: Float,
        tools: List<ApiToolDefinition>,
        timeoutSeconds: Int
    ): String = withContext(Dispatchers.IO) {
        try {
            val endpoint = resolveChatCompletionsUrl(baseUrl)
            val requestClient = clientForTimeout(timeoutSeconds)
            val primaryBody = buildChatRequest(
                model = model,
                systemPrompt = systemPrompt,
                messages = messages,
                temperature = temperature,
                tools = tools
            )
            val primaryResponse = executeCancellable(requestClient.newCall(buildRequest(endpoint, primaryBody)))
            val primaryBodyText = primaryResponse.body?.string().orEmpty()
            recordApiUsage(
                requestType = "text",
                model = model,
                responseBody = primaryBodyText
            )

            if (primaryResponse.isSuccessful) {
                return@withContext extractTextContent(primaryBodyText).ifBlank { primaryBodyText }
            }

            val trimmedError = primaryBodyText.take(512)
            if (tools.isNotEmpty() && shouldRetryWithoutTools(primaryResponse.code, primaryBodyText)) {
                Log.w(
                    tag,
                    "Model may not support tools, retrying without tools. endpoint=$endpoint, model=$model, code=${primaryResponse.code}"
                )
                val fallbackBody = buildChatRequest(
                    model = model,
                    systemPrompt = systemPrompt,
                    messages = messages,
                    temperature = temperature,
                    tools = emptyList()
                )
                val fallbackResponse = executeCancellable(requestClient.newCall(buildRequest(endpoint, fallbackBody)))
                val fallbackBodyText = fallbackResponse.body?.string().orEmpty()
                recordApiUsage(
                    requestType = "text",
                    model = model,
                    responseBody = fallbackBodyText
                )
                if (fallbackResponse.isSuccessful) {
                    return@withContext extractTextContent(fallbackBodyText).ifBlank { fallbackBodyText }
                }
                Log.e(
                    tag,
                    "Fallback request without tools also failed: ${fallbackResponse.code} ${fallbackResponse.message}, endpoint=$endpoint, model=$model, body=${fallbackBodyText.take(512)}"
                )
                return@withContext ""
            }

            Log.e(
                tag,
                "API request failed: ${primaryResponse.code} ${primaryResponse.message}, endpoint=$endpoint, model=$model, body=$trimmedError"
            )
            FlowTraceLogger.warn(
                stage = "api_http_error",
                kv = mapOf(
                    "type" to "text",
                    "provider" to "openai_compatible",
                    "code" to primaryResponse.code,
                    "model" to model,
                    "body" to trimmedError
                )
            )
            ""
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            FlowTraceLogger.warn(
                stage = "api_request_exception",
                kv = mapOf(
                    "type" to "text",
                    "provider" to "openai_compatible",
                    "model" to model,
                    "error" to (e.message ?: e::class.java.simpleName).take(160)
                )
            )
            Log.e(tag, "Error in chat request", e)
            ""
        }
    }

    private suspend fun chatVisionInternal(
        baseUrl: String,
        model: String,
        systemPrompt: String,
        messages: List<ChatMessage>,
        imageBase64: String,
        temperature: Float,
        tools: List<ApiToolDefinition>,
        timeoutSeconds: Int
    ): String = withContext(Dispatchers.IO) {
        try {
            val endpoint = resolveChatCompletionsUrl(baseUrl)
            val requestClient = clientForTimeout(timeoutSeconds)
            val primaryBody = buildVisionRequest(
                model = model,
                systemPrompt = systemPrompt,
                messages = messages,
                imageBase64 = imageBase64,
                temperature = temperature,
                tools = tools
            )
            val primaryResponse = executeCancellable(requestClient.newCall(buildRequest(endpoint, primaryBody)))
            val primaryBodyText = primaryResponse.body?.string().orEmpty()
            recordApiUsage(
                requestType = "vision",
                model = model,
                responseBody = primaryBodyText
            )

            if (primaryResponse.isSuccessful) {
                return@withContext extractTextContent(primaryBodyText).ifBlank { primaryBodyText }
            }

            val trimmedError = primaryBodyText.take(512)
            if (tools.isNotEmpty() && shouldRetryWithoutTools(primaryResponse.code, primaryBodyText)) {
                Log.w(
                    tag,
                    "Vision model may not support tools, retrying without tools. endpoint=$endpoint, model=$model, code=${primaryResponse.code}"
                )
                val fallbackBody = buildVisionRequest(
                    model = model,
                    systemPrompt = systemPrompt,
                    messages = messages,
                    imageBase64 = imageBase64,
                    temperature = temperature,
                    tools = emptyList()
                )
                val fallbackResponse = executeCancellable(requestClient.newCall(buildRequest(endpoint, fallbackBody)))
                val fallbackBodyText = fallbackResponse.body?.string().orEmpty()
                recordApiUsage(
                    requestType = "vision",
                    model = model,
                    responseBody = fallbackBodyText
                )
                if (fallbackResponse.isSuccessful) {
                    return@withContext extractTextContent(fallbackBodyText).ifBlank { fallbackBodyText }
                }
                Log.e(
                    tag,
                    "Vision fallback request without tools also failed: ${fallbackResponse.code} ${fallbackResponse.message}, endpoint=$endpoint, model=$model, body=${fallbackBodyText.take(512)}"
                )
                return@withContext ""
            }

            Log.e(
                tag,
                "API request failed: ${primaryResponse.code} ${primaryResponse.message}, endpoint=$endpoint, model=$model, body=$trimmedError"
            )
            FlowTraceLogger.warn(
                stage = "api_http_error",
                kv = mapOf(
                    "type" to "vision",
                    "provider" to "openai_compatible",
                    "code" to primaryResponse.code,
                    "model" to model,
                    "body" to trimmedError
                )
            )
            ""
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            FlowTraceLogger.warn(
                stage = "api_request_exception",
                kv = mapOf(
                    "type" to "vision",
                    "provider" to "openai_compatible",
                    "model" to model,
                    "error" to (e.message ?: e::class.java.simpleName).take(160)
                )
            )
            Log.e(tag, "Error in vision chat request", e)
            ""
        }
    }

    private fun buildRequest(endpoint: String, requestBody: okhttp3.RequestBody): Request {
        return Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer ${settingsProvider.getApiKey()}")
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

    private fun resolveChatCompletionsUrl(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        return if (normalized.endsWith("/chat/completions")) {
            normalized
        } else {
            "$normalized/chat/completions"
        }
    }

    private fun extractTextContent(responseBody: String): String {
        if (responseBody.isBlank()) return ""
        val root = runCatching { json.parseToJsonElement(responseBody).jsonObject }.getOrNull() ?: return ""
        val content = extractContentFromEnvelope(root)
        if (!content.isNullOrBlank()) return content
        return root["choices"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("text")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            .orEmpty()
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

    /**
     * Parse JSON Function Calling output
     */
    override fun parseFunctionCall(response: String): FunctionCall? {
        return try {
            // 1) Direct tool-call JSON response.
            parseFunctionCallFromText(response)?.let { return it }

            // 2) OpenAI-compatible envelope with native tool_calls/function_call.
            val root = json.parseToJsonElement(response).jsonObject
            parseFunctionCallFromOpenAiEnvelope(root)?.let { return it }

            // 3) OpenAI-compatible envelope -> choices[0].message.content.
            val content = extractContentFromEnvelope(root) ?: return null
            parseFunctionCallFromText(content)
        } catch (e: Exception) {
            Log.w(tag, "Failed to parse function call: ${e.message}")
            null
        }
    }

    private fun extractContentFromEnvelope(root: kotlinx.serialization.json.JsonObject): String? {
        val choices = root["choices"]?.jsonArray ?: return null
        val firstChoice = choices.firstOrNull()?.jsonObject ?: return null
        val message = firstChoice["message"]?.jsonObject ?: return null
        return normalizeContentElement(message["content"])
    }

    private fun normalizeContentElement(element: JsonElement?): String? {
        if (element == null) return null
        return when (element) {
            is JsonArray -> {
                element.joinToString("\n") { item ->
                    val obj = runCatching { item.jsonObject }.getOrNull()
                    if (obj != null) {
                        obj["text"]?.jsonPrimitive?.contentOrNull
                            ?: obj["content"]?.jsonPrimitive?.contentOrNull
                            ?: item.toString()
                    } else {
                        item.toString()
                    }
                }.trim().ifBlank { null }
            }
            else -> element.jsonPrimitive.contentOrNull?.trim().orEmpty().ifBlank { null }
        }
    }

    private fun parseFunctionCallFromText(rawText: String): FunctionCall? {
        if (rawText.isBlank()) return null
        val content = rawText.trim()
        val codeBlock = Regex(
            """```(?:json)?\s*(.*?)\s*```""",
            setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)
        ).find(content)?.groupValues?.get(1)?.trim().orEmpty()
        val primary = if (codeBlock.isNotBlank()) codeBlock else content

        parseFunctionCallFromJsonCandidate(primary)?.let { return it }

        val candidates = extractJsonObjectCandidates(primary)
        for (candidate in candidates.asReversed()) {
            parseFunctionCallFromJsonCandidate(candidate)?.let { return it }
        }
        return null
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

    private fun parseFunctionCallFromJsonCandidate(candidate: String): FunctionCall? {
        if (candidate.isBlank()) return null
        return try {
            when (val element = json.parseToJsonElement(candidate)) {
                is JsonObject -> parseFunctionCallFromObject(element)
                is JsonPrimitive -> {
                    val nested = element.jsonPrimitive.contentOrNull?.trim().orEmpty()
                    if (nested.startsWith("{") && nested.endsWith("}")) {
                        parseFunctionCallFromJsonCandidate(nested)
                    } else {
                        null
                    }
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseFunctionCallFromObject(root: JsonObject): FunctionCall? {
        val name = root["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (name.isBlank()) return null

        val args = mutableMapOf<String, String>()
        when (val argsElement = root["args"]) {
            is JsonObject -> {
                argsElement.forEach { (k, v) ->
                    args[k] = runCatching { v.jsonPrimitive.contentOrNull }.getOrNull() ?: v.toString()
                }
            }
            is JsonPrimitive -> {
                val raw = argsElement.contentOrNull?.trim().orEmpty()
                if (raw.startsWith("{") && raw.endsWith("}")) {
                    val nested = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                    nested?.forEach { (k, v) ->
                        args[k] = runCatching { v.jsonPrimitive.contentOrNull }.getOrNull() ?: v.toString()
                    }
                }
            }
            else -> Unit
        }
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
                val args = parseArgumentsElement(functionObj?.get("arguments"))
                return FunctionCall(name = name, args = args)
            }
        }

        val legacy = message["function_call"]?.jsonObject
        if (legacy != null) {
            val name = legacy["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (name.isNotBlank()) {
                val args = parseArgumentsElement(legacy["arguments"])
                return FunctionCall(name = name, args = args)
            }
        }
        return null
    }

    private fun parseArgumentsElement(arguments: JsonElement?): Map<String, String> {
        if (arguments == null) return emptyMap()

        return when (arguments) {
            is JsonObject -> {
                arguments.entries.associate { (k, v) ->
                    k to (runCatching { v.jsonPrimitive.contentOrNull }.getOrNull() ?: v.toString())
                }
            }
            is JsonPrimitive -> {
                val raw = arguments.contentOrNull?.trim().orEmpty()
                if (raw.startsWith("{") && raw.endsWith("}")) {
                    val nested = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                    nested?.entries?.associate { (k, v) ->
                        k to (runCatching { v.jsonPrimitive.contentOrNull }.getOrNull() ?: v.toString())
                    } ?: emptyMap()
                } else {
                    emptyMap()
                }
            }
            else -> emptyMap()
        }
    }

    private fun buildChatRequest(
        model: String,
        systemPrompt: String,
        messages: List<ChatMessage>,
        temperature: Float,
        tools: List<ApiToolDefinition>
    ): okhttp3.RequestBody {
        val payload = buildJsonObject {
            put("model", model)
            put("temperature", temperature)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                messages.forEach { message ->
                    val content = message.content.trim()
                    if (content.isBlank()) return@forEach
                    add(buildJsonObject {
                        put("role", message.role)
                        put("content", content)
                    })
                }
            }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    buildToolsPayload(tools).forEach { add(it) }
                }
            }
        }

        return payload.toString().toRequestBody("application/json".toMediaType())
    }

    private fun buildVisionRequest(
        model: String,
        systemPrompt: String,
        messages: List<ChatMessage>,
        imageBase64: String,
        temperature: Float,
        tools: List<ApiToolDefinition>
    ): okhttp3.RequestBody {
        val imageUrl = normalizeVisionImage(imageBase64)
        val instructionText = messages.lastOrNull { it.role == "user" }?.content?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "请根据截图完成任务。"

        val payload = buildJsonObject {
            put("model", model)
            put("temperature", temperature)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })

                messages.forEach { message ->
                    val content = message.content.trim()
                    if (content.isBlank()) return@forEach
                    add(buildJsonObject {
                        put("role", message.role)
                        put("content", content)
                    })
                }

                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                put("url", imageUrl)
                            }
                        })
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", instructionText)
                        })
                    }
                })
            }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    buildToolsPayload(tools).forEach { add(it) }
                }
            }
        }

        return payload.toString().toRequestBody("application/json".toMediaType())
    }

    private fun buildToolsPayload(tools: List<ApiToolDefinition>): JsonArray {
        return buildJsonArray {
            tools.forEach { tool ->
                add(
                    buildJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", tool.name)
                            put("description", tool.description)
                            putJsonObject("parameters") {
                                put("type", "object")
                                putJsonObject("properties") {
                                    tool.parameters.forEach { param ->
                                        putJsonObject(param.name) {
                                            put("type", normalizeOpenAiType(param.type))
                                            put("description", param.description)
                                        }
                                    }
                                }
                                val requiredParams = tool.parameters.filter { it.required }.map { it.name }
                                if (requiredParams.isNotEmpty()) {
                                    putJsonArray("required") {
                                        requiredParams.forEach { add(JsonPrimitive(it)) }
                                    }
                                }
                            }
                        }
                    }
                )
            }
        }
    }

    private fun normalizeOpenAiType(rawType: String): String {
        val normalized = rawType.trim().lowercase()
        return when (normalized) {
            "int" -> "integer"
            "float", "double" -> "number"
            "bool" -> "boolean"
            "string", "integer", "number", "boolean", "array", "object" -> normalized
            else -> "string"
        }
    }

    private fun shouldRetryWithoutTools(statusCode: Int, errorBody: String): Boolean {
        if (statusCode !in 400..499) return false
        val lowered = errorBody.lowercase()
        val unsupportedToolHints = listOf(
            "tool",
            "tools",
            "tool_choice",
            "function call",
            "function calling",
            "unsupported",
            "not support",
            "unknown field",
            "invalid parameter"
        )
        return unsupportedToolHints.any { lowered.contains(it) }
    }

    private fun normalizeVisionImage(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("data:image")) {
            return trimmed
        }
        val compact = trimmed.replace(Regex("\\s+"), "")
        return "data:image/jpeg;base64,$compact"
    }
}

// Standard OpenAI-compatible data classes
@Serializable
data class StandardChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class StandardChatRequest(
    val model: String,
    val messages: List<StandardChatMessage>,
    val temperature: Float = 0.7f
)

@Serializable
data class StandardChatResponse(
    val choices: List<StandardChoice>
)

@Serializable
data class StandardChoice(
    val message: StandardMessage
)

@Serializable
data class StandardMessage(
    val content: String
)
