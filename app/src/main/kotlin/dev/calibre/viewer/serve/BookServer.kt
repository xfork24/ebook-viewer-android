// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// The URL router, a faithful port of web_view.py:UrlSchemeHandler.requestStarted()
// (plus viewer.js serving, which upstream does via an injected QWebEngineScript
// instead of a URL — WebView has no script injection into subframes, so the
// <script src="/viewer.js"> tags our index.html and iframe stubs rely on must
// resolve). Serves both hosts with the same rule table:
//
//   /                        index.html (the built viewer.html)
//   /viewer.js /viewer.js.map bundled assets
//   /manifest                [manifest.json, metadata.json] concatenated
//   /book/<name>             file from the rendered book dir (__index__/__popup__ stubs)
//   /mathjax/<name>          bundled mathjax tree; startup.js gets the loader prefix
//   /reader-background[-hex] configured background image
//
// Only GET, only the two appassets hosts, and the sandbox host is further
// restricted to book/ + mathjax/ — exactly the upstream allow-lists. The
// router is pure JVM (strings in, ServeResult out); ViewerWebViewClient is
// the thin android.webkit glue.

package dev.calibre.viewer.serve

import java.io.IOException

class BookServer(
    private val assets: AssetSource,
    private val backgroundImages: BackgroundImages,
) {

    @Volatile
    private var book: ViewerBook? = null

    /** Install the currently-open book; null = home page, no book files. */
    fun setBook(book: ViewerBook?) {
        this.book = book
    }

    val currentBook: ViewerBook? get() = book

    /** @param path decoded URL path including the leading '/'. */
    fun handle(method: String, scheme: String, host: String, path: String): ServeResult {
        if (!method.equals("GET", ignoreCase = true)) {
            return ServeResult.Fail(403, "Denied non-GET $method")
        }
        if (host !in ALLOWED_HOSTS || scheme != FAKE_PROTOCOL) {
            return ServeResult.Fail(404, "Blocked $scheme://$host")
        }
        val name = path.drop(1)
        if (host == SANDBOX_HOST) {
            val first = name.substringBefore('/')
            if (first != "book" && first != "mathjax") {
                return ServeResult.Fail(404, "Blocked sandbox path /$name")
            }
        }

        return try {
            route(name)
        } catch (e: IOException) {
            // Upstream: exception in the book branch → RequestFailed.
            ServeResult.Fail(400, "Read failed for /$name: ${e.message}")
        }
    }

    private fun route(name: String): ServeResult {
        if (name.startsWith("book/")) {
            val bookName = name.substringAfter('/')
            // The iframe bootstrap: the sandbox host loads /book/__index__ and
            // /book/__popup__ as empty shells that pull in viewer.js.
            if (bookName == "__index__" || bookName == "__popup__") {
                return ServeResult.Ok("text/html", INDEX_STUB)
            }
            val b = book ?: return ServeResult.Fail(404, "No book open for /$name")
            val file = b.getData(bookName) ?: return ServeResult.Fail(404, "Not found /$name")
            return ServeResult.Ok(file.mime, file.bytes)
        }
        if (name == "manifest") {
            // b'[' + manifest + b',' + metadata + b']' — with no book this is
            // the (invalid) "[,]" upstream would emit too; the viewer only
            // fetches it once a book is loading.
            val b = book
            val manifest = b?.manifestBytes ?: ByteArray(0)
            val metadata = b?.metadataBytes ?: ByteArray(0)
            val out = ByteArray(1 + manifest.size + 1 + metadata.size + 1)
            out[0] = '['.code.toByte()
            manifest.copyInto(out, 1)
            out[1 + manifest.size] = ','.code.toByte()
            metadata.copyInto(out, 2 + manifest.size)
            out[out.size - 1] = ']'.code.toByte()
            return ServeResult.Ok(b?.manifestMime ?: "application/json", out)
        }
        if (name == "reader-background") {
            return backgroundResult("")
        }
        if (name == "viewer.js") {
            // Platform deviation (documented above): upstream injects
            // viewer.js as a QWebEngineScript; WebView needs a real URL.
            return asset("viewer.js", "application/javascript")
        }
        if (name == "viewer.js.map") {
            return asset("viewer.js.map", "application/json")
        }
        if (name.startsWith("reader-background-")) {
            return backgroundResult(name.removePrefix("reader-background-"))
        }
        if (name.startsWith("mathjax/")) {
            return mathjax(name)
        }
        if (name.isEmpty()) {
            return asset("index.html", "text/html; charset=utf-8")
        }
        return ServeResult.Fail(404, "Unroutable /$name")
    }

    private fun asset(path: String, mime: String): ServeResult {
        val bytes = assets.read(path) ?: return ServeResult.Fail(404, "Missing asset $path")
        return ServeResult.Ok(mime, bytes)
    }

    private fun backgroundResult(encoded: String): ServeResult {
        val image = backgroundImages.get(encoded) ?: return ServeResult.Fail(404, "No background")
        if (image.bytes.isEmpty()) return ServeResult.Fail(404, "Empty background")
        return ServeResult.Ok(image.mime, image.bytes)
    }

    private fun mathjax(name: String): ServeResult {
        // name is "mathjax/<sub>"; traversal-check the sub-path before it
        // reaches the asset source.
        val sub = name.substringAfter('/')
        val parts = sub.replace('\\', '/').split('/')
        if (sub.isEmpty() || parts.any { it.isEmpty() || it == "." || it == ".." }) {
            return ServeResult.Fail(400, "Outside mathjax directory: /$name")
        }
        val raw = assets.read("mathjax/$sub")
            ?: return ServeResult.Fail(404, "Missing mathjax asset /$name")
        val body = if (name.endsWith("/startup.js")) {
            val loader = assets.read("pdf-mathjax-loader.js") ?: ByteArray(0)
            loader + raw
        } else {
            raw
        }
        return ServeResult.Ok(dev.calibre.viewer.parser.epub.MimeTypes.guessType(name), body)
    }

    companion object {
        const val FAKE_PROTOCOL = "https"
        const val FAKE_HOST = "appassets.androidplatform.net"
        const val SANDBOX_HOST = "appassets.androidplatform.sandbox"
        val ALLOWED_HOSTS = setOf(FAKE_HOST, SANDBOX_HOST)

        // b'<div>\xa0</div>' — NBSP, not &nbsp;.
        private val INDEX_STUB = "<div>\u00A0</div>".toByteArray(Charsets.UTF_8)
    }
}
