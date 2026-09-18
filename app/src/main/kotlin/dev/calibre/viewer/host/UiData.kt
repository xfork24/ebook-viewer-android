// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// ui_data for create_view, mirroring web_view.py:on_bridge_ready. The JS side
// (viewer-main.pyj create_view) reads exactly these keys; unknown ones are
// ignored, missing ones break the matching panel.

package dev.calibre.viewer.host

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object UiData {

    /**
     * QT_VERSION is a sentinel: the JS compares it to skip Qt-specific
     * workarounds (text selection bugs, fullscreen flicker fixes), and "6.6.0"
     * is above every version guard, so none of those paths activate.
     */
    const val QT_VERSION_SENTINEL = "6.6.0"

    /**
     * M1 font list — the generic CSS families every Android WebView resolves,
     * plus the serif/sans defaults the font panel offers. M2 will enumerate
     * the device's real families.
     */
    private val FONT_FAMILIES = listOf(
        "serif", "sans-serif", "monospace", "serif-monospace", "sans-serif-condensed",
        "cursive", "Noto Serif", "Noto Sans",
    )

    fun build(isDark: Boolean): JsonObject = buildJsonObject {
        put("all_font_families", JsonArray(FONT_FAMILIES.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        put("ui_font_family", "sans-serif")
        put("ui_font_sz", "10pt")
        put("show_home_page_on_ready", true)
        put("system_colors", systemColors(isDark))
        put("QT_VERSION", QT_VERSION_SENTINEL)
        put("short_time_fmt", "HH:mm")
        put("use_roman_numerals_for_series_number", false)
    }

    /**
     * set_system_colors() maps the palette onto the default light/dark color
     * schemes; e-ink screens are black-on-white either way, so only the
     * ordering flips. The optional "link" key is only needed for dark themes
     * where the default link blue would go unreadable — skipped here.
     */
    fun systemColors(isDark: Boolean): JsonObject {
        val background = if (isDark) "#000000" else "#ffffff"
        val foreground = if (isDark) "#ffffff" else "#000000"
        return buildJsonObject {
            put("background", background)
            put("foreground", foreground)
        }
    }
}
