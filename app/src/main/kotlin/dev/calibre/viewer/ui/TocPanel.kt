// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// The native Table of Contents panel — the Android equivalent of the Qt
// viewer's toc_dock. In standalone mode the toolbar's TOC button hands off
// to the host (toggle_toc signal), so this panel is the only TOC UI there
// is. E-ink style: flat white, black 1dp border, no elevation or motion;
// currently-viewed entries render bold (the Qt dock uses bold+italic).

package dev.calibre.viewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.calibre.viewer.host.TocRow
import dev.calibre.viewer.ui.scaled

@Composable
fun TocPanel(
    rows: List<TocRow>,
    viewedNodes: Set<Long>,
    onNodeClick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    // Outside-tap closes the panel; the panel itself consumes taps so its
    // own whitespace doesn't fall through.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(2f / 3f)
                .background(Color.White)
                .border(1.dp.scaled, Color.Black)
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp.scaled, vertical = 10.dp.scaled),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Table of Contents", fontSize = 18.sp.scaled, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    "✕",
                    fontSize = 18.sp.scaled,
                    modifier = Modifier
                        .padding(start = 12.dp.scaled)
                        .clickable { onDismiss() },
                )
            }

            if (rows.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("This book has no table of contents", color = Color.Black, fontSize = 14.sp.scaled)
                }
            } else {
                val listState = rememberLazyListState()
                // toggle_toc → scroll_to_current_toc_node: land on the entry
                // nearest the current reading position when opening.
                LaunchedEffect(Unit) {
                    val current = rows.indexOfLast { it.id in viewedNodes }
                    if (current > 0) listState.scrollToItem(current)
                }
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(rows, key = { it.id }) { row ->
                        Text(
                            text = row.title.ifEmpty { "Untitled" },
                            color = Color.Black,
                            fontSize = 15.sp.scaled,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (row.id in viewedNodes) FontWeight.Bold else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNodeClick(row.id) }
                                .padding(
                                    start = ((12 + 16 * row.depth).dp).scaled,
                                    end = 12.dp.scaled,
                                    top = 8.dp.scaled,
                                    bottom = 8.dp.scaled,
                                ),
                        )
                    }
                }
            }
        }
    }
}
