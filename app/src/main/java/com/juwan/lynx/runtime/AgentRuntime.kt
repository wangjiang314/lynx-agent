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

/**
 * Unified interface for Agent execution, decoupling Agent logic from specific framework implementations.
 * Supports switching between Koog and custom ReAct loop without modifying Agent code.
 */
interface AgentRuntime {
    /**
     * Execute an agent with the given configuration and input.
     *
     * @param config Agent configuration containing model, tools, and execution parameters
     * @param input User input or task description
     * @return AgentResult indicating the outcome of execution
     */
    suspend fun runAgent(config: AgentConfig, input: String): AgentResult
}

/**
 * Unified interface for tools that can be executed by agents.
 */
interface Tool {
    /** Tool name for identification and invocation */
    val name: String

    /** Human-readable description of what the tool does */
    val description: String

    /** List of parameters this tool accepts */
    val parameters: List<ToolParam>

    /**
     * Execute the tool with the given arguments.
     *
     * @param args Map of parameter names to values
     * @return Result string describing the outcome
     */
    suspend fun execute(args: Map<String, String>): String
}

/**
 * Represents a parameter that a tool accepts.
 *
 * @param name Parameter name
 * @param type Parameter type (e.g., "string", "int", "float")
 * @param description Human-readable description of the parameter
 * @param required Whether this parameter is required
 */
data class ToolParam(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true
)

/**
 * Configuration for an agent execution.
 *
 * @param name Agent name for identification
 * @param model Model configuration (API endpoint, model name, etc.)
 * @param systemPrompt System prompt to guide the agent's behavior
 * @param tools List of tools available to the agent
 * @param maxIterations Maximum number of iterations before terminating
 * @param useVision Whether to route this agent call through vision API
 * @param imageBase64 Optional screenshot payload for vision model input
 */
data class AgentConfig(
    val name: String,
    val model: ModelConfig,
    val systemPrompt: String,
    val tools: List<Tool>,
    val maxIterations: Int = 30,
    val useVision: Boolean = false,
    val imageBase64: String? = null,
    val conversationHistory: List<com.juwan.lynx.api.ChatMessage> = emptyList(),
    val iterationContextProvider: (suspend (iteration: Int) -> AgentIterationContext?)? = null
)

data class AgentIterationContext(
    val input: String,
    val useVision: Boolean,
    val imageBase64: String?
)

/**
 * Configuration for the language model.
 *
 * @param baseUrl API base URL (configurable, no hardcoding)
 * @param modelName Model name (configurable, no hardcoding)
 * @param temperature Sampling temperature (0.0-1.0)
 * @param timeoutSeconds Request timeout in seconds
 */
data class ModelConfig(
    val baseUrl: String,
    val modelName: String,
    val temperature: Float,
    val timeoutSeconds: Int = 60
)

/**
 * Result of agent execution.
 * Sealed class representing different possible outcomes.
 */
sealed class AgentResult {
    /**
     * Agent finished execution with a final output.
     *
     * @param output Final output string from the agent
     */
    data class Finished(val output: String) : AgentResult()

    /**
     * Agent called a tool and got a result.
     * This is typically an intermediate result during execution.
     *
     * @param toolName Name of the tool that was called
     * @param result Result from the tool execution
     */
    data class ToolCallResult(val toolName: String, val result: String) : AgentResult()

    /**
     * Agent reached the maximum number of iterations without finishing.
     */
    data object MaxIterationsReached : AgentResult()

    /**
     * Agent execution encountered an error.
     *
     * @param message Error message describing what went wrong
     */
    data class Error(val message: String) : AgentResult()
}
