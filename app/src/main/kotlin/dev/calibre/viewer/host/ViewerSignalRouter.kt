// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// JSON args → typed effect calls. Arg counts follow web_view.py's from_js
// declarations (e.g. show_error(title, details, html) — the html part is
// unused here). Signals not listed are M2+ concerns: the panels, TTS bar and
// lookup widgets they belong to are handled inside viewer.js or arrive with
// later milestones, so they are dropped silently.

package dev.calibre.viewer.host

import kotlinx.serialization.json.JsonArray
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
            // toggle_full_screen: the toolbar item is patched out of viewer.js,
            // but F11/Ctrl+Shift+F still emit the signal (viewer.js:47283) —
            // _register_signals binds to_python.<name> at page load, so the
            // name must stay registered in SignalNames or the page throws a
            // TypeError. Deliberate no-op, like the upstream host dropping an
            // unhandled signal. Same story for print_book / toggle_inspector.
            "toggle_full_screen" -> Unit
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
            // ask_for_open: viewer.js:50380 passes the recent-entry path
            // (or null for the plain "Open book" button). Blank counts as
            // absent — the page sometimes sends "" for a missing entry.
            "ask_for_open" -> effects.askForOpen(args.stringAt(0)?.takeIf { it.isNotBlank() })
            // change_background_image: viewer.js:50455 — the Styles "Change
            // image" button; args[0] is the preview <img> DOM id.
            "change_background_image" -> effects.changeBackgroundImage(args.stringAt(0).orEmpty())
            "copy_selection" -> effects.copySelection(args.stringAt(0).orEmpty())
            "open_url" -> effects.openUrl(args.stringAt(0).orEmpty())
            "show_error" -> effects.showError(
                title = args.stringAt(0).orEmpty(),
                details = args.stringAt(1).orEmpty(),
            )
            "show_loading_message" -> effects.showLoadingMessage(args.stringAt(0)?.takeIf { it.isNotEmpty() })
            "quit" -> effects.quit()
            "close_prep_finished" -> effects.closePrepFinished()
            // ---- M2: Highlights / Bookmarks / Profiles ----------------------
            "toggle_bookmarks" -> effects.toggleBookmarks()
            "toggle_highlights" -> effects.toggleHighlights()
            "toggle_profiles" -> effects.toggleProfiles()
            // Standalone fallback: the JS toolbar "Profiles" shortcut
            // dispatches `show_profiles`, not `toggle_profiles`, even when
            // ui_operations.toggle_profiles is set. Handle both so the
            // host panel opens regardless of which signal the page sends.
            "show_profiles" -> effects.toggleProfiles()
            // new_bookmark: viewer.js:50124 to_python.new_bookmark(data)
            "new_bookmark" -> effects.newBookmark(args.objAt(0))
            // highlights_changed: viewer.js:50456 — JS passes the array
            // directly as args[0] (not wrapped in {highlight:[...]}).
            "highlights_changed" -> {
                val arr = args.at(0) as? JsonArray ?: return@dispatch
                effects.highlightsChanged(arr)
            }
            // profile_op: viewer.js:49294 + 49303.
            //   profile_op("all-profiles", "", null)        — host replies via
            //                                                 bridge.profileResponse(...)
            //   profile_op("save-profile", name, settings)  — settings===null means delete
            //   profile_op("apply-profile", name, settings) — host writes each key to session
            "profile_op" -> effects.profileOp(
                which = args.stringAt(0).orEmpty(),
                name = args.stringAt(1).orEmpty(),
                data = args.objAt(2),
            )
            // report_cfi: page replies to host-initiated get_current_cfi(req_id).
            // viewer.js:50262 calls `to_python.report_cfi(request_id, data)`
            // — args[0] is the request id (string), args[1] is the CFI dict.
            "report_cfi" -> effects.reportCfi(
                requestId = args.stringAt(0).orEmpty(),
                data = args.objAt(1) ?: JsonObject(emptyMap()),
            )
            // ---- M2: reading statistics persistence -------------------------
            // update_reading_rates: Timers' {rates, chapter_times} payload
            // (viewer.js:46224); reset_reading_rates: no args.
            "update_reading_rates" -> effects.updateReadingRates(
                payload = args.objAt(0) ?: return@dispatch,
            )
            "reset_reading_rates" -> effects.resetReadingRates()
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
