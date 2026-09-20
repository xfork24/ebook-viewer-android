// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Android-facing actions the controller needs. Keeping them behind an
// interface means the controller (and its signal routing) is plain JVM code
// that unit tests can drive with a fake.

package dev.calibre.viewer.host

interface HostUi {

    /** ask_for_open — launch the system file picker (SAF). */
    fun pickBook()

    /** change_background_image — launch the system file manager for an image. */
    fun pickBackgroundImage()

    /**
     * ask_for_open(path) from the recently-opened list — resolve the entry's
     * original key (persisted content URI, or a plain path for legacy
     * entries) and open the book from it directly.
     * @return false when the source is no longer readable (grant expired,
     *   file moved/deleted); the caller then drops the stale recent entry
     *   and falls back to [pickBook]. Implementations toast on failure.
     */
    fun tryOpenRecent(path: String): Boolean

    /** quit / close_prep_finished — close the activity. */
    fun finishApp()

    /**
     * apply_font_settings — push session-data-derived font sizes/families
     * onto the WebView (the standalone viewer sizes book text through the
     * engine defaults, not JS).
     * @return true when the font SIZES changed (the caller answers with the
     *   viewer_font_size_changed signal)
     */
    fun applyFontSettings(settings: FontSettings): Boolean

    /** copy_selection — put text on the system clipboard. */
    fun copyToClipboard(text: String)

    /** open_url — hand the URL to the system (browser / Custom Tabs). */
    fun openUrl(url: String)
}
