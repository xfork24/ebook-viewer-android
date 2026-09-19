// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// The Compose layer draws ONLY the plain monochrome overlays (preparing /
// loading / error). Everything else — home page, TOC, bookmarks, preferences,
// goto, selection bars — is drawn by viewer.js inside the WebView, which
// lives in the classic view hierarchy underneath this ComposeView (see
// MainActivity: hosting the WebView inside Compose's AndroidView wedges
// Blink's CSS viewport height to 0 — vh units, height media queries and the
// root percentage containing block all collapse, leaving the page
// unpaintable-but-visible and untouchable). The ComposeView is kept GONE
// while no overlay is composed so it never intercepts reading touches.
// E-ink style: flat white background, black text, no elevation.

package dev.calibre.viewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.calibre.viewer.host.SearchMode
import dev.calibre.viewer.host.ViewerUiState
import dev.calibre.viewer.ui.scaled

@Composable
fun ViewerScreen(
    state: ViewerUiState,
    onDismissError: () -> Unit,
    onTocNodeClick: (Long) -> Unit = {},
    onDismissToc: () -> Unit = {},
    onSearch: (text: String, mode: SearchMode, caseSensitive: Boolean) -> Unit = { _, _, _ -> },
    onSearchStep: (backwards: Boolean) -> Unit = {},
    onSearchResultClick: (resultNum: Long) -> Unit = {},
    onDismissSearch: () -> Unit = {},
    onBookmarkClick: (String) -> Unit = {},
    onHighlightClick: (String) -> Unit = {},
    onSaveProfile: (String) -> Unit = {},
    onApplyProfile: (String) -> Unit = {},
    onDeleteProfile: (String) -> Unit = {},
    onResetProfileDefaults: () -> Unit = {},
    onProfileDraftChange: (String) -> Unit = {},
    onDismissPanels: () -> Unit = {},
) {
    if (state.tocVisible) {
        TocPanel(
            rows = state.tocRows,
            viewedNodes = state.viewedTocNodes,
            onNodeClick = onTocNodeClick,
            onDismiss = onDismissToc,
        )
    }
    if (state.searchVisible) {
        SearchPanel(
            prefill = state.searchPrefill,
            results = state.searchResults,
            searching = state.searching,
            searchError = state.searchError,
            currentResultNum = state.currentResultNum,
            onSearch = onSearch,
            onStep = onSearchStep,
            onResultClick = onSearchResultClick,
            onDismiss = onDismissSearch,
        )
    }
    if (state.bookmarksVisible) {
        BookmarksPanel(
            bookmarks = state.bookmarks,
            onBookmarkClick = onBookmarkClick,
            onDismiss = onDismissPanels,
        )
    }
    if (state.highlightsVisible) {
        HighlightsPanel(
            highlights = state.highlights,
            onHighlightClick = onHighlightClick,
            onDismiss = onDismissPanels,
        )
    }
    if (state.profilesVisible) {
        ProfilesPanel(
            profiles = state.profiles,
            draftName = state.profileDraftName,
            onDraftNameChange = onProfileDraftChange,
            onSave = onSaveProfile,
            onApply = onApplyProfile,
            onDelete = onDeleteProfile,
            onResetDefaults = onResetProfileDefaults,
            onDismiss = onDismissPanels,
        )
    }
    if (state.preparing) {
        OverlayLabel(AnnotatedString("正在准备书籍…"))
    }
    state.loadingMessage?.let { message ->
        // Qt rich text from the viewer (e.g. "…from <i>title</i>, please wait…").
        OverlayLabel(richTextOf(message))
    }
    state.errorMessage?.let { message ->
        ErrorOverlay(message = message, onDismiss = onDismissError)
    }
}

@Composable
private fun OverlayLabel(text: AnnotatedString) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.Black,
            fontSize = 16.sp.scaled,
        )
    }
}

@Composable
private fun ErrorOverlay(message: String, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(24.dp.scaled),
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            verticalArrangement = Arrangement.spacedBy(16.dp.scaled),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = richTextOf(message),
                color = Color.Black,
                fontSize = 14.sp.scaled,
                lineHeight = 20.sp.scaled,
            )
            Row {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Black,
                        contentColor = Color.White,
                    ),
                ) {
                    Text("关闭")
                }
            }
        }
    }
}
