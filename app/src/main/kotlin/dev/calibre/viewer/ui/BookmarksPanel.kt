// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Native bookmarks panel — e-ink style, mirrors TocPanel/SearchPanel.

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.calibre.viewer.core.annot.BookmarkRecord
import dev.calibre.viewer.ui.scaled

@Composable
fun BookmarksPanel(
    bookmarks: List<BookmarkRecord>,
    onBookmarkClick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
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
                Text("书签", fontSize = 18.sp.scaled, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    "✕",
                    fontSize = 18.sp.scaled,
                    modifier = Modifier
                        .padding(start = 12.dp.scaled)
                        .clickable { onDismiss() },
                )
            }
            if (bookmarks.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("暂无书签,用顶部菜单的 Bookmark this page 添加", color = Color.Black, fontSize = 14.sp.scaled)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // Key by identity (timestamp+pos), not title — duplicate
                    // titles must not crash the LazyColumn.
                    items(bookmarks, key = { it.timestamp + "/" + it.pos }) { record ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onBookmarkClick(record.pos) }
                                .padding(horizontal = 12.dp.scaled, vertical = 8.dp.scaled),
                        ) {
                            Text(
                                text = record.title.ifEmpty { "(未命名)" },
                                color = if (record.removed) Color.Gray else Color.Black,
                                fontSize = 15.sp.scaled,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = record.pos.takeLast(24),
                                color = Color.DarkGray,
                                fontSize = 10.sp.scaled,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
