// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Host-layer integration tests against a real ViewerBridge + FakeJs (no
// Android): the qt-ready → create_view handshake, the full
// openBook → render → start_book_load pipeline on a minimal EPUB, the
// signal-router arg extraction, and the staging slot's fingerprint
// stability that keeps the render cache warm across re-opens.

package dev.calibre.viewer.host

import dev.calibre.viewer.bridge.JsRunner
import dev.calibre.viewer.bridge.ViewerBridge
import dev.calibre.viewer.parser.dom.DomParser
import dev.calibre.viewer.parser.render.RenderBookCache
import dev.calibre.viewer.serve.AssetSource
import dev.calibre.viewer.serve.BookServer
import dev.calibre.viewer.serve.ServeResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonPrimitive
import org.xmlpull.v1.XmlPullParserFactory

class ViewerHostTest {

    private class FakeJs : JsRunner {
        val calls = mutableListOf<String>()
        var pollResponse: String? = "[]"

        override fun eval(js: String, callback: ((String?) -> Unit)?) {
            calls.add(js)
            if (callback != null && js == dev.calibre.viewer.bridge.ViewerBridge.POLL_JS) callback(pollResponse)
        }

        fun fromPythonCalls(): List<String> =
            calls.filter { it.startsWith("if (window.python_comm) python_comm._from_python(") }
    }

    private class FakeHostUi : HostUi {
        val events = mutableListOf<String>()
        var immersiveResult = true

        /** The engine's font knobs as last applied (starts at Chromium defaults). */
        var currentFonts: FontSettings =
            FontSettings(null, null, null, StandardFontFamily.SERIF, 0, 16, 13)
        val fontEvents = mutableListOf<FontSettings>()

        override fun pickBook() {
            events.add("pickBook")
        }

        override fun finishApp() {
            events.add("finish")
        }

        override fun toggleImmersive(): Boolean {
            events.add("toggleImmersive")
            return immersiveResult
        }

        override fun applyFontSettings(settings: FontSettings): Boolean {
            val sizesChanged = currentFonts.sizesDifferFrom(settings)
            currentFonts = settings
            fontEvents.add(settings)
            return sizesChanged
        }

        override fun copyToClipboard(text: String) {
            events.add("copy:$text")
        }

        override fun openUrl(url: String) {
            events.add("url:$url")
        }
    }

    private val factory = XmlPullParserFactory.newInstance()
    private fun newParser(): DomParser = DomParser { factory.newPullParser() }

    private fun tempRoot(): File = createTempDir(prefix = "host").also {
        Runtime.getRuntime().addShutdownHook(Thread { it.deleteRecursively() })
    }

    private fun newController(
        root: File,
        js: FakeJs,
        hostUi: FakeHostUi,
        attach: Boolean = true,
    ): ViewerController {
        val controller = ViewerController(
            filesDir = File(root, "files"),
            cacheDir = File(root, "cache"),
            ioDispatcher = Dispatchers.Unconfined,
            scope = CoroutineScope(Job() + Dispatchers.Unconfined),
            assets = AssetSource { null },
            fieldMetadata = kotlinx.serialization.json.JsonNull,
            uiData = UiData.build(isDark = false),
            domParserFactory = ::newParser,
        )
        controller.hostUi = hostUi
        if (attach) controller.attachBridge(ViewerBridge(js, controller::onSignal) {})
        return controller
    }

    /** Fire a qt-ready poll through the bridge, like a real title ping would. */
    private fun bringBridgeUp(controller: ViewerController, js: FakeJs) {
        js.pollResponse = """[{"type":"qt-ready"}]"""
        controller.bridge!!.onTitleChanged(ViewerBridge.SPECIAL_TITLE + "0")
    }

    // ---- handshake ----------------------------------------------------------

    @Test
    fun `qt-ready sends create view with ui data`() {
        val js = FakeJs()
        val controller = newController(tempRoot(), js, FakeHostUi())
        bringBridgeUp(controller, js)

        val createView = js.fromPythonCalls().single { "create_view" in it }
        assertTrue(""""QT_VERSION":"6.6.0"""" in createView, createView)
        assertTrue(""""show_home_page_on_ready":true""" in createView, createView)
        assertTrue(""""background":"#ffffff"""" in createView, createView)
        // session data starts empty and is passed as the first argument
        assertTrue(createView.contains("""["_from_name_placeholder_""").not())
    }

    // ---- open book pipeline ---------------------------------------------------

    @Test
    fun `open book renders and sends start book load`() {
        val root = tempRoot()
        val js = FakeJs()
        val controller = newController(root, js, FakeHostUi())
        bringBridgeUp(controller, js)

        controller.openBook { dest -> dest.writeBytes(minimalEpub()) }

        assertTrue(controller.state.value.preparing, "preparing until the first spine item is displayed")
        val load = js.fromPythonCalls().single { "start_book_load" in it }
        assertTrue("],null," in load, "fresh book has no initial position: $load")
        assertTrue("cfi" !in load, load)
        assertTrue(File(root, "files/books/inbox.epub").path in load, load)
        assertNotNull(controller.bookServer.currentBook)

        // The router serves the committed manifest for /manifest.
        val result = controller.bookServer.handle("GET", "https", BookServer.FAKE_HOST, "/manifest")
        assertTrue(result is ServeResult.Ok, "manifest route: $result")

        // view_created belongs to the long-lived viewer shell and can arrive
        // before a book is selected, so it must not dismiss this overlay.
        controller.onSignal("view_created", emptyList())
        assertTrue(controller.state.value.preparing)
        assertFalse(controller.state.value.bookOpen)

        controller.onSignal("content_file_changed", listOf(JsonPrimitive("text/chapter.xhtml")))
        assertFalse(controller.state.value.preparing)
        assertTrue(controller.state.value.bookOpen)
    }

    @Test
    fun `open book failure lands in the error overlay`() {
        val root = tempRoot()
        val controller = newController(root, FakeJs(), FakeHostUi())
        bringBridgeUp(controller, FakeJs())

        controller.openBook { dest -> dest.writeBytes(ByteArray(64)) } // not an epub

        assertFalse(controller.state.value.preparing)
        assertTrue(controller.state.value.errorMessage.orEmpty().isNotEmpty())
        assertNull(controller.bookServer.currentBook)
    }

    @Test
    fun `last read position restores into start book load`() {
        val root = tempRoot()
        val js = FakeJs()
        val controller = newController(root, js, FakeHostUi())
        bringBridgeUp(controller, js)
        controller.openBook { dest -> dest.writeBytes(minimalEpub()) }
        controller.onSignal("content_file_changed", listOf(JsonPrimitive("text/chapter.xhtml")))

        controller.onSignal(
            "update_last_read_position",
            listOf(JsonPrimitive("epubcfi(/6/4!)"), JsonPrimitive(0.25)),
        )
        controller.persistNow()
        assertTrue("epubcfi(/6/4!)" in File(root, "files/last-read.json").readText())

        // Reopening the same staged book feeds the saved cfi as initial position.
        js.calls.clear()
        controller.openBook { dest -> dest.writeBytes(minimalEpub()) }
        val load = js.fromPythonCalls().single { "start_book_load" in it }
        assertTrue("""{"type":"cfi","data":"epubcfi(/6/4!)"}""" in load, load)
    }

    @Test
    fun `session data signal persists write through`() {
        val root = tempRoot()
        val controller = newController(root, FakeJs(), FakeHostUi())
        controller.onSignal("set_session_data", listOf(JsonPrimitive("read_mode"), JsonPrimitive("paged")))
        controller.persistNow() // store writes are queued; flush them
        val text = File(root, "files/session/session_data.json").readText()
        assertTrue(""""read_mode":"paged"""" in text, text)
    }

    // ---- font settings (web_view.py:set_session_data / apply_font_settings) --

    @Test
    fun `font size change applies engine settings and emits viewer_font_size_changed`() {
        val root = tempRoot()
        val js = FakeJs()
        val hostUi = FakeHostUi()
        val controller = newController(root, js, hostUi)
        bringBridgeUp(controller, js)

        // What the standalone font-size panel sends on "apply".
        controller.onSignal("set_session_data", listOf(JsonPrimitive("base_font_size"), JsonPrimitive(24)))

        assertEquals(24, hostUi.currentFonts.defaultFontSize)
        assertEquals(24 * 13 / 16, hostUi.currentFonts.fixedFontSize)
        assertTrue(js.fromPythonCalls().any { "viewer_font_size_changed" in it })

        // Same size again: nothing changed → no re-apply, no extra signal.
        js.calls.clear()
        controller.onSignal("set_session_data", listOf(JsonPrimitive("base_font_size"), JsonPrimitive(24)))
        assertFalse(js.fromPythonCalls().any { "viewer_font_size_changed" in it })

        // Non-font keys never touch the engine.
        controller.onSignal("set_session_data", listOf(JsonPrimitive("read_mode"), JsonPrimitive("flow")))
        assertEquals(1, hostUi.fontEvents.size)
    }

    @Test
    fun `family-only changes apply without a relayout signal`() {
        val root = tempRoot()
        val js = FakeJs()
        val hostUi = FakeHostUi()
        val controller = newController(root, js, hostUi)
        bringBridgeUp(controller, js)

        val families = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"serif_family":"Liberation Serif","standard_font":"serif"}""",
        )
        controller.onSignal(
            "set_session_data",
            listOf(JsonPrimitive("standalone_font_settings"), families),
        )

        assertEquals("Liberation Serif", hostUi.currentFonts.serifFamily)
        assertFalse(js.fromPythonCalls().any { "viewer_font_size_changed" in it })
    }

    @Test
    fun `clearing session data restores default sizes with a signal`() {
        val root = tempRoot()
        val js = FakeJs()
        val hostUi = FakeHostUi()
        val controller = newController(root, js, hostUi)
        bringBridgeUp(controller, js)
        controller.onSignal("set_session_data", listOf(JsonPrimitive("base_font_size"), JsonPrimitive(24)))
        js.calls.clear()

        controller.onSignal("set_session_data", listOf(JsonPrimitive("*"), kotlinx.serialization.json.JsonNull))

        assertEquals(16, hostUi.currentFonts.defaultFontSize)
        assertTrue(js.fromPythonCalls().any { "viewer_font_size_changed" in it })
    }

    @Test
    fun `persisted font settings seed the engine on rebuild`() {
        val root = tempRoot()
        val js = FakeJs()
        val hostUi = FakeHostUi()
        val controller = newController(root, js, hostUi)
        bringBridgeUp(controller, js)
        controller.onSignal("set_session_data", listOf(JsonPrimitive("base_font_size"), JsonPrimitive(32)))

        // Render-process rebuild: a fresh WebView must get the sizes before
        // the reloaded page renders (WebPage.__init__ parity).
        hostUi.currentFonts = FontSettings(null, null, null, StandardFontFamily.SERIF, 0, 16, 13)
        js.calls.clear()
        controller.applyPersistedFontSettings()

        assertEquals(32, hostUi.currentFonts.defaultFontSize)
        // Seeding is silent — create_view/start_book_load carry the state.
        assertFalse(js.fromPythonCalls().any { "viewer_font_size_changed" in it })
    }

    // ---- TOC panel (toggle_toc / update_current_toc_nodes / goto_toc_node) ---

    @Test
    fun `toc panel toggles follows the viewed nodes and navigates`() {
        val root = tempRoot()
        val js = FakeJs()
        val controller = newController(root, js, FakeHostUi())
        bringBridgeUp(controller, js)
        controller.openBook { dest -> dest.writeBytes(tocEpub()) }
        controller.onSignal("content_file_changed", listOf(JsonPrimitive("text/ch1.xhtml")))

        // The manifest feeds the panel; rows are document order with
        // post-order ids (第二节 has id 1, its parent 第二章 id 2).
        val rows = controller.state.value.tocRows
        assertEquals(listOf("第一章", "第二章", "第二节"), rows.map { it.title })
        assertEquals(listOf(0, 0, 1), rows.map { it.depth })
        assertEquals(listOf(0L, 2L, 1L), rows.map { it.id })

        // The toolbar's TOC button → toggle_toc: panel visibility flips.
        assertFalse(controller.state.value.tocVisible)
        controller.onSignal("toggle_toc", emptyList())
        assertTrue(controller.state.value.tocVisible)

        // The page reports the visible toc family leaves; ancestors light up.
        controller.onSignal(
            "update_current_toc_nodes",
            listOf(kotlinx.serialization.json.Json.parseToJsonElement("[1]")),
        )
        assertEquals(setOf(1L, 2L), controller.state.value.viewedTocNodes)

        // Row click → goto_toc_node(id) and the panel closes.
        js.calls.clear()
        controller.gotoTocNode(1)
        assertFalse(controller.state.value.tocVisible)
        val goto = js.fromPythonCalls().single()
        assertTrue("\"goto_toc_node\", [1]" in goto, goto)

        // Back-style dismiss when already hidden: no crash, no state churn.
        controller.dismissToc()
        assertFalse(controller.state.value.tocVisible)
    }

    @Test
    fun `opening another book resets the toc panel`() {
        val root = tempRoot()
        val js = FakeJs()
        val controller = newController(root, js, FakeHostUi())
        bringBridgeUp(controller, js)
        controller.openBook { dest -> dest.writeBytes(tocEpub()) }
        controller.onSignal("content_file_changed", listOf(JsonPrimitive("text/ch1.xhtml")))
        controller.onSignal("toggle_toc", emptyList())
        assertTrue(controller.state.value.tocVisible)

        controller.openBook { dest -> dest.writeBytes(minimalEpub()) }
        assertFalse(controller.state.value.tocVisible, "panel must not survive into the next book")
    }

    // ---- search panel (show_search / find_next / search_result_*) -----------

    @Test
    fun `search panel opens searches streams results and navigates`() {
        val root = tempRoot()
        val js = FakeJs()
        val controller = newController(root, js, FakeHostUi())
        bringBridgeUp(controller, js)
        controller.openBook { dest -> dest.writeBytes(tocEpub()) }
        val manifestEl = kotlinx.serialization.json.Json.parseToJsonElement(
            String(controller.bookServer.currentBook!!.manifestBytes),
        ) as kotlinx.serialization.json.JsonObject
        val spine = (manifestEl["spine"] as kotlinx.serialization.json.JsonArray)
            .map { (it as JsonPrimitive).content }
        val first = spine.first()
        controller.onSignal("content_file_changed", listOf(JsonPrimitive(first)))
        assertEquals(first, controller.state.value.currentName)

        // The toolbar's search button → show_search(selection, false).
        controller.onSignal("show_search", listOf(JsonPrimitive(""), JsonPrimitive(false)))
        assertTrue(controller.state.value.searchVisible)
        assertEquals("", controller.state.value.searchPrefill)

        // Running the query streams every result with an on_discovery tag,
        // then the finished marker (run_searches/on_result_found).
        js.calls.clear()
        controller.startSearch("胡适", SearchMode.NORMAL, caseSensitive = false)
        val state = controller.state.value
        assertEquals(3, state.searchResults.size, state.searchResults.toString())
        assertFalse(state.searching)
        val sends = js.fromPythonCalls().filter { "show_search_result" in it }
        assertEquals(4, sends.size, sends.joinToString("\n")) // 3 discoveries + finished
        assertTrue(sends.take(3).all { "\"on_discovery\":1" in it }, sends.joinToString("\n"))
        assertTrue("\"search_finished\":true" in sends[3], sends[3])

        // The page reports the result it jumped to (selection follows).
        controller.onSignal(
            "search_result_discovered",
            listOf(kotlinx.serialization.json.Json.parseToJsonElement("""{"search_result":{"result_num":2}}""")),
        )
        assertEquals(2L, controller.state.value.currentResultNum)

        // Panel row click → navigate (no discovery tag) and the panel closes.
        js.calls.clear()
        controller.showSearchResult(2)
        assertFalse(controller.state.value.searchVisible)
        val nav = js.fromPythonCalls().single { "show_search_result" in it }
        assertTrue("\"result_num\":2" in nav && "on_discovery" !in nav, nav)

        // find_next steps forward, wraps, and steps back.
        controller.findNext(false)
        assertEquals(3L, controller.state.value.currentResultNum)
        controller.findNext(false)
        assertEquals(1L, controller.state.value.currentResultNum)
        controller.findNext(true)
        assertEquals(3L, controller.state.value.currentResultNum) // wraps backwards too

        // search_result_not_found hides the matching row (is_result matching).
        val target = controller.state.value.searchResults.first { it.resultNum == 2L }
        controller.onSignal(
            "search_result_not_found",
            listOf(
                kotlinx.serialization.json.buildJsonObject {
                    put(
                        "search_result",
                        kotlinx.serialization.json.buildJsonObject {
                            put("spine_idx", JsonPrimitive(target.spineIdx))
                            put("index", JsonPrimitive(target.index))
                            put("q", JsonPrimitive(target.q))
                        },
                    )
                },
            ),
        )
        assertTrue(controller.state.value.searchResults.first { it.resultNum == 2L }.isHidden)

        // Back-style dismiss when hidden: no crash, no state churn.
        controller.dismissSearch()
        assertFalse(controller.state.value.searchVisible)
    }

    @Test
    fun `opening another book resets the search panel`() {
        val root = tempRoot()
        val js = FakeJs()
        val controller = newController(root, js, FakeHostUi())
        bringBridgeUp(controller, js)
        controller.openBook { dest -> dest.writeBytes(tocEpub()) }
        // the page reports its first spine item before any search can run
        val manifestEl = kotlinx.serialization.json.Json.parseToJsonElement(
            String(controller.bookServer.currentBook!!.manifestBytes),
        ) as kotlinx.serialization.json.JsonObject
        val first = (manifestEl["spine"] as kotlinx.serialization.json.JsonArray)
            .map { (it as JsonPrimitive).content }.first()
        controller.onSignal("content_file_changed", listOf(JsonPrimitive(first)))

        controller.onSignal("show_search", listOf(JsonPrimitive("胡适"), JsonPrimitive(false)))
        controller.startSearch("胡适", SearchMode.NORMAL, caseSensitive = false)
        assertTrue(controller.state.value.searchResults.isNotEmpty())
        assertTrue(controller.state.value.searchVisible)

        controller.openBook { dest -> dest.writeBytes(minimalEpub()) }
        val state = controller.state.value
        assertFalse(state.searchVisible, "panel must not survive into the next book")
        assertTrue(state.searchResults.isEmpty(), "results belong to the previous book")
    }

    // ---- router extraction ----------------------------------------------------

    @Test
    fun `router maps payloads to typed effects`() {
        val js = FakeJs()
        val hostUi = FakeHostUi()
        val controller = newController(tempRoot(), js, hostUi)
        bringBridgeUp(controller, js)

        controller.onSignal("show_error", listOf(JsonPrimitive("T"), JsonPrimitive("D")))
        assertEquals("T\n\nD", controller.state.value.errorMessage)

        controller.onSignal("show_loading_message", listOf(JsonPrimitive("Loading book…")))
        assertEquals("Loading book…", controller.state.value.loadingMessage)
        controller.onSignal("show_loading_message", listOf(JsonPrimitive("")))
        assertNull(controller.state.value.loadingMessage)

        controller.onSignal("copy_selection", listOf(JsonPrimitive("hello"), JsonPrimitive("<b>hello</b>")))
        assertEquals(listOf("copy:hello"), hostUi.events)

        controller.onSignal("open_url", listOf(JsonPrimitive("https://calibre-ebook.com")))
        assertEquals("url:https://calibre-ebook.com", hostUi.events.last())

        controller.onSignal("ask_for_open", listOf(JsonPrimitive("")))
        assertEquals("pickBook", hostUi.events.last())

        controller.onSignal("quit", emptyList())
        assertEquals("finish", hostUi.events.last())

        controller.onSignal("toggle_full_screen", emptyList())
        assertEquals("toggleImmersive", hostUi.events.last())
        assertTrue(js.fromPythonCalls().any { "full_screen_state_changed" in it && "true" in it })

        controller.dismissError()
        assertNull(controller.state.value.errorMessage)
    }

    @Test
    fun `javascript load error leaves preparing state`() {
        val root = tempRoot()
        val js = FakeJs()
        val controller = newController(root, js, FakeHostUi())
        bringBridgeUp(controller, js)
        controller.openBook { dest -> dest.writeBytes(minimalEpub()) }

        assertTrue(controller.state.value.preparing)
        controller.onSignal(
            "show_error",
            listOf(JsonPrimitive("Could not open book"), JsonPrimitive("Manifest failed")),
        )

        assertFalse(controller.state.value.preparing)
        assertEquals("Could not open book\n\nManifest failed", controller.state.value.errorMessage)
    }

    @Test
    fun `empty cfi is dropped and unknown signals ignored`() {
        val root = tempRoot()
        val controller = newController(root, FakeJs(), FakeHostUi())
        controller.openBook { dest -> dest.writeBytes(minimalEpub()) }

        controller.onSignal("update_last_read_position", listOf(JsonPrimitive(""), JsonPrimitive(0.5)))
        controller.persistNow()
        assertFalse(File(root, "files/last-read.json").exists())

        controller.onSignal("print_book", emptyList())
        controller.onSignal("tts", listOf(JsonPrimitive("play"), JsonPrimitive("x")))
        assertFalse(controller.state.value.bookOpen)
    }

    @Test
    fun `book opened before bridge attach loads once bridge arrives`() {
        val root = tempRoot()
        val js = FakeJs()
        val controller = newController(root, js, FakeHostUi(), attach = false)

        // ACTION_VIEW in onCreate: the book pipeline runs with no WebView yet.
        controller.openBook { dest -> dest.writeBytes(minimalEpub()) }
        assertTrue(js.fromPythonCalls().isEmpty())
        assertNotNull(controller.bookServer.currentBook)

        // Compose creates the WebView; attach replays the load. The bridge is
        // not ready, so start_book_load queues behind create_view.
        controller.attachBridge(ViewerBridge(js, controller::onSignal) {})
        assertTrue(js.fromPythonCalls().isEmpty(), "still queued until qt-ready")
        bringBridgeUp(controller, js)

        val calls = js.fromPythonCalls()
        assertEquals("create_view", calls.first().substringAfter("_from_python(\"").substringBefore("\""))
        assertTrue(calls.any { "start_book_load" in it })
    }

    @Test
    fun `re attaching a fresh bridge re sends the current book`() {
        // The render-process-crash rebuild: a brand-new bridge/page must get
        // the open book again, not an empty home page.
        val root = tempRoot()
        val js1 = FakeJs()
        val controller = newController(root, js1, FakeHostUi())
        bringBridgeUp(controller, js1)
        controller.openBook { dest -> dest.writeBytes(minimalEpub()) }
        controller.onSignal(
            "update_last_read_position",
            listOf(JsonPrimitive("epubcfi(/6/8)"), JsonPrimitive(0.5)),
        )
        controller.persistNow()

        val js2 = FakeJs()
        controller.onRenderProcessGone()
        controller.attachBridge(ViewerBridge(js2, controller::onSignal) {})
        bringBridgeUp(controller, js2)

        val calls = js2.fromPythonCalls()
        assertEquals("create_view", calls.first().substringAfter("_from_python(\"").substringBefore("\""))
        val load = calls.single { "start_book_load" in it }
        // The latest saved position is the recovery point.
        assertTrue("""{"type":"cfi","data":"epubcfi(/6/8)"}""" in load, load)
    }

    // ---- staging fingerprint ---------------------------------------------------

    @Test
    fun `staging reuses inbox for identical content`() {
        val root = tempRoot()
        val opener = BookOpener(
            cache = RenderBookCache(File(root, "cache")),
            booksDir = File(root, "files/books"),
            domParserFactory = ::newParser,
        )
        val book = minimalEpub()
        val first = opener.stage { dest -> dest.writeBytes(book) }
        val mtime = first.lastModified()
        val second = opener.stage { dest -> dest.writeBytes(book) }
        assertEquals(first, second)
        assertEquals(mtime, second.lastModified(), "unchanged content must not restage")
    }

    @Test
    fun `staging replaces changed content and cache hits on reopen`() {
        val root = tempRoot()
        val cache = RenderBookCache(File(root, "cache"))
        val opener = BookOpener(cache, File(root, "files/books"), ::newParser)

        val staged = opener.stage { dest -> dest.writeBytes(minimalEpub("v1")) }
        val opened1 = opener.open(staged)
        assertTrue(File(opened1.bookDir, "calibre-book-manifest.json").isFile)

        // Cache hit: same directory back, nothing transient left behind.
        val opened2 = opener.open(staged)
        assertEquals(opened1.bookDir, opened2.bookDir)
        assertTrue(File(root, "cache/ev2/t").listFiles().orEmpty().isEmpty())

        // Changed content → restaged → different hash → different cache entry.
        val replaced = opener.stage { dest -> dest.writeBytes(minimalEpub("v2")) }
        val opened3 = opener.open(replaced)
        assertTrue(opened3.bookHash != opened1.bookHash)
    }

    // ---- fixture ---------------------------------------------------------------

    /** Minimal valid EPUB with a single chapter; [variant] changes the bytes. */
    private fun minimalEpub(variant: String = "v1"): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("mimetype", "application/epub+zip")
            put(
                "META-INF/container.xml",
                """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>
""",
            )
            put(
                "content.opf",
                """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="BookId">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>Host Test $variant</dc:title>
    <dc:identifier id="BookId">urn:uuid:host-$variant</dc:identifier>
  </metadata>
  <manifest>
    <item id="ch1" href="ch1.html" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="ch1"/></spine>
</package>
""",
            )
            put(
                "ch1.html",
                """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
  <head><title>Ch1</title></head>
  <body><p>Hello host $variant.</p></body>
</html>
""",
            )
        }
        return out.toByteArray()
    }

    /** EPUB 3 with a nested nav toc: 第一章 / 第二章 > 第二节. */
    private fun tocEpub(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("mimetype", "application/epub+zip")
            put(
                "META-INF/container.xml",
                """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>
""",
            )
            put(
                "content.opf",
                """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="BookId">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>TOC Test</dc:title>
    <dc:identifier id="BookId">urn:uuid:toc-test</dc:identifier>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
</package>
""",
            )
            put(
                "nav.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
  <head><title>toc</title></head>
  <body>
    <nav epub:type="toc"><ol>
      <li><a href="ch1.xhtml">第一章</a></li>
      <li><a href="ch2.xhtml">第二章</a>
        <ol><li><a href="ch2.xhtml#s1">第二节</a></li></ol>
      </li>
    </ol></nav>
  </body>
</html>
""",
            )
            for ((name, title, body) in listOf(
                Triple("ch1.xhtml", "Ch1", "这是胡适的口述自传。"),
                Triple("ch2.xhtml", "Ch2", "胡适再次出现。胡适继续讲述。"),
            )) {
                put(
                    name,
                    """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
  <head><title>$title</title></head>
  <body><h1 id="s1">$title</h1><p>$body</p></body>
</html>
""",
                )
            }
        }
        return out.toByteArray()
    }
}
