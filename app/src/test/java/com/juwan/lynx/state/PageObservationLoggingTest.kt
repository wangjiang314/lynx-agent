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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageObservationLoggingTest {

    @Test
    fun `trusted title should take precedence over raw page label`() {
        val logged = PageObservationLogging.fromValues(
            debugPageLabel = "搜索框，阜宁县射滨村",
            debugPageTitleHint = "阜宁县射滨村",
            debugPageSemanticTag = "search_list",
            debugPageSignals = listOf("title_source:ui_top")
        )

        assertEquals("阜宁县射滨村", logged.page)
        assertEquals("阜宁县射滨村", logged.pageTitle)
        assertEquals("搜索框，阜宁县射滨村", logged.rawPage)
        assertNull(logged.rawPageTitle)
    }

    @Test
    fun `raw labels should pass through when no trusted title exists`() {
        val logged = PageObservationLogging.fromValues(
            debugPageLabel = "我的位置",
            debugPageTitleHint = "切换起终点",
            debugPageSemanticTag = "search_list",
            debugPageSignals = listOf("title_source:ui_candidate")
        )

        assertEquals("我的位置", logged.page)
        assertEquals("切换起终点", logged.pageTitle)
        assertNull(logged.rawPage)
        assertNull(logged.rawPageTitle)
    }

    @Test
    fun `semantic tag should be fallback only when page label is missing`() {
        val logged = PageObservationLogging.fromValues(
            debugPageLabel = null,
            debugPageTitleHint = null,
            debugPageSemanticTag = "content_detail",
            debugPageSignals = emptyList()
        )

        assertEquals("content_detail", logged.page)
        assertEquals("none", logged.pageTitle)
        assertNull(logged.rawPage)
        assertNull(logged.rawPageTitle)
    }
}
