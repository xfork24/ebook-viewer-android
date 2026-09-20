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
import dev.calibre.viewer.core.annot.AnnotationsStore
import dev.calibre.viewer.core.annot.BookmarkRecord
import dev.calibre.viewer.core.annot.HighlightRecord
import dev.calibre.viewer.core.lastread.LastReadStore
import dev.calibre.viewer.core.profile.ProfileStore
import dev.calibre.viewer.core.rates.ReadingRatesStore
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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
    private val annotationsStore = AnnotationsStore(File(filesDir, "annot"))
    private val profileStore = ProfileStore(File(filesDir, "profiles.json"))
    private val ratesStore = ReadingRatesStore(File(filesDir, "reading-rates.json"))
    // RenderBookCache lays out <root>/ev2/{f,t}/ itself (mirroring
    // convert_book.py); hand it the plain cache dir, not an ev2 subdir.
    private val renderCache = RenderBookCache(cacheDir)
    private val bookOpener = BookOpener(renderCache, File(filesDir, "books"), domParserFactory, log)
    private val router = ViewerSignalRouter(this)

    init {
        // One-time migration: inboxes staged before mtime pinning hash with
        // their real mtime; re-date the file and re-key every store hanging
        // off the old hash (position, annotations, rates), then drop the old
        // render-cache entry (one re-render on the next open of that book).
        bookOpener.normalizeLegacyInbox()?.let { (oldHash, newHash) ->
            lastReadStore.rekey(oldHash, newHash)
            annotationsStore.rekey(oldHash, newHash)
            ratesStore.rekey(oldHash, newHash)
            // The rekeys above are queued writes and the inbox mtime is
            // already pinned — process death before they land would orphan
            // the old-hash entries forever (the migration won't re-fire).
            // Block until they're on disk, then drop the old cache entry.
            annotationsStore.flush()
            ratesStore.flush()
            renderCache.evict(oldHash)
            log("migrated legacy inbox hash $oldHash -> $newHash")
        }
    }

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

    /**
     * The open pipeline's coroutine. `preparing` in the UI state is true
     * exactly while this is active; the error paths that clear preparing
     * consult (or wait on) this job so the two can never disagree.
     */
    private var openJob: Job? = null

    private var searchJob: Job? = null

    /** The query behind searchResults — echoed into each show_search_result. */
    private var lastQuery: SearchQuery? = null

    /** ui.py's discovery_counter — tags discovery sends so the page can tell them from user clicks. */
    private var searchDiscoveryCounter: Long = 0

    /** True after start_book_load until the first spine item is displayed. */
    private var awaitingBookDisplay = false

    /** Retained so reload_book can re-copy the same source. */
    private var lastCopy: ((File) -> Unit)? = null
    private var lastOriginKey: String? = null

    private var lastReadJob: Job? = null

    /** Debounced last-read position, keyed by the book that reported it. */
    private var pendingPosition: PendingPosition? = null

    private data class PendingPosition(
        val bookHash: String,
        val cfi: String,
        val posFrac: Double,
        val originKey: String,
    )

    private val uiData: JsonObject = uiData

    fun attachBridge(bridge: ViewerBridge) {
        this.bridge = bridge
        bridge.onReady = {
            bridge.createView(
                sessionData = seededSessionData(),
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
        // Highlights MUST ride along: seeded empty, the page's next
        // highlights_changed would push a 1-item array and wipe the stored
        // list for this book.
        current?.let {
            sendBookLoad(
                it,
                lastReadStore.get(it.bookHash),
                annotationsStore.loadHighlights(it.bookHash),
            )
        }
    }

    /** Signal entry point handed to the ViewerBridge constructor. */
    fun onSignal(name: String, args: List<JsonElement>) {
        router.dispatch(name, args)
    }

    /**
     * Open a book: [copy] drains the source (SAF stream) into the staging
     * slot, then the render pipeline runs on the IO dispatcher and
     * start_book_load is queued/sent. [originKey] is the opaque key the book
     * was opened by (the SAF content URI) — it becomes pathtoebook so the
     * page's recents list dedupes per source book and echoes it back through
     * ask_for_open. [force] re-renders even when the render cache has a
     * finished entry for the book (reload_book after a viewer.js transform
     * update). UI state flips to Reading once the first content_file_changed
     * arrives — the page is the source of truth.
     */
    fun openBook(originKey: String? = null, force: Boolean = false, copy: (File) -> Unit) {
        if (_state.value.preparing) {
            // Re-entrancy is dropped, not queued: a queued open would fire
            // its state writes after whichever open is still in flight,
            // interleaving two books' current/bookServer/hash transitions.
            log("openBook dropped: another open is already in flight")
            return
        }
        // A still-running search for the previous book must not keep
        // streaming results into the freshly loaded page.
        searchJob?.cancel()
        lastCopy = copy
        lastOriginKey = originKey
        awaitingBookDisplay = false
        // currentBookHash, bookmarks and highlights intentionally keep the
        // outgoing book's values: every signal that can still arrive during
        // the open window comes from the outgoing page and must attribute to
        // the outgoing book (new_bookmark, highlights_changed, last-read).
        // Nulled here, a FAILED open left the hash null until the next
        // successful open, silently dropping every bookmark/highlight made
        // over the old page in between. On success all three are replaced
        // with the new book's values below.
        _state.value = _state.value.copy(
            preparing = true, errorMessage = null, loadingMessage = null, tocVisible = false,
            searchVisible = false, searchResults = emptyList(), searching = false,
            searchError = null,
            currentResultNum = null, currentName = null,
            bookmarksVisible = false, highlightsVisible = false, profilesVisible = false,
            profiles = profileStore.list(),
            profileDraftName = "",
        )
        openJob = scope.launch {
            try {
                val opened = withContext(ioDispatcher) {
                    val staged = bookOpener.stage(copy)
                    bookOpener.open(staged, force = force, originKey = originKey)
                }
                current = opened
                val viewerBook = ViewerBook.open(opened.bookDir, opened.sourceFile.path, onWarn = log)
                    ?: throw IOException("Rendered book is incomplete: ${opened.bookDir}")
                bookServer.setBook(viewerBook)
                // The native TOC panel and the search core are fed from the
                // rendered manifest — the same file the Qt docks load their
                // models from.
                val manifest = withContext(ioDispatcher) { manifestFor(opened.bookDir) }
                searcher = manifest?.let { BookSearcher(opened.bookDir, it) }
                val (bookmarks, highlights) = withContext(ioDispatcher) {
                    annotationsStore.loadBookmarks(opened.bookHash) to
                        annotationsStore.loadHighlights(opened.bookHash)
                }
                _state.value = _state.value.copy(
                    tocRows = TocRows.fromManifest(manifest?.get("toc") as? JsonObject),
                    viewedTocNodes = emptySet(),
                    tocVisible = false,
                    bookmarks = bookmarks,
                    highlights = highlights,
                    currentBookHash = opened.bookHash,
                )
                if (bridge != null) {
                    sendBookLoad(opened, lastReadStore.get(opened.bookHash), highlights)
                }
                // bridge == null: attachBridge re-sends the current book.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Throwable: an OutOfMemoryError on 2 GB devices while staging
                // or rendering must surface as the friendly error overlay,
                // not kill the process.
                log("open book failed: $e")
                awaitingBookDisplay = false
                _state.value = _state.value.copy(
                    preparing = false,
                    errorMessage = friendlyError(e),
                )
            }
        }
    }

    /** Volume/page-turn keys → trigger_shortcut (pageup/pagedown). */
    fun pageTurn(shortcut: String) {
        // No book open → the page has nothing to turn; skip the send (the
        // home page's trigger_shortcut is inert at best). The key is still
        // consumed in the activity: letting volume keys through just to
        // flash the system volume UI on every turn is the worse outcome.
        if (!_state.value.bookOpen) return
        bridge?.executeWhenReady("trigger_shortcut", JsonPrimitive(shortcut))
    }

    /** Error overlay dismissed. */
    fun dismissError() {
        _state.value = _state.value.copy(errorMessage = null)
    }

    /** Native input/picker failures that happen before [openBook] starts. */
    fun showOpenError(message: String) {
        if (openJob?.isActive == true) {
            // A contradictory host-side error mid-open (e.g. a broken second
            // ACTION_VIEW): clearing preparing underneath the in-flight open
            // would reopen the double-open window.
            log("showOpenError suppressed during open: $message")
            return
        }
        awaitingBookDisplay = false
        _state.value = _state.value.copy(
            preparing = false,
            loadingMessage = null,
            errorMessage = message,
        )
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
        ratesStore.flush()
        // Annotation/profile writes are queued off-thread; drain them too so
        // a bookmark made seconds before backgrounding survives process death.
        annotationsStore.flush()
        profileStore.flush()
    }

    /** Render-process crash: the WebView stack is being rebuilt. */
    fun onRenderProcessGone() {
        awaitingBookDisplay = false
        // Everything queued against the dead page is orphaned: stop streaming
        // search results into it, drop get_current_cfi requests whose replies
        // died with the renderer, and reclaim book streams it never drained
        // (they would otherwise sit open until the next setBook).
        searchJob?.cancel()
        pendingCfiRequests.clear()
        bookServer.closeAllStreams()
        // An in-flight open is left to land: its send to the dead bridge is
        // inert, and attachBridge re-sends `current` to the rebuilt page.
        // Clearing preparing underneath it would reopen the double-open
        // window, so keep preparing exactly while the open is alive.
        _state.value = _state.value.copy(
            preparing = openJob?.isActive == true,
            loadingMessage = null, tocVisible = false, searchVisible = false,
        )
    }

    /** System palette changed (dark-mode toggle) → set_system_palette. */
    fun updateSystemColors(colors: JsonObject) {
        bridge?.call("set_system_palette", colors)
    }

    /**
     * WebPage.__init__ parity: seed a freshly built WebView with the
     * persisted font settings before any content loads. No signal — the
     * initial layout picks the sizes up directly. Also migrates the
     * legacy default base_font_size (16) to the new default (32) for
     * installs upgrading from before the change.
     */
    fun applyPersistedFontSettings() {
        val migrated = migrateLegacyFontDefault(sessionStore.sessionDataJson)
        if (migrated !== sessionStore.sessionDataJson) {
            // Persist the migrated value so the in-page Font size chooser
            // shows the new default too, not just the host engine.
            sessionStore.setSessionData(JsonPrimitive("base_font_size"), JsonPrimitive(DEFAULT_BASE_FONT_SIZE))
        }
        hostUi.applyFontSettings(fontSettingsFromSessionData(migrated))
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
        // Attribute at SIGNAL arrival. Reading `current` at debounce-fire
        // time wrote the outgoing book's cfi under the newly opened book's
        // hash whenever a book switch landed inside the debounce window —
        // corrupting the new book's restore point.
        val book = current ?: return
        val pending = pendingPosition
        if (pending != null && pending.bookHash != book.bookHash) {
            // The pending position belongs to the outgoing book and is about
            // to be overwritten; write it out now so its final reading spot
            // is not lost.
            pendingPosition = null
            lastReadStore.update(pending.bookHash, pending.cfi, pending.posFrac, pending.originKey)
        }
        pendingPosition = PendingPosition(book.bookHash, cfi, posFrac, book.originKey)
        lastReadJob?.cancel()
        lastReadJob = scope.launch {
            delay(LAST_READ_DEBOUNCE_MS)
            val pos = pendingPosition ?: return@launch
            pendingPosition = null
            withContext(ioDispatcher) {
                lastReadStore.update(pos.bookHash, pos.cfi, pos.posFrac, pos.originKey)
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
        // No pre-evict: evicting the current book's rendered dir here yanked
        // the files out from under the page still being served (404s for the
        // whole re-render duration). force re-renders into a transient dir
        // and cache.commit swaps it in; the old dir stays served until then.
        // The preparing guard inside openBook drops the reload when another
        // open is in flight.
        openBook(originKey = lastOriginKey, force = true, copy = copy)
    }

    /**
     * Housekeeping sweep for the ev2 render cache (exploded books on disk):
     * drop entries untouched for a month, then cap the tree at [maxBytes],
     * never touching the currently open book. Called once at startup — the
     * cache grows without bound otherwise and these are 1-2GB devices.
     */
    fun trimRenderCache(maxBytes: Long = RENDER_CACHE_MAX_BYTES) {
        scope.launch(ioDispatcher) {
            // Let an in-flight open settle first: once it lands, its book IS
            // the protected current book below. Trimming mid-open could race
            // cache.commit for the same directory.
            openJob?.join()
            renderCache.evictOlderThan(RENDER_CACHE_AGE_MS)
            renderCache.evictToSize(maxBytes, _state.value.currentBookHash)
        }
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
            searchError = null,
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
            } catch (e: Throwable) {
                // Throwable, not Exception: the pre-fix Matcher.results() call
                // crashed 2 GB e-ink devices with a NoSuchMethodError (an
                // Error) that sailed straight through catch(Exception). Any
                // future Error must degrade to a panel message, not a crash.
                log("search failed: $e")
                _state.value = _state.value.copy(
                    searchResults = found.toList(),
                    searching = false,
                    searchError = "搜索失败:${e.javaClass.simpleName}",
                )
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

    /**
     * ask_for_open — null (the plain "Open book" button) launches the
     * picker; a path (a recent-list click) opens that book directly. When
     * the original source is gone (grant expired / file deleted) the stale
     * entry is dropped from the page's recently-opened list via
     * generic_action("remove-recently-opened") before the picker opens.
     */
    override fun askForOpen(path: String?) {
        if (path == null) {
            hostUi.pickBook()
            return
        }
        if (hostUi.tryOpenRecent(path)) return
        bridge?.genericAction("remove-recently-opened", buildJsonObject { put("path", path) })
        hostUi.pickBook()
    }

    override fun copySelection(text: String) {
        if (text.isNotEmpty()) hostUi.copyToClipboard(text)
    }

    override fun openUrl(url: String) {
        if (url.isNotEmpty()) hostUi.openUrl(url)
    }

    override fun showError(title: String, details: String) {
        if (openJob?.isActive == true) {
            // The error came from the outgoing page while a new open is in
            // flight (it is being replaced anyway); surfacing it would clear
            // preparing underneath the open and reopen the double-open
            // window. The open's own outcome decides the final state.
            log("showError suppressed during open: $title")
            return
        }
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

    // ---- M2: Highlights / Bookmarks / Profiles effects -----------------------

    override fun toggleBookmarks() {
        val s = _state.value
        val visible = !s.bookmarksVisible
        _state.value = s.copy(
            bookmarksVisible = visible,
            highlightsVisible = false,
            profilesVisible = false,
            searchVisible = false,
            tocVisible = false,
        )
    }

    override fun toggleHighlights() {
        val s = _state.value
        val visible = !s.highlightsVisible
        _state.value = s.copy(
            highlightsVisible = visible,
            bookmarksVisible = false,
            profilesVisible = false,
            searchVisible = false,
            tocVisible = false,
        )
    }

    override fun toggleProfiles() {
        val s = _state.value
        val visible = !s.profilesVisible
        _state.value = s.copy(
            profilesVisible = visible,
            bookmarksVisible = false,
            highlightsVisible = false,
            searchVisible = false,
            tocVisible = false,
            // Saved sets may have changed via the JS-side panel while we
            // were closed; refresh on open so the list is never stale.
            profiles = if (visible) profileStore.list() else s.profiles,
        )
    }

    /**
     * Viewer.js:50124 fires this from Ctrl+Alt+B with `{title?, cfi}`.
     * If the page supplied a cfi, anchor the new bookmark there; otherwise
     * fall back to the get_current_cfi round-trip so we never persist a
     * bookmark with an empty position. Falls back to "Bookmark N" if no
     * title is given.
     */
    override fun newBookmark(data: JsonObject?) {
        val hash = _state.value.currentBookHash ?: return
        val cfi = data?.stringPrimitive("cfi").orEmpty()
        if (cfi.isEmpty()) {
            bookmarkThisPage()
            return
        }
        val title = data?.stringPrimitive("title")?.takeIf { it.isNotBlank() } ?: nextBookmarkTitle()
        val record = BookmarkRecord(
            timestamp = java.time.Instant.now().toString(),
            pos = cfi,
            title = title,
        )
        val updated = annotationsStore.loadBookmarks(hash) + record
        annotationsStore.save(hash, updated, annotationsStore.loadHighlights(hash))
        _state.value = _state.value.copy(bookmarks = updated)
    }

    private fun nextBookmarkTitle(): String {
        val existing = _state.value.bookmarks
        var n = existing.size + 1
        while (existing.any { it.title == "Bookmark $n" }) n++
        return "Bookmark $n"
    }

    /**
     * Page pushes its full highlights array upward (viewer.js:50456-50467).
     * The JS callsite is `to_python.highlights_changed(amap.highlight)` —
     * args[0] is the array directly, NOT wrapped in a `{highlight: [...]}`.
     * We rebuild the host list from it (tombstones for entries missing
     * required fields) so the host store and the page's annotations_manager
     * converge. After save, the panel automatically picks up state.highlights.
     */
    override fun highlightsChanged(map: JsonArray) {
        val hash = _state.value.currentBookHash ?: return
        val incoming = map.mapNotNull { parseHighlightFromPage(it) }
        annotationsStore.save(hash, annotationsStore.loadBookmarks(hash), incoming)
        _state.value = _state.value.copy(highlights = incoming)
    }

    private fun parseHighlightFromPage(el: JsonElement): HighlightRecord? {
        val o = el as? JsonObject ?: return null
        val uuid = o.stringPrimitive("uuid") ?: return null
        val timestamp = o.stringPrimitive("timestamp") ?: return null
        val startCfi = o.stringPrimitive("start_cfi") ?: return null
        return HighlightRecord(
            timestamp = timestamp,
            uuid = uuid,
            highlightedText = o.stringPrimitive("highlighted_text") ?: "",
            startCfi = startCfi,
            endCfi = o.stringPrimitive("end_cfi") ?: startCfi,
            style = (o["style"] as? JsonObject) ?: JsonObject(emptyMap()),
            spineName = o.stringPrimitive("spine_name") ?: "",
            spineIndex = o.intPrimitive("spine_index") ?: 0,
            notes = o.stringPrimitive("notes"),
            tocFamilyTitles = (o["toc_family_titles"] as? JsonArray)?.mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull
            }.orEmpty(),
            removed = o.booleanPrimitive("removed"),
        )
    }

    /**
     * Profile RPC (viewer.js:49294 / 49303). The page sends:
     *   "all-profiles"  → reply via bridge.profileResponse
     *   "save-profile"  → settings===null deletes; otherwise persist
     *   "apply-profile" → write each setting key into the session store
     */
    override fun profileOp(which: String, name: String, data: JsonObject?) {
        when (which) {
            "all-profiles" -> sendAllProfiles()
            "save-profile" -> {
                if (name.isBlank()) return
                if (data == null) {
                    profileStore.delete(name)
                } else {
                    profileStore.save(name, data)
                }
                _state.value = _state.value.copy(profiles = profileStore.list())
                bridge?.profileResponse("save-profile", JsonPrimitive(""))
            }
            "apply-profile" -> applyProfile(name, data)
            else -> log("unknown profile_op: $which")
        }
    }

    private fun sendAllProfiles() {
        // viewer.js:40683 (got_all_profiles) expects the FLAT {name: settings}
        // map and adds the __default__ entry itself — wrapping it like the
        // old code did made the JS panel render a bogus "profiles" item.
        bridge?.profileResponse("all-profiles", JsonObject(profileStore.list()))
    }

    private fun applyProfile(name: String, data: JsonObject? = null) {
        // The page may either name an existing profile (data == null → look it
        // up) or pass the dict inline (data != null). Viewer.js always passes
        // a dict, but the host-only profileApply path uses the stored form.
        val settings = data ?: profileStore.get(name) ?: return
        // Route through setSessionData so font-size changes trigger the
        // engine re-apply + viewer_font_size_changed signal exactly the
        // same way as a normal set_session_data signal would.
        for ((k, v) in settings) {
            setSessionData(JsonPrimitive(k), v)
        }
        // Tell the page to re-render with the new settings.
        bridge?.profileResponse("apply-profile", settings)
    }

    // ---- M2: panel row clicks (host → JS) -----------------------------------

    /** BookmarksPanel row tap → navigate via goto_cfi. */
    fun bookmarkClick(pos: String) {
        _state.value = _state.value.copy(bookmarksVisible = false)
        bridge?.executeWhenReady("goto_cfi", JsonPrimitive(pos), JsonPrimitive(true))
    }

    /**
     * Compose "Bookmark this page" button → asks the page for the current
     * CFI via get_current_cfi; report_cfi replies through onSignal. We
     * tag the request with an id so concurrent calls don't cross.
     */
    fun bookmarkThisPage() {
        val id = java.util.UUID.randomUUID().toString()
        pendingCfiRequests[id] = { cfi -> persistBookmarkAt(cfi) }
        bridge?.getCurrentCfi(id)
    }

    override fun reportCfi(requestId: String, data: JsonObject) {
        if (requestId.isEmpty()) return
        val handler = pendingCfiRequests.remove(requestId) ?: return
        val cfi = data.stringPrimitive("cfi") ?: return
        if (cfi.isNotEmpty()) handler(cfi)
    }

    private val pendingCfiRequests = HashMap<String, (String) -> Unit>()

    private fun persistBookmarkAt(cfi: String) {
        val hash = _state.value.currentBookHash ?: return
        val title = nextBookmarkTitle()
        val record = BookmarkRecord(
            timestamp = java.time.Instant.now().toString(),
            pos = cfi,
            title = title,
        )
        val updated = annotationsStore.loadBookmarks(hash) + record
        annotationsStore.save(hash, updated, annotationsStore.loadHighlights(hash))
        _state.value = _state.value.copy(bookmarks = updated)
    }

    /**
     * HighlightsPanel row tap → use highlight_action(uuid, "goto") — the
     * JS AnnotationsManager already knows the highlight's CFIs.
     */
    fun highlightClick(uuid: String) {
        _state.value = _state.value.copy(highlightsVisible = false)
        bridge?.highlightAction(uuid, "goto")
    }

    /** ProfilesPanel "save current as profile" → persist, no JS round-trip. */
    fun profileSave(name: String) {
        if (name.isBlank()) return
        // Snapshot the live session into a profile dict. We use the same
        // session keys the JS settings_for_reader_profile would capture —
        // for now we record all of sessionData verbatim (the user can
        // trim later).
        profileStore.save(name, sessionStore.sessionDataJson)
        _state.value = _state.value.copy(profiles = profileStore.list(), profileDraftName = "")
    }

    fun profileApply(name: String) = applyProfile(name)

    /**
     * ProfilesPanel "恢复默认设置" — profile_response("apply-profile", {})
     * is the JS-side "Restore settings to default values" path
     * (viewer.js:49337): apply_reader_profile with an empty dict resets
     * every read_book-category setting to its default, re-renders the page
     * and reports the (now default) values back through set_session_data,
     * which the host session store already persists.
     */
    fun profileResetDefaults() {
        _state.value = _state.value.copy(profilesVisible = false)
        bridge?.profileResponse("apply-profile", JsonObject(emptyMap()))
    }

    fun profileDelete(name: String) {
        profileStore.delete(name)
        _state.value = _state.value.copy(profiles = profileStore.list())
    }

    fun setProfileDraftName(name: String) {
        _state.value = _state.value.copy(profileDraftName = name)
    }

    /** Close any of the three native panels (toolbar / back key / outside tap). */
    fun dismissPanels() {
        val s = _state.value
        if (s.bookmarksVisible || s.highlightsVisible || s.profilesVisible) {
            _state.value = s.copy(
                bookmarksVisible = false,
                highlightsVisible = false,
                profilesVisible = false,
            )
        }
    }

    // ---- M2: reading statistics ---------------------------------------------

    /** update_reading_rates — persist the page's {rates, chapter_times}. */
    override fun updateReadingRates(payload: JsonObject) {
        val hash = current?.bookHash ?: return
        ratesStore.update(hash, payload)
    }

    /** reset_reading_rates — the stats overlay's reset; clear the stored set. */
    override fun resetReadingRates() {
        val hash = current?.bookHash ?: return
        ratesStore.reset(hash)
    }

    // ---- helpers ------------------------------------------------------------

    /**
     * Gate the desktop controls-help (welcome) overlay off for good: the
     * page shows it while controls_help_shown_count < 2 — with an
     * _rtl_page_progression variant key (viewer.js display_book). Seed 2
     * for both keys when the user has no stored value; a plain JSON number,
     * the exact shape the page itself stores, so SessionData's per-key
     * merge carries it. Not persisted here — re-merged per create_view,
     * idempotent.
     */
    private fun seededSessionData(): JsonObject {
        val current = sessionStore.sessionDataJson
        if (CONTROLS_HELP_KEY in current && CONTROLS_HELP_RTL_KEY in current) return current
        return JsonObject(
            current + mapOf(
                CONTROLS_HELP_KEY to JsonPrimitive(2),
                CONTROLS_HELP_RTL_KEY to JsonPrimitive(2),
            )
        )
    }

    private fun flushPosition() {
        val pos = pendingPosition ?: return
        pendingPosition = null
        lastReadStore.update(pos.bookHash, pos.cfi, pos.posFrac, pos.originKey)
    }

    private fun sendBookLoad(
        book: BookOpener.OpenedBook,
        saved: LastReadStore.LastReadPosition?,
        highlights: List<HighlightRecord> = emptyList(),
    ) {
        awaitingBookDisplay = true
        bridge?.startBookLoad(
            renderedBookDir = book.bookDir.path,
            initialPosition = saved?.cfi?.let { cfiToPosition(it) },
            // The origin key (SAF URI), not the staged path: viewer.js only
            // uses pathtoebook as an opaque recents key, so one entry per
            // source book accumulates and ask_for_open can reopen it.
            pathtoebook = book.originKey,
            highlights = buildJsonArray {
                for (h in highlights) add(highlightToForJs(h))
            },
            // Timers.start_book copies these back in-memory so the reading
            // statistics overlay survives restarts (used to be "Calculating…").
            readingRates = ratesStore.get(book.bookHash),
        )
    }

    /** host highlight record → JSON shape viewer.js expects in start_book_load's
     *  `highlights` arg (mirror of what the JS AnnotationsManager builds:
     *  viewer.js:15815-15842, minus "type":"highlight" which JS adds). */
    private fun highlightToForJs(h: HighlightRecord): JsonObject = buildJsonObject {
        put("uuid", h.uuid)
        put("timestamp", h.timestamp)
        put("highlighted_text", h.highlightedText)
        put("start_cfi", h.startCfi)
        put("end_cfi", h.endCfi)
        put("style", h.style)
        put("spine_name", h.spineName)
        put("spine_index", h.spineIndex)
        h.notes?.let { put("notes", it) }
        if (h.tocFamilyTitles.isNotEmpty()) put("toc_family_titles", JsonArray(h.tocFamilyTitles.map { JsonPrimitive(it) }))
        if (h.removed) put("removed", true)
    }

    private fun cfiToPosition(cfi: String): JsonObject = buildJsonObject {
        put("type", "cfi")
        put("data", cfi)
    }

    private fun friendlyError(e: Throwable): String = when (e) {
        is InvalidBookException -> e.message ?: "Invalid book"
        is ZipException -> "不是有效的 EPUB 文件（无法解压）"
        is FileNotFoundException -> "找不到或无法读取书籍文件，请通过应用内“选择书籍”重新选择"
        is SecurityException -> "无权读取所选文件，请通过应用内“选择书籍”重新授权"
        else -> "打开书籍失败：${e.message ?: e.javaClass.simpleName}"
    }

    private companion object {
        const val LAST_READ_DEBOUNCE_MS = 2_000L

        /** Render-cache housekeeping: 30 days untouched, 256MB ceiling. */
        const val RENDER_CACHE_AGE_MS = 30L * 24 * 60 * 60 * 1000
        const val RENDER_CACHE_MAX_BYTES = 256L * 1024 * 1024

        /** Session keys gating the desktop welcome overlay (viewer.js). */
        const val CONTROLS_HELP_KEY = "controls_help_shown_count"
        const val CONTROLS_HELP_RTL_KEY = "controls_help_shown_count_rtl_page_progression"
    }
}

// ---- JSON helpers (local to this file — small enough to inline) ----------
private fun JsonObject.stringPrimitive(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.intPrimitive(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.booleanPrimitive(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.contentOrNull == "true"
