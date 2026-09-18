// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Protocol tests for the title-ping bridge against a fake JsRunner: ping →
// poll dispatch, qt-ready → _register_signals + pending replay (including
// the reload replay upstream relies on), exact _from_python call shapes,
// and the watchdog's pacing.

package dev.calibre.viewer.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

class ViewerBridgeTest {

    private class FakeJs : JsRunner {
        val calls = mutableListOf<String>()
        var pollResponse: String? = "[]"

        override fun eval(js: String, callback: ((String?) -> Unit)?) {
            calls.add(js)
            if (callback != null && js == ViewerBridge.POLL_JS) {
                callback(pollResponse)
            }
        }

        fun fromPythonCalls(): List<String> =
            calls.filter { it.startsWith("if (window.python_comm) python_comm._from_python(") }
    }

    private val warnings = mutableListOf<String>()

    private fun bridge(js: FakeJs, onSignal: (String, List<kotlinx.serialization.json.JsonElement>) -> Unit = { _, _ -> }) =
        ViewerBridge(js, onSignal) { warnings.add(it) }

    @Test
    fun `title ping triggers a poll`() {
        val js = FakeJs()
        val b = bridge(js)
        b.onTitleChanged("Some book title")
        assertTrue(js.calls.isEmpty())
        b.onTitleChanged(ViewerBridge.SPECIAL_TITLE + "0")
        assertEquals(listOf(ViewerBridge.POLL_JS), js.calls)
    }

    @Test
    fun `signals dispatch to the handler with parsed args`() {
        val js = FakeJs()
        js.pollResponse = """[{"type":"signal","name":"set_session_data","args":["read_mode","flow"]}]"""
        val received = mutableListOf<Pair<String, List<kotlinx.serialization.json.JsonElement>>>()
        val b = bridge(js) { name, args -> received.add(name to args) }
        b.onTitleChanged(ViewerBridge.SPECIAL_TITLE + "1")
        assertEquals(
            listOf("set_session_data" to listOf<kotlinx.serialization.json.JsonElement>(JsonPrimitive("read_mode"), JsonPrimitive("flow"))),
            received,
        )
    }

    @Test
    fun `unknown signals and bad payloads only warn`() {
        val js = FakeJs()
        val received = mutableListOf<String>()
        val b = bridge(js) { name, _ -> received.add(name) }
        js.pollResponse = """[{"type":"signal","name":"not_a_signal","args":[]},{"type":"mystery","args":[]}]"""
        b.onTitleChanged(ViewerBridge.SPECIAL_TITLE + "0")
        assertTrue(received.isEmpty())
        assertTrue(warnings.any { "not_a_signal" in it })
        // invalid JSON / null / empty all no-op
        js.pollResponse = "null"
        b.onTitleChanged(ViewerBridge.SPECIAL_TITLE + "1")
        js.pollResponse = "{broken"
        b.onTitleChanged(ViewerBridge.SPECIAL_TITLE + "0")
        assertTrue(received.isEmpty())
    }

    @Test
    fun `qt-ready registers the full from_js signal list`() {
        val js = FakeJs()
        val b = bridge(js)
        js.pollResponse = """[{"type":"qt-ready"}]"""
        b.onTitleChanged(ViewerBridge.SPECIAL_TITLE + "0")
        val registerCall = js.calls.first { it.startsWith("python_comm._register_signals(") }
        val payload = registerCall.substringAfter("(").removeSuffix(")")
        val names = (Json.parseToJsonElement(payload) as JsonArray)
            .map { (it as JsonPrimitive).content }
        assertEquals(SignalNames.JS_TO_HOST, names)
        assertTrue(b.ready)
    }

    @Test
    fun `pending actions replay on flush and again after reload`() {
        val js = FakeJs()
        val b = bridge(js)
        b.executeWhenReady("trigger_shortcut", JsonPrimitive("pageup"))
        b.executeWhenReady("trigger_shortcut", JsonPrimitive("pagedown")) // same action overwrites
        b.executeWhenReady("goto_cfi", JsonPrimitive("epubcfi(/6/4!)"), JsonPrimitive(false))
        assertTrue(js.fromPythonCalls().isEmpty())

        // Host contract: qt-ready → create_view FIRST, then flush, so a
        // queued start_book_load can never overtake create_view.
        js.pollResponse = """[{"type":"qt-ready"}]"""
        var createViewSent = false
        b.onReady = {
            b.call("create_view", JsonPrimitive("{}"))
            createViewSent = true
            b.flushPendingActions()
        }
        b.onTitleChanged(ViewerBridge.SPECIAL_TITLE + "0")
        assertTrue(createViewSent)
        assertEquals(
            listOf(
                """if (window.python_comm) python_comm._from_python("create_view", ["{}"])""",
                """if (window.python_comm) python_comm._from_python("trigger_shortcut", ["pagedown"])""",
                """if (window.python_comm) python_comm._from_python("goto_cfi", ["epubcfi(/6/4!)",false])""",
            ),
            js.fromPythonCalls(),
        )

        // Reload: python_comm re-sends qt-ready; host re-sends create_view
        // and pending actions replay again.
        js.calls.clear()
        js.pollResponse = """[{"type":"qt-ready"}]"""
        b.onTitleChanged(ViewerBridge.SPECIAL_TITLE + "1")
        assertEquals(3, js.fromPythonCalls().size)
    }

    @Test
    fun `onReady fires on every qt-ready`() {
        val js = FakeJs()
        val b = bridge(js)
        var fired = 0
        b.onReady = { fired++ }
        js.pollResponse = """[{"type":"qt-ready"}]"""
        b.onTitleChanged(ViewerBridge.SPECIAL_TITLE + "0")
        b.onTitleChanged(ViewerBridge.SPECIAL_TITLE + "1")
        assertEquals(2, fired)
    }

    @Test
    fun `direct call before ready warns but sends after`() {
        val js = FakeJs()
        val b = bridge(js)
        b.call("goto_frac", JsonPrimitive(0.5))
        assertTrue(js.fromPythonCalls().isNotEmpty())
        assertTrue(warnings.any { "before the javascript bridge is ready" in it })
    }

    @Test
    fun `start_book_load sends the upstream seven argument shape`() {
        val js = FakeJs()
        val b = bridge(js)
        js.pollResponse = """[{"type":"qt-ready"}]"""
        b.onTitleChanged(ViewerBridge.SPECIAL_TITLE + "0")
        js.calls.clear()
        b.startBookLoad(
            renderedBookDir = "/cache/ev2/t/abc",
            initialPosition = buildJsonObject {
                put("type", JsonPrimitive("cfi"))
                put("data", JsonPrimitive("epubcfi(/6/4!)"))
            },
            pathtoebook = "/books/novel.epub",
        )
        val expected =
            """if (window.python_comm) python_comm._from_python("start_book_load", [["/cache/ev2/t/abc"],""" +
                """{"type":"cfi","data":"epubcfi(/6/4!)"},"/books/novel.epub",[],null,null,null])"""
        assertEquals(listOf(expected), js.fromPythonCalls())
    }

    @Test
    fun `start_book_load without initial position sends null`() {
        val js = FakeJs()
        val b = bridge(js)
        js.pollResponse = """[{"type":"qt-ready"}]"""
        b.onTitleChanged(ViewerBridge.SPECIAL_TITLE + "0")
        js.calls.clear()
        b.startBookLoad("/d", null, "p.epub")
        assertTrue(js.fromPythonCalls().single().contains("[[\"/d\"],null,\"p.epub\",[],null,null,null]"))
    }

    @Test
    fun `create_view sends four args`() {
        val js = FakeJs()
        val b = bridge(js)
        js.pollResponse = """[{"type":"qt-ready"}]"""
        b.onTitleChanged(ViewerBridge.SPECIAL_TITLE + "0")
        js.calls.clear()
        b.createView(
            sessionData = buildJsonObject { put("read_mode", JsonPrimitive("paged")) },
            localStorage = buildJsonObject {},
            fieldMetadata = JsonNull,
            uiData = buildJsonObject { put("QT_VERSION", JsonPrimitive("6.6.0")) },
        )
        val call = js.fromPythonCalls().single()
        assertTrue(call.startsWith("""if (window.python_comm) python_comm._from_python("create_view", ["""))
        assertTrue(""""QT_VERSION":"6.6.0"""" in call)
    }

    @Test
    fun `watchdog polls on a schedule and backs off when quiet`() {
        val js = FakeJs()
        val b = bridge(js)
        val delays = mutableListOf<Long>()
        val due = ArrayDeque<() -> Unit>()
        val scheduler = BridgeWatchdog.TaskScheduler { delay, action ->
            delays.add(delay)
            due.add(action)
            val cancel: () -> Unit = { due.clear() }
            cancel
        }
        val watchdog = BridgeWatchdog(poll = { b.poll() }, scheduler = scheduler)
        b.watchdog = watchdog
        js.pollResponse = "[]" // always quiet
        watchdog.start()
        // Drive 30 quiet ticks: first 25 fast, then slow.
        repeat(30) { due.removeFirstOrNull()?.invoke() }
        assertTrue(delays.take(25).all { it == BridgeWatchdog.FAST_INTERVAL_MS })
        assertTrue(delays.drop(25).all { it == BridgeWatchdog.SLOW_INTERVAL_MS })
        assertEquals(30, js.calls.count { it == ViewerBridge.POLL_JS })
        // A poll with messages resets the streak.
        js.pollResponse = """[{"type":"signal","name":"quit","args":[]}]"""
        due.removeFirstOrNull()?.invoke()
        assertTrue(delays.last() == BridgeWatchdog.FAST_INTERVAL_MS)
        watchdog.stop()
        val after = js.calls.size
        due.removeFirstOrNull()?.invoke()
        assertEquals(after, js.calls.size)
    }

    @Test
    fun `to json element converts scalars and nests`() {
        assertEquals(JsonNull, toJsonElement(null))
        assertEquals(JsonPrimitive(3), toJsonElement(3))
        val obj = toJsonElement(
            mapOf("a" to listOf(1, "x", true), "b" to mapOf("c" to 1.5))
        )
        assertEquals("""{"a":[1,"x",true],"b":{"c":1.5}}""", Json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), obj))
    }
}
