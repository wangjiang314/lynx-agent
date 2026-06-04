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

package com.juwan.lynx.tool.primitive

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.juwan.lynx.runtime.Tool
import com.juwan.lynx.runtime.ToolParam
import com.juwan.lynx.util.AppQueryNormalizer
import java.util.Locale

/**
 * Primitive tool for resolving target app from installed launchable applications.
 *
 * Returns compact key-value lines so both LLM and runtime can parse reliably:
 * - found: status, display_name, package_name, match_type, confidence
 * - ambiguous: status, candidate_names, candidate_packages
 * - not_found: status
 * - error: status, reason
 */
class ResolveInstalledAppTool(
    private val context: Context
) : Tool {

    override val name: String = "resolve_installed_app"
    override val description: String = "查询已安装应用并解析目标包名"
    override val parameters: List<ToolParam> = listOf(
        ToolParam("query", "string", "目标应用名称或包名", required = true)
    )

    override suspend fun execute(args: Map<String, String>): String {
        return try {
            val query = extractQuery(args)
            if (query.isEmpty()) {
                return buildErrorResponse("缺少 query 参数")
            }

            val packageManager = context.packageManager
            val launchableApps = getLaunchableApps(packageManager)

            if (launchableApps.isEmpty()) {
                return buildErrorResponse("未获取到可启动应用列表", query)
            }

            // 1) Exact package match
            val exactPackageMatch = launchableApps.firstOrNull {
                it.packageName.equals(query, ignoreCase = true)
            }
            if (exactPackageMatch != null) {
                return buildFoundResponse(
                    query = query,
                    candidate = exactPackageMatch,
                    matchType = "exact_package",
                    confidence = 1.0f
                )
            }

            // 2) Ranked matching
            val ranked = launchableApps
                .mapNotNull { candidate ->
                    scoreCandidate(query, candidate)?.let { scored ->
                        ScoredCandidate(
                            candidate = candidate,
                            score = scored.score,
                            matchType = scored.matchType
                        )
                    }
                }
                .sortedByDescending { it.score }

            if (ranked.isEmpty() || ranked.first().score < NOT_FOUND_THRESHOLD) {
                return buildNotFoundResponse(query)
            }

            val top = ranked.first()
            val second = ranked.getOrNull(1)

            val isAmbiguous = second != null &&
                second.score >= (top.score - AMBIGUOUS_GAP_THRESHOLD) &&
                second.score >= AMBIGUOUS_SCORE_THRESHOLD

            if (isAmbiguous) {
                return buildAmbiguousResponse(query, ranked.take(MAX_AMBIGUOUS_CANDIDATES))
            }

            return buildFoundResponse(
                query = query,
                candidate = top.candidate,
                matchType = top.matchType,
                confidence = top.score
            )
        } catch (e: Exception) {
            buildErrorResponse(e.message ?: "unknown")
        }
    }

    private fun extractQuery(args: Map<String, String>): String {
        val raw = args["query"]?.trim().orEmpty()
            .ifBlank { args["app"]?.trim().orEmpty() }
            .ifBlank { args["app_name"]?.trim().orEmpty() }
            .ifBlank { args["name"]?.trim().orEmpty() }
        return AppQueryNormalizer.normalizeLaunchQuery(raw)
    }

    private fun getLaunchableApps(packageManager: PackageManager): List<AppCandidate> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        @Suppress("DEPRECATION")
        val resolveInfos = packageManager.queryIntentActivities(intent, 0)

        val uniqueByPackage = LinkedHashMap<String, AppCandidate>()

        for (resolveInfo in resolveInfos) {
            val packageName = resolveInfo.activityInfo?.packageName ?: continue
            if (packageName.isBlank()) continue

            val displayName = resolveInfo.loadLabel(packageManager).toString().trim()
                .ifBlank { packageName }

            uniqueByPackage.putIfAbsent(
                packageName,
                AppCandidate(
                    displayName = displayName,
                    packageName = packageName
                )
            )
        }

        return uniqueByPackage.values
            .sortedWith(compareBy<AppCandidate> { it.displayName.length }.thenBy { it.displayName })
    }

    private fun scoreCandidate(query: String, candidate: AppCandidate): ScoredMatch? {
        val queryLower = query.trim().lowercase()
        if (queryLower.isBlank()) return null

        val packageLower = candidate.packageName.lowercase()
        val nameLower = candidate.displayName.lowercase()

        // Strong deterministic matches first
        if (packageLower == queryLower) {
            return ScoredMatch(score = 1.0f, matchType = "exact_package")
        }
        if (nameLower == queryLower) {
            return ScoredMatch(score = 0.98f, matchType = "exact_name")
        }
        if (packageLower.startsWith("$queryLower.") || queryLower.startsWith("$packageLower.")) {
            return ScoredMatch(score = 0.94f, matchType = "package_family")
        }
        if (nameLower.contains(queryLower) || queryLower.contains(nameLower)) {
            return ScoredMatch(score = 0.90f, matchType = "name_contains")
        }
        if (packageLower.contains(queryLower) || queryLower.contains(packageLower)) {
            return ScoredMatch(score = 0.82f, matchType = "package_contains")
        }

        return null
    }

    private fun buildFoundResponse(
        query: String,
        candidate: AppCandidate,
        matchType: String,
        confidence: Float
    ): String {
        return buildString {
            appendLine("status=found")
            appendLine("query=${sanitize(query)}")
            appendLine("display_name=${sanitize(candidate.displayName)}")
            appendLine("package_name=${sanitize(candidate.packageName)}")
            appendLine("match_type=${sanitize(matchType)}")
            append("confidence=${formatConfidence(confidence)}")
        }
    }

    private fun buildAmbiguousResponse(
        query: String,
        candidates: List<ScoredCandidate>
    ): String {
        val names = candidates.joinToString(",") {
            sanitize(it.candidate.displayName).replace(',', '，')
        }
        val packages = candidates.joinToString(",") {
            sanitize(it.candidate.packageName).replace(',', '，')
        }

        return buildString {
            appendLine("status=ambiguous")
            appendLine("query=${sanitize(query)}")
            appendLine("candidate_names=$names")
            append("candidate_packages=$packages")
        }
    }

    private fun buildNotFoundResponse(query: String): String {
        return buildString {
            appendLine("status=not_found")
            append("query=${sanitize(query)}")
        }
    }

    private fun buildErrorResponse(reason: String, query: String = ""): String {
        return buildString {
            appendLine("status=error")
            if (query.isNotBlank()) {
                appendLine("query=${sanitize(query)}")
            }
            append("reason=${sanitize(reason)}")
        }
    }

    private fun sanitize(value: String): String {
        return value
            .replace("\n", " ")
            .replace("\r", " ")
            .replace("=", " ")
            .trim()
    }

    private fun formatConfidence(value: Float): String {
        return String.format(Locale.US, "%.2f", value.coerceIn(0f, 1f))
    }

    private data class AppCandidate(
        val displayName: String,
        val packageName: String
    )

    private data class ScoredMatch(
        val score: Float,
        val matchType: String
    )

    private data class ScoredCandidate(
        val candidate: AppCandidate,
        val score: Float,
        val matchType: String
    )

    private companion object {
        private const val NOT_FOUND_THRESHOLD = 0.65f
        private const val AMBIGUOUS_GAP_THRESHOLD = 0.06f
        private const val AMBIGUOUS_SCORE_THRESHOLD = 0.75f
        private const val MAX_AMBIGUOUS_CANDIDATES = 3
    }
}
