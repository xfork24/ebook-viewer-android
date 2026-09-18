// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// web_view.py:apply_font_settings, Android half. In standalone mode the
// viewer does NOT size book text in JS (iframe apply_font_size is guarded
// by !is_standalone_viewer): the font-size panel only persists
// base_font_size via set_session_data, and the host is expected to
//   (a) mirror it onto the engine's default font size — what the book
//       iframes actually render at — and
//   (b) answer with the viewer_font_size_changed signal so pagination is
//       recomputed for the new text metrics.
// This file computes the font knobs implied by session data and upstream's
// rule for when the relayout signal is owed (font SIZES changed; family
// changes re-render without a relayout).

package dev.calibre.viewer.host

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Which family unstyled text ("the standard font") follows. */
enum class StandardFontFamily { SERIF, SANS, MONO }

/**
 * The WebView font knobs implied by session data. Null families mean
 * "reset to the platform default"; the host maps that onto the CSS generic
 * families.
 */
data class FontSettings(
    val serifFamily: String?,
    val sansFamily: String?,
    val monoFamily: String?,
    val standard: StandardFontFamily,
    val minimumFontSize: Int,
    val defaultFontSize: Int,
    val fixedFontSize: Int,
) {
    /** Upstream sends viewer_font_size_changed only when a SIZE changed. */
    fun sizesDifferFrom(other: FontSettings): Boolean =
        minimumFontSize != other.minimumFontSize ||
            defaultFontSize != other.defaultFontSize ||
            fixedFontSize != other.fixedFontSize
}

/** Session-data keys whose change requires re-applying font settings. */
private val FONT_KEYS = setOf("standalone_font_settings", "base_font_size")

/** viewer.js clamps opts.base_font_size into this range; mirror it host-side. */
private const val MIN_BASE_FONT_SIZE = 8
private const val MAX_BASE_FONT_SIZE = 64

/** The engine's defaults when session data says nothing (Chromium's). */
private const val DEFAULT_BASE_FONT_SIZE = 16

private const val MAX_FAMILY_LENGTH = 128

fun fontSettingsFromSessionData(sessionData: JsonObject): FontSettings {
    val fs = sessionData["standalone_font_settings"] as? JsonObject ?: JsonObject(emptyMap())
    val base = (sessionData["base_font_size"].asIntOrNull() ?: DEFAULT_BASE_FONT_SIZE)
        .coerceIn(MIN_BASE_FONT_SIZE, MAX_BASE_FONT_SIZE)
    return FontSettings(
        serifFamily = fs.familyOrNull("serif_family"),
        sansFamily = fs.familyOrNull("sans_family"),
        monoFamily = fs.familyOrNull("mono_family"),
        standard = when (fs.stringOrNull("standard_font")) {
            "sans" -> StandardFontFamily.SANS
            "mono" -> StandardFontFamily.MONO
            else -> StandardFontFamily.SERIF
        },
        minimumFontSize = fs["minimum_font_size"].asIntOrNull()?.coerceIn(0, MAX_BASE_FONT_SIZE) ?: 0,
        defaultFontSize = base,
        fixedFontSize = base * 13 / 16,
    )
}

/** web_view.py's fonts_changed: did this payload touch any font key? */
fun touchesFontKeys(before: JsonObject, after: JsonObject): Boolean =
    FONT_KEYS.any { before[it] != after[it] }

/**
 * Family names are persisted text anything (a desktop install, an edited
 * file) could have written, and they end up inside the engine's CSS font
 * stack — keep them to a conservative identifier charset.
 */
private fun JsonObject.familyOrNull(key: String): String? {
    val value = stringOrNull(key) ?: return null
    val safe = value.all { ch ->
        ch.isLetterOrDigit() || ch == ' ' || ch == '-' || ch == '_' || ch == '.' || ch == '\''
    }
    return if (safe && value.isNotBlank()) value else null
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

/** int or integral float (JS numbers may arrive either way); null otherwise. */
private fun JsonElement?.asIntOrNull(): Int? = when (this) {
    is JsonPrimitive -> intOrNull ?: doubleOrNull
        ?.takeIf { it % 1.0 == 0.0 }
        ?.toInt()
    else -> null
}
