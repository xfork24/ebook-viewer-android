// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// The result of routing one intercepted request through BookServer. The
// Android glue (ViewerWebViewClient) maps Ok → WebResourceResponse and
// Fail → an empty error response carrying the status code — WebView has no
// way to truly abort an intercepted request.

package dev.calibre.viewer.serve

sealed interface ServeResult {

    /** 200 with a mime type and body bytes. */
    data class Ok(val mime: String, val data: ByteArray) : ServeResult

    /**
     * 200 with a body streamed straight off disk — book files never land in
     * memory as a whole (a chunky image-rich EPUB chapter can be tens of MB).
     * Chromium reads and closes the stream itself. The only host-side close
     * is BookServer's bounded reclaim (setBook/closeAllStreams over
     * CloseOnceInputStream wrappers): it aborts at most a fetch on a page
     * being replaced — noise, never an error path.
     */
    class OkStream(val mime: String, val data: java.io.InputStream) : ServeResult

    /**
     * Non-200. Status mirrors the QWebEngineUrlRequestJob.Error codes the
     * upstream handler uses: 404 UrlNotFound (missing/unroutable), 403
     * RequestDenied (non-GET), and 400 RequestFailed (book file read error).
     */
    data class Fail(val status: Int, val reason: String) : ServeResult
}
