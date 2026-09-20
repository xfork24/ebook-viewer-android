// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// The viewer activity: owns the controller (plain-JVM logic) and hosts the
// WebView stack inside Compose. Inputs wired here: SAF book picking, VIEW
// intents (file managers), physical page-turn keys, back navigation, and the
// HostUi surface (clipboard, browser, immersive mode, finish).

package dev.calibre.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import dev.calibre.viewer.eink.EinkKeys
import dev.calibre.viewer.host.FontSettings
import dev.calibre.viewer.host.HostUi
import dev.calibre.viewer.host.StandardFontFamily
import dev.calibre.viewer.host.SystemFontFamilies
import dev.calibre.viewer.host.prefersGetContentPicker
import dev.calibre.viewer.host.UiData
import dev.calibre.viewer.host.ViewerController
import dev.calibre.viewer.ui.NoIndication
import dev.calibre.viewer.ui.ViewerScreen
import dev.calibre.viewer.web.AndroidAssetSource
import dev.calibre.viewer.web.ViewerWebHost
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

class MainActivity : ComponentActivity(), HostUi {

    private lateinit var controller: ViewerController
    private var host: ViewerWebHost? = null
    private var lastDark = false
    private lateinit var assetSource: AndroidAssetSource

    /** Classic container: the WebView (index 0) under the overlay ComposeView. */
    private lateinit var webHolder: FrameLayout
    private lateinit var composeHolder: ComposeView

    /** Bumped to force the AndroidView factory to run again (host rebuild). */
    private val rebuildCounter = mutableIntStateOf(0)

    private val pickBook =
        registerForActivityResult(OpenBookDocument()) { result ->
            when (result) {
                BookPickerResult.Cancelled -> Unit
                BookPickerResult.MissingUri -> controller.showOpenError(
                    "The system file picker returned no readable file. Try a different file manager and retry.",
                )
                is BookPickerResult.Selected -> openRequest(result.request)
            }
        }

    /**
     * Styles "Change image": OpenDocument (ACTION_OPEN_DOCUMENT) always
     * routes to DocumentsUI — the system file manager — and stays the
     * default. E-ink vendor ROMs whose pickers open but never finish with a
     * result (iReader SmartOS among them) route to GetContent instead; see
     * [pickBackgroundImage] and VendorPickers.
     */
    private val pickBackgroundImageDoc =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) handleBackgroundImagePicked(uri)
        }

    /** Vendor-escape contract for the same flow; both feed one handler. */
    private val pickBackgroundImageContent =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) handleBackgroundImagePicked(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The reader owns the whole panel. Lay out edge-to-edge so system
        // bars or panel cutouts never shrink the window (on iReader SmartOS
        // the app otherwise windows short and the page keeps the shrunken
        // CSS viewport: content in a top band, blank below), and start
        // immersive — a reading surface shows no chrome by default.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // SHORT_EDGES + layoutInDisplayCutoutMode are API 28+; below that the
        // edge-to-edge flag alone is the best available behavior.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        // Always-fullscreen reader: classic FULLSCREEN flag covers pre-30,
        // WindowInsetsController covers 30+. Both run on every API level
        // to be safe, then re-applied in onResume / onWindowFocusChanged
        // so swipe-reveals (status bar pulse, notif shade, gesture nav) are
        // immediately hidden again.
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        applyImmersive()

        val assets = AndroidAssetSource(assets).also { assetSource = it }
        val isDark = isSystemDark()
        lastDark = isDark
        controller = ViewerController(
            filesDir = filesDir,
            cacheDir = cacheDir,
            ioDispatcher = Dispatchers.IO,
            scope = lifecycleScope,
            assets = assets,
            fieldMetadata = loadFieldMetadata(assets),
            uiData = UiData.build(isDark, SystemFontFamilies.deviceFamilies(UiData.DEFAULT_FONT_FAMILIES)),
            domParserFactory = {
                dev.calibre.viewer.parser.dom.DomParser { android.util.Xml.newPullParser() }
            },
            log = { Log.d(TAG, it) },
        )
        controller.hostUi = this

        // Startup housekeeping: the ev2 render cache grows without bound
        // otherwise; sweep month-old entries and cap at 256MB off the main
        // thread (never touches the book currently being opened).
        controller.trimRenderCache()

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
        // The panel overlay fills the whole window above the WebView.
        webHolder.addView(composeHolder, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(webHolder)

        composeHolder.setContent {
            val state by controller.state.collectAsState()
            LaunchedEffect(rebuildCounter.intValue) { rebuildHost() }
            // Reading keeps the e-ink panel awake; the home/error pages have
            // no reason to burn it. Add/clear is idempotent, so an effect
            // rerun with the same bookOpen value is a no-op.
            LaunchedEffect(state.bookOpen) {
                if (state.bookOpen) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            composeHolder.visibility = when {
                state.preparing || state.loadingMessage != null ||
                    state.errorMessage != null || state.tocVisible || state.searchVisible ||
                    state.bookmarksVisible || state.highlightsVisible || state.profilesVisible -> View.VISIBLE
                else -> View.GONE
            }
            // One-shot ripple/motion kill for every native panel: clickable
            // and Button resolve their indication through LocalIndication.
            CompositionLocalProvider(LocalIndication provides NoIndication) {
                ViewerScreen(
                    state = state,
                    onDismissError = controller::dismissError,
                    onTocNodeClick = controller::gotoTocNode,
                    onDismissToc = controller::dismissToc,
                    onSearch = controller::startSearch,
                    onSearchStep = controller::stepSearchResult,
                    onSearchResultClick = controller::showSearchResult,
                    onDismissSearch = controller::dismissSearch,
                    onBookmarkClick = controller::bookmarkClick,
                    onHighlightClick = controller::highlightClick,
                    onSaveProfile = controller::profileSave,
                    onApplyProfile = controller::profileApply,
                    onDeleteProfile = controller::profileDelete,
                    onResetProfileDefaults = controller::profileResetDefaults,
                    onProfileDraftChange = controller::setProfileDraftName,
                    onDismissPanels = controller::dismissPanels,
                )
            }
        }

        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.let(::handleOpenIntent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.takeIf { it.action == Intent.ACTION_VIEW }?.let(::handleOpenIntent)
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
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            newHost.startPolling()
        } else {
            // The renderer died while we were paused (a frozen WebView keeps
            // its process). Match the fresh host to the suspended state —
            // its timers are process-wide paused anyway; starting the
            // watchdog now would only queue dead polls. onResume's
            // resumeFromBackground + startPolling drives it.
            newHost.suspendForBackground()
        }
    }

    /** WebView renderer died: stop using it, then rebuild the whole stack. */
    private fun onRenderProcessGone() {
        Log.e(TAG, "WebView render process gone; rebuilding host")
        host?.stopPolling()
        // The dead WebView must be unusable immediately: onPause/applyFont-
        // Settings would otherwise touch a renderless view until the next
        // composition runs rebuildHost.
        host = null
        controller.onRenderProcessGone()
        rebuildCounter.intValue++
        // The rebuildCounter change recomposes and runs rebuildHost, which
        // tears the old host down and attachBridge re-sends the current book
        // to the fresh page.
    }

    private fun destroyWebView(webView: android.webkit.WebView) {
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    private fun isSystemDark(): Boolean =
        (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun handleOpenIntent(intent: Intent) {
        val request = intent.toBookOpenRequest()
        if (request == null) {
            controller.showOpenError("The open request contains no readable file address")
            return
        }
        openRequest(request)
    }

    private fun openRequest(request: BookOpenRequest) {
        val uri = request.uri
        val scheme = uri.scheme?.lowercase()
        if (scheme != null && scheme != "content" && scheme != "file") {
            controller.showOpenError("Unsupported file address type: $scheme")
            return
        }
        Log.i(
            TAG,
            "open request action=${request.sourceAction} carrier=${request.carrier} " +
                "scheme=$scheme authority=${uri.authority} flags=0x${request.grantFlags.toString(16)}",
        )
        // openBook flips the UI into its preparing state synchronously, then
        // copies and parses on Dispatchers.IO. In particular, do not make any
        // ContentResolver/Binder call on the main thread before this point:
        // BOOX DocumentsProvider builds have been observed to wedge instead
        // of rejecting unsupported persistable-grant requests.
        controller.openBook(originKey = uri.toString()) { destination ->
            drainInto(uri, destination)
            persistReadGrantInBackground(request)
        }
    }

    private fun persistReadGrantInBackground(request: BookOpenRequest) {
        if (request.uri.scheme != "content" || !hasPersistableReadGrant(request.grantFlags)) return
        // Copy first while the transient grant is unquestionably valid. The
        // best-effort persist call is deliberately detached from the open
        // pipeline: a broken vendor provider may block this Binder call, but
        // it must never freeze the UI or prevent the staged book from opening.
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.takePersistableUriPermission(
                    request.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                Log.d(TAG, "persisted read grant for authority=${request.uri.authority}")
            } catch (e: SecurityException) {
                Log.w(TAG, "provider did not allow a persistent read grant", e)
            } catch (e: RuntimeException) {
                Log.w(TAG, "failed to persist read grant", e)
            }
        }
    }

    /** ask_for_open(recent path): open the original source directly. */
    override fun tryOpenRecent(path: String): Boolean {
        val uri = Uri.parse(path)
        // Do not probe a content provider synchronously on the main thread.
        // The IO open below will either succeed or surface the normal friendly
        // error overlay; probing first can itself hang on vendor providers.
        openRequest(
            BookOpenRequest(
                uri = uri,
                grantFlags = 0,
                sourceAction = "recent",
                carrier = BookUriCarrier.RECENT,
            ),
        )
        return true
    }

    /** Copy a book source (SAF stream or plain file) into the staging slot. */
    private fun drainInto(uri: Uri, destination: File) {
        val input: java.io.InputStream = when (uri.scheme?.lowercase()) {
            "content" -> contentResolver.openInputStream(uri)
                ?: throw FileNotFoundException(uri.toString())
            "file", null -> File(uri.path ?: uri.toString()).inputStream()
            else -> throw FileNotFoundException("Unsupported URI scheme: ${uri.scheme}")
        }
        input.use { source ->
            destination.outputStream().use { source.copyTo(it) }
        }
    }

    // ---- input --------------------------------------------------------------

    // dispatchKeyEvent (a public framework Activity entry point) is the only
    // place that intercepts hardware page-turn keys BEFORE the focused
    // WebView consumes them for its own scrolling; ComponentActivity's
    // intermediate override carries an androidx-internal @RestrictTo that
    // lint flags on the super call — a false positive for an override.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val shortcut = EinkKeys.shortcutForKey(event.keyCode)
        if (shortcut != null) {
            // Consume EVERY event for a mapped key: repeats and UPs leaking
            // through would double-turn (the WebView has its own keyboard
            // handling) or flash the system volume UI. Only the initial
            // press turns a page.
            if (EinkKeys.isPageTurnPress(event.action, event.repeatCount)) {
                controller.pageTurn(shortcut)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleBack(): Boolean {
        // The error overlay is the topmost layer: back dismisses it (back
        // to reading, or back to the home page after a failed first open)
        // instead of exiting the app under the user's thumb.
        if (controller.state.value.errorMessage != null) {
            controller.dismissError()
            return true
        }
        // Panels close first, like dialogs over the reader.
        if (controller.state.value.tocVisible) {
            controller.dismissToc()
            return true
        }
        if (controller.state.value.searchVisible) {
            controller.dismissSearch()
            return true
        }
        if (controller.state.value.bookmarksVisible ||
            controller.state.value.highlightsVisible ||
            controller.state.value.profilesVisible
        ) {
            controller.dismissPanels()
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
        applyImmersive()
        // Un-freeze the WebView before polling resumes, or the first bridge
        // polls would queue against paused timers.
        host?.resumeFromBackground()
        host?.startPolling()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // System gestures (notif shade, recents, status pulse) briefly
        // re-show the bars. Re-hide them on every focus regain so the
        // reader always returns to true fullscreen.
        if (hasFocus) applyImmersive()
    }

    override fun onPause() {
        super.onPause()
        // Stop polling first so no watchdog tick lands between "stopped"
        // and "frozen"; then freeze the whole WebView (timers + layout).
        host?.stopPolling()
        host?.suspendForBackground()
    }

    override fun onStop() {
        super.onStop()
        controller.persistNow()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Level values matter and are NOT monotonic in "severity" as a
        // reader would guess: the RUNNING_* levels (5/10/15) are numerically
        // BELOW UI_HIDDEN (20). Split into the three real states:
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE &&
            level <= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            // App still visible: the WebView's own memory dwarfs anything we
            // could drop, and stalling a mid-read fetch is the worse outcome
            // on e-ink (a frozen page turn). Observe only — these snapshots
            // are what OOM post-mortems are diagnosed from.
            Log.i(TAG, "onTrimMemory(running) level=$level ${memorySnapshot()}")
            return
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            // Backgrounded AND the system is pressing (40/60/80): reclaim
            // everything that survives a setBook — the paused page's
            // abandoned streams, the asset LRU, and a tighter render-cache
            // budget than the startup sweep's. The currently open book is
            // never evicted (protect inside trimRenderCache).
            if (::assetSource.isInitialized) assetSource.clearCache()
            controller.reclaimAbandonedStreams()
            controller.trimRenderCache(maxBytes = TRIM_CACHE_BG_BYTES)
            Log.i(TAG, "onTrimMemory(background-pressure) level=$level ${memorySnapshot()}")
            return
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && ::assetSource.isInitialized) {
            // Plain UI_HIDDEN (20): app not visible, no system pressure yet.
            // The asset LRU is the only cheap drop — keep the old behavior.
            assetSource.clearCache()
        }
    }

    /** One-line memory snapshot for trim logs: system avail + our own PSS. */
    private fun memorySnapshot(): String = try {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val sys = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val own = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
        "availMem=${sys.availMem / (1024 * 1024)}MB totalPss=${own.totalPss}kB"
    } catch (e: Exception) {
        // Odd firmwares / restricted profiles: never let the observer crash
        // the trim path it is supposed to be measuring.
        "unavailable(${e.javaClass.simpleName})"
    }

    override fun onDestroy() {
        host?.let { old ->
            host = null
            old.stopPolling()
            destroyWebView(old.webView)
        }
        // Best-effort close of anything the browser side never drained; the
        // process is going away, so fds would be reclaimed regardless — this
        // just keeps fd counts honest for as long as we do linger.
        controller.bookServer.closeAllStreams()
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
        pickBook.launch(Unit)
    }

    override fun pickBackgroundImage() {
        if (prefersGetContentPicker(
                android.os.Build.MANUFACTURER, android.os.Build.BRAND, android.os.Build.MODEL,
            )
        ) {
            pickBackgroundImageContent.launch("image/*")
            return
        }
        try {
            pickBackgroundImageDoc.launch(arrayOf("image/*"))
        } catch (e: android.content.ActivityNotFoundException) {
            // ROM with no OPEN_DOCUMENT handler at all: fall back to the
            // older, universally implemented contract.
            Log.w(TAG, "no OPEN_DOCUMENT handler; falling back to GetContent", e)
            pickBackgroundImageContent.launch("image/*")
        }
    }

    /**
     * Read the picked image off the main thread, then hand it to the
     * controller. The read carries a hard timeout: vendor DocumentsProviders
     * (e-ink firmwares) have been observed to hang on openInputStream, and a
     * wedged Binder call must surface the normal error overlay, not sit
     * silent while the user wonders why nothing happened.
     */
    private fun handleBackgroundImagePicked(uri: Uri) {
        lifecycleScope.launch {
            Log.i(TAG, "background image picked: $uri")
            val picked = try {
                withTimeout(BACKGROUND_IMAGE_READ_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { readBackgroundImage(uri) }
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "image provider did not answer within " +
                    "${BACKGROUND_IMAGE_READ_TIMEOUT_MS}ms; abandoning read", e)
                null
            }
            if (picked == null) {
                controller.showOpenError(
                    "Could not read the selected image. Try a different file or file manager.",
                )
                return@launch
            }
            Log.i(TAG, "background image read ok: mime=${picked.first} bytes=${picked.second.size}")
            controller.onBackgroundImagePicked(picked.first, picked.second)
        }
    }

    /**
     * (mime, bytes) or null when unreadable. Oversized picks are downscaled
     * to JPEG: BookServer buffers whole images per request and caches four,
     * so a camera-sized original would hammer low-memory e-ink devices on
     * every color-scheme change.
     */
    private fun readBackgroundImage(uri: Uri): Pair<String, ByteArray>? {
        try {
            val raw = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            val mime = contentResolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
            if (raw.size > BACKGROUND_IMAGE_DOWNSCALE_THRESHOLD) {
                downscale(raw)?.let { return "image/jpeg" to it }
            }
            return mime to raw
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Vendor providers can throw almost anything across the Binder
            // (SecurityException, IllegalStateException, ...); every failure
            // mode funnels into the same friendly error.
            Log.w(TAG, "failed to read picked image: $uri", e)
            return null
        }
    }

    private fun downscale(raw: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= BACKGROUND_IMAGE_MAX_EDGE) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        return out.toByteArray()
    }

    override fun finishApp() {
        finish()
    }

    private fun applyImmersive() {
        val controllerCompat = WindowCompat.getInsetsController(window, window.decorView)
        // Always immersive: hide both status and navigation bars and only
        // let the user peek them transiently via an edge swipe. Subsequent
        // onResume / onWindowFocusChanged re-applies this so the OS can't
        // leave the bars revealed after a system gesture.
        controllerCompat.hide(WindowInsetsCompat.Type.systemBars())
        controllerCompat.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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

        // Render-cache budget when the system presses a backgrounded app
        // (TRIM_BACKGROUND and up): half the startup sweep's 256 MB cap.
        const val TRIM_CACHE_BG_BYTES = 128L * 1024 * 1024

        // Qt resetFontFamily → the platform's default families; the CSS
        // generic families are the portable Android equivalent.
        const val DEFAULT_SERIF_FAMILY = "serif"
        const val DEFAULT_SANS_FAMILY = "sans-serif"
        const val DEFAULT_MONO_FAMILY = "monospace"

        // Picked background images above this size get decoded and
        // re-encoded as a bounded JPEG (long edge BACKGROUND_IMAGE_MAX_EDGE).
        const val BACKGROUND_IMAGE_DOWNSCALE_THRESHOLD = 1024 * 1024
        const val BACKGROUND_IMAGE_MAX_EDGE = 2048

        // Hard cap on the picker read: a hung vendor DocumentsProvider must
        // degrade to the error overlay, not an eternal silent no-op.
        const val BACKGROUND_IMAGE_READ_TIMEOUT_MS = 10_000L
    }
}
