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

package com.juwan.lynx.agent.planner

import android.util.Log
import com.juwan.lynx.state.WorldStateManager

internal class PlanningContextBuilder(
    private val worldStateManager: WorldStateManager,
    private val logTag: String
) {
    suspend fun buildPlanningContext(userInstruction: String): String {
        val worldStateContext = runCatching { worldStateManager.toPlannerContext() }
            .recoverCatching {
                Log.w(logTag, "Failed to build planner context, falling back to prompt context: ${it.message}")
                worldStateManager.toPromptContext()
            }
            .getOrElse {
                Log.w(logTag, "Failed to build planning world state context: ${it.message}")
                "当前状态: 暂时不可用"
            }

        return buildString {
            appendLine("用户指令: $userInstruction")
            appendLine()
            appendLine(worldStateContext)
            appendLine()
            appendLine("请仅基于用户指令和当前最小必要事实生成高层执行策略。")
        }
    }
}
