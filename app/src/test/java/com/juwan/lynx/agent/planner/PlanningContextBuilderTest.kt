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

import com.juwan.lynx.state.WorldState
import com.juwan.lynx.state.WorldStateManager
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking

class PlanningContextBuilderTest : FunSpec({
    test("buildPlanningContext should fall back to legacy prompt context when planner context fails") {
        val worldStateManager = mockk<WorldStateManager>()

        every { worldStateManager.getState() } returns WorldState(
            currentApp = "com.test.app"
        )
        every { worldStateManager.toPlannerContext() } throws IllegalStateException("planner context unavailable")
        every { worldStateManager.toPromptContext() } returns "LEGACY_CONTEXT"

        val builder = PlanningContextBuilder(
            worldStateManager = worldStateManager,
            logTag = "PlanningContextBuilderTest"
        )

        val context = runBlocking {
            builder.buildPlanningContext("执行测试任务")
        }

        context shouldContain "LEGACY_CONTEXT"
        verify(exactly = 1) { worldStateManager.toPromptContext() }
    }
})
