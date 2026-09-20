// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Vendor picker-contract selection: e-ink vendors whose OPEN_DOCUMENT picker
// never returns must route to GetContent; everything else keeps the
// system-file-manager default.

package dev.calibre.viewer.host

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VendorPickersTest {

    @Test
    fun `ireader and boox vendors prefer getcontent`() {
        assertTrue(prefersGetContentPicker("Zhangyue", "iReader", "Smart 5"))
        assertTrue(prefersGetContentPicker("iReader", "ireader", "T6"))
        assertTrue(prefersGetContentPicker("ONYX", "BOOX", "Note Air 3"))
        assertTrue(prefersGetContentPicker("Boyue", "Likebook", "Ares"))
    }

    @Test
    fun `stock and phone vendors keep opendocument`() {
        assertFalse(prefersGetContentPicker("Google", "google", "emu64a"))
        assertFalse(prefersGetContentPicker("samsung", "samsung", "SM-S928B"))
        assertFalse(prefersGetContentPicker("Xiaomi", "Xiaomi", "23049PD8C"))
        assertFalse(prefersGetContentPicker("", "", ""))
    }
}
