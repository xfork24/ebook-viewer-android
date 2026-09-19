// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Single source of truth for native panel sizing. Bump SCALE to enlarge every
// text / icon / padding / row dimension across the Compose overlays
// (ViewerScreen + Toc/Search/Bookmarks/Highlights/Profiles panels + Error
// overlay). Keep the JS-side viewer.js toolbar at 1× (it has its own flex
// wrap + per-panel scaling); only the host-drawn chrome obeys this scale.

package dev.calibre.viewer.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Multiplier for every native panel dimension. */
internal const val UI_SCALE: Float = 1.5f

/** Multiply any Dp literal by [UI_SCALE]; preserves the value when SCALE=1. */
internal val Dp.scaled: Dp get() = (this.value * UI_SCALE).dp

/** Multiply any TextUnit (sp) literal by [UI_SCALE]. */
internal val TextUnit.scaled: TextUnit get() = (this.value * UI_SCALE).sp