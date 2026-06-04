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

import com.juwan.lynx.state.VerifierDecision
import com.juwan.lynx.state.WorldState

class CompletionGate(
    private val minConfidence: Float = 0.80f
) {
    sealed class Decision {
        data class Accepted(val evidence: List<String>) : Decision()
        data class Rejected(
            val reason: String,
            val missing: List<String>,
            val nextHint: String
        ) : Decision()
    }

    fun evaluate(decision: VerifierDecision, state: WorldState): Decision {
        if (state.pendingConfirmation != null) {
            return reject(
                reason = "仍存在待确认动作，不能接受完成",
                missing = listOf("pending_confirmation:${state.pendingConfirmation.actionSummary}"),
                nextHint = "先处理待确认动作，再重新验证完成状态"
            )
        }
        if (!decision.complete) {
            return reject(
                reason = "Verifier 判定任务尚未完成",
                missing = decision.missing,
                nextHint = decision.nextHint
            )
        }
        if (decision.confidence < minConfidence) {
            return reject(
                reason = "Verifier 置信度不足: ${"%.2f".format(decision.confidence)}",
                missing = decision.missing.ifEmpty { listOf("需要更明确的可观察完成证据") },
                nextHint = decision.nextHint
            )
        }
        if (decision.evidence.isEmpty()) {
            return reject(
                reason = "Verifier 未提供完成证据",
                missing = decision.missing.ifEmpty { listOf("缺少完成证据") },
                nextHint = decision.nextHint
            )
        }
        return Decision.Accepted(decision.evidence)
    }

    private fun reject(
        reason: String,
        missing: List<String>,
        nextHint: String
    ): Decision.Rejected {
        val normalizedMissing = missing
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .take(8)
        val normalizedHint = nextHint.replace(Regex("\\s+"), " ").trim()
            .ifBlank { "继续推进并收集可观察完成证据" }
            .take(240)
        return Decision.Rejected(
            reason = reason,
            missing = normalizedMissing,
            nextHint = normalizedHint
        )
    }
}
