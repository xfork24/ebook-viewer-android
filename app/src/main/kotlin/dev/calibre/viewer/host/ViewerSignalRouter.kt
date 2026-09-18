// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// JSON args → typed effect calls. Arg counts follow web_view.py's from_js
// declarations (e.g. show_error(title, details, html) — the html part is
// unused here). Signals not listed are M2+ concerns: the panels, TTS bar and
// lookup widgets they belong to are handled inside viewer.js or arrive with
// later milestones, so they are dropped silently.

package dev.calibre.viewer.host

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

class ViewerSignalRouter(private val effects: SignalEffects) {

    fun dispatch(name: String, args: List<JsonElement>) {
        when (name) {
            "view_created" -> effects.viewCreated()
            "on_iframe_ready" -> Unit // upstream only logs a performance marker
            "content_file_changed" -> effects.contentFileChanged(args.stringAt(0))
            "update_last_read_position" -> effects.updateLastReadPosition(
                cfi = args.stringAt(0).orEmpty(),
                posFrac = args.doubleAt(1) ?: 0.0,
            )
            "set_session_data" -> effects.setSessionData(args.at(0), args.at(1))
            "set_local_storage" -> effects.setLocalStorage(args.at(0), args.at(1))
            "reload_book" -> effects.reloadBook()
            "toggle_full_screen" -> effects.toggleFullScreen()
            "ask_for_open" -> effects.askForOpen()
            "copy_selection" -> effects.copySelection(args.stringAt(0).orEmpty())
            "open_url" -> effects.openUrl(args.stringAt(0).orEmpty())
            "show_error" -> effects.showError(
                title = args.stringAt(0).orEmpty(),
                details = args.stringAt(1).orEmpty(),
            )
            "show_loading_message" -> effects.showLoadingMessage(args.stringAt(0)?.takeIf { it.isNotEmpty() })
            "quit" -> effects.quit()
            "close_prep_finished" -> effects.closePrepFinished()
            else -> Unit
        }
    }

    private fun List<JsonElement>.at(index: Int): JsonElement =
        getOrNull(index) ?: JsonNull

    private fun List<JsonElement>.stringAt(index: Int): String? {
        val el = getOrNull(index) ?: return null
        if (el !is JsonPrimitive || el is JsonNull) return null
        return el.content
    }

    private fun List<JsonElement>.doubleAt(index: Int): Double? =
        (getOrNull(index) as? JsonPrimitive)?.doubleOrNull
}
