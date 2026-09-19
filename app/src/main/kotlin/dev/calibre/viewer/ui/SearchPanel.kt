// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// The native search panel — the Android equivalent of the Qt viewer's
// search_dock. In standalone mode the toolbar's search button hands off to
// the host (show_search signal), the host runs gui2/viewer/search.py-style
// matching over the rendered JSON and streams results to the page via
// show_search_result. E-ink style: flat white, black 1dp border, no
// elevation or motion; the current match renders bold.

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.calibre.viewer.host.SearchMode
import dev.calibre.viewer.host.SearchResult
import dev.calibre.viewer.ui.scaled

private val MODE_LABELS = linkedMapOf(
    SearchMode.NORMAL to "包含",
    SearchMode.WORD to "全字匹配",
    SearchMode.NEAR to "邻近词",
    SearchMode.REGEX to "正则",
)

@Composable
fun SearchPanel(
    prefill: String,
    results: List<SearchResult>,
    searching: Boolean,
    searchError: String?,
    currentResultNum: Long?,
    onSearch: (text: String, mode: SearchMode, caseSensitive: Boolean) -> Unit,
    onStep: (backwards: Boolean) -> Unit,
    onResultClick: (resultNum: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember(prefill) { mutableStateOf(prefill) }
    var mode by remember { mutableStateOf(SearchMode.NORMAL) }
    var caseSensitive by remember { mutableStateOf(false) }
    var modeMenuOpen by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // The panel absorbs all taps outside the sidebar area.
                // Inside the sidebar, the inner Column's pointerInput wins
                // first (children are dispatched before parents), so taps
                // on the search button / results never reach this handler.
                detectTapGestures { onDismiss() }
            },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(2f / 3f)
                .background(Color.White)
                .border(1.dp.scaled, Color.Black)
                // Consume taps on the panel body so the outer Box's
                // dismiss-on-blank-tap never fires from inside the panel;
                // children with their own click handlers still receive events.
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp.scaled, vertical = 10.dp.scaled),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when {
                        searching -> "搜索中…"
                        results.isEmpty() -> "搜索"
                        else -> "${results.size} 个结果"
                    },
                    fontSize = 16.sp.scaled,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "✕",
                    fontSize = 18.sp.scaled,
                    modifier = Modifier
                        .padding(start = 12.dp.scaled)
                        .clickable { onDismiss() },
                )
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp.scaled, color = Color.Black),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp.scaled),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp.scaled, vertical = 6.dp.scaled),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    Text(
                        MODE_LABELS.getValue(mode),
                        fontSize = 13.sp.scaled,
                        modifier = Modifier
                            .clickable { modeMenuOpen = true }
                            .padding(vertical = 6.dp.scaled),
                    )
                    // Plain Popup instead of M3 DropdownMenu: white box,
                    // black border, zero expand animation.
                    if (modeMenuOpen) {
                        Popup(
                            onDismissRequest = { modeMenuOpen = false },
                            properties = PopupProperties(focusable = true),
                        ) {
                            Column(
                                modifier = Modifier
                                    .background(Color.White)
                                    .border(1.dp.scaled, Color.Black)
                                    .padding(vertical = 4.dp.scaled),
                            ) {
                                MODE_LABELS.forEach { (m, label) ->
                                    Text(
                                        label,
                                        fontSize = 14.sp.scaled,
                                        modifier = Modifier
                                            .clickable {
                                                mode = m
                                                modeMenuOpen = false
                                            }
                                            .padding(
                                                horizontal = 12.dp.scaled,
                                                vertical = 8.dp.scaled,
                                            ),
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    "区分大小写",
                    fontSize = 13.sp.scaled,
                    modifier = Modifier.padding(start = 4.dp.scaled),
                )
                EinkCheckbox(
                    checked = caseSensitive,
                    onToggle = { caseSensitive = it },
                )
                Box(modifier = Modifier.weight(1f))
                Text(
                    "‹",
                    fontSize = 20.sp.scaled,
                    modifier = Modifier
                        .clickable(enabled = results.isNotEmpty()) { onStep(true) }
                        .padding(horizontal = 6.dp.scaled),
                )
                Text(
                    "›",
                    fontSize = 20.sp.scaled,
                    modifier = Modifier
                        .clickable(enabled = results.isNotEmpty()) { onStep(false) }
                        .padding(horizontal = 6.dp.scaled),
                )
            }

            TextButton(
                onClick = { onSearch(query, mode, caseSensitive) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp.scaled),
            ) {
                Text("搜索", color = Color.Black, fontSize = 15.sp.scaled)
            }

            if (results.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            searching -> "搜索中…"
                            searchError != null -> searchError
                            query.isBlank() -> "输入要搜索的内容"
                            else -> "无匹配结果"
                        },
                        color = Color.Black,
                        fontSize = 14.sp.scaled,
                    )
                }
            } else {
                val listState = rememberLazyListState()
                // keep the selection visible as discovery/clicks move it
                val currentIdx = results.indexOfFirst { it.resultNum == currentResultNum }
                LaunchedEffect(currentIdx, results.size) {
                    // Instant jump: animated scrolling is a smear on e-ink.
                    if (currentIdx >= 0) listState.scrollToItem(currentIdx)
                }
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(results, key = { it.resultNum }) { result ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onResultClick(result.resultNum) }
                                .padding(horizontal = 12.dp.scaled, vertical = 6.dp.scaled),
                        ) {
                            val isCurrent = result.resultNum == currentResultNum
                            if (isCurrent) {
                                Text(
                                    result.tocTitles.lastOrNull() ?: result.fileName,
                                    fontSize = 11.sp.scaled,
                                    color = Color.DarkGray,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                snippet(result),
                                fontSize = 14.sp.scaled,
                                color = if (result.isHidden) Color.Gray else Color.Black,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (isCurrent) FontWeight.Bold else null,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "…before <b>match</b> after…" — the Qt Results delegate's preview line. */
private fun snippet(result: SearchResult): AnnotatedString = buildAnnotatedString {
    val before = result.before.takeLast(30)
    val after = result.after.take(30)
    if (before.length == 30) append("…")
    append(before)
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(result.text) }
    append(after)
    if (after.length == 30) append("…")
}

/**
 * Static checkbox: black-bordered square with a black inner square when
 * checked. The M3 Checkbox animates check/fill state — on e-ink that is a
 * multi-frame gray flicker for a binary state.
 */
@Composable
private fun EinkCheckbox(checked: Boolean, onToggle: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .size(18.dp.scaled)
            .border(1.dp.scaled, Color.Black)
            .clickable { onToggle(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Box(Modifier.size(10.dp.scaled).background(Color.Black))
        }
    }
}
