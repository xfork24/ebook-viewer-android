// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Guards the e-ink patches applied by tools/build_viewer_js.py against
// silent regression: every assertion here corresponds to an anchored
// literal patch that dies at build time if upstream renames its anchor.
// This file is the runtime-side double check — it fails if a regenerated
// viewer.js/index.html ever ships without the monochrome palette, the
// motion kill, or the book-color binarization default.

package dev.calibre.viewer.serve

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViewerAssetsMonochromeTest {

    private fun asset(name: String): File =
        sequenceOf(
            File("src/main/assets/viewer/$name"),
            File("app/src/main/assets/viewer/$name"),
        ).firstOrNull { it.isFile }
            ?: error("asset not found: $name (from ${File(".").absolutePath})")

    @Test
    fun `theme base constants are neutralized to pure black and white`() {
        val js = asset("viewer.js").readText()
        // Light: pure black fg on pure white bg; dark: pure black bg, pure white fg.
        assertTrue(js.contains("""DARK = "#000000";"""), "DARK must be #000000")
        assertTrue(js.contains("""LIGHT = "#ffffff";"""), "LIGHT must be #ffffff")
        assertTrue(js.contains("""DT_DARK = "#000000";"""), "DT_DARK must be #000000")
        assertTrue(js.contains("""DT_LIGHT = "#ffffff";"""), "DT_LIGHT must be #ffffff")
        // The warm sepia chrome constants must be gone as *assignments*. The
        // same hex literals legitimately survive in the book-content color
        // scheme presets (sepia-light/sepia-dark, user-selectable reading
        // colors) — those are deliberately unpatched.
        assertFalse(js.contains("""DARK = "#39322B";"""), "upstream sepia DARK still assigned")
        assertFalse(js.contains("""LIGHT = "#F6F3E9";"""), "upstream cream LIGHT still assigned")
    }

    @Test
    fun `chromatic DEFAULT_COLORS entries are replaced with neutral pairs`() {
        val js = asset("viewer.js").readText()
        assertTrue(js.contains("""ρσ_d["window-error-foreground"] = c(DARK, DT_LIGHT);"""))
        assertTrue(js.contains("""ρσ_d["link-foreground"] = c(DARK, DT_LIGHT);"""))
        assertTrue(js.contains("""ρσ_d["bar-highlight"] = c("#888888", "#ffffff");"""))
        assertTrue(js.contains("""ρσ_d["input-background"] = c("#ffffff", DT_DARK_DARKER);"""))
        assertTrue(js.contains("""ρσ_d["input-foreground"] = c("#000000", DT_LIGHT);"""))
        // The chromatic literals must not survive anywhere in the bundle.
        assertFalse(js.contains("#437000"), "calibre green primary still present")
        assertFalse(js.contains("#C40233"), "calibre red error color still present")
        assertFalse(js.contains("#4D90FE"), "calibre blue focus outline still present")
        assertFalse(js.contains("""c("yellow")"""), "yellow bar-highlight still present")
    }

    @Test
    fun `primary color default drives a monochrome ui palette`() {
        val js = asset("viewer.js").readText()
        assertTrue(js.contains("""ρσ_d["light"] = "#000000";"""), "light primary must be black")
        assertTrue(js.contains("""ρσ_d["dark"] = "#ffffff";"""), "dark primary must be white")
    }

    @Test
    fun `book color override defaults to always for e-ink binarization`() {
        val js = asset("viewer.js").readText()
        // The patched defaults-table IIFE (upstream default was "never").
        val block = js.substringAfter("""ρσ_d["override_book_colors"] = (function(){""", "")
            .substringBefore("}).call(this);", "")
        assertTrue(block.contains("""ρσ_d["default"] = "always";"""),
            "override_book_colors default must be \"always\"")
    }

    @Test
    fun `index html carries the motion kill style and inherits icon strokes`() {
        val html = asset("index.html").readText()
        // 1ms durations — never `none` (animationend listeners must fire).
        assertTrue(html.contains("""id="android-eink-motion""""))
        assertTrue(html.contains("transition-duration:1ms !important;"))
        assertTrue(html.contains("animation-duration:1ms !important;"))
        assertTrue(html.contains("animation-iteration-count:1 !important;"))
        assertFalse(html.contains("transition-duration:none"), "motion kill must use 1ms, not none")
        // The one hardcoded stroke color in the merged sprite inherits instead.
        assertFalse(html.contains("stroke:#30a82d"), "green icon stroke still present")
    }
}
