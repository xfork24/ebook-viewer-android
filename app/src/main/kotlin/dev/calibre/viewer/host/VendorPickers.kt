// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Which picker contract to use for the Styles "Change image" flow on a given
// device. Extracted from MainActivity so the rule is unit-testable.

package dev.calibre.viewer.host

/**
 * E-ink vendor ROMs whose bundled pickers OPEN for ACTION_OPEN_DOCUMENT but
 * never finish with a result (tap-to-select, no confirm button, no way back).
 * ACTION_GET_CONTENT — the one contract every vendor file manager implements
 * correctly — is used on these devices instead. On stock Android GetContent
 * can be intercepted by the photo picker, so it is strictly a vendor escape
 * hatch, never the default.
 */
private val GET_CONTENT_VENDORS = listOf(
    "zhangyue", // 掌阅 iReader e-paper readers
    "ireader",
    "onyx", // BOOX
    "boox",
    "boyue",
)

fun prefersGetContentPicker(manufacturer: String, brand: String, model: String): Boolean {
    val haystack = "$manufacturer $brand $model".lowercase()
    return GET_CONTENT_VENDORS.any { haystack.contains(it) }
}
