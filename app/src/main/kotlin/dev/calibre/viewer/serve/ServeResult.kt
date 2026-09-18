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
     * Non-200. Status mirrors the QWebEngineUrlRequestJob.Error codes the
     * upstream handler uses: 404 UrlNotFound (missing/unroutable), 403
     * RequestDenied (non-GET), and 400 RequestFailed (book file read error).
     */
    data class Fail(val status: Int, val reason: String) : ServeResult
}
