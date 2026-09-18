// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// The host half of the viewer: owns the book server, the bridge handshake,
// the persistence stores and the native UI state. Deliberately plain JVM
// code (files + coroutines + JSON) so the M1 signal/bridge contract is unit
// tested without Android; the activity injects HostUi for the native bits.
//
// Startup handshake (web_view.py:on_bridge_ready parity):
//   qt-ready → create_view(session_data, local_storage, field_metadata,
//                          ui_data) → flushPendingActions()
// so a start_book_load queued while the bridge was still coming up replays
// after create_view — and replays again after a page reload, exactly like
// upstream's pending-action dict.

package dev.calibre.viewer.host

import dev.calibre.viewer.bridge.ViewerBridge
import dev.calibre.viewer.core.lastread.LastReadStore
import dev.calibre.viewer.core.session.SessionStore
import dev.calibre.viewer.parser.dom.DomParser
import dev.calibre.viewer.parser.epub.InvalidBookException
import dev.calibre.viewer.parser.render.RenderBookCache
import dev.calibre.viewer.serve.AssetSource
import dev.calibre.viewer.serve.BackgroundImages
import dev.calibre.viewer.serve.BookServer
import dev.calibre.viewer.serve.ViewerBook
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.ZipException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ViewerController(
    filesDir: File,
    cacheDir: File,
    private val ioDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
    assets: AssetSource,
    private val fieldMetadata: JsonElement,
    uiData: JsonObject,
    private val domParserFactory: () -> DomParser,
    private val log: (String) -> Unit = {},
) : SignalEffects {

    val bookServer: BookServer = BookServer(assets, BackgroundImages(File(filesDir, "background-images")))

    private val sessionStore = SessionStore(File(filesDir, "session"))
    private val lastReadStore = LastReadStore(File(filesDir, "last-read.json"))
    private val renderCache = RenderBookCache(File(cacheDir, "ev2"))
    private val bookOpener = BookOpener(renderCache, File(filesDir, "books"), domParserFactory, log)
    private val router = ViewerSignalRouter(this)

    /** Set by the activity before the WebView starts loading. */
    lateinit var hostUi: HostUi

    /** Attached when the WebView (and its bridge) exist. */
    var bridge: ViewerBridge? = null
        private set

    private val _state = MutableStateFlow(ViewerUiState())
    val state: StateFlow<ViewerUiState> = _state

    private var current: BookOpener.OpenedBook? = null

    /** Retained so reload_book can re-copy the same source. */
    private var lastCopy: ((File) -> Unit)? = null

    private var lastReadJob: Job? = null
    private var pendingPosition: Pair<String, Double>? = null

    private val uiData: JsonObject = uiData

    fun attachBridge(bridge: ViewerBridge) {
        this.bridge = bridge
        bridge.onReady = {
            bridge.createView(
                sessionData = sessionStore.sessionDataJson,
                localStorage = sessionStore.localStorageJson,
                fieldMetadata = fieldMetadata,
                uiData = uiData,
            )
            bridge.flushPendingActions()
        }
        // A book can be open without the bridge having it: ACTION_VIEW in
        // onCreate (WebView not composed yet) or a WebView rebuild after a
        // render-process crash. Re-send — executeWhenReady queues it behind
        // create_view, and the keyed dict overwrites harmlessly otherwise.
        current?.let { sendBookLoad(it, lastReadStore.get(it.bookHash)) }
    }

    /** Signal entry point handed to the ViewerBridge constructor. */
    fun onSignal(name: String, args: List<JsonElement>) {
        log("signal: $name ${args.joinToString(",").take(200)}")
        router.dispatch(name, args)
    }

    /**
     * Open a book: [copy] drains the source (SAF stream) into the staging
     * slot, then the render pipeline runs on the IO dispatcher and
     * start_book_load is queued/sent. UI state flips to Reading on the
     * view_created signal, not here — the page is the source of truth.
     */
    fun openBook(copy: (File) -> Unit) {
        if (_state.value.preparing) return
        lastCopy = copy
        _state.value = _state.value.copy(preparing = true, errorMessage = null, loadingMessage = null)
        scope.launch {
            try {
                val opened = withContext(ioDispatcher) {
                    val staged = bookOpener.stage(copy)
                    bookOpener.open(staged)
                }
                current = opened
                val viewerBook = ViewerBook.open(opened.bookDir, opened.sourceFile.path)
                    ?: throw IOException("Rendered book is incomplete: ${opened.bookDir}")
                bookServer.setBook(viewerBook)
                if (bridge != null) {
                    sendBookLoad(opened, lastReadStore.get(opened.bookHash))
                }
                // bridge == null: attachBridge re-sends the current book.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("open book failed: $e")
                _state.value = _state.value.copy(preparing = false, errorMessage = friendlyError(e))
            }
        }
    }

    /** Volume/page-turn keys → trigger_shortcut (pageup/pagedown). */
    fun pageTurn(shortcut: String) {
        bridge?.executeWhenReady("trigger_shortcut", JsonPrimitive(shortcut))
    }

    /** Error overlay dismissed. */
    fun dismissError() {
        _state.value = _state.value.copy(errorMessage = null)
    }

    /** Flush the debounced last-read position; call from onStop. */
    fun persistNow() {
        lastReadJob?.cancel()
        lastReadJob = null
        flushPosition()
        sessionStore.flush()
    }

    /** Render-process crash: the WebView stack is being rebuilt. */
    fun onRenderProcessGone() {
        _state.value = _state.value.copy(preparing = false, loadingMessage = null)
    }

    /** System palette changed (dark-mode toggle) → set_system_palette. */
    fun updateSystemColors(colors: JsonObject) {
        bridge?.call("set_system_palette", colors)
    }

    // ---- SignalEffects ------------------------------------------------------

    override fun viewCreated() {
        _state.value = _state.value.copy(preparing = false, bookOpen = true)
    }

    override fun contentFileChanged(name: String?) {
        // Upstream tracks it for the window title; the Android shell has none.
    }

    override fun updateLastReadPosition(cfi: String, posFrac: Double) {
        if (cfi.isEmpty()) return
        pendingPosition = cfi to posFrac
        lastReadJob?.cancel()
        lastReadJob = scope.launch {
            delay(LAST_READ_DEBOUNCE_MS)
            // Read-and-clear on the main thread, write off it — the store is
            // @Synchronized so an openBook reading a position concurrently is
            // safe.
            val book = current ?: return@launch
            val pos = pendingPosition ?: return@launch
            pendingPosition = null
            withContext(ioDispatcher) {
                lastReadStore.update(book.bookHash, pos.first, pos.second, book.sourceFile.path)
            }
        }
    }

    override fun setSessionData(key: JsonElement, value: JsonElement) {
        sessionStore.setSessionData(key, value)
    }

    override fun setLocalStorage(key: JsonElement, value: JsonElement) {
        sessionStore.setLocalStorage(key, value)
    }

    override fun reloadBook() {
        val copy = lastCopy ?: return
        val hash = current?.bookHash
        if (hash != null) renderCache.evict(hash)
        openBook(copy)
    }

    override fun toggleFullScreen() {
        val immersive = hostUi.toggleImmersive()
        bridge?.call("full_screen_state_changed", JsonPrimitive(immersive))
    }

    override fun askForOpen() {
        hostUi.pickBook()
    }

    override fun copySelection(text: String) {
        if (text.isNotEmpty()) hostUi.copyToClipboard(text)
    }

    override fun openUrl(url: String) {
        if (url.isNotEmpty()) hostUi.openUrl(url)
    }

    override fun showError(title: String, details: String) {
        _state.value = _state.value.copy(
            errorMessage = if (details.isEmpty()) title else "$title\n\n$details",
        )
    }

    override fun showLoadingMessage(message: String?) {
        _state.value = _state.value.copy(loadingMessage = message)
    }

    override fun quit() {
        hostUi.finishApp()
    }

    override fun closePrepFinished() {
        hostUi.finishApp()
    }

    // ---- helpers ------------------------------------------------------------

    private fun flushPosition() {
        val book = current ?: return
        val pos = pendingPosition ?: return
        pendingPosition = null
        lastReadStore.update(book.bookHash, pos.first, pos.second, book.sourceFile.path)
    }

    private fun sendBookLoad(book: BookOpener.OpenedBook, saved: LastReadStore.LastReadPosition?) {
        bridge?.startBookLoad(
            renderedBookDir = book.bookDir.path,
            initialPosition = saved?.cfi?.let { cfiToPosition(it) },
            pathtoebook = book.sourceFile.path,
        )
    }

    private fun cfiToPosition(cfi: String): JsonObject = buildJsonObject {
        put("type", "cfi")
        put("data", cfi)
    }

    private fun friendlyError(e: Exception): String = when (e) {
        is InvalidBookException -> e.message ?: "Invalid book"
        is ZipException -> "不是有效的 EPUB 文件（无法解压）"
        is FileNotFoundException -> "找不到书籍文件"
        is SecurityException -> "无权读取所选文件"
        else -> "打开书籍失败：${e.message ?: e.javaClass.simpleName}"
    }

    private companion object {
        const val LAST_READ_DEBOUNCE_MS = 2_000L
    }
}
