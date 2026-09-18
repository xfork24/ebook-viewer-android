// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Typed callbacks the signal router extracts from the JSON args. Default
// no-ops keep the implementing class readable — M1 deliberately ignores the
// panel/desktop-only signals (see ViewerSignalRouter).

package dev.calibre.viewer.host

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

interface SignalEffects {

    fun viewCreated() {}

    fun contentFileChanged(name: String?) {}

    fun updateLastReadPosition(cfi: String, posFrac: Double) {}

    fun setSessionData(key: JsonElement, value: JsonElement) {}

    fun setLocalStorage(key: JsonElement, value: JsonElement) {}

    fun reloadBook() {}

    fun toggleFullScreen() {}

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

    fun askForOpen() {}

    fun copySelection(text: String) {}

    fun openUrl(url: String) {}

    fun showError(title: String, details: String) {}

    /** @param message null hides the loading overlay (empty-string payload). */
    fun showLoadingMessage(message: String?) {}

    fun quit() {}

    fun closePrepFinished() {}
}
