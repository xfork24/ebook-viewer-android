// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Typed callbacks the signal router extracts from the JSON args. Default
// no-ops keep the implementing class readable — M1 deliberately ignores the
// panel/desktop-only signals (see ViewerSignalRouter).

package dev.calibre.viewer.host

import kotlinx.serialization.json.JsonElement

interface SignalEffects {

    fun viewCreated() {}

    fun contentFileChanged(name: String?) {}

    fun updateLastReadPosition(cfi: String, posFrac: Double) {}

    fun setSessionData(key: JsonElement, value: JsonElement) {}

    fun setLocalStorage(key: JsonElement, value: JsonElement) {}

    fun reloadBook() {}

    fun toggleFullScreen() {}

    fun askForOpen() {}

    fun copySelection(text: String) {}

    fun openUrl(url: String) {}

    fun showError(title: String, details: String) {}

    /** @param message null hides the loading overlay (empty-string payload). */
    fun showLoadingMessage(message: String?) {}

    fun quit() {}

    fun closePrepFinished() {}
}
