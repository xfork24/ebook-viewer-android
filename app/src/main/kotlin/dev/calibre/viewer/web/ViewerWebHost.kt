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
     *
     * The attach check matters on vendor window managers (iReader SmartOS,
     * launch animations): pre-attach measure passes can carry intermediate
     * window bounds, and a page parsed against those keeps the shrunken CSS
     * viewport — content confined to a short band at the top.
     */
    private fun loadWhenLaidOut(url: String) {
        webView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (webView.isAttachedToWindow && webView.width > 0 && webView.height > 0) {
                    webView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    if (webView.url == null) webView.loadUrl(url)
                }
            }
        })
    }

    /** Keep the WebView's process alive while paused; stop polling only. */
    fun startPolling() = watchdog.start()

    fun stopPolling() = watchdog.stop()

    /**
     * Freeze the WebView for background residence (the e-ink battery win:
     * otherwise Blink keeps timers/layout/JS running behind a hidden
     * activity). `onPause()` pauses this view; `pauseTimers()` freezes every
     * WebView timer process-wide. Freeze, not cancel — the 1s scroll-position
     * debounce still fires on resume, so the reading position survives. The
     * only unrecoverable path is process death within that debounce of a
     * page turn, which loses at most one turn.
     */
    fun suspendForBackground() {
        webView.onPause()
        // Historically static, instance-shaped in SDK 35 — call on the view
        // so this compiles against both forms. Affects every WebView in the
        // process (we own exactly one).
        webView.pauseTimers()
    }

    /** Inverse of [suspendForBackground]; call before resuming polling. */
    fun resumeFromBackground() {
        webView.resumeTimers()
        webView.onResume()
    }

    private companion object {
        const val TAG = "ViewerWebHost"
    }
}
