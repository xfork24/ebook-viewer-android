// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors

package dev.calibre.viewer.serve

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViewerAssetsLayoutTest {

    private fun asset(name: String): File =
        sequenceOf(
            File("src/main/assets/viewer/$name"),
            File("app/src/main/assets/viewer/$name"),
        ).firstOrNull { it.isFile }
            ?: error("asset not found: $name (from ${File(".").absolutePath})")

    @Test
    fun `secondary menu cards stay top aligned while row contents are centered`() {
        val js = asset("viewer.js").readText()

        // Overlay.update_visibility owns this block/none state. A stylesheet
        // display declaration with !important leaves the open-book screen in
        // front after a book has finished loading.
        assertFalse(
            js.contains("#book-overlay{display:flex !important"),
            "book overlay visibility must not be overridden by CSS",
        )
        assertTrue(js.contains("self.container.style.display = \"none\";"))

        // Only the Go-to and Preferences title/subtitle lists receive these
        // hooks; the open-book/recent-books layout must keep upstream sizing.
        assertTrue(js.contains("panel.classList.add(\"android-aligned-secondary-list\");"))
        assertTrue(js.contains("c.classList.add(\"android-aligned-secondary-list\");"))
        assertTrue(js.contains(".android-aligned-secondary-list .item-first-row{align-items:center !important;}"))
        assertTrue(js.contains(".android-aligned-secondary-list .item-subtitle{padding-top:0 !important;}"))
        assertFalse(js.contains("android-centered-secondary-menu"))
        assertFalse(js.contains(".android-aligned-secondary-list>ul{margin-top:auto"))
    }
}
