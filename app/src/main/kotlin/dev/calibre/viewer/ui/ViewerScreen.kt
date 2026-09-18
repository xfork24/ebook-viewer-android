// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// The single Compose screen: a full-bleed WebView with plain monochrome
// overlays. Everything else (home page, TOC, bookmarks, preferences, goto,
// selection bars) is drawn by viewer.js inside the WebView — the shell stays
// out of the way. E-ink style: flat white background, black text, no
// elevation, no animated indicators.

package dev.calibre.viewer.ui

import android.content.Context
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.calibre.viewer.host.ViewerUiState

@Composable
fun ViewerScreen(
    state: ViewerUiState,
    createWebView: (Context) -> WebView,
    onDismissError: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
    ) {
        AndroidView(
            factory = { context -> createWebView(context) },
            modifier = Modifier.fillMaxSize(),
        )
        if (state.preparing) {
            OverlayLabel("正在准备书籍…")
        }
        state.loadingMessage?.let { message ->
            OverlayLabel(message)
        }
        state.errorMessage?.let { message ->
            ErrorOverlay(message = message, onDismiss = onDismissError)
        }
    }
}

@Composable
private fun OverlayLabel(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.Black,
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun ErrorOverlay(message: String, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(24.dp),
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = message,
                color = Color.Black,
                fontSize = 14.sp,
                lineHeight = 20.sp,
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
