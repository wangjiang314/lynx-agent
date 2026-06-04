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

import com.juwan.lynx.api.ApiClient
import com.juwan.lynx.api.ApiToolDefinition
import com.juwan.lynx.api.ChatMessage
import com.juwan.lynx.api.FunctionCall
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf


/**
 * Unit tests for finish marker behavior in [SimpleReActRuntime].
 *
 * Verifies:
 * 1) FINISH_TOOL_EXECUTED marker is emitted only when finished tool is actually executed.
 * 2) Plain text without tool call is treated as a protocol error in tool-driven mode.
 */
class SimpleReActRuntimeFinishMarkerTest : FunSpec({

    test("should emit strict finish marker only after finished tool execution") {
        val apiClient = SequenceApiClient(
            responses = listOf(
                // First turn: call non-finished tool
                """{"name":"noop","args":{}}""",
                // Second turn: call finished tool
                """{"name":"finished","args":{"result":"done"}}"""
            )
        )

        val runtime = SimpleReActRuntime(apiClient)

        val noopTool = object : Tool {
            override val name: String = "noop"
            override val description: String = "No-op tool"
            override val parameters: List<ToolParam> = emptyList()
            override suspend fun execute(args: Map<String, String>): String = "noop-ok"
        }

        val finishTool = object : Tool {
            override val name: String = "finished"
            override val description: String = "Finished tool"
            override val parameters: List<ToolParam> = listOf(
                ToolParam("result", "string", "finish result", required = false)
            )

            override suspend fun execute(args: Map<String, String>): String {
                return "任务完成: ${args["result"] ?: "ok"}"
            }
        }

        val config = AgentConfig(
            name = "TestAgent",
            model = ModelConfig(
                baseUrl = "http://test",
                modelName = "test-model",
                temperature = 0.0f
            ),
            systemPrompt = "test system prompt",
            tools = listOf(noopTool, finishTool),
            maxIterations = 5
        )

        val result = runtime.runAgent(config, "test input")

        val finished = result.shouldBeInstanceOf<AgentResult.Finished>()
        finished.output.startsWith("FINISH_TOOL_EXECUTED:") shouldBe true
        finished.output.contains("任务完成: done") shouldBe true
        apiClient.lastTools.map { it.name } shouldBe listOf("noop", "finished")
    }

    test("should route to vision API when screenshot is present") {
        val apiClient = TrackingApiClient(
            responses = listOf(
                """{"name":"finished","args":{"result":"vision done"}}"""
            )
        )

        val runtime = SimpleReActRuntime(apiClient)

        val finishTool = object : Tool {
            override val name: String = "finished"
            override val description: String = "Finished tool"
            override val parameters: List<ToolParam> = listOf(
                ToolParam("result", "string", "finish result", required = false)
            )

            override suspend fun execute(args: Map<String, String>): String {
                return "任务完成: ${args["result"] ?: "ok"}"
            }
        }

        val config = AgentConfig(
            name = "ExecutorAgent",
            model = ModelConfig(
                baseUrl = "http://test",
                modelName = "test-vision-model",
                temperature = 0.0f
            ),
            systemPrompt = "test executor prompt",
            tools = listOf(finishTool),
            maxIterations = 3,
            useVision = true,
            imageBase64 = "ZmFrZS1pbWFnZS1iYXNlNjQ="
        )

        val result = runtime.runAgent(config, "test input with screenshot")

        val finished = result.shouldBeInstanceOf<AgentResult.Finished>()
        finished.output.startsWith("FINISH_TOOL_EXECUTED:") shouldBe true

        apiClient.lastVisionImageBase64 shouldBe "ZmFrZS1pbWFnZS1iYXNlNjQ="
        apiClient.visionCallCount shouldBe 1
        apiClient.chatCallCount shouldBe 0
    }

    test("should retry empty responses across multiple bounded attempts") {
        val apiClient = SequenceApiClient(
            responses = listOf(
                "",
                "",
                """{"name":"finished","args":{"result":"retry ok"}}"""
            )
        )

        val runtime = SimpleReActRuntime(apiClient)
        val finishTool = object : Tool {
            override val name: String = "finished"
            override val description: String = "Finished tool"
            override val parameters: List<ToolParam> = emptyList()
            override suspend fun execute(args: Map<String, String>): String = "任务完成: retry ok"
        }

        val config = AgentConfig(
            name = "RetryAgent",
            model = ModelConfig(
                baseUrl = "http://test",
                modelName = "test-model",
                temperature = 0.0f,
                timeoutSeconds = 35
            ),
            systemPrompt = "test system prompt",
            tools = listOf(finishTool),
            maxIterations = 2
        )

        val result = runtime.runAgent(config, "test input")

        val finished = result.shouldBeInstanceOf<AgentResult.Finished>()
        finished.output shouldBe "FINISH_TOOL_EXECUTED: 任务完成: retry ok"
        apiClient.timeoutLog shouldBe listOf(35, 45, 55)
    }

    test("should fallback to text API when vision responses stay empty") {
        val apiClient = TrackingApiClient(
            responses = listOf(
                "",
                "",
                """{"name":"finished","args":{"result":"text fallback ok"}}"""
            )
        )

        val runtime = SimpleReActRuntime(apiClient)
        val finishTool = object : Tool {
            override val name: String = "finished"
            override val description: String = "Finished tool"
            override val parameters: List<ToolParam> = listOf(
                ToolParam("result", "string", "finish result", required = false)
            )
            override suspend fun execute(args: Map<String, String>): String {
                return "任务完成: ${args["result"] ?: "ok"}"
            }
        }

        val config = AgentConfig(
            name = "ExecutorAgent",
            model = ModelConfig(
                baseUrl = "http://test",
                modelName = "test-vision-model",
                temperature = 0.0f,
                timeoutSeconds = 35
            ),
            systemPrompt = "test executor prompt",
            tools = listOf(finishTool),
            maxIterations = 1,
            useVision = true,
            imageBase64 = "ZmFrZS1pbWFnZS1iYXNlNjQ="
        )

        val result = runtime.runAgent(config, "screen facts and candidates")

        val finished = result.shouldBeInstanceOf<AgentResult.Finished>()
        finished.output shouldBe "FINISH_TOOL_EXECUTED: 任务完成: text fallback ok"
        apiClient.visionCallCount shouldBe 2
        apiClient.chatCallCount shouldBe 1
    }

    test("text fallback should remove coordinate tools from the tool contract") {
        val apiClient = TrackingApiClient(
            responses = listOf(
                "",
                "",
                """{"name":"finished","args":{"result":"safe fallback"}}"""
            )
        )

        val runtime = SimpleReActRuntime(apiClient)
        val clickTool = object : Tool {
            override val name: String = "click"
            override val description: String = "Coordinate click"
            override val parameters: List<ToolParam> = listOf(
                ToolParam("x", "int", "x", required = true),
                ToolParam("y", "int", "y", required = true)
            )
            override suspend fun execute(args: Map<String, String>): String = "clicked"
        }
        val scrollTool = object : Tool {
            override val name: String = "scroll"
            override val description: String = "Coordinate scroll"
            override val parameters: List<ToolParam> = listOf(
                ToolParam("direction", "string", "direction", required = true)
            )
            override suspend fun execute(args: Map<String, String>): String = "scrolled"
        }
        val finishTool = object : Tool {
            override val name: String = "finished"
            override val description: String = "Finished tool"
            override val parameters: List<ToolParam> = emptyList()
            override suspend fun execute(args: Map<String, String>): String = "任务完成"
        }

        val config = AgentConfig(
            name = "ExecutorAgent",
            model = ModelConfig(
                baseUrl = "http://test",
                modelName = "test-vision-model",
                temperature = 0.0f,
                timeoutSeconds = 35
            ),
            systemPrompt = "test executor prompt",
            tools = listOf(clickTool, scrollTool, finishTool),
            maxIterations = 1,
            useVision = true,
            imageBase64 = "ZmFrZS1pbWFnZS1iYXNlNjQ="
        )

        val result = runtime.runAgent(config, "screen facts")

        result.shouldBeInstanceOf<AgentResult.Finished>()
        apiClient.visionCallCount shouldBe 2
        apiClient.chatCallCount shouldBe 1
        apiClient.lastTools.map { it.name } shouldBe listOf("finished")
    }

    test("text fallback should not execute coordinate tools even if the model emits one") {
        val apiClient = TrackingApiClient(
            responses = listOf(
                "",
                "",
                """{"name":"click","args":{"x":500,"y":200}}"""
            )
        )
        var clickExecuted = false

        val runtime = SimpleReActRuntime(apiClient)
        val clickTool = object : Tool {
            override val name: String = "click"
            override val description: String = "Coordinate click"
            override val parameters: List<ToolParam> = listOf(
                ToolParam("x", "int", "x", required = true),
                ToolParam("y", "int", "y", required = true)
            )
            override suspend fun execute(args: Map<String, String>): String {
                clickExecuted = true
                return "clicked"
            }
        }
        val finishTool = object : Tool {
            override val name: String = "finished"
            override val description: String = "Finished tool"
            override val parameters: List<ToolParam> = emptyList()
            override suspend fun execute(args: Map<String, String>): String = "任务完成"
        }

        val config = AgentConfig(
            name = "ExecutorAgent",
            model = ModelConfig(
                baseUrl = "http://test",
                modelName = "test-vision-model",
                temperature = 0.0f,
                timeoutSeconds = 35
            ),
            systemPrompt = "test executor prompt",
            tools = listOf(clickTool, finishTool),
            maxIterations = 1,
            useVision = true,
            imageBase64 = "ZmFrZS1pbWFnZS1iYXNlNjQ="
        )

        val result = runtime.runAgent(config, "screen facts")

        result.shouldBeInstanceOf<AgentResult.Error>()
        clickExecuted shouldBe false
    }

    test("should include conversationHistory in outgoing messages") {
        val apiClient = TrackingApiClient(
            responses = listOf(
                """{"name":"finished","args":{"result":"ok"}}"""
            )
        )
        val runtime = SimpleReActRuntime(apiClient)

        val finishTool = object : Tool {
            override val name: String = "finished"
            override val description: String = "Finished tool"
            override val parameters: List<ToolParam> = emptyList()
            override suspend fun execute(args: Map<String, String>): String = "done"
        }

        val config = AgentConfig(
            name = "ExecutorAgent",
            model = ModelConfig(
                baseUrl = "http://test",
                modelName = "test-vision-model",
                temperature = 0.0f
            ),
            systemPrompt = "test system prompt",
            tools = listOf(finishTool),
            maxIterations = 1,
            useVision = true,
            imageBase64 = "ZmFrZS1pbWFnZS1iYXNlNjQ=",
            conversationHistory = listOf(
                ChatMessage("user", "Previous actions taken in this task:"),
                ChatMessage("assistant", "click(point=<point>200 300</point>)"),
                ChatMessage("user", "Action result: 点击成功")
            )
        )

        runtime.runAgent(config, "Current screen: 搜索页")

        val outgoing = apiClient.lastMessages
        outgoing.size shouldBe 4
        outgoing[0].content shouldBe "Previous actions taken in this task:"
        outgoing[1].content shouldBe "click(point=<point>200 300</point>)"
        outgoing[2].content shouldBe "Action result: 点击成功"
        outgoing[3].content shouldBe "Current screen: 搜索页"
    }

    test("should treat plain text without tool call as protocol error when tools are configured") {
        val apiClient = SequenceApiClient(
            responses = listOf(
                // No JSON function call => runtime should reject as protocol error in tool-driven mode
                "All done without calling tools."
            )
        )

        val runtime = SimpleReActRuntime(apiClient)

        val finishTool = object : Tool {
            override val name: String = "finished"
            override val description: String = "Finished tool"
            override val parameters: List<ToolParam> = emptyList()
            override suspend fun execute(args: Map<String, String>): String = "任务完成: ok"
        }

        val config = AgentConfig(
            name = "TestAgent",
            model = ModelConfig(
                baseUrl = "http://test",
                modelName = "test-model",
                temperature = 0.0f
            ),
            systemPrompt = "test system prompt",
            tools = listOf(finishTool),
            maxIterations = 3
        )

        val result = runtime.runAgent(config, "test input")

        val error = result.shouldBeInstanceOf<AgentResult.Error>()
        error.message.contains("No tool call in model response") shouldBe true
    }

    test("should parse Reason line and execute the following JSON tool call") {
        val apiClient = SequenceApiClient(
            responses = listOf(
                """
                Reason: 当前在消息页面，应该找私信入口而非互动通知
                {"name":"finished","args":{"result":"ok"}}
                """.trimIndent()
            )
        )

        val runtime = SimpleReActRuntime(apiClient)
        val finishTool = object : Tool {
            override val name: String = "finished"
            override val description: String = "Finished tool"
            override val parameters: List<ToolParam> = listOf(
                ToolParam("result", "string", "finish result", required = false)
            )

            override suspend fun execute(args: Map<String, String>): String {
                return "任务完成: ${args["result"] ?: "default"}"
            }
        }

        val config = AgentConfig(
            name = "TestAgent",
            model = ModelConfig(
                baseUrl = "http://test",
                modelName = "test-model",
                temperature = 0.0f
            ),
            systemPrompt = "test system prompt",
            tools = listOf(finishTool),
            maxIterations = 2
        )

        val result = runtime.runAgent(config, "test input")
        val finished = result.shouldBeInstanceOf<AgentResult.Finished>()
        finished.output shouldBe "FINISH_TOOL_EXECUTED: 任务完成: ok"
    }

    test("should ignore JSON-like snippet inside Reason and execute trailing tool call") {
        val apiClient = SequenceApiClient(
            responses = listOf(
                """
                Reason: 当前页面有 {"type":"input"} 控件，优先执行真正的工具调用
                {"name":"finished","args":{"result":"after-reason"}}
                """.trimIndent()
            )
        )
        val runtime = SimpleReActRuntime(apiClient)
        val finishTool = object : Tool {
            override val name: String = "finished"
            override val description: String = "Finished tool"
            override val parameters: List<ToolParam> = listOf(
                ToolParam("result", "string", "finish result", required = false)
            )

            override suspend fun execute(args: Map<String, String>): String {
                return "任务完成: ${args["result"] ?: "default"}"
            }
        }
        val config = AgentConfig(
            name = "TestAgent",
            model = ModelConfig(
                baseUrl = "http://test",
                modelName = "test-model",
                temperature = 0.0f
            ),
            systemPrompt = "test system prompt",
            tools = listOf(finishTool),
            maxIterations = 2
        )

        val result = runtime.runAgent(config, "test input")
        val finished = result.shouldBeInstanceOf<AgentResult.Finished>()
        finished.output shouldBe "FINISH_TOOL_EXECUTED: 任务完成: after-reason"
    }

    test("should parse function-style pseudo tool call when JSON is absent") {
        val runtime = SimpleReActRuntime(SequenceApiClient(emptyList()))

        val parsed = runtime.parseFunctionCall(
            "click(point='<point>921 69</point>')"
        )

        parsed?.name shouldBe "click"
        parsed?.args?.get("point") shouldBe "<point>921 69</point>"
    }

    test("should parse reason plus trailing function-style pseudo tool call") {
        val runtime = SimpleReActRuntime(SequenceApiClient(emptyList()))

        val parsed = runtime.parseFunctionCall(
            """
            Reason: 先点击顶部消息入口
            click(point='<point>921 69</point>')
            """.trimIndent()
        )

        parsed?.name shouldBe "click"
        parsed?.args?.get("point") shouldBe "<point>921 69</point>"
    }
})

/**
 * Minimal fake API client returning predefined responses in sequence.
 */
private class SequenceApiClient(
    private val responses: List<String>
) : ApiClient {
    private var index = 0
    var lastTools: List<ApiToolDefinition> = emptyList()
        private set
    val timeoutLog = mutableListOf<Int>()

    override suspend fun chat(
        systemPrompt: String,
        messages: List<ChatMessage>,
        temperature: Float,
        tools: List<ApiToolDefinition>,
        timeoutSeconds: Int
    ): String {
        lastTools = tools
        timeoutLog += timeoutSeconds
        if (index >= responses.size) {
            return responses.lastOrNull() ?: ""
        }
        return responses[index++]
    }

    override suspend fun chatWithVision(
        systemPrompt: String,
        messages: List<ChatMessage>,
        imageBase64: String,
        temperature: Float,
        tools: List<ApiToolDefinition>,
        timeoutSeconds: Int
    ): String {
        return chat(systemPrompt, messages, temperature, tools, timeoutSeconds)
    }

    override fun parseFunctionCall(response: String): FunctionCall? {
        return null
    }
}

private class TrackingApiClient(
    private val responses: List<String>
) : ApiClient {
    private var index = 0
    var chatCallCount: Int = 0
        private set
    var visionCallCount: Int = 0
        private set
    var lastVisionImageBase64: String? = null
        private set
    var lastMessages: List<ChatMessage> = emptyList()
        private set
    var lastTools: List<ApiToolDefinition> = emptyList()
        private set

    override suspend fun chat(
        systemPrompt: String,
        messages: List<ChatMessage>,
        temperature: Float,
        tools: List<ApiToolDefinition>,
        timeoutSeconds: Int
    ): String {
        chatCallCount++
        lastMessages = messages
        lastTools = tools
        if (index >= responses.size) {
            return responses.lastOrNull() ?: ""
        }
        return responses[index++]
    }

    override suspend fun chatWithVision(
        systemPrompt: String,
        messages: List<ChatMessage>,
        imageBase64: String,
        temperature: Float,
        tools: List<ApiToolDefinition>,
        timeoutSeconds: Int
    ): String {
        visionCallCount++
        lastVisionImageBase64 = imageBase64
        lastMessages = messages
        lastTools = tools
        if (index >= responses.size) {
            return responses.lastOrNull() ?: ""
        }
        return responses[index++]
    }

    override fun parseFunctionCall(response: String): FunctionCall? {
        return null
    }
}
