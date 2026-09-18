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
            if (callback != null && js == "python_comm._poll()") callback(pollResponse)
        }

        fun fromPythonCalls(): List<String> =
            calls.filter { it.startsWith("if (window.python_comm) python_comm._from_python(") }
    }

    private class FakeHostUi : HostUi {
        val events = mutableListOf<String>()
        var immersiveResult = true

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

        assertTrue(controller.state.value.preparing, "preparing until view_created arrives")
        val load = js.fromPythonCalls().single { "start_book_load" in it }
        assertTrue("],null," in load, "fresh book has no initial position: $load")
        assertTrue("cfi" !in load, load)
        assertTrue(File(root, "files/books/inbox.epub").path in load, load)
        assertNotNull(controller.bookServer.currentBook)

        // The router serves the committed manifest for /manifest.
        val result = controller.bookServer.handle("GET", "https", BookServer.FAKE_HOST, "/manifest")
        assertTrue(result is ServeResult.Ok, "manifest route: $result")

        controller.onSignal("view_created", emptyList())
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
        controller.onSignal("view_created", emptyList())

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
            cache = RenderBookCache(File(root, "cache/ev2")),
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
        val cache = RenderBookCache(File(root, "cache/ev2"))
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
}
