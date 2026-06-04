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

import kotlinx.serialization.Serializable

/**
 * Unified API client interface for LLM communication.
 * Supports both standard OpenAI-compatible APIs and OTHER custom API.
 */
interface ApiClient {
    /**
     * Send text-only chat request to LLM API
     */
    suspend fun chat(
        systemPrompt: String,
        messages: List<ChatMessage>,
        temperature: Float = 0.7f,
        tools: List<ApiToolDefinition> = emptyList(),
        timeoutSeconds: Int = 60
    ): String

    /**
     * Send vision chat request with image to LLM API
     */
    suspend fun chatWithVision(
        systemPrompt: String,
        messages: List<ChatMessage>,
        imageBase64: String,
        temperature: Float = 0.7f,
        tools: List<ApiToolDefinition> = emptyList(),
        timeoutSeconds: Int = 60
    ): String

    /**
     * Parse JSON Function Calling output
     */
    fun parseFunctionCall(response: String): FunctionCall?
}

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class FunctionCall(
    val name: String,
    val args: Map<String, String>
)

@Serializable
data class ApiToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<ApiToolParameter> = emptyList()
)

@Serializable
data class ApiToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true
)
