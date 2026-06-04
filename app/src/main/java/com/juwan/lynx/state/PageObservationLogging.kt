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

package com.juwan.lynx.state

internal data class LoggedPageObservation(
    val page: String,
    val pageTitle: String,
    val rawPage: String? = null,
    val rawPageTitle: String? = null
)

internal object PageObservationLogging {
    fun extractTitleSource(debugPageSignals: List<String>): String {
        return debugPageSignals
            .firstOrNull { signal -> signal.startsWith("title_source:") }
            ?.substringAfter(':')
            ?.trim()
            ?.lowercase()
            ?: "none"
    }

    fun fromObservation(observation: Observation): LoggedPageObservation {
        return fromValues(
            debugPageLabel = observation.debugPageLabel,
            debugPageTitleHint = observation.debugPageTitleHint,
            debugPageSemanticTag = observation.debugPageSemanticTag,
            debugPageSignals = observation.debugPageSignals
        )
    }

    fun fromValues(
        debugPageLabel: String?,
        debugPageTitleHint: String?,
        debugPageSemanticTag: String?,
        debugPageSignals: List<String>
    ): LoggedPageObservation {
        val rawPage = debugPageLabel?.trim()?.takeIf { it.isNotBlank() }
        val rawTitle = debugPageTitleHint?.trim()?.takeIf { it.isNotBlank() }
        val semanticTag = debugPageSemanticTag
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() && it != "unknown" }
        val titleSource = extractTitleSource(debugPageSignals)
        val trustedTitle = rawTitle?.takeIf {
            titleSource == "ui_top" || titleSource == "ocr_top"
        }
        val displayPage = trustedTitle ?: rawPage ?: semanticTag ?: "unknown"
        val displayTitle = trustedTitle ?: rawTitle ?: "none"

        return LoggedPageObservation(
            page = displayPage,
            pageTitle = displayTitle,
            rawPage = rawPage?.takeIf { it != displayPage },
            rawPageTitle = rawTitle?.takeIf { it != displayTitle }
        )
    }
}
