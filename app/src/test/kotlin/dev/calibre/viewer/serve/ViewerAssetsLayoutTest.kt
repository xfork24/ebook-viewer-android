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

    @Test
    fun `secondary overlays dismiss only when the real backdrop is clicked`() {
        val js = asset("viewer.js").readText()

        val clickHandler = js.substringAfter(
            "Overlay.prototype.container_clicked = function container_clicked(evt)",
        ).substringBefore("Overlay.prototype.show_loading_message")
        assertTrue(
            clickHandler.contains("if (evt && evt.target !== self.container)"),
            "overlay clicks from child content must return before dismissal",
        )
        assertFalse(
            js.contains("evt.target.closest(\"form,table,input"),
            "dismissal must not depend on an incomplete interactive-element whitelist",
        )

        for (panel in listOf("GotoOverlay", "SimpleOverlay", "PrefsOverlay", "ReadingStatsOverlay")) {
            val handler = js.substringAfter(
                "$panel.prototype.on_container_click = function on_container_click(evt)",
            ).substringBefore("if (!$panel.prototype.on_container_click.__argnames__)")
            assertTrue(
                handler.contains("self.overlay.hide_current_panel();"),
                "$panel must dismiss after the shared backdrop boundary accepts a click",
            )
        }

        val openBookHandler = js.substringAfter(
            "OpenBook.prototype.on_container_click = function on_container_click(evt)",
        ).substringBefore("if (!OpenBook.prototype.on_container_click.__argnames__)")
        assertTrue(openBookHandler.contains("if (!self.closeable)"))
        assertTrue(openBookHandler.contains("self.overlay.hide_current_panel();"))
    }

    @Test
    fun `index html preloads viewer js so cold start can fetch in parallel with head parsing`() {
        // The 2.7 MiB viewer.js has to download + parse before the page is
        // interactive; without a preload hint the HTML parser only starts
        // fetching it when it hits <script src="/viewer.js"> deep inside
        // the body, after every style/sprite/reset tag in <head>. The
        // <link rel="preload"> tag lets Chromium start the network round
        // trip in parallel with the rest of <head>, saving ~100-200 ms on
        // cold start (and proportionally more on slow networks).
        val html = asset("index.html").readText()
        // Must be present in <head> (not just anywhere in the file), before
        // the actual <script src> tag — preload has to fire during head
        // parsing.
        val headEnd = html.indexOf("</head>")
        assertTrue(headEnd > 0, "index.html must have a </head>")
        val head = html.substring(0, headEnd)
        assertTrue(
            head.contains("""<link rel="preload" as="script" href="/viewer.js">"""),
            "index.html <head> must preload viewer.js; head was:\n$head",
        )
        // The preload must NOT carry crossorigin: the classic <script> below
        // fetches with same-origin credentials, and an "anonymous" preload
        // does not match that credentials mode — Chromium then discards the
        // preload and re-fetches (observed on WebView 113 as "request
        // credentials mode does not match" plus a double fetch, making the
        // hint a pure pessimization).
        assertFalse(
            head.contains("""href="/viewer.js" crossorigin"""),
            "preload must not set crossorigin (credentials-mode mismatch)",
        )
        // And the preload must precede the <script src="/viewer.js"> tag.
        val preloadAt = head.indexOf("rel=\"preload\"")
        val scriptAt = html.indexOf("""<script src="/viewer.js">""")
        assertTrue(preloadAt > 0 && scriptAt > 0, "both preload and script tags must exist")
        assertTrue(preloadAt < scriptAt, "preload tag must come before the script tag")
    }
}
