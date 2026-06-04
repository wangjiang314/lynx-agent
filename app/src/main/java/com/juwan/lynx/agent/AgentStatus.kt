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

import kotlinx.serialization.Serializable

/**
 * Structured status for debugging and UI visualization (Requirement E).
 * Provides insight into the Agent's Chain of Thought (CoT).
 */
@Serializable
data class AgentStatus(
    val phase: AgentPhase,
    val message: String,
    val thought: String? = null,
    val action: String? = null,
    val observation: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Different phases of the Agent's workflow.
 */
enum class AgentPhase {
    PLANNING,    // Planner is creating a strategy
    EXECUTING,   // Executor is performing actions
    REFLECTING,  // Planner is reflecting on failure
    COMPLETED,   // Task finished successfully
    FAILED       // Task failed
}
