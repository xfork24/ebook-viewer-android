// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Native shell UI state. The reading experience itself (home page, panels,
// menus) lives inside viewer.js — these fields only drive the thin native
// overlays on top of the WebView.

package dev.calibre.viewer.host

import dev.calibre.viewer.core.annot.BookmarkRecord
import dev.calibre.viewer.core.annot.HighlightRecord
import kotlinx.serialization.json.JsonObject

data class ViewerUiState(
    /** Book pipeline (extract → transform → render) in progress. */
    val preparing: Boolean = false,
    /** show_loading_message signal payload; null/empty hides the overlay. */
    val loadingMessage: String? = null,
    /** show_error signal payload or a native pipeline failure. */
    val errorMessage: String? = null,
    /** The first spine item of the current book finished rendering. */
    val bookOpen: Boolean = false,
    /** Native TOC panel — the standalone viewer's TOC is host-side. */
    val tocVisible: Boolean = false,
    /** manifest toc as flat rows; empty until a book is open. */
    val tocRows: List<TocRow> = emptyList(),
    /** Node ids on screen (leaves + ancestors), from update_current_toc_nodes. */
    val viewedTocNodes: Set<Long> = emptySet(),
    /** Native search panel — standalone search is host-side too. */
    val searchVisible: Boolean = false,
    /** Query text the panel should start with (selection text passed by the page). */
    val searchPrefill: String = "",
    /** Results of the current/last search, in book order. */
    val searchResults: List<SearchResult> = emptyList(),
    /** A search is running (results still streaming in). */
    val searching: Boolean = false,
    /** Why the last search failed (Throwable escaped the pipeline); null when fine. */
    val searchError: String? = null,
    /** result_num of the selection, from discovery or a panel click. */
    val currentResultNum: Long? = null,
    /** The spine file currently displayed (content_file_changed payload). */
    val currentName: String? = null,

    // ---- M2: Highlights / Bookmarks / Profiles --------------------

    /** BookmarksPanel visibility. */
    val bookmarksVisible: Boolean = false,
    /** HighlightsPanel visibility. */
    val highlightsVisible: Boolean = false,
    /** ProfilesPanel visibility. */
    val profilesVisible: Boolean = false,

    /** Per-book bookmark records, loaded from AnnotationsStore on openBook. */
    val bookmarks: List<BookmarkRecord> = emptyList(),
    /** Per-book highlight records, loaded from AnnotationsStore on openBook. */
    val highlights: List<HighlightRecord> = emptyList(),

    /** Saved profiles (global, not per-book). */
    val profiles: Map<String, JsonObject> = emptyMap(),
    /** In-flight input field value for the profiles panel's "save as" row. */
    val profileDraftName: String = "",

    /** The book hash whose annotations are loaded into [bookmarks] / [highlights]. */
    val currentBookHash: String? = null,
)
