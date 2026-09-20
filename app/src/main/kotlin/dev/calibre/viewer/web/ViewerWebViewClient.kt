// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// android.webkit glue for BookServer: turns intercepted requests into
// WebResourceResponses. Requests to any host other than the two appassets
// hosts are answered with 404 instead of reaching the network — the viewer
// is fully offline by construction.

package dev.calibre.viewer.web

import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.calibre.viewer.serve.BookServer
import dev.calibre.viewer.serve.ServeResult
import java.io.ByteArrayInputStream

class ViewerWebViewClient(
    private val server: BookServer,
    private val onRenderGone: (() -> Unit)? = null,
) : WebViewClient() {

    /** One-shot: the post-load viewport re-sync runs at most once. */
    private var viewportKicked = false

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse {
        val uri = request.url
        val host = uri.host
        if (host == null || host !in BookServer.ALLOWED_HOSTS) {
            Log.w(TAG, "Blocking foreign request: $uri")
            return errorResponse(404, "Not Found")
        }
        val path = uri.path ?: "/"
        return when (val result = server.handle(request.method, uri.scheme ?: "https", host, path)) {
            is ServeResult.Ok -> {
                // Bare type + separate encoding: several vendor WebViews
                // (iReader Smart Air Pro, Android 11) render a parameterised
                // type as text/plain — see ResponseMime.
                val mime = splitResponseMime(result.mime)
                // The applied background is fetched by its clean URL (the
                // preview <img> carries the page-level ?timestamp buster,
                // this fetch does not) — without an explicit no-store a
                // vendor WebView may heuristically cache the previous image
                // after Change image.
                val headers = if (path == "/reader-background" || path.startsWith("/reader-background-")) {
                    mapOf("Cache-Control" to "no-store")
                } else {
                    emptyMap()
                }
                WebResourceResponse(mime.type, mime.encoding, 200, "OK", headers, ByteArrayInputStream(result.data))
            }
            is ServeResult.OkStream -> {
                // Chromium reads and closes this stream itself; never close
                // or use{} it here (see OkStream).
                val mime = splitResponseMime(result.mime)
                WebResourceResponse(mime.type, mime.encoding, result.data)
            }
            is ServeResult.Fail -> {
                Log.w(TAG, "Failing ${request.method} $uri: ${result.reason}")
                errorResponse(result.status, reasonPhrase(result.status))
            }
        }
    }

    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
        // The WebView object is dead; tell the host so it can rebuild the
        // stack, and report the crash as handled (no system crash dialog).
        onRenderGone?.invoke()
        return true
    }

    override fun onPageFinished(view: WebView, url: String) {
        // Some vendor WebViews (iReader SmartOS, older Chromium) never
        // re-dispatch a CSS resize when the native view settles after the
        // load started, leaving the page laid out against the intermediate
        // bounds — content in a short band at the top, blank below. One
        // fresh layout pass plus a synthetic resize makes the viewer
        // re-measure; a no-op when the geometry was already correct.
        if (viewportKicked) return
        viewportKicked = true
        view.post {
            view.requestLayout()
            view.evaluateJavascript("window.dispatchEvent(new Event('resize'))", null)
        }
    }

    private fun errorResponse(status: Int, reason: String): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            status,
            reason,
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )

    private fun reasonPhrase(status: Int): String = when (status) {
        400 -> "Bad Request"
        403 -> "Denied"
        else -> "Not Found"
    }

    private companion object {
        const val TAG = "ViewerServer"
    }
}
