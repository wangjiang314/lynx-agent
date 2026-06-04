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

/**
 * UI-TARS style app launcher.
 */
class OpenAppTool(
    private val context: Context
) : Tool {
    override val name: String = "open_app"
    override val description: String = "启动应用（UI-TARS 风格）"
    override val parameters: List<ToolParam> = listOf(
        ToolParam("app_name", "string", "应用名或包名", required = false),
        ToolParam("app", "string", "应用名或包名", required = false),
        ToolParam("package_name", "string", "包名", required = false)
    )

    override suspend fun execute(args: Map<String, String>): String {
        val rawApp = args["app_name"]?.trim().orEmpty()
            .ifBlank { args["app"]?.trim().orEmpty() }
            .ifBlank { args["name"]?.trim().orEmpty() }
            .ifBlank { args["package_name"]?.trim().orEmpty() }
            .ifBlank { args["package"]?.trim().orEmpty() }
            .ifBlank { args["pkg"]?.trim().orEmpty() }
        val app = AppQueryNormalizer.normalizeLaunchQuery(rawApp)

        if (app.isBlank()) {
            return "错误: 缺少 app_name/app/package_name 参数"
        }

        val packageName = args["package_name"]?.trim().orEmpty()
            .ifBlank { args["package"]?.trim().orEmpty() }
            .ifBlank { args["pkg"]?.trim().orEmpty() }
            .ifBlank { app.takeIf { it.contains('.') }.orEmpty() }
        val explicitPackageArg = args["package_name"]?.trim().orEmpty()
            .ifBlank { args["package"]?.trim().orEmpty() }
            .ifBlank { args["pkg"]?.trim().orEmpty() }

        return try {
            val pm = context.packageManager
            if (packageName.isNotBlank()) {
                val byPackage = launchByPackage(pm, packageName)
                if (byPackage.launched) return buildLaunchSuccess(byPackage.display, packageName)
                if (explicitPackageArg.isNotBlank()) {
                    return "错误: 未找到包名为 \"$packageName\" 的可启动应用"
                }
            }

            if (looksLikePackageName(app)) {
                val direct = launchByPackage(pm, app)
                if (direct.launched) return buildLaunchSuccess(direct.display, app)
            }

            val resolved = resolveLaunchableByName(pm, app)
            if (resolved != null) {
                val intent = pm.getLaunchIntentForPackage(resolved.packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return buildLaunchSuccess(resolved.displayName, resolved.packageName)
                }
            }

            "错误: 未找到应用 \"$app\""
        } catch (e: Exception) {
            "错误: 启动应用失败 - ${e.message}"
        }
    }

    private fun launchByPackage(pm: PackageManager, packageName: String): LaunchResult {
        val intent = pm.getLaunchIntentForPackage(packageName) ?: return LaunchResult(false, packageName)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return LaunchResult(true, packageName)
    }

    private fun buildLaunchSuccess(displayName: String, packageName: String): String {
        return "已启动应用: $displayName package_name=$packageName"
    }

    private fun resolveLaunchableByName(pm: PackageManager, query: String): AppCandidate? {
        if (query.isBlank()) return null
        val launchableApps = getLaunchableApps(pm)
        if (launchableApps.isEmpty()) return null

        val q = query.lowercase()
        launchableApps.firstOrNull { it.displayName.lowercase() == q }?.let { return it }
        launchableApps.firstOrNull {
            val n = it.displayName.lowercase()
            n.contains(q) || q.contains(n)
        }?.let { return it }
        launchableApps.firstOrNull { it.packageName.lowercase() == q }?.let { return it }
        launchableApps.firstOrNull {
            val p = it.packageName.lowercase()
            p.contains(q) || q.contains(p)
        }?.let { return it }

        val nq = normalizeForFuzzy(q)
        if (nq.isBlank()) return null
        val familyMatches = launchableApps.filter {
            val dn = normalizeForFuzzy(it.displayName.lowercase())
            val pn = normalizeForFuzzy(it.packageName.lowercase())
            (dn.isNotBlank() && (dn == nq || dn.contains(nq) || nq.contains(dn))) ||
                (pn.isNotBlank() && (pn == nq || pn.contains(nq) || nq.contains(pn)))
        }
        return when {
            familyMatches.isEmpty() -> null
            familyMatches.size == 1 -> familyMatches.first()
            else -> familyMatches.minByOrNull { it.displayName.length }
        }
    }

    private fun getLaunchableApps(pm: PackageManager): List<AppCandidate> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolveInfos = pm.queryIntentActivities(intent, 0)

        val dedup = LinkedHashMap<String, AppCandidate>()
        for (ri in resolveInfos) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg.isBlank()) continue
            val label = ri.loadLabel(pm).toString().trim().ifBlank { pkg }
            dedup.putIfAbsent(pkg, AppCandidate(displayName = label, packageName = pkg))
        }
        return dedup.values.toList()
    }

    private fun looksLikePackageName(value: String): Boolean {
        if (value.isBlank()) return false
        return Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+").matches(value)
    }

    private fun normalizeForFuzzy(value: String): String {
        return value
            .replace(Regex("[^\\p{L}\\p{N}]"), "")
            .trim()
    }

    private data class AppCandidate(
        val displayName: String,
        val packageName: String
    )

    private data class LaunchResult(
        val launched: Boolean,
        val display: String
    )
}
