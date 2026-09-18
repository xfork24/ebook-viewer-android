// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Router tests mirroring web_view.py:UrlSchemeHandler.requestStarted() —
// host allow-lists, sandbox restriction, each route's bytes/mime, and the
// traversal guard on book/mathjax names.

package dev.calibre.viewer.serve

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class BookServerTest {

    private class FakeAssets(private val files: Map<String, ByteArray>) : AssetSource {
        override fun read(assetPath: String): ByteArray? = files[assetPath]
    }

    private fun serverWithBook(
        assets: Map<String, ByteArray> = mapOf(
            "index.html" to "<html>index</html>".toByteArray(),
            "viewer.js" to "function viewer(){}".toByteArray(),
            "viewer.js.map" to "{}".toByteArray(),
        ),
    ): BookServer {
        val bookDir = createTempDir(prefix = "serve-test-book")
        Runtime.getRuntime().addShutdownHook(Thread { bookDir.deleteRecursively() })
        File(bookDir, "calibre-book-manifest.json").writeText("""{"version":1,"spine":["ch1.html"]}""")
        File(bookDir, "calibre-book-metadata.json").writeText("""{"title":"T"}""")
        File(bookDir, "ch1.html").writeText("""{"version":1,"tree":{}}""")
        File(bookDir, "style.css").writeText("body{}")
        val book = ViewerBook.open(bookDir, "/books/x.epub")
        assertNotNull(book)
        val configDir = createTempDir(prefix = "serve-test-config")
        Runtime.getRuntime().addShutdownHook(Thread { configDir.deleteRecursively() })
        val server = BookServer(FakeAssets(assets), BackgroundImages(configDir))
        server.setBook(book)
        return server
    }

    private fun BookServer.get(path: String, host: String = BookServer.FAKE_HOST) =
        handle("GET", "https", host, path)

    @Test
    fun `root serves index html`() {
        val r = serverWithBook().get("/")
        assertTrue(r is ServeResult.Ok && r.mime.startsWith("text/html"))
        assertEquals("<html>index</html>", (r as ServeResult.Ok).data.toString(Charsets.UTF_8))
    }

    @Test
    fun `viewer js and map are served from assets`() {
        val s = serverWithBook()
        val js = s.get("/viewer.js")
        assertTrue(js is ServeResult.Ok && js.mime == "application/javascript")
        val map = s.get("/viewer.js.map")
        assertTrue(map is ServeResult.Ok && map.mime == "application/json")
    }

    @Test
    fun `manifest route concatenates manifest and metadata as a json array`() {
        val r = serverWithBook().get("/manifest")
        assertTrue(r is ServeResult.Ok)
        val parsed = Json.parseToJsonElement((r as ServeResult.Ok).data.toString(Charsets.UTF_8)).jsonArray
        assertEquals(1, parsed[0].jsonObject["version"].toString().toInt())
        assertEquals("T", parsed[1].jsonObject["title"].toString().trim('"'))
    }

    @Test
    fun `book files are served with name-derived mimes`() {
        val s = serverWithBook()
        // ch1.html holds html_as_json content after rendering, but the mime
        // follows the NAME (guess_type) — upstream serves it as text/html and
        // the viewer reads XHR responseText regardless of mime.
        val html = s.get("/book/ch1.html")
        assertTrue(html is ServeResult.Ok && (html as ServeResult.Ok).mime == "text/html")
        val css = s.get("/book/style.css")
        assertTrue(css is ServeResult.Ok && (css as ServeResult.Ok).mime == "text/css; charset=utf-8")
    }

    @Test
    fun `index and popup stubs return the nbsp div`() {
        val s = serverWithBook()
        for (name in listOf("__index__", "__popup__")) {
            val r = s.get("/book/$name", host = BookServer.SANDBOX_HOST)
            assertTrue(r is ServeResult.Ok, "$name failed: $r")
            val expected = "<div>\u00A0</div>".toByteArray(Charsets.UTF_8)
            assertTrue((r as ServeResult.Ok).data.contentEquals(expected), "$name bytes mismatch")
        }
    }

    @Test
    fun `missing book file is 404 and traversal is blocked`() {
        val s = serverWithBook()
        assertTrue(s.get("/book/nope.html") is ServeResult.Fail)
        assertTrue(s.get("/book/../calibre-book-manifest.json") is ServeResult.Fail)
        assertTrue(s.get("/book/../../etc/passwd") is ServeResult.Fail)
        // The %-decoded form of ../ — Uri.path would have decoded %2e%2e.
        assertTrue(s.get("/book/%2e%2e/secret") is ServeResult.Fail)
    }

    @Test
    fun `sandbox host only allows book and mathjax prefixes`() {
        val s = serverWithBook()
        assertTrue(s.get("/book/ch1.html", host = BookServer.SANDBOX_HOST) is ServeResult.Ok)
        assertTrue(s.get("/manifest", host = BookServer.SANDBOX_HOST) is ServeResult.Fail)
        assertTrue(s.get("/", host = BookServer.SANDBOX_HOST) is ServeResult.Fail)
        assertTrue(s.get("/viewer.js", host = BookServer.SANDBOX_HOST) is ServeResult.Fail)
    }

    @Test
    fun `foreign hosts schemes and methods are rejected`() {
        val s = serverWithBook()
        assertTrue(s.get("/", host = "evil.example.com") is ServeResult.Fail)
        assertTrue(s.handle("POST", "https", BookServer.FAKE_HOST, "/") is ServeResult.Fail)
        assertTrue(s.handle("GET", "http", BookServer.FAKE_HOST, "/") is ServeResult.Fail)
    }

    @Test
    fun `unroutable paths are 404`() {
        val s = serverWithBook()
        assertTrue(s.get("/random.js") is ServeResult.Fail)
        assertTrue(s.get("/book/") is ServeResult.Fail)
    }

    @Test
    fun `mathjax assets resolve and startup gets the loader prefix`() {
        val s = serverWithBook(
            assets = mapOf(
                "mathjax/es5/startup.js" to "STARTUP".toByteArray(),
                "mathjax/es5/tex-chtml.js" to "TEX".toByteArray(),
                "pdf-mathjax-loader.js" to "LOADER".toByteArray(),
            )
        )
        val startup = s.get("/mathjax/es5/startup.js")
        assertTrue(startup is ServeResult.Ok)
        assertEquals("LOADERSTARTUP", (startup as ServeResult.Ok).data.toString(Charsets.UTF_8))
        val tex = s.get("/mathjax/es5/tex-chtml.js")
        assertEquals("TEX", (tex as ServeResult.Ok).data.toString(Charsets.UTF_8))
        assertTrue(s.get("/mathjax/../secrets") is ServeResult.Fail)
        assertTrue(s.get("/mathjax/missing.js") is ServeResult.Fail)
    }

    @Test
    fun `reader-background is 404 without a configured image`() {
        val s = serverWithBook()
        assertTrue(s.get("/reader-background") is ServeResult.Fail)
        assertTrue(s.get("/reader-background-zz") is ServeResult.Fail)
    }

    @Test
    fun `reader-background serves named images from the config dir`() {
        val configDir = createTempDir(prefix = "serve-bg")
        Runtime.getRuntime().addShutdownHook(Thread { configDir.deleteRecursively() })
        val imgDir = File(configDir, "background-images")
        imgDir.mkdirs()
        File(imgDir, "wood.jpg").writeBytes(byteArrayOf(1, 2, 3))
        val server = BookServer(FakeAssets(emptyMap()), BackgroundImages(configDir))
        val hex = "wood.jpg".toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        val r = server.handle("GET", "https", BookServer.FAKE_HOST, "/reader-background-$hex")
        assertTrue(r is ServeResult.Ok && (r as ServeResult.Ok).mime == "image/jpeg")
        assertTrue(r.data.contentEquals(byteArrayOf(1, 2, 3)))
        // traversal via hex-encoded name is blocked
        val evil = "../bg-image.data".toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        assertTrue(server.handle("GET", "https", BookServer.FAKE_HOST, "/reader-background-$evil") is ServeResult.Fail)
    }

    @Test
    fun `manifest without a book mirrors the upstream empty concatenation`() {
        val configDir = createTempDir(prefix = "serve-nobook")
        Runtime.getRuntime().addShutdownHook(Thread { configDir.deleteRecursively() })
        val server = BookServer(FakeAssets(emptyMap()), BackgroundImages(configDir))
        val r = server.get("/manifest")
        assertTrue(r is ServeResult.Ok)
        assertEquals("[,]", (r as ServeResult.Ok).data.toString(Charsets.UTF_8))
        assertTrue(server.get("/book/ch1.html") is ServeResult.Fail)
    }

    @Test
    fun `safe book path rejects and accepts`() {
        val root = createTempDir(prefix = "sbp")
        Runtime.getRuntime().addShutdownHook(Thread { root.deleteRecursively() })
        File(root, "a/b.html").apply { parentFile.mkdirs(); writeText("x") }
        assertNotNull(SafeBookPath.resolve(root, "a/b.html"))
        assertEquals(null, SafeBookPath.resolve(root, "a/../a/b.html"))
        assertEquals(null, SafeBookPath.resolve(root, "/a/b.html"))
        assertEquals(null, SafeBookPath.resolve(root, ""))
        assertEquals(null, SafeBookPath.resolve(root, "a//b.html"))
        // symlink-style escape via canonical resolution
        val outside = createTempDir(prefix = "sbp-outside")
        Runtime.getRuntime().addShutdownHook(Thread { outside.deleteRecursively() })
        File(outside, "secret.txt").writeText("s")
        java.nio.file.Files.createSymbolicLink(
            File(root, "link").toPath(), outside.toPath()
        )
        assertEquals(null, SafeBookPath.resolve(root, "link/secret.txt"))
    }

    @Test
    fun `viewer book open returns null without manifest`() {
        val empty = createTempDir(prefix = "serve-empty")
        Runtime.getRuntime().addShutdownHook(Thread { empty.deleteRecursively() })
        assertEquals(null, ViewerBook.open(empty, "x.epub"))
    }
}
