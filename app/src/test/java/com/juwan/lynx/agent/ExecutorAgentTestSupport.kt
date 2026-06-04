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

import com.juwan.lynx.runtime.AgentRuntime
import com.juwan.lynx.runtime.ModelConfig
import com.juwan.lynx.runtime.Tool
import com.juwan.lynx.runtime.ToolParam
import com.juwan.lynx.safety.SafetyGuard
import com.juwan.lynx.state.WorldStateManager
import io.mockk.mockk

internal fun testExecutor(
    runtime: AgentRuntime,
    worldStateManager: WorldStateManager,
    primitiveTools: List<Tool> = emptyList()
): ExecutorAgent {
    return ExecutorAgent(
        runtime = runtime,
        worldStateManager = worldStateManager,
        safetyGuard = mockk<SafetyGuard>(relaxed = true),
        visionModelConfig = ModelConfig(
            baseUrl = "http://test",
            modelName = "test-model",
            temperature = 0.0f,
            timeoutSeconds = 30
        ),
        primitiveTools = primitiveTools
    )
}

internal fun namedTool(name: String): Tool {
    return object : Tool {
        override val name: String = name
        override val description: String = name
        override val parameters: List<ToolParam> = emptyList()

        override suspend fun execute(args: Map<String, String>): String {
            return "ok"
        }
    }
}
