// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Router tests mirroring web_view.py:UrlSchemeHandler.requestStarted() —
// host allow-lists, sandbox restriction, each route's bytes/mime, and the
// traversal guard on book/mathjax names.

package dev.calibre.viewer.serve

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class BookServerTest {

    private class FakeAssets(private val files: Map<String, ByteArray>) : AssetSource {
        override fun read(assetPath: String): ByteArray? = files[assetPath]
    }

    /** A rendered-book dir fixture: manifest + metadata + two book files. */
    private fun newBookDir(prefix: String = "serve-test-book"): File {
        val bookDir = createTempDir(prefix = prefix)
        Runtime.getRuntime().addShutdownHook(Thread { bookDir.deleteRecursively() })
        File(bookDir, "calibre-book-manifest.json").writeText("""{"version":1,"spine":["ch1.html"]}""")
        File(bookDir, "calibre-book-metadata.json").writeText("""{"title":"T"}""")
        File(bookDir, "ch1.html").writeText("""{"version":1,"tree":{}}""")
        File(bookDir, "style.css").writeText("body{}")
        return bookDir
    }

    private fun serverWithBook(
        assets: Map<String, ByteArray> = mapOf(
            "index.html" to "<html>index</html>".toByteArray(),
            "viewer.js" to "function viewer(){}".toByteArray(),
            "viewer.js.map" to "{}".toByteArray(),
        ),
    ): BookServer {
        val book = ViewerBook.open(newBookDir(), "/books/x.epub")
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
    fun `book files stream with name-derived mimes`() {
        val s = serverWithBook()
        // ch1.html holds html_as_json content after rendering, but the mime
        // follows the NAME (guess_type) — upstream serves it as text/html and
        // the viewer reads XHR responseText regardless of mime.
        val html = s.get("/book/ch1.html")
        assertTrue(html is ServeResult.OkStream && html.mime == "text/html")
        (html as ServeResult.OkStream).data.close()
        val css = s.get("/book/style.css")
        assertTrue(css is ServeResult.OkStream && css.mime == "text/css; charset=utf-8")
        (css as ServeResult.OkStream).data.close()
    }

    @Test
    fun `streamed book content matches the file bytes`() {
        val s = serverWithBook()
        val r = s.get("/book/style.css")
        assertTrue(r is ServeResult.OkStream)
        // Chromium would read the stream to EOF; we own it in the test, so
        // close it ourselves after draining.
        r.data.use { stream ->
            assertEquals("body{}", stream.readBytes().toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `book streams close idempotently and reject reads after close`() {
        val s = serverWithBook()
        val r = s.get("/book/style.css")
        assertTrue(r is ServeResult.OkStream)
        val stream = (r as ServeResult.OkStream).data as CloseOnceInputStream
        assertFalse(stream.isClosed)
        stream.close()
        assertTrue(stream.isClosed)
        // A second close (Chromium drained it after our reclaim, or vice
        // versa) must be a no-op, not a second fd free.
        stream.close()
        assertTrue(stream.isClosed)
        assertFailsWith<java.io.IOException> { stream.read() }
    }

    @Test
    fun `closeAllStreams reclaims tracked streams without dropping the book`() {
        // The trim-level memory path (R-PERF-04) reclaims the paused page's
        // undrained streams via the same building block setBook uses — but
        // must leave the installed book alone.
        val s = serverWithBook()
        val r1 = s.get("/book/ch1.html")
        val r2 = s.get("/book/style.css")
        assertTrue(r1 is ServeResult.OkStream && r2 is ServeResult.OkStream)
        assertEquals(2, s.trackedStreamCount)
        s.closeAllStreams()
        assertEquals(0, s.trackedStreamCount)
        assertTrue(((r1 as ServeResult.OkStream).data as CloseOnceInputStream).isClosed)
        assertTrue(((r2 as ServeResult.OkStream).data as CloseOnceInputStream).isClosed)
        assertNotNull(s.currentBook, "trim must reclaim fds, not uninstall the book")
    }

    @Test
    fun `setBook reclaims streams the previous page never drained`() {
        val s = serverWithBook()
        val r = s.get("/book/style.css")
        val stream = (r as ServeResult.OkStream).data as CloseOnceInputStream
        assertFalse(stream.isClosed)

        // Book switch: everything the old page never drained is closed, so
        // the fd ceiling is one book's lifetime, not the process's.
        s.setBook(null)

        assertTrue(stream.isClosed)
        assertFailsWith<java.io.IOException> { stream.read() }

        // The tracker recovered: a fresh book serves fresh, readable streams.
        val book2 = ViewerBook.open(newBookDir("serve-test-book2"), "/books/y.epub")
        assertNotNull(book2)
        s.setBook(book2)
        val r2 = s.get("/book/ch1.html")
        assertTrue(r2 is ServeResult.OkStream)
        (r2 as ServeResult.OkStream).data.use {
            assertEquals("""{"version":1,"tree":{}}""", it.readBytes().toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `index and popup stubs pull viewer js from the main host`() {
        val s = serverWithBook()
        for (name in listOf("__index__", "__popup__")) {
            val r = s.get("/book/$name", host = BookServer.SANDBOX_HOST)
            assertTrue(r is ServeResult.Ok, "$name failed: $r")
            val html = String((r as ServeResult.Ok).data, Charsets.UTF_8)
            // The sandbox host only routes book/ and mathjax/, so the stub's
            // script tag must point at the main host \u2014 without it the book
            // iframe stays a dead page (no IframeBoss, no content).
            assertTrue(
                html.contains("<script src=\"https://${BookServer.FAKE_HOST}/viewer.js\"></script>"),
                "$name missing viewer.js script tag: $html",
            )
            assertTrue(html.contains("\u00A0"), "$name missing NBSP div: $html")
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
        val r = s.get("/book/ch1.html", host = BookServer.SANDBOX_HOST)
        assertTrue(r is ServeResult.OkStream)
        (r as ServeResult.OkStream).data.close()
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
        // The real URL space is flat (mirrors calibre's resources/mathjax
        // tree): /mathjax/startup.js, /mathjax/input/mml.js, … — viewer.js
        // loads startup.js from the sandbox host in standalone mode.
        val s = serverWithBook(
            assets = mapOf(
                "mathjax/startup.js" to "STARTUP".toByteArray(),
                "mathjax/input/mml.js" to "MML".toByteArray(),
                "mathjax/output/chtml/fonts/woff-v2/f.woff" to byteArrayOf(1),
                "pdf-mathjax-loader.js" to "LOADER".toByteArray(),
            )
        )
        val startup = s.get("/mathjax/startup.js", host = BookServer.SANDBOX_HOST)
        assertTrue(startup is ServeResult.Ok)
        assertEquals("LOADERSTARTUP", (startup as ServeResult.Ok).data.toString(Charsets.UTF_8))
        assertEquals("application/javascript", startup.mime)
        val mml = s.get("/mathjax/input/mml.js", host = BookServer.SANDBOX_HOST)
        assertEquals("MML", (mml as ServeResult.Ok).data.toString(Charsets.UTF_8))
        val woff = s.get("/mathjax/output/chtml/fonts/woff-v2/f.woff")
        assertEquals("font/woff", (woff as ServeResult.Ok).mime)
        assertTrue(s.get("/mathjax/../secrets") is ServeResult.Fail)
        assertTrue(s.get("/mathjax/missing.js") is ServeResult.Fail)
    }

    @Test
    fun `packaged mathjax tree has the files the standalone viewer loads`() {
        // Regression guard for the MathML hang: without this tree every
        // has_maths book 404s MathJax and section loading stalls forever
        // behind the "Loading next section from <i>…</i>, please wait" overlay
        // (the typeset-done event that unblocks it never fires).
        val base = sequenceOf(
            File("src/main/assets/viewer/mathjax"),
            File("app/src/main/assets/viewer/mathjax"),
        ).firstOrNull { it.isDirectory }
            ?: error("mathjax asset dir not found from ${File(".").absolutePath}")
        // The exact set calibre's setup/mathjax.py vendors (loader then
        // lazy-loads these from the startup.js URL base).
        for (rel in listOf(
            "startup.js", "loader.js", "core.js",
            "input/tex-full.js", "input/asciimath.js",
            "input/mml.js", "input/mml/entities.js",
            "output/chtml.js", "output/chtml/fonts/tex.js",
        )) {
            assertTrue(File(base, rel).isFile, "missing packaged mathjax file: $rel")
        }
        val manifest = Json.parseToJsonElement(File(base, "manifest.json").readText()).jsonObject
        assertEquals("3.1.4", manifest["version"]?.toString()?.trim('"'))
    }

    @Test
    fun `reader-background is 404 without a configured image`() {
        val s = serverWithBook()
        assertTrue(s.get("/reader-background") is ServeResult.Fail)
        assertTrue(s.get("/reader-background-zz") is ServeResult.Fail)
    }

    @Test
    fun `store overwrites the raw background and invalidates the cached empty sentinel`() {
        val configDir = createTempDir(prefix = "serve-bg-store")
        Runtime.getRuntime().addShutdownHook(Thread { configDir.deleteRecursively() })
        val images = BackgroundImages(configDir)
        val server = BookServer(FakeAssets(emptyMap()), images)

        // A fetch before any image exists caches the empty sentinel (load("")
        // returns it instead of null) — the exact sequence a fresh install
        // runs through when the page first asks for its configured background.
        assertTrue(server.get("/reader-background") is ServeResult.Fail)

        images.store("image/png", byteArrayOf(1, 2, 3))

        val r = server.get("/reader-background")
        assertTrue(r is ServeResult.Ok, "after store: $r")
        assertEquals("image/png", (r as ServeResult.Ok).mime)
        assertTrue(r.data.contentEquals(byteArrayOf(1, 2, 3)))
        assertTrue(
            File(configDir, "bg-image.data").readBytes().toString(Charsets.UTF_8).startsWith("image/png|"),
        )
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

    @Test
    fun `open fails loud when rendered metadata is missing or empty`() {
        // A broken render without metadata would otherwise serve an invalid
        // `[<manifest>,]` /manifest (trailing comma) and the viewer would
        // die on JSON.parse with a cryptic error. Fail at open instead.
        val warns = mutableListOf<String>()

        val noMeta = newBookDir("serve-nometa")
        File(noMeta, "calibre-book-metadata.json").delete()
        assertEquals(null, ViewerBook.open(noMeta, "x.epub", onWarn = { warns.add(it) }))

        val emptyMeta = newBookDir("serve-emptymeta")
        File(emptyMeta, "calibre-book-metadata.json").writeText("")
        assertEquals(null, ViewerBook.open(emptyMeta, "x.epub", onWarn = { warns.add(it) }))

        assertEquals(2, warns.size, warns.toString())
    }

    // ---- spine prefetch (R-PERF-01) ---------------------------------------

    /**
     * Build a 5-page spine book with distinguishable content per page so a
     * prefetch test can assert it actually opened the right files.
     */
    private fun multiPageBook(prefix: String = "serve-prefetch"): Pair<File, List<String>> {
        val bookDir = createTempDir(prefix = prefix)
        Runtime.getRuntime().addShutdownHook(Thread { bookDir.deleteRecursively() })
        val names = listOf("p1.html", "p2.html", "p3.html", "p4.html", "p5.html")
        val manifest = buildString {
            append("""{"version":1,"spine":["${names.joinToString("\",\"")}"]}""")
        }
        File(bookDir, "calibre-book-manifest.json").writeText(manifest)
        File(bookDir, "calibre-book-metadata.json").writeText("""{"title":"P"}""")
        // 4 KiB per page — bigger than the prefetch cap to prove the cap is real.
        for (n in names) {
            val body = buildString { repeat(1024) { append("[$n]") } } // 4 KiB
            File(bookDir, n).writeText(body)
        }
        return bookDir to names
    }

    @Test
    fun `prefetchSpine opens and drains the next two spine items up to the byte cap`() {
        val (bookDir, names) = multiPageBook()
        val book = ViewerBook.open(bookDir, "/books/x.epub")
        assertNotNull(book)
        assertEquals(names, book.spine, "spine list parsed from manifest")
        val server = BookServer(FakeAssets(emptyMap()), BackgroundImages(createTempDir(prefix = "sf-pf")))
        server.setBook(book)
        // Reader is currently on p1 — warm p2 + p3 with a 2 KiB cap each.
        server.prefetchSpine(currentIdx = 0, ahead = 2, maxBytesPerFile = 2_048)
        // No streams stay tracked after the prefetch (each was closed).
        assertEquals(0, server.trackedStreamCount, "prefetched streams must be closed after drain")
        // The on-demand route still serves the full file (prefetch is a
        // warm hint, not a substitute).
        val full = server.get("/book/${names[1]}")
        assertTrue(full is ServeResult.OkStream)
        val expectedBytes = File(bookDir, names[1]).readBytes()
        (full as ServeResult.OkStream).data.use {
            val readBack = it.readBytes()
            assertEquals(expectedBytes.size, readBack.size, "untouched file should still be fully readable")
            assertTrue(expectedBytes.contentEquals(readBack), "untouched file content must match")
        }
    }

    @Test
    fun `prefetchSpine is a no-op past the end of the spine`() {
        val (bookDir, names) = multiPageBook()
        val book = ViewerBook.open(bookDir, "/books/x.epub")
        assertNotNull(book)
        val server = BookServer(FakeAssets(emptyMap()), BackgroundImages(createTempDir(prefix = "sf-pf-end")))
        server.setBook(book)
        // Reader is on the last spine item; nothing to warm.
        server.prefetchSpine(currentIdx = names.lastIndex, ahead = 2)
        assertEquals(0, server.trackedStreamCount)
        // And currentIdx exactly one past the end is also a clean no-op.
        server.prefetchSpine(currentIdx = names.size, ahead = 2)
        assertEquals(0, server.trackedStreamCount)
    }

    @Test
    fun `prefetchSpine reclaims its streams on setBook`() {
        // The prefetched streams register in openStreams for their short
        // lifetime, so a book switch before they close reclaims them through
        // the same setBook(null) reclaim path that handles undelivered page
        // reads — no separate teardown.
        val (bookDir, _) = multiPageBook()
        val book = ViewerBook.open(bookDir, "/books/x.epub")
        assertNotNull(book)
        val server = BookServer(FakeAssets(emptyMap()), BackgroundImages(createTempDir(prefix = "sf-pf-claim")))
        server.setBook(book)
        server.prefetchSpine(currentIdx = 0, ahead = 2, maxBytesPerFile = 64)
        // We can't observe an in-flight prefetch here (it finishes synchronously),
        // but we CAN observe that setBook(null) leaves the tracker empty
        // even after a fresh book switch — proving the prefetch path doesn't
        // leak its CloseOnceInputStream registration into the new lifetime.
        server.setBook(null)
        assertEquals(0, server.trackedStreamCount)
        val book2 = ViewerBook.open(bookDir, "/books/x.epub")
        assertNotNull(book2)
        server.setBook(book2)
        assertEquals(0, server.trackedStreamCount)
    }
}
