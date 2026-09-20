// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// ui_data plumbing for the Fonts panel: all_font_families must mirror the
// caller-supplied list verbatim (the page iterates it with no fallback).

package dev.calibre.viewer.host

import kotlin.test.Test
import kotlin.test.assertEquals

class UiDataTest {

    @Test
    fun `all font families mirror the caller-supplied list`() {
        val ui = UiData.build(isDark = false, fontFamilies = listOf("a", "b"))
        assertEquals("""["a","b"]""", ui["all_font_families"].toString())
    }
}
