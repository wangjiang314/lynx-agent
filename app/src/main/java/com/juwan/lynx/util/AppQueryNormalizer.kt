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

package com.juwan.lynx.util

/**
 * Normalizes natural-language app launch queries without mapping specific apps
 * to packages or page flows.
 */
object AppQueryNormalizer {
    fun normalizeLaunchQuery(raw: String): String {
        val original = raw
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('"', '\'', '“', '”', '‘', '’')
        if (original.isBlank()) return ""
        if (looksLikePackageName(original)) return original.lowercase()

        val withoutIntent = original
            .replace(
                Regex("""^(please\s+)?(open|launch|start|run)\s+(the\s+)?""", RegexOption.IGNORE_CASE),
                ""
            )
            .replace(
                Regex("""^(请|帮我|麻烦你|麻烦)?\s*(打开|启动|开启)\s*"""),
                ""
            )
            .trim()

        val beforeDownstream = splitBeforeDownstreamIntent(withoutIntent).trim()
        val withoutAppNoun = beforeDownstream
            .replace(Regex("""\s+(app|application)$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""(应用程序|应用|软件)$"""), "")
            .trim()
            .trim('"', '\'', '“', '”', '‘', '’')

        return withoutAppNoun.ifBlank { original }
    }

    fun extractedLaunchTargetOrNull(raw: String): String? {
        val normalized = normalizeLaunchQuery(raw)
        val compactRaw = raw.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) return null
        if (looksLikePackageName(normalized)) return normalized
        return normalized.takeIf {
            it != compactRaw &&
                it.length <= 40 &&
                !it.contains(Regex("""\b(open|launch|start|run)\b""", RegexOption.IGNORE_CASE)) &&
                !it.contains(Regex("""打开|启动|开启"""))
        }
    }

    private fun splitBeforeDownstreamIntent(value: String): String {
        val english = Regex(
            """\s+(and\s+then|then|and|to|for)\s+""",
            RegexOption.IGNORE_CASE
        ).find(value)
        val punctuation = Regex("""[，,。；;:]""").find(value)
        val chinese = Regex("""(并且|然后|并|再|去|给|向|进入|搜索|查找|发送|发消息|发|新建|创建|编辑|写入|写|记录|计算|停留)""")
            .find(value)

        val firstIndex = listOfNotNull(
            english?.range?.first,
            punctuation?.range?.first,
            chinese?.range?.first
        ).minOrNull()

        return if (firstIndex != null && firstIndex > 0) {
            value.substring(0, firstIndex)
        } else {
            value
        }
    }

    private fun looksLikePackageName(value: String): Boolean {
        return Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+").matches(value.trim())
    }
}
