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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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
    // RenderBookCache lays out <root>/ev2/{f,t}/ itself (mirroring
    // convert_book.py); hand it the plain cache dir, not an ev2 subdir.
    private val renderCache = RenderBookCache(cacheDir)
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

    /** Search core for the open book; rebuilt on openBook. */
    private var searcher: BookSearcher? = null

    private var searchJob: Job? = null

    /** The query behind searchResults — echoed into each show_search_result. */
    private var lastQuery: SearchQuery? = null

    /** ui.py's discovery_counter — tags discovery sends so the page can tell them from user clicks. */
    private var searchDiscoveryCounter: Long = 0

    /** True after start_book_load until the first spine item is displayed. */
    private var awaitingBookDisplay = false

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
        router.dispatch(name, args)
    }

    /**
     * Open a book: [copy] drains the source (SAF stream) into the staging
     * slot, then the render pipeline runs on the IO dispatcher and
     * start_book_load is queued/sent. UI state flips to Reading once the
     * first content_file_changed arrives — the page is the source of truth.
     */
    fun openBook(copy: (File) -> Unit) {
        if (_state.value.preparing) return
        lastCopy = copy
        awaitingBookDisplay = false
        _state.value = _state.value.copy(
            preparing = true, errorMessage = null, loadingMessage = null, tocVisible = false,
            searchVisible = false, searchResults = emptyList(), searching = false,
            currentResultNum = null, currentName = null,
        )
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
                // The native TOC panel and the search core are fed from the
                // rendered manifest — the same file the Qt docks load their
                // models from.
                val manifest = withContext(ioDispatcher) { manifestFor(opened.bookDir) }
                searcher = manifest?.let { BookSearcher(opened.bookDir, it) }
                _state.value = _state.value.copy(
                    tocRows = TocRows.fromManifest(manifest?.get("toc") as? JsonObject),
                    viewedTocNodes = emptySet(),
                    tocVisible = false,
                )
                if (bridge != null) {
                    sendBookLoad(opened, lastReadStore.get(opened.bookHash))
                }
                // bridge == null: attachBridge re-sends the current book.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("open book failed: $e")
                awaitingBookDisplay = false
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

    /** The Qt host loads manifest['toc'] for its dock model; same source here. */
    private fun manifestFor(bookDir: File): JsonObject? = try {
        val manifestText = File(bookDir, "calibre-book-manifest.json").readText(Charsets.UTF_8)
        Json.parseToJsonElement(manifestText).jsonObject
    } catch (e: Exception) {
        log("manifest load failed: $e")
        null
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
        awaitingBookDisplay = false
        _state.value = _state.value.copy(
            preparing = false, loadingMessage = null, tocVisible = false, searchVisible = false,
        )
    }

    /** System palette changed (dark-mode toggle) → set_system_palette. */
    fun updateSystemColors(colors: JsonObject) {
        bridge?.call("set_system_palette", colors)
    }

    /**
     * WebPage.__init__ parity: seed a freshly built WebView with the
     * persisted font settings before any content loads. No signal — the
     * initial layout picks the sizes up directly.
     */
    fun applyPersistedFontSettings() {
        hostUi.applyFontSettings(fontSettingsFromSessionData(sessionStore.sessionDataJson))
    }

    // ---- SignalEffects ------------------------------------------------------

    override fun viewCreated() {
        // This means the top-level viewer shell exists. It is emitted once
        // when create_view runs, normally before any book has been selected,
        // so it cannot be used as the completion signal for start_book_load.
    }

    override fun contentFileChanged(name: String?) {
        // Emitted from View.on_content_loaded after the iframe has rendered
        // its first spine item. This is the per-book completion signal;
        // view_created only describes the lifetime of the viewer shell.
        if (!name.isNullOrEmpty()) {
            _state.value = _state.value.copy(currentName = name)
        }
        if (awaitingBookDisplay && !name.isNullOrEmpty()) {
            awaitingBookDisplay = false
            _state.value = _state.value.copy(preparing = false, bookOpen = true)
        }
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
        // web_view.py:set_session_data — font-key changes additionally push
        // the values onto the web engine and, when a SIZE changed, answer
        // with viewer_font_size_changed so pagination is recomputed.
        val before = sessionStore.sessionDataJson
        sessionStore.setSessionData(key, value)
        if (touchesFontKeys(before, sessionStore.sessionDataJson)) {
            val settings = fontSettingsFromSessionData(sessionStore.sessionDataJson)
            if (hostUi.applyFontSettings(settings)) {
                bridge?.executeWhenReady("viewer_font_size_changed")
            }
        }
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

    override fun toggleToc() {
        _state.value = _state.value.copy(tocVisible = !_state.value.tocVisible)
    }

    override fun updateCurrentTocNodes(ids: List<Long>) {
        val viewed = TocRows.viewedWithAncestors(_state.value.tocRows, ids)
        if (viewed != _state.value.viewedTocNodes) {
            _state.value = _state.value.copy(viewedTocNodes = viewed)
        }
    }

    /** Panel row click → goto_toc_node(node_id) and hide the panel. */
    fun gotoTocNode(id: Long) {
        _state.value = _state.value.copy(tocVisible = false)
        bridge?.executeWhenReady("goto_toc_node", JsonPrimitive(id))
    }

    /** Back key / tap outside the panel. */
    fun dismissToc() {
        if (_state.value.tocVisible) {
            _state.value = _state.value.copy(tocVisible = false)
        }
    }

    // ---- search (gui2/viewer/search.py + ui.py wiring) ----------------------

    /** show_search(text, trigger): open the panel; trigger runs immediately. */
    override fun showSearch(text: String, trigger: Boolean) {
        _state.value = _state.value.copy(searchVisible = true, searchPrefill = text, tocVisible = false)
        if (trigger && text.isNotBlank()) {
            startSearch(text, SearchMode.NORMAL, caseSensitive = false)
        }
    }

    /**
     * SearchPanel's search button: run the query over the rendered book and
     * stream every result to the page (on_discovery) so it can jump to the
     * first visible match, exactly like run_searches/on_result_found.
     */
    fun startSearch(text: String, mode: SearchMode, caseSensitive: Boolean) {
        val book = searcher ?: return
        val query = SearchQuery(text = text, mode = mode, caseSensitive = caseSensitive)
        if (query.isEmpty) return
        lastQuery = query
        searchJob?.cancel()
        searchDiscoveryCounter += 1
        val discovery = searchDiscoveryCounter
        _state.value = _state.value.copy(
            searchResults = emptyList(), searching = true, currentResultNum = null,
        )
        searchJob = scope.launch {
            var found = emptyList<SearchResult>()
            try {
                val startName = _state.value.currentName
                val searchRun = withContext(ioDispatcher) {
                    val results = ArrayList<SearchResult>()
                    val ran = book.search(query, startName) { results.add(it) }
                    ran to results.toList()
                }
                val (ran, results) = searchRun
                found = results
                // WebView calls must happen on the controller/main dispatcher.
                // Send the completion marker only after every discovery result;
                // nested launches previously allowed it to overtake the results.
                for (result in found) {
                    bridge?.executeWhenReady(
                        "show_search_result",
                        result.forJs(mode, JsonPrimitive(discovery)),
                    )
                }
                _state.value = _state.value.copy(searchResults = found.toList(), searching = false)
                if (ran) {
                    // the finished marker (on_result_found's SearchFinished branch)
                    bridge?.executeWhenReady(
                        "show_search_result",
                        buildJsonObject {
                            put("on_discovery", true)
                            put("search_finished", true)
                            put("result_num", -1)
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("search failed: $e")
                _state.value = _state.value.copy(searchResults = found.toList(), searching = false)
            }
        }
    }

    /** find_next(previous) from the page, and the panel's prev/next buttons. */
    override fun findNext(backwards: Boolean) = stepSearchResult(backwards)

    fun stepSearchResult(backwards: Boolean) {
        val results = _state.value.searchResults
        if (results.isEmpty()) return
        val current = _state.value.currentResultNum ?: return
        val i = results.indexOfFirst { it.resultNum == current }
        if (i < 0) return
        val next = results[Math.floorMod(i + if (backwards) -1 else 1, results.size)]
        showSearchResult(next.resultNum)
    }

    /** Panel row click (or stepped selection) → navigate the page to it. */
    fun showSearchResult(resultNum: Long) {
        val result = _state.value.searchResults.firstOrNull { it.resultNum == resultNum } ?: return
        _state.value = _state.value.copy(currentResultNum = resultNum, searchVisible = false)
        bridge?.executeWhenReady(
            "show_search_result",
            result.forJs(lastQuery?.mode ?: SearchMode.NORMAL),
        )
    }

    override fun searchResultNotFound(sr: JsonObject) {
        val results = _state.value.searchResults
        val updated = results.map { if (it.isResult(sr)) it.hidden() else it }
        if (updated != results) {
            _state.value = _state.value.copy(searchResults = updated)
        }
    }

    override fun searchResultDiscovered(sr: JsonObject) {
        val num = (sr["result_num"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return
        _state.value = _state.value.copy(currentResultNum = num)
    }

    /** Back key / tap outside the panel. Results are kept for re-opening. */
    fun dismissSearch() {
        if (_state.value.searchVisible) {
            _state.value = _state.value.copy(searchVisible = false)
        }
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
        awaitingBookDisplay = false
        _state.value = _state.value.copy(
            preparing = false,
            loadingMessage = null,
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
        awaitingBookDisplay = true
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
