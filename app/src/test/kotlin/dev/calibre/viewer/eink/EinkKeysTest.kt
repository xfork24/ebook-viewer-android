// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Physical key mapping and press-filter semantics for e-ink page turns.

package dev.calibre.viewer.eink

import android.view.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EinkKeysTest {

    @Test
    fun `volume and page keys map to page turns`() {
        assertEquals("pageup", EinkKeys.shortcutForKey(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals("pageup", EinkKeys.shortcutForKey(KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals("pagedown", EinkKeys.shortcutForKey(KeyEvent.KEYCODE_PAGE_DOWN))
        assertEquals("pagedown", EinkKeys.shortcutForKey(KeyEvent.KEYCODE_VOLUME_DOWN))
    }

    @Test
    fun `other keys fall through`() {
        assertNull(EinkKeys.shortcutForKey(KeyEvent.KEYCODE_SPACE))
        assertNull(EinkKeys.shortcutForKey(KeyEvent.KEYCODE_DPAD_LEFT))
        assertNull(EinkKeys.shortcutForKey(KeyEvent.KEYCODE_BACK))
    }

    @Test
    fun `only the initial press is a page turn`() {
        // Initial DOWN.
        assertTrue(EinkKeys.isPageTurnPress(KeyEvent.ACTION_DOWN, 0))
        // Auto-repeat DOWNs (long-press) must not machine-gun turns.
        assertFalse(EinkKeys.isPageTurnPress(KeyEvent.ACTION_DOWN, 1))
        assertFalse(EinkKeys.isPageTurnPress(KeyEvent.ACTION_DOWN, 7))
        // Key release.
        assertFalse(EinkKeys.isPageTurnPress(KeyEvent.ACTION_UP, 0))
    }
}
