// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Bridge-to-WebView seam. evaluateJavascript must run on the UI thread and
// delivers its result as a JSON-encoded string; abstracting it keeps the
// bridge protocol logic JVM-unit-testable. The #9 shell implements this with
// webView.evaluateJavascript posted to the main looper.

package dev.calibre.viewer.bridge

fun interface JsRunner {
    /**
     * Evaluate JS in the viewer page. [callback] (null = fire and forget)
     * receives the JSON-encoded value of the last expression — e.g. "null",
     * "[]", "[{\"type\":\"signal\",...}]" — mirroring
     * QWebEnginePage.runJavaScript.
     */
    fun eval(js: String, callback: ((String?) -> Unit)?)

    /** Fire-and-forget variant. */
    fun eval(js: String) = eval(js, null)
}
