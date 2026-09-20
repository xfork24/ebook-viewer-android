// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Typed callbacks the signal router extracts from the JSON args. Default
// no-ops keep the implementing class readable — M1 deliberately ignores the
// panel/desktop-only signals (see ViewerSignalRouter).

package dev.calibre.viewer.host

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

interface SignalEffects {

    fun viewCreated() {}

    fun contentFileChanged(name: String?) {}

    fun updateLastReadPosition(cfi: String, posFrac: Double) {}

    fun setSessionData(key: JsonElement, value: JsonElement) {}

    fun setLocalStorage(key: JsonElement, value: JsonElement) {}

    fun reloadBook() {}

    /**
     * report_cfi — page replies to a host-initiated get_current_cfi(req_id).
     * Args: [request_id, data]. Data shape (viewer.js:29608):
     * {cfi, progress_frac, file_progress_frac, selected_text,
     * selection_bounds, page_counts} — note request_id is the OTHER arg.
     */
    fun reportCfi(requestId: String, data: JsonObject) {}

    /** toggle_toc — standalone TOC panel visibility (host-side panel). */
    fun toggleToc() {}

    /** update_current_toc_nodes — leaf ids of the TOC families on screen. */
    fun updateCurrentTocNodes(ids: List<Long>) {}

    /** show_search — open the panel, pre-filled (with the selection, if any). */
    fun showSearch(text: String, trigger: Boolean) {}

    /** find_next — next/previous match request from the page's shortcuts. */
    fun findNext(backwards: Boolean) {}

    /** search_result_not_found — the page cannot display this result. */
    fun searchResultNotFound(sr: JsonObject) {}

    /** search_result_discovered — the page is now showing this result. */
    fun searchResultDiscovered(sr: JsonObject) {}

    /**
     * ask_for_open — open another book. [path] is the recent-list entry's
     * original key (a persisted content URI, or a plain path for legacy
     * entries); null means the plain "Open book" button → file picker.
     */
    fun askForOpen(path: String?) {}

    /**
     * change_background_image — Styles "Change image" button; [imgId] is the
     * DOM id of the preferences preview <img> the reply must target.
     */
    fun changeBackgroundImage(imgId: String) {}

    fun copySelection(text: String) {}

    fun openUrl(url: String) {}

    fun showError(title: String, details: String) {}

    /** @param message null hides the loading overlay (empty-string payload). */
    fun showLoadingMessage(message: String?) {}

    fun quit() {}

    fun closePrepFinished() {}

    // ---- M2: Highlights / Bookmarks / Profiles -------------------------

    /** toggle_bookmarks — open the bookmarks panel. */
    fun toggleBookmarks() {}

    /** toggle_highlights — open the highlights panel. */
    fun toggleHighlights() {}

    /** toggle_profiles — open the profiles panel. */
    fun toggleProfiles() {}

    /** new_bookmark — Ctrl+Alt+B; data may carry `{cfi, title}`. */
    fun newBookmark(data: JsonObject?) {}

    /**
     * highlights_changed — page pushes its full highlights array upward
     * (viewer.js:50456 calls `to_python.highlights_changed(amap.highlight)`
     * — args[0] is the array directly). The host merges it with its own
     * store; the host is the source of truth.
     */
    fun highlightsChanged(map: JsonArray) {}

    /**
     * profile_op — page-side profile round-trip (viewer.js:49294 / 49303).
     * [which] ∈ {"all-profiles", "save-profile", "apply-profile", ...}.
     */
    fun profileOp(which: String, name: String, data: JsonObject?) {}

    // ---- M2: reading statistics -------------------------------------------

    /**
     * update_reading_rates — Timers' payload
     * `{rates: [chars/sec...], chapter_times: {spine-name: seconds}}`
     * (viewer.js:46224), the whole object as args[0]. Persist per book.
     */
    fun updateReadingRates(payload: JsonObject) {}

    /** reset_reading_rates — the user cleared the stats overlay; no args. */
    fun resetReadingRates() {}
}
