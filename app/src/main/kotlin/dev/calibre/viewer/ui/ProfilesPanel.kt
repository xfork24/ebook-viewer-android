// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Native profiles panel — save / apply / delete reading-mode presets.
// The host is the source of truth (ProfileStore); this UI is the local
// mirror. JS can also save/apply via profile_op, but those paths also
// hit the same ProfileStore and the host updates state.profiles.

package dev.calibre.viewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import dev.calibre.viewer.ui.scaled

@Composable
fun ProfilesPanel(
    profiles: Map<String, JsonObject>,
    draftName: String,
    onDraftNameChange: (String) -> Unit,
    onSave: (String) -> Unit,
    onApply: (String) -> Unit,
    onDelete: (String) -> Unit,
    onResetDefaults: () -> Unit,
    onDismiss: () -> Unit,
) {
    var local by remember(draftName) { mutableStateOf(draftName) }

    // Two-step delete: the first tap arms ("确认?"), the second commits.
    // Resets after a pause so a stray tap can't stay armed forever.
    var armedDelete by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(armedDelete) {
        if (armedDelete != null) {
            delay(3_000)
            armedDelete = null
        }
    }

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
                Text("配置档案", color = Color.Black, fontSize = 18.sp.scaled, modifier = Modifier.weight(1f))
                Text(
                    "✕",
                    color = Color.Black,
                    fontSize = 18.sp.scaled,
                    modifier = Modifier
                        .padding(start = 12.dp.scaled)
                        .clickable { onDismiss() },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp.scaled, vertical = 6.dp.scaled),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = local,
                    onValueChange = {
                        local = it
                        onDraftNameChange(it)
                    },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp.scaled, color = Color.Black),
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { onSave(local) },
                    enabled = local.isNotBlank(),
                ) { Text("保存当前", color = Color.Black, fontSize = 14.sp.scaled) }
            }

            if (profiles.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("暂无配置档案", color = Color.Black, fontSize = 14.sp.scaled)
                }
            } else {
                val itemsList = profiles.entries.toList()
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(itemsList, key = { it.key }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp.scaled, vertical = 6.dp.scaled),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = entry.key,
                                color = Color.Black,
                                fontSize = 15.sp.scaled,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(onClick = { onApply(entry.key) }) {
                                Text("应用", color = Color.Black, fontSize = 14.sp.scaled)
                            }
                            TextButton(onClick = {
                                if (armedDelete == entry.key) {
                                    armedDelete = null
                                    onDelete(entry.key)
                                } else {
                                    armedDelete = entry.key
                                }
                            }) {
                                Text(
                                    if (armedDelete == entry.key) "确认?" else "删除",
                                    color = Color.Black,
                                    // The armed (confirm) state distinguishes itself by
                                    // weight, not color — pure grayscale panel.
                                    fontWeight = if (armedDelete == entry.key) FontWeight.Bold else null,
                                    fontSize = 14.sp.scaled,
                                )
                            }
                        }
                    }
                }
            }

            // Mirrors the JS panel's "Restore settings to default values" —
            // profileResetDefaults() → profile_response("apply-profile", {})
            // resets every reader setting to its default.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp.scaled, vertical = 6.dp.scaled),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onResetDefaults) {
                    Text("恢复默认设置", color = Color.Black, fontSize = 14.sp.scaled)
                }
            }
        }
    }
}
