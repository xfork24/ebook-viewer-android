// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Native highlights panel — e-ink style, mirrors TocPanel/SearchPanel.

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.calibre.viewer.core.annot.HighlightRecord
import dev.calibre.viewer.ui.scaled

@Composable
fun HighlightsPanel(
    highlights: List<HighlightRecord>,
    onHighlightClick: (String) -> Unit,
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
                Text("Highlights", color = Color.Black, fontSize = 18.sp.scaled, modifier = Modifier.weight(1f))
                Text(
                    "✕",
                    color = Color.Black,
                    fontSize = 18.sp.scaled,
                    modifier = Modifier
                        .padding(start = 12.dp.scaled)
                        .clickable { onDismiss() },
                )
            }
            if (highlights.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No highlights yet — long-press text to create one", color = Color.Black, fontSize = 14.sp.scaled)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(highlights, key = { it.uuid }) { record ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onHighlightClick(record.uuid) }
                                .padding(horizontal = 12.dp.scaled, vertical = 8.dp.scaled),
                        ) {
                            if (record.tocFamilyTitles.isNotEmpty()) {
                                Text(
                                    text = record.tocFamilyTitles.last(),
                                    color = Color.DarkGray,
                                    fontSize = 11.sp.scaled,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = record.highlightedText.ifEmpty { "(empty)" },
                                color = if (record.removed) Color.Gray else Color.Black,
                                fontSize = 14.sp.scaled,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            record.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                                Text(
                                    text = notes,
                                    color = Color.DarkGray,
                                    fontSize = 12.sp.scaled,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
