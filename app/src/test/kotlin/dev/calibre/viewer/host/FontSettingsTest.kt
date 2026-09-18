// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// fontSettingsFromSessionData mirrors web_view.py:apply_font_settings —
// the arithmetic (fixed = base*13/16, defaults 16/13/0) and the standalone
// viewer's trigger rule (only SIZE changes owe a relayout signal) must hold
// exactly, or the font-size panel silently stops working.

package dev.calibre.viewer.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class FontSettingsTest {

    private fun parse(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `empty session data yields the engine defaults`() {
        val s = fontSettingsFromSessionData(parse("{}"))
        assertNull(s.serifFamily)
        assertNull(s.sansFamily)
        assertNull(s.monoFamily)
        assertEquals(StandardFontFamily.SERIF, s.standard)
        assertEquals(0, s.minimumFontSize)
        assertEquals(16, s.defaultFontSize)
        assertEquals(13, s.fixedFontSize)
    }

    @Test
    fun `base font size drives default and fixed sizes`() {
        // int(20 * 13 / 16) == 16 — Python truncation, mirrored by Int math.
        val s = fontSettingsFromSessionData(parse("""{"base_font_size":20}"""))
        assertEquals(20, s.defaultFontSize)
        assertEquals(16, s.fixedFontSize)
        assertEquals(0, s.minimumFontSize)
    }

    @Test
    fun `base font size is clamped to the viewer range`() {
        assertEquals(64, fontSettingsFromSessionData(parse("""{"base_font_size":300}""")).defaultFontSize)
        assertEquals(8, fontSettingsFromSessionData(parse("""{"base_font_size":1}""")).defaultFontSize)
    }

    @Test
    fun `float and garbage sizes fall back safely`() {
        assertEquals(18, fontSettingsFromSessionData(parse("""{"base_font_size":18.0}""")).defaultFontSize)
        assertEquals(16, fontSettingsFromSessionData(parse("""{"base_font_size":"big"}""")).defaultFontSize)
        assertEquals(16, fontSettingsFromSessionData(parse("""{"base_font_size":null}""")).defaultFontSize)
    }

    @Test
    fun `standalone font settings map families standard and minimum`() {
        val s = fontSettingsFromSessionData(
            parse(
                """{"standalone_font_settings":{
                    "serif_family":"Liberation Serif","sans_family":"Roboto",
                    "mono_family":"Droid Sans Mono","standard_font":"sans",
                    "minimum_font_size":12}}""",
            ),
        )
        assertEquals("Liberation Serif", s.serifFamily)
        assertEquals("Roboto", s.sansFamily)
        assertEquals("Droid Sans Mono", s.monoFamily)
        assertEquals(StandardFontFamily.SANS, s.standard)
        assertEquals(12, s.minimumFontSize)
    }

    @Test
    fun `hostile family names are rejected`() {
        // session_data.json is a droppable external file; quotes or braces
        // must not reach the engine's CSS font stack.
        val s = fontSettingsFromSessionData(
            parse(
                """{"standalone_font_settings":{"serif_family":"a\";}body{x:ss",
                    "sans_family":"  ","mono_family":null}}""",
            ),
        )
        assertNull(s.serifFamily)
        assertNull(s.sansFamily)
        assertNull(s.monoFamily)
        // unknown standard_font degrades to serif rather than crashing
        assertEquals(StandardFontFamily.SERIF, fontSettingsFromSessionData(
            parse("""{"standalone_font_settings":{"standard_font":"comic"}}"""),
        ).standard)
    }

    @Test
    fun `sizes differ ignores family changes`() {
        val a = FontSettings(null, null, null, StandardFontFamily.SERIF, 0, 16, 13)
        val familiesOnly = a.copy(serifFamily = "X", standard = StandardFontFamily.SANS)
        assertFalse(a.sizesDifferFrom(familiesOnly))
        assertTrue(a.sizesDifferFrom(a.copy(defaultFontSize = 24)))
        assertTrue(a.sizesDifferFrom(a.copy(fixedFontSize = 14)))
        assertTrue(a.sizesDifferFrom(a.copy(minimumFontSize = 8)))
    }

    @Test
    fun `touches font keys detects the payload shapes`() {
        val empty = parse("{}")
        assertTrue(touchesFontKeys(empty, parse("""{"base_font_size":20}""")))
        assertTrue(touchesFontKeys(empty, parse("""{"standalone_font_settings":{}}""")))
        assertFalse(touchesFontKeys(empty, parse("""{"read_mode":"flow"}""")))
        // no actual change → no re-apply
        val sized = parse("""{"base_font_size":20}""")
        assertFalse(touchesFontKeys(sized, parse("""{"base_font_size":20}""")))
    }
}
