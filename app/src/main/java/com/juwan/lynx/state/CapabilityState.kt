package com.juwan.lynx.state

internal object CapabilityState {
    const val CAN_SEARCH = "can_search"
    const val CAN_OPEN_CANDIDATE = "can_open_candidate"
    const val CAN_SCROLL = "can_scroll"
    const val CAN_INPUT_TEXT = "can_input_text"
    const val CAN_SUBMIT_TEXT = "can_submit_text"
    const val CAN_LIKE = "can_like"
    const val CAN_OPEN_COMMENT = "can_open_comment"
    const val CAN_SUBMIT_COMMENT = "can_submit_comment"
    const val CAN_GO_BACK = "can_go_back"
    const val HAS_OVERLAY = "has_overlay"
    const val HAS_BOTTOM_INPUT = "has_bottom_input"
    const val HAS_MEDIA_SURFACE = "has_media_surface"
}

internal fun normalizeCapabilities(values: List<String>): List<String> {
    return values
        .asSequence()
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()
}

internal fun hasCapability(capabilities: List<String>, capability: String): Boolean {
    return normalizeCapabilities(capabilities).contains(capability.trim().lowercase())
}
