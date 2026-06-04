package com.juwan.lynx.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ClipboardUtilsTest : FunSpec({
    test("extractUrl should accept direct URLs") {
        ClipboardUtils.extractUrl("https://example.com/foo?bar=1") shouldBe "https://example.com/foo?bar=1"
    }

    test("extractUrl should extract embedded URLs from clipboard payload") {
        ClipboardUtils.extractUrl("复制成功 https://example.com/foo?bar=1，快去查看") shouldBe "https://example.com/foo?bar=1"
    }

    test("extractUrl should trim trailing punctuation") {
        ClipboardUtils.extractUrl("https://example.com/foo)。") shouldBe "https://example.com/foo"
    }
})
