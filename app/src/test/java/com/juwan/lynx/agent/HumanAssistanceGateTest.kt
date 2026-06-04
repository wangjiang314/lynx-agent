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

import com.juwan.lynx.state.HumanAssistanceRequestType
import com.juwan.lynx.state.WorldState
import com.juwan.lynx.state.WorldStateManager
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking

class HumanAssistanceGateTest : FunSpec({

    test("requests user text assistance for verification blocker and resumes after completion") {
        val manager = mockk<WorldStateManager>(relaxed = true)
        every { manager.getState() } returns WorldState(
            visibleTexts = listOf("请输入短信验证码")
        )
        coEvery { manager.recordHumanAssistanceEvent(any()) } returns Unit
        coEvery { manager.refreshObservation() } returns Unit

        var capturedType: HumanAssistanceRequestType? = null
        val gate = HumanAssistanceGate(manager) { request ->
            capturedType = request.type
            HumanAssistanceResponse(completed = true, text = "123456")
        }

        val assisted = runBlocking { gate.maybeAssist("验证码阻塞") }

        assisted shouldBe true
        capturedType shouldBe HumanAssistanceRequestType.USER_TEXT
        coVerify(exactly = 2) { manager.recordHumanAssistanceEvent(any()) }
        coVerify(exactly = 1) { manager.refreshObservation() }
    }

    test("does nothing when no external blocker is visible") {
        val manager = mockk<WorldStateManager>(relaxed = true)
        every { manager.getState() } returns WorldState(visibleTexts = listOf("普通页面"))

        val gate = HumanAssistanceGate(manager) {
            HumanAssistanceResponse(completed = true)
        }

        val assisted = runBlocking { gate.maybeAssist("普通失败") }

        assisted shouldBe false
        coVerify(exactly = 0) { manager.recordHumanAssistanceEvent(any()) }
    }
})
