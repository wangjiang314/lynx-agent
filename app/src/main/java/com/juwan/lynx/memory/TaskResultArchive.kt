package com.juwan.lynx.memory

import com.juwan.lynx.agent.Strategy

/**
 * Thin persistence boundary for task terminal results.
 */
interface TaskResultArchive {
    suspend fun persistTaskResult(strategy: Strategy, success: Boolean, summary: String)
}
