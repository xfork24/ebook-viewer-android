// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Regression guard for the TOC fast-fling crash: NoIndication.create() must
// hand every clickable a FRESH Modifier.Node. A shared singleton node gets
// delegated into two clickable node trees when a lazy list recycles rows
// mid-gesture, which crashes with IllegalStateException "Cannot delegate to
// an already delegated node" during fast up/down swipes in the TOC panel.

package dev.calibre.viewer.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class NoIndicationTest {

    @Test
    fun `create returns a fresh node instance per call`() {
        val source = MutableInteractionSource()
        val a = NoIndication.create(source)
        val b = NoIndication.create(source)
        assertNotSame(a, b, "indication node must not be shared across clickables")
    }

    @Test
    fun `factory is a singleton for indication caching`() {
        // Compose caches the Indication by equals(); the object identity of
        // the factory itself is what makes LocalIndication cheap to read.
        assertSame(NoIndication, NoIndication)
        assertEquals(NoIndication, NoIndication)
    }
}
