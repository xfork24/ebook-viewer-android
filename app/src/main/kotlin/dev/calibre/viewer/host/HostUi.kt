// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Android-facing actions the controller needs. Keeping them behind an
// interface means the controller (and its signal routing) is plain JVM code
// that unit tests can drive with a fake.

package dev.calibre.viewer.host

interface HostUi {

    /** ask_for_open — launch the system file picker (SAF). */
    fun pickBook()

    /** quit / close_prep_finished — close the activity. */
    fun finishApp()

    /**
     * toggle_full_screen — enter/leave immersive mode.
     * @return the new immersive state (reported back to JS via
     *   full_screen_state_changed)
     */
    fun toggleImmersive(): Boolean

    /** copy_selection — put text on the system clipboard. */
    fun copyToClipboard(text: String)

    /** open_url — hand the URL to the system (browser / Custom Tabs). */
    fun openUrl(url: String)
}
