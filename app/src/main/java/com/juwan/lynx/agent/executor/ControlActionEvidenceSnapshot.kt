package com.juwan.lynx.agent.executor

import com.juwan.lynx.state.ActionOutcome
import com.juwan.lynx.state.ActionRecord

internal data class ControlActionEvidenceSnapshot(
    val recentExecutionWindow: List<ActionRecord>,
    val businessActionWindow: List<ActionRecord>,
    val reliableBusinessWindow: List<ActionRecord>,
    val recentBusinessOutcomes: List<ActionOutcome>,
    val successfulBusinessOutcomes: List<ActionOutcome>,
    val reliableEnvironmentTransitions: List<ActionRecord>
) {
    val hasRecentBusinessSuccess: Boolean
        get() = successfulBusinessOutcomes.isNotEmpty()
}
