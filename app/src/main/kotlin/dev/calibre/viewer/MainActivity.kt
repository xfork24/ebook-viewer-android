// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// The viewer activity: owns the controller (plain-JVM logic) and hosts the
// WebView stack inside Compose. Inputs wired here: SAF book picking, VIEW
// intents (file managers), physical page-turn keys, back navigation, and the
// HostUi surface (clipboard, browser, immersive mode, finish).

package dev.calibre.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import dev.calibre.viewer.eink.EinkKeys
import dev.calibre.viewer.host.FontSettings
import dev.calibre.viewer.host.HostUi
import dev.calibre.viewer.host.StandardFontFamily
import dev.calibre.viewer.host.UiData
import dev.calibre.viewer.host.ViewerController
import dev.calibre.viewer.ui.ViewerScreen
import dev.calibre.viewer.web.AndroidAssetSource
import dev.calibre.viewer.web.ViewerWebHost
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

class MainActivity : ComponentActivity(), HostUi {

    private lateinit var controller: ViewerController
    private var host: ViewerWebHost? = null
    private var immersive = true
    private var lastDark = false

    /** Classic container: the WebView (index 0) under the overlay ComposeView. */
    private lateinit var webHolder: FrameLayout
    private lateinit var composeHolder: ComposeView

    /** Bumped to force the AndroidView factory to run again (host rebuild). */
    private val rebuildCounter = mutableIntStateOf(0)

    private val pickBook =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(::openUri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The reader owns the whole panel. Lay out edge-to-edge so system
        // bars or panel cutouts never shrink the window (on iReader SmartOS
        // the app otherwise windows short and the page keeps the shrunken
        // CSS viewport: content in a top band, blank below), and start
        // immersive — a reading surface shows no chrome by default.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        applyImmersive()

        val assets = AndroidAssetSource(assets)
        val isDark = isSystemDark()
        lastDark = isDark
        controller = ViewerController(
            filesDir = filesDir,
            cacheDir = cacheDir,
            ioDispatcher = Dispatchers.IO,
            scope = lifecycleScope,
            assets = assets,
            fieldMetadata = loadFieldMetadata(assets),
            uiData = UiData.build(isDark),
            domParserFactory = {
                dev.calibre.viewer.parser.dom.DomParser { android.util.Xml.newPullParser() }
            },
            log = { Log.d(TAG, it) },
        )
        controller.hostUi = this

        onBackPressedDispatcher.addCallback(this) {
            if (!handleBack()) finish()
        }

        // chrome://inspect support for the M1 verification pass.
        android.webkit.WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        // The WebView must live OUTSIDE Compose's AndroidView interop: hosted
        // there, Blink's CSS viewport height wedges to 0 (vh units, height
        // media queries and the root percentage containing block collapse —
        // page renders but every interactive element is 0-sized). A classic
        // FrameLayout measures the WebView through the normal view pipeline
        // and the viewport stays correct; the ComposeView on top draws only
        // the overlays and is kept GONE while none is showing so reading
        // touches always reach the WebView.
        webHolder = FrameLayout(this)
        composeHolder = ComposeView(this)
        webHolder.addView(composeHolder, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(webHolder)

        composeHolder.setContent {
            val state by controller.state.collectAsState()
            LaunchedEffect(rebuildCounter.intValue) { rebuildHost() }
            composeHolder.visibility = when {
                state.preparing || state.loadingMessage != null ||
                    state.errorMessage != null || state.tocVisible || state.searchVisible -> View.VISIBLE
                else -> View.GONE
            }
            ViewerScreen(
                state = state,
                onDismissError = controller::dismissError,
                onTocNodeClick = controller::gotoTocNode,
                onDismissToc = controller::dismissToc,
                onSearch = controller::startSearch,
                onSearchStep = controller::stepSearchResult,
                onSearchResultClick = controller::showSearchResult,
                onDismissSearch = controller::dismissSearch,
            )
        }

        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data?.let(::openUri)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.takeIf { it.action == Intent.ACTION_VIEW }?.data?.let(::openUri)
    }

    /**
     * (Re)builds the WebView host, adding the WebView to the classic
     * [webHolder] under the ComposeView. Runs on first composition and again
     * whenever [rebuildCounter] changes (render-process crash).
     */
    private fun rebuildHost() {
        host?.let { old ->
            host = null
            old.stopPolling()
            destroyWebView(old.webView)
        }
        val newHost = ViewerWebHost(this, controller, ::onRenderProcessGone)
        controller.attachBridge(newHost.bridge)
        host = newHost
        webHolder.addView(newHost.webView, 0, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // WebPage.__init__ parity: the persisted font sizes must be on the
        // engine before the deferred initial load renders any book text.
        controller.applyPersistedFontSettings()
        newHost.startPolling()
    }

    /** WebView renderer died: stop using it, then rebuild the whole stack. */
    private fun onRenderProcessGone() {
        Log.e(TAG, "WebView render process gone; rebuilding host")
        host?.stopPolling()
        controller.onRenderProcessGone()
        rebuildCounter.intValue++
        // The key() change disposes the old AndroidView node; the next
        // factory pass tears the old host down and attachBridge re-sends
        // the current book to the fresh page.
    }

    private fun destroyWebView(webView: android.webkit.WebView) {
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    private fun isSystemDark(): Boolean =
        (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun openUri(uri: Uri) {
        controller.openBook { destination ->
            val input = contentResolver.openInputStream(uri)
                ?: throw FileNotFoundException(uri.toString())
            input.use { source ->
                destination.outputStream().use { source.copyTo(it) }
            }
        }
    }

    // ---- input --------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val shortcut = EinkKeys.shortcutForKey(event.keyCode)
        if (shortcut != null && EinkKeys.isPageTurnAction(event.action)) {
            controller.pageTurn(shortcut)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleBack(): Boolean {
        // Panels close first, like dialogs over the reader.
        if (controller.state.value.tocVisible) {
            controller.dismissToc()
            return true
        }
        if (controller.state.value.searchVisible) {
            controller.dismissSearch()
            return true
        }
        val webView = host?.webView
        return if (webView != null && webView.canGoBack()) {
            webView.goBack()
            true
        } else {
            false
        }
    }

    // ---- lifecycle ----------------------------------------------------------

    override fun onResume() {
        super.onResume()
        host?.startPolling()
    }

    override fun onPause() {
        super.onPause()
        host?.stopPolling()
    }

    override fun onStop() {
        super.onStop()
        controller.persistNow()
    }

    override fun onDestroy() {
        host?.let { old ->
            host = null
            old.stopPolling()
            destroyWebView(old.webView)
        }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // uiMode is in configChanges, so dark-mode toggles arrive here rather
        // than recreating the activity — push the new palette to the page
        // (upstream emits set_system_palette the same way).
        val dark = (newConfig.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (dark != lastDark) {
            lastDark = dark
            controller.updateSystemColors(UiData.systemColors(dark))
        }
    }

    // ---- HostUi -------------------------------------------------------------

    override fun pickBook() {
        pickBook.launch(arrayOf("application/epub+zip", "application/octet-stream"))
    }

    override fun finishApp() {
        finish()
    }

    override fun toggleImmersive(): Boolean {
        immersive = !immersive
        applyImmersive()
        return immersive
    }

    private fun applyImmersive() {
        val controllerCompat = WindowCompat.getInsetsController(window, window.decorView)
        if (immersive) {
            controllerCompat.hide(WindowInsetsCompat.Type.systemBars())
            controllerCompat.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controllerCompat.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun applyFontSettings(settings: FontSettings): Boolean {
        val webView = host?.webView ?: return false
        val s = webView.settings
        val oldSizes = Triple(s.minimumFontSize, s.defaultFontSize, s.defaultFixedFontSize)
        val serif = settings.serifFamily ?: DEFAULT_SERIF_FAMILY
        val sans = settings.sansFamily ?: DEFAULT_SANS_FAMILY
        val mono = settings.monoFamily ?: DEFAULT_MONO_FAMILY
        s.serifFontFamily = serif
        s.sansSerifFontFamily = sans
        s.fixedFontFamily = mono
        // The standard font follows whichever family the user picked.
        s.standardFontFamily = when (settings.standard) {
            StandardFontFamily.SERIF -> serif
            StandardFontFamily.SANS -> sans
            StandardFontFamily.MONO -> mono
        }
        s.minimumFontSize = settings.minimumFontSize
        s.defaultFontSize = settings.defaultFontSize
        s.defaultFixedFontSize = settings.fixedFontSize
        return oldSizes != Triple(s.minimumFontSize, s.defaultFontSize, s.defaultFixedFontSize)
    }

    override fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("calibre viewer", text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    override fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadFieldMetadata(assets: AndroidAssetSource): JsonElement {
        val bytes = assets.read("field_metadata.json") ?: return kotlinx.serialization.json.JsonNull
        return runCatching { Json.parseToJsonElement(String(bytes, Charsets.UTF_8)) }
            .getOrElse { kotlinx.serialization.json.JsonNull }
    }

    private companion object {
        const val TAG = "MainActivity"

        // Qt resetFontFamily → the platform's default families; the CSS
        // generic families are the portable Android equivalent.
        const val DEFAULT_SERIF_FAMILY = "serif"
        const val DEFAULT_SANS_FAMILY = "sans-serif"
        const val DEFAULT_MONO_FAMILY = "monospace"
    }
}
