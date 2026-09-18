// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Native shell UI state. The reading experience itself (home page, panels,
// menus) lives inside viewer.js — these fields only drive the thin native
// overlays on top of the WebView.

package dev.calibre.viewer.host

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
    /** result_num of the selection, from discovery or a panel click. */
    val currentResultNum: Long? = null,
    /** The spine file currently displayed (content_file_changed payload). */
    val currentName: String? = null,
)
