// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// The iReader bug: a parameterised content-type must reach WebResourceResponse
// as a bare type + separate encoding, or the page renders as plain text.

package dev.calibre.viewer.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResponseMimeTest {

    @Test
    fun `parameterised html splits into bare type and charset`() {
        val m = splitResponseMime("text/html; charset=utf-8")
        assertEquals("text/html", m.type)
        assertEquals("utf-8", m.encoding)
    }

    @Test
    fun `bare html defaults to utf 8`() {
        val m = splitResponseMime("text/html")
        assertEquals("text/html", m.type)
        assertEquals("utf-8", m.encoding)
    }

    @Test
    fun `css json javascript and xml are textual`() {
        for (type in listOf("text/css", "application/json", "application/javascript", "application/xhtml+xml", "application/x-dtbncx+xml")) {
            assertEquals("utf-8", splitResponseMime(type).encoding, type)
        }
    }

    @Test
    fun `images fonts and unknown types stay binary`() {
        for (type in listOf("image/jpeg", "image/png", "font/woff2", "font/ttf", "application/octet-stream", "audio/mpeg")) {
            assertNull(splitResponseMime(type).encoding, type)
            assertEquals(type, splitResponseMime(type).type)
        }
    }

    @Test
    fun `case and whitespace are normalised`() {
        val m = splitResponseMime("  TEXT/HTML;  Charset=UTF-8  ")
        assertEquals("text/html", m.type)
        assertEquals("UTF-8", m.encoding)
    }

    @Test
    fun `non charset parameters are ignored but keep text default`() {
        val m = splitResponseMime("text/html; boundary=x")
        assertEquals("text/html", m.type)
        assertEquals("utf-8", m.encoding)
    }
}
