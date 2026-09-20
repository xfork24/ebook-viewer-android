// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// The title-ping bridge, a faithful port of calibre.utils.webengine.Bridge
// (host half) against the python_comm object qt.pyj installs in the page
// (JS half, already inside viewer.js):
//
//   JS → host:  python_comm queues {type:'signal'|'qt-ready',...} and pings
//               by setting document.title = "__webengine_messages_pending__0|1"
//               (alternating so identical consecutive titles still fire an
//               event). Host polls with `python_comm._poll()`, which returns
//               and clears the queue.
//   host → JS:  `python_comm._from_python(name, [args])` dispatches to the
//               @from_python slot registered under that name;
//               `python_comm._register_signals([names])` binds the emitters.
//
// Upstream semantics preserved deliberately:
//   - qt-ready may arrive more than once (page reload): every arrival
//     re-registers signals, re-flushes pending actions and re-emits
//     onReady — on_bridge_ready upstream re-sends create_view and replays
//     start_book_load so a reloaded page shows the same book.
//   - pending actions are a dict keyed by action name: a repeat of the same
//     action overwrites the earlier one, and entries are NOT removed after
//     being flushed (they replay on reload).
//   - a direct call before the bridge is ready only warns (upstream to_js
//     prints a warning); executeWhenReady is the queueing variant.
//
// All methods must be called from the main thread (evaluateJavascript and
// title events are main-thread affairs); the watchdog drives poll() on a
// timer as a fallback for WebViews that coalesce title changes.

package dev.calibre.viewer.bridge

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ViewerBridge(
    private val js: JsRunner,
    private val onSignal: (name: String, args: List<JsonElement>) -> Unit,
    private val warn: (String) -> Unit = {},
) {

    /** True between _register_signals and the end (never reset — upstream parity). */
    var ready: Boolean = false
        private set

    /** bridge_ready equivalent; may fire again after a page reload. */
    var onReady: (() -> Unit)? = null

    /** Optional watchdog kept informed of poll outcomes. */
    var watchdog: BridgeWatchdog? = null

    private val pendingActions = LinkedHashMap<String, List<JsonElement>>()

    // ---- JS → host ---------------------------------------------------------

    /** WebChromeClient.onReceivedTitle → here; pings start a poll. */
    fun onTitleChanged(title: String) {
        if (title.startsWith(SPECIAL_TITLE)) poll()
    }

    /** python_comm._poll(): collect-and-clear the queued messages. */
    fun poll() {
        js.eval(POLL_JS) { result -> dispatchPolled(result) }
    }

    private fun dispatchPolled(resultJson: String?) {
        val messages = parseMessages(resultJson)
        watchdog?.recordPoll(messages.isEmpty())
        for (msg in messages) {
            when (msg.type) {
                "qt-ready" -> registerSignals()
                "signal" -> {
                    val name = msg.name
                    if (name == null) {
                        warn("Signal message without a name: $msg")
                    } else if (name !in SignalNames.JS_TO_HOST) {
                        warn("No js-to-python signal named: $name")
                    } else {
                        onSignal(name, msg.args)
                    }
                }
                else -> warn("Unknown message type: ${msg.type}")
            }
        }
    }

    private fun registerSignals() {
        ready = true
        js.eval("python_comm._register_signals($REGISTER_SIGNALS_JSON)")
        onReady?.invoke()
    }

    /**
     * Replay queued actions. Upstream flushes from its on_bridge_ready slot
     * AFTER sending create_view — the host must do the same (send create_view
     * first, then flush) so a queued start_book_load can't beat create_view.
     * Entries are deliberately NOT removed (see class doc).
     */
    fun flushPendingActions() {
        for ((action, args) in pendingActions) send(action, args)
    }

    private fun parseMessages(resultJson: String?): List<PolledMessage> {
        if (resultJson.isNullOrBlank() || resultJson == "null") return emptyList()
        val parsed = try {
            Json.parseToJsonElement(resultJson)
        } catch (e: Exception) {
            warn("Bad poll result: ${e.message}")
            return emptyList()
        }
        if (parsed !is JsonArray) return emptyList()
        val out = ArrayList<PolledMessage>(parsed.size)
        for (element in parsed) {
            val obj = element as? JsonObject ?: continue
            val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: continue
            val name = obj["name"]?.jsonPrimitive?.contentOrNull
            val args = (obj["args"] as? JsonArray)?.toList() ?: emptyList()
            out.add(PolledMessage(type, name, args))
        }
        return out
    }

    private class PolledMessage(val type: String, val name: String?, val args: List<JsonElement>)

    // ---- host → JS ---------------------------------------------------------

    /** execute_when_ready(): queue until ready; same action overwrites. */
    fun executeWhenReady(action: String, vararg args: JsonElement) {
        if (ready) {
            send(action, args.toList())
        } else {
            pendingActions[action] = args.toList()
        }
    }

    /** Direct to_js call — only warns when the bridge is not up yet. */
    fun call(action: String, vararg args: JsonElement) {
        if (!ready) warn("Calling $action() before the javascript bridge is ready")
        send(action, args.toList())
    }

    private fun send(action: String, args: List<JsonElement>) {
        // Defense in depth: a single failure here must not crash the host
        // (e-ink devices with vendor WebViews occasionally throw on payload
        // sizing or argument types — recover and continue rather than die).
        try {
            val payload = Json.encodeToString(JsonElement.serializer(), buildJsonArray { args.forEach(::add) })
            val name = Json.encodeToString(JsonElement.serializer(), JsonPrimitive(action))
            js.eval("if (window.python_comm) python_comm._from_python($name, $payload)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            warn("send($action) failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ---- typed entry points -------------------------------------------------

    /** web_view.py:on_bridge_ready → create_view(session_data, local_storage, field_metadata, ui_data). */
    fun createView(
        sessionData: JsonElement,
        localStorage: JsonElement,
        fieldMetadata: JsonElement,
        uiData: JsonObject,
    ) {
        executeWhenReady("create_view", sessionData, localStorage, fieldMetadata, uiData)
    }

    /**
     * web_view.py:start_book_load. key is the 1-tuple [book_path] upstream
     * passes (the rendered book dir); book_url / book_in_library_url are null
     * without a calibre library connection.
     */
    fun startBookLoad(
        renderedBookDir: String,
        initialPosition: JsonElement?,
        pathtoebook: String,
        highlights: JsonElement = buildJsonArray {},
        readingRates: JsonElement? = null,
    ) {
        executeWhenReady(
            "start_book_load",
            buildJsonArray { add(JsonPrimitive(renderedBookDir)) },
            initialPosition ?: JsonNull,
            JsonPrimitive(pathtoebook),
            highlights,
            JsonNull,
            readingRates ?: JsonNull,
            JsonNull,
        )
    }

    // ---- M2: HOST → JS annotations / profiles -----------------------

    /**
     * viewer.js:49640 highlight_action(uuid, which). The page calls this
     * back into the annotations manager to mutate or focus a highlight.
     * Used by the bookmarks / highlights panel rows to navigate the page
     * to a known CFI without going through goto_cfi.
     */
    fun highlightAction(uuid: String, which: String) {
        executeWhenReady("highlight_action", JsonPrimitive(uuid), JsonPrimitive(which))
    }

    /**
     * viewer.js:49655 generic_action(name, data). The catch-all host→JS
     * RPC used by search/profile/preference flows. Pass-through — name and
     * data are echoed exactly. Use this only when no narrower slot exists.
     */
    fun genericAction(name: String, data: JsonElement) {
        executeWhenReady("generic_action", JsonPrimitive(name), data)
    }

    /**
     * viewer.js:49916 background_image_changed(img_id, url) — refreshes the
     * Styles preview <img> (cache-busted src + dataset.url); the page
     * persists dataset.url as the background_image session value on commit,
     * so the URL must be the absolute /reader-background form that
     * modify_background_image_url_for_fetch passes through unchanged.
     */
    fun backgroundImageChanged(imgId: String, url: String) {
        executeWhenReady("background_image_changed", JsonPrimitive(imgId), JsonPrimitive(url))
    }

    /**
     * viewer.js:49256 profile_response(which, x). Reply to profile_op
     * round-trips: "all-profiles" → list, "save-profile" → ack, "apply-profile"
     * → forward the dict so the page can apply settings locally, "request-save"
     * → snapshot current settings.
     */
    fun profileResponse(which: String, data: JsonElement) {
        executeWhenReady("profile_response", JsonPrimitive(which), data)
    }

    /**
     * viewer.js:49776 get_current_cfi(request_id). Asks the page for the
     * current CFI; the page replies asynchronously via `report_cfi(request_id,
     * data)` (host-side handler already routes it back through `onSignal`).
     * Used by the host's "Bookmark this page" button.
     */
    fun getCurrentCfi(requestId: String) {
        executeWhenReady("get_current_cfi", JsonPrimitive(requestId))
    }

    companion object {
        /** calibre.constants.SPECIAL_TITLE_FOR_WEBENGINE_COMMS. */
        const val SPECIAL_TITLE = "__webengine_messages_pending__"

        // Guarded: during page (re)loads python_comm is briefly absent, and
        // an unguarded reference would surface as an uncaught exception in
        // the page console for every watchdog tick. Internal for the
        // protocol tests, which assert on the exact JS emitted.
        internal const val POLL_JS = "window.python_comm ? python_comm._poll() : null"

        /** json.dumps(tuple(from_js signal names)) — bound by _register_signals. */
        private val REGISTER_SIGNALS_JSON: String = Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(JsonPrimitive.serializer()),
            SignalNames.JS_TO_HOST.map { JsonPrimitive(it) },
        )
    }
}
