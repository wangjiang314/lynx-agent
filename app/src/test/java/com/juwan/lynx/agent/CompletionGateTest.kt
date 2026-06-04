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

import com.juwan.lynx.state.PendingConfirmation
import com.juwan.lynx.state.VerifierDecision
import com.juwan.lynx.state.WorldState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf

class CompletionGateTest : FunSpec({

    test("accepts only high-confidence completion with observable evidence") {
        val decision = VerifierDecision(
            complete = true,
            confidence = 0.91f,
            evidence = listOf("目标消息已在会话中可见"),
            missing = emptyList(),
            nextHint = ""
        )

        CompletionGate().evaluate(decision, WorldState())
            .shouldBeInstanceOf<CompletionGate.Decision.Accepted>()
    }

    test("rejects low confidence completion") {
        val decision = VerifierDecision(
            complete = true,
            confidence = 0.70f,
            evidence = listOf("疑似完成"),
            missing = emptyList(),
            nextHint = "继续确认结果"
        )

        CompletionGate().evaluate(decision, WorldState())
            .shouldBeInstanceOf<CompletionGate.Decision.Rejected>()
    }

    test("rejects completion without evidence") {
        val decision = VerifierDecision(
            complete = true,
            confidence = 0.95f,
            evidence = emptyList(),
            missing = emptyList(),
            nextHint = ""
        )

        CompletionGate().evaluate(decision, WorldState())
            .shouldBeInstanceOf<CompletionGate.Decision.Rejected>()
    }

    test("rejects while confirmation is pending") {
        val decision = VerifierDecision(
            complete = true,
            confidence = 0.95f,
            evidence = listOf("已到达确认页"),
            missing = emptyList(),
            nextHint = ""
        )
        val state = WorldState(
            pendingConfirmation = PendingConfirmation(
                actionHash = "hash",
                actionSummary = "发送消息",
                reason = "sensitive_action"
            )
        )

        CompletionGate().evaluate(decision, state)
            .shouldBeInstanceOf<CompletionGate.Decision.Rejected>()
    }
})
