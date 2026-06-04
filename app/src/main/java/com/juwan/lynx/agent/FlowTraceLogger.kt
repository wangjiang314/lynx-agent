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

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight end-to-end execution tracing logger.
 *
 * Design goals:
 * - Single-line structured logs for grep/filter.
 * - Stable tag (`LynxFlow`) to avoid mixing with low-level noisy logs.
 * - Minimal payload with truncation to keep signal high.
 */
object FlowTraceLogger {
    private const val TAG = "LynxFlow"
    private const val MAX_VAL_LEN = 100
    private const val MAX_KV_COUNT = 18
    private const val MAX_RECENT_EVENTS = 240

    data class TraceEntry(
        val traceId: String,
        val seq: Int,
        val level: String,
        val stage: String,
        val kv: Map<String, String>,
        val timestampMs: Long
    )

    @Volatile
    private var traceId: String = "none"
    private val seq = AtomicInteger(0)
    private val totalPromptTokens = AtomicLong(0L)
    private val totalCompletionTokens = AtomicLong(0L)
    private val apiCallCount = AtomicInteger(0)
    private val toolCallCount = AtomicInteger(0)
    private val runStartTimeMs = AtomicLong(0L)
    private val recentEventsLock = Any()
    private val recentEvents = ArrayDeque<TraceEntry>()
    private val fileLock = Any()

    @Volatile
    private var traceFile: File? = null

    fun configureFileSink(context: Context) {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(root, "lynxflow")
        runCatching { dir.mkdirs() }
        traceFile = File(dir, "lynxflow.log")
    }

    fun start(
        userInstruction: String?,
        runId: String? = null,
        scenarioId: String? = null
    ) {
        traceId = buildTraceId()
        seq.set(0)
        totalPromptTokens.set(0L)
        totalCompletionTokens.set(0L)
        apiCallCount.set(0)
        toolCallCount.set(0)
        runStartTimeMs.set(System.currentTimeMillis())
        event(
            stage = "run_start",
            kv = linkedMapOf<String, Any?>(
                "instruction" to userInstruction.orEmpty(),
                "mode" to if (userInstruction.isNullOrBlank()) "resume" else "new"
            ).apply {
                runId?.takeIf { it.isNotBlank() }?.let { put("run_id", it) }
                scenarioId?.takeIf { it.isNotBlank() }?.let { put("scenario_id", it) }
            }
        )
    }

    fun finish(status: String, reason: String? = null) {
        val durationSec = ((System.currentTimeMillis() - runStartTimeMs.get()).coerceAtLeast(0L)) / 1000L
        val promptTokens = totalPromptTokens.get().coerceAtLeast(0L)
        val completionTokens = totalCompletionTokens.get().coerceAtLeast(0L)
        event(
            stage = "run_finish",
            kv = mapOf(
                "status" to status,
                "reason" to reason.orEmpty(),
                "duration_sec" to durationSec,
                "api_calls" to apiCallCount.get().coerceAtLeast(0),
                "tool_calls" to toolCallCount.get().coerceAtLeast(0),
                "prompt_tokens" to promptTokens,
                "completion_tokens" to completionTokens,
                "total_tokens" to (promptTokens + completionTokens)
            )
        )
        traceId = "none"
        seq.set(0)
        totalPromptTokens.set(0L)
        totalCompletionTokens.set(0L)
        apiCallCount.set(0)
        toolCallCount.set(0)
        runStartTimeMs.set(0L)
    }

    fun recordApiUsage(promptTokens: Int, completionTokens: Int) {
        val safePrompt = promptTokens.coerceAtLeast(0)
        val safeCompletion = completionTokens.coerceAtLeast(0)
        totalPromptTokens.addAndGet(safePrompt.toLong())
        totalCompletionTokens.addAndGet(safeCompletion.toLong())
        apiCallCount.incrementAndGet()
    }

    fun recordToolCall() {
        toolCallCount.incrementAndGet()
    }

    fun event(stage: String, kv: Map<String, Any?> = emptyMap()) {
        val currentTrace = traceId
        val index = seq.incrementAndGet()
        val sanitizedKv = sanitizeKv(kv)
        val body = renderBody(sanitizedKv)
        appendEntry(
            traceId = currentTrace,
            seq = index,
            level = "INFO",
            stage = stage,
            kv = sanitizedKv
        )
        val line = "trace=$currentTrace seq=$index stage=$stage $body".trim()
        Log.i(TAG, line)
        appendFileLine(line)
    }

    fun warn(stage: String, kv: Map<String, Any?> = emptyMap()) {
        val currentTrace = traceId
        val index = seq.incrementAndGet()
        val sanitizedKv = sanitizeKv(kv)
        val body = renderBody(sanitizedKv)
        appendEntry(
            traceId = currentTrace,
            seq = index,
            level = "WARN",
            stage = stage,
            kv = sanitizedKv
        )
        val line = "trace=$currentTrace seq=$index stage=$stage $body".trim()
        Log.w(TAG, line)
        appendFileLine(line)
    }

    fun snapshotEntries(): List<TraceEntry> {
        return synchronized(recentEventsLock) {
            recentEvents.toList()
        }
    }

    fun clearEntriesForTest() {
        synchronized(recentEventsLock) {
            recentEvents.clear()
        }
    }

    private fun appendFileLine(line: String) {
        val file = traceFile ?: return
        synchronized(fileLock) {
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(line + "\n")
            }
        }
    }

    private fun appendEntry(
        traceId: String,
        seq: Int,
        level: String,
        stage: String,
        kv: Map<String, String>
    ) {
        synchronized(recentEventsLock) {
            if (recentEvents.size >= MAX_RECENT_EVENTS) {
                recentEvents.removeFirstOrNull()
            }
            recentEvents.addLast(
                TraceEntry(
                    traceId = traceId,
                    seq = seq,
                    level = level,
                    stage = stage,
                    kv = kv,
                    timestampMs = System.currentTimeMillis()
                )
            )
        }
    }

    private fun sanitizeKv(kv: Map<String, Any?>): Map<String, String> {
        return kv.entries
            .take(MAX_KV_COUNT)
            .associate { (key, value) -> key to sanitize(value) }
    }

    private fun renderBody(kv: Map<String, String>): String {
        return kv.entries.joinToString(" ") { (k, v) ->
            "$k=$v"
        }
    }

    private fun sanitize(value: Any?): String {
        val raw = value?.toString().orEmpty()
            .replace(Regex("\\s+"), " ")
            .replace("|", "/")
            .trim()
        return if (raw.length <= MAX_VAL_LEN) raw else raw.take(MAX_VAL_LEN)
    }

    private fun buildTraceId(): String {
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        return "T${fmt.format(Date())}"
    }
}
