// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Builds and owns the WebView stack: settings, URL interception, chrome
// client (title pings + console), the bridge with its watchdog, and the
// initial load of the fake-host root. The e-ink-relevant choices (no
// algorithmic darkening, no offscreen pre-raster, plain scrollbars off) are
// commented inline.

package dev.calibre.viewer.web

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.ViewTreeObserver
import android.webkit.WebSettings
import android.webkit.WebView
import dev.calibre.viewer.BuildConfig
import dev.calibre.viewer.bridge.BridgeWatchdog
import dev.calibre.viewer.bridge.ViewerBridge
import dev.calibre.viewer.host.ViewerController
import dev.calibre.viewer.serve.BookServer

class ViewerWebHost(
    context: Context,
    controller: ViewerController,
    onRenderProcessGone: () -> Unit = {},
) {
    val webView: WebView
    val bridge: ViewerBridge
    val watchdog: BridgeWatchdog

    init {
        webView = WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                // viewer.js carries its own persistence via set_local_storage
                // signals; DOM storage would just shadow it.
                domStorageEnabled = false
                userAgentString = "calibre-viewer-android ${BuildConfig.VERSION_NAME} android"
                // Text zoom must stay 100%: viewer.js sizes text from session
                // data and a system font-scale would double-apply.
                textZoom = 100
                allowFileAccess = false
                allowContentAccess = false
                setSupportMultipleWindows(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                // E-ink: never pre-raster offscreen content.
                offscreenPreRaster = false
                if (Build.VERSION.SDK_INT >= 33) {
                    // Colors come from session data, never from the system theme.
                    isAlgorithmicDarkeningAllowed = false
                }
            }
            // viewer.js draws its own scroll position indicators; native
            // scrollbars would double up and repaint dirty regions.
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = WebView.OVER_SCROLL_NEVER
        }
        val jsRunner = WebViewJsRunner { webView }
        bridge = ViewerBridge(jsRunner, controller::onSignal) { Log.w(TAG, it) }
        watchdog = BridgeWatchdog(bridge::poll, HandlerTaskScheduler())
        bridge.watchdog = watchdog
        webView.webViewClient = ViewerWebViewClient(controller.bookServer, onRenderProcessGone)
        webView.webChromeClient = ViewerChromeClient(bridge)
        loadWhenLaidOut("https://${BookServer.FAKE_HOST}/")
    }

    /**
     * Load only once the view has been measured. This WebView is created
     * inside a Compose AndroidView factory, i.e. before attach: loading in
     * the constructor parses the page against a 0x0 viewport, which wedges
     * Blink's CSS viewport (vh units, height media queries and the root
     * percentage containing block all stay 0 forever — resizes only update
     * window.innerWidth/innerHeight). Deferred loading matches the Qt host,
     * where the page always loads into an already-sized QWebEngineView.
     */
    private fun loadWhenLaidOut(url: String) {
        webView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (webView.width > 0 && webView.height > 0) {
                    webView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    if (webView.url == null) webView.loadUrl(url)
                }
            }
        })
    }

    /** Keep the WebView's process alive while paused; stop polling only. */
    fun startPolling() = watchdog.start()

    fun stopPolling() = watchdog.stop()

    private companion object {
        const val TAG = "ViewerWebHost"
    }
}
