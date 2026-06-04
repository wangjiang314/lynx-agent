package com.juwan.lynx.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

object ClipboardUtils {
    private val urlRegex = Regex("""https?://[^\s，。,；;）)】\]}>]+""", RegexOption.IGNORE_CASE)

    fun clear(context: Context) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        manager.setPrimaryClip(ClipData.newPlainText("lynx-empty", ""))
    }

    fun readText(context: Context): String? {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun extractUrl(value: String?): String? {
        val normalized = value?.trim().orEmpty()
        if (normalized.isBlank()) return null
        if (normalized.startsWith("http://", ignoreCase = true) ||
            normalized.startsWith("https://", ignoreCase = true)
        ) {
            return normalized.trimEnd(')', ']', '}', '，', ',', '。', ';')
        }
        return urlRegex.find(normalized)?.value?.trimEnd(')', ']', '}', '，', ',', '。', ';')
    }
}
