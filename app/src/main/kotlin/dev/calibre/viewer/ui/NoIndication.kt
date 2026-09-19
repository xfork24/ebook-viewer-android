// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// A no-op Indication: ripple (and any pressed/hovered feedback) renders
// nothing. Provided as LocalIndication at the activity root so every
// clickable/Button across the six native panels picks it up in one place.
// E-ink panels repaint dirty regions — a ripple is a slow gray smear there.

package dev.calibre.viewer.ui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.ui.Modifier

object NoIndication : IndicationNodeFactory {

    // An empty node: no attach work, no observation, nothing to invalidate.
    override fun create(interactionSource: InteractionSource): Modifier.Node =
        NoIndicationNode

    override fun equals(other: Any?): Boolean = other === NoIndication
    override fun hashCode(): Int = System.identityHashCode(this)

    private object NoIndicationNode : Modifier.Node()
}
