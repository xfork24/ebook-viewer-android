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
    /** view_created received — a View exists in the page. */
    val bookOpen: Boolean = false,
)
