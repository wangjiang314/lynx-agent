package com.juwan.lynx.agent

import com.juwan.lynx.state.LoggedPageObservation

internal object TraceLogFields {
    fun putSurfaceHintFields(
        kv: MutableMap<String, Any?>,
        page: LoggedPageObservation
    ) {
        kv["hint_surface"] = page.page
        kv["hint_title"] = page.pageTitle
        page.rawPage?.let { kv["debug_raw_surface"] = it }
        page.rawPageTitle?.let { kv["debug_raw_title"] = it }
    }
}
