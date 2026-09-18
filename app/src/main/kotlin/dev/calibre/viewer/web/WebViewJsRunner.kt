// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// JsRunner over WebView.evaluateJavascript. evaluateJavascript must run on
// the thread the WebView was created on (main); the bridge's watchdog timer
// is the only caller that might arrive from elsewhere, so off-main calls are
// posted. The provider indirection keeps this usable across WebView
// recreations (onRenderProcessGone rebuilds).

package dev.calibre.viewer.web

import android.os.Looper
import android.webkit.WebView
import dev.calibre.viewer.bridge.JsRunner

class WebViewJsRunner(private val webViewProvider: () -> WebView) : JsRunner {

    override fun eval(js: String, callback: ((String?) -> Unit)?) {
        val view = webViewProvider()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            view.evaluateJavascript(js) { result -> callback?.invoke(result) }
        } else {
            view.post { view.evaluateJavascript(js) { result -> callback?.invoke(result) } }
        }
    }
}
