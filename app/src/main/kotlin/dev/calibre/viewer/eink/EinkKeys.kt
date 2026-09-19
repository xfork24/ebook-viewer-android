// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Physical page-turn keys → viewer.js trigger_shortcut names. Only the keys
// the WebView page would NOT handle itself are intercepted (volume keys never
// reach the page; consuming PAGE_UP/DOWN here prevents a double turn when
// viewer.js's own keyboard handling also fires). Soft keyboard, DPAD and
// Space go straight through to the page untouched.

package dev.calibre.viewer.eink

import android.view.KeyEvent

object EinkKeys {

    /**
     * @return the trigger_shortcut name for this key, or null when the key is
     *   not a page-turn key and should fall through to normal dispatch.
     */
    fun shortcutForKey(keyCode: Int): String? = when (keyCode) {
        KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_VOLUME_UP -> "pageup"
        KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN -> "pagedown"
        else -> null
    }

    /**
     * Initial press only: ACTION_DOWN with repeatCount == 0. Holding a
     * volume key auto-repeats further DOWNs (repeatCount > 0) which must not
     * machine-gun page turns on a slow e-ink panel. Non-initial events for a
     * mapped key are still consumed by the dispatcher (see
     * MainActivity.dispatchKeyEvent) so they never reach the WebView or the
     * system volume UI.
     */
    fun isPageTurnPress(action: Int, repeatCount: Int): Boolean =
        action == KeyEvent.ACTION_DOWN && repeatCount == 0
}
