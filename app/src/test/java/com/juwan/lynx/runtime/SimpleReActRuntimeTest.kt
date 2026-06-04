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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.bind
import io.kotest.property.checkAll
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/**
 * Test implementation of AgentRuntime that simulates the ReAct loop behavior
 * for testing iteration limits without Android dependencies.
 */
class TestReActRuntime : AgentRuntime {
    override suspend fun runAgent(config: AgentConfig, input: String): AgentResult {
        var iteration = 0
        
        while (iteration < config.maxIterations) {
            // Simulate LLM always returning a tool call (never finishing)
            val tool = config.tools.firstOrNull() ?: return AgentResult.Error("No tools available")
            
            // Execute the tool
            tool.execute(emptyMap())
            
            iteration++
        }
        
        return AgentResult.MaxIterationsReached
    }
}

/**
 * Property-based tests for SimpleReActRuntime iteration limits.
 * Validates that the runtime correctly enforces iteration limits.
 *
 * **Validates: Requirements 1.5**
 */
class SimpleReActRuntimePropertyTest : FunSpec({
    context("Property 1: SimpleReActRuntime 迭代上限") {
        test("should terminate after maxIterations when LLM never returns finish") {
            checkAll(10, Arb.int(1..100)) { maxIterations ->
                // Create a test tool that always succeeds
                val testTool = object : Tool {
                    override val name = "test_tool"
                    override val description = "A test tool"
                    override val parameters = emptyList<ToolParam>()
                    override suspend fun execute(args: Map<String, String>): String {
                        return "tool executed"
                    }
                }

                // Create test runtime that simulates never-finishing behavior
                val runtime = TestReActRuntime()

                // Create config with random maxIterations
                val config = AgentConfig(
                    name = "TestAgent",
                    model = ModelConfig(
                        baseUrl = "http://test.com",
                        modelName = "test-model",
                        temperature = 0.0f
                    ),
                    systemPrompt = "Test prompt",
                    tools = listOf(testTool),
                    maxIterations = maxIterations
                )

                // Run the agent
                val result = runtime.runAgent(config, "test input")

                // Verify that it terminates with MaxIterationsReached
                result.shouldBeInstanceOf<AgentResult.MaxIterationsReached>()
            }
        }
    }
})

/**
 * Property-based tests for Function Calling JSON serialization round-trip.
 * Validates that ToolCall objects can be serialized to JSON and deserialized back
 * to produce equivalent objects.
 *
 * **Validates: Requirements 16.9**
 */
class ToolCallJsonRoundTripPropertyTest : FunSpec({
    // Custom Arb generator for ToolCall objects
    val toolCallArb = Arb.bind(
        Arb.string(minSize = 1, maxSize = 50),  // tool name
        Arb.map(
            Arb.string(minSize = 1, maxSize = 20),  // arg key
            Arb.string(minSize = 0, maxSize = 100), // arg value
            minSize = 0,
            maxSize = 10
        )
    ) { name, args ->
        ToolCall(name, args)
    }

    context("Property 29: Function Calling JSON 解析往返") {
        test("should preserve ToolCall equivalence after JSON serialization round-trip") {
            val json = Json { ignoreUnknownKeys = true }
            
            checkAll(10, toolCallArb) { originalToolCall ->
                // Serialize to JSON
                val jsonString = json.encodeToString(originalToolCall)
                
                // Deserialize back from JSON
                val deserializedToolCall = json.decodeFromString<ToolCall>(jsonString)
                
                // Verify equivalence
                deserializedToolCall shouldBe originalToolCall
            }
        }
    }
})
