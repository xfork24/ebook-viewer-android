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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

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
            "toggle_toc" -> effects.toggleToc()
            "update_current_toc_nodes" -> effects.updateCurrentTocNodes(args.longIdsAt(0))
            "show_search" -> effects.showSearch(
                text = args.stringAt(0).orEmpty(),
                trigger = args.boolAt(1),
            )
            "find_next" -> effects.findNext(backwards = args.boolAt(0))
            // both carry their payload as {search_result: {...}} (kwargs)
            "search_result_not_found" -> effects.searchResultNotFound(sr = args.searchResultAt())
            "search_result_discovered" -> effects.searchResultDiscovered(sr = args.searchResultAt())
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

    private fun List<JsonElement>.boolAt(index: Int): Boolean =
        (getOrNull(index) as? JsonPrimitive)?.content == "true"

    private fun List<JsonElement>.objAt(index: Int): JsonObject? =
        getOrNull(index) as? JsonObject

    /** search_result_* payload: {search_result: {...}}. */
    private fun List<JsonElement>.searchResultAt(): JsonObject {
        val wrapper = objAt(0) ?: return JsonObject(emptyMap())
        return wrapper["search_result"] as? JsonObject ?: wrapper
    }

    /** update_current_toc_nodes payload: an array of node ids. */
    private fun List<JsonElement>.longIdsAt(index: Int): List<Long> {
        val array = getOrNull(index) as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonPrimitive)?.longOrNull }
    }
}
