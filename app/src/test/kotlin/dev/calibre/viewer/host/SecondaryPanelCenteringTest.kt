// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Regression guards for the secondary-panel vertical centering fix:
// Go-to and Preferences lists must be vertically centered inside their
// overlay container, and we must never regress on the #book-overlay
// visibility-state-machine rule from 814f1e6 (its inline display
// block/none is the reader's open/closed indicator — switching it to
// flex broke visibility).

package dev.calibre.viewer.host

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class SecondaryPanelCenteringTest {

    private val viewerJs: String = sequenceOf(
        File("src/main/assets/viewer/viewer.js"),
        File("app/src/main/assets/viewer/viewer.js"),
        File("../src/main/assets/viewer/viewer.js"),
    ).first { it.isFile }.readText(Charsets.UTF_8)

    @Test
    fun `go-to container is a flex column so margin-auto can vertically center the list`() {
        // The Go-to panel is built by create_goto_panel; before its list is
        // appended, the container must be set to a flex column so the
        // .android-aligned-secondary-list { margin-top:auto; margin-bottom:auto }
        // rule has a flex context to center against.
        val goToHook = viewerJs.substringAfter(
            "panel.classList.add(\"android-aligned-secondary-list\");",
        )
        val context = goToHook.substring(0, minOf(goToHook.length, 400))
        assertTrue(
            context.contains("set_css") && context.contains("flex_direction"),
            "Go-to hook must set_css(container, flex column) before appending the panel; got:\n$context",
        )
    }

    @Test
    fun `preferences container is a flex column so margin-auto can vertically center the list`() {
        // Preferences uses a different local variable ('c' not 'panel'), so
        // the set_css call sits on its own line right after set_top_title.
        val prefsHook = viewerJs.substringAfter(
            "self.set_top_title(self.main_title);\n",
        )
        val context = prefsHook.substring(0, minOf(prefsHook.length, 400))
        assertTrue(
            context.contains("set_css") && context.contains("flex_direction"),
            "Preferences hook must set_css(container, flex column) before c = E.div(); got:\n$context",
        )
    }

    @Test
    fun `android-aligned-secondary-list is top-aligned and shrinks to content`() {
        // The single CSS rule for the secondary lists:
        // - flex-grow:0 !important overrides Go-to's inline flex_grow:10 so
        //   the list doesn't stretch off-screen.
        // - no margin-top/bottom auto: the user wants entries anchored at the
        //   top of the panel, not vertically centered.
        val expected =
            "\".android-aligned-secondary-list{flex-grow:0 !important;}\""
        assertTrue(
            viewerJs.contains(expected),
            "viewer.js must contain the top-aligned flex-grow:0 rule for the secondary lists",
        )
        // Guard against regressing back to the vertical-centered variant.
        assertTrue(
            "margin-top:auto !important" !in viewerJs &&
                "margin-bottom:auto !important" !in viewerJs,
            "viewer.js must not auto-center the secondary lists with margin-top/bottom auto",
        )
    }

    @Test
    fun `book-overlay display is never set to flex (regression guard for 814f1e6)`() {
        // #book-overlay's inline block/none is the reader's visibility
        // state machine. Forcing display:flex would break the open/closed
        // indicator. Any future patch that touches this is a bug.
        assertTrue(
            "#book-overlay{display:flex" !in viewerJs &&
                "#book-overlay {display:flex" !in viewerJs &&
                "#book-overlay{display: flex" !in viewerJs,
            "viewer.js must not force #book-overlay to display:flex — its block/none toggles visibility",
        )
    }

    @Test
    fun `prefs main IIFE declares set_css from rho modules dom (regression guard for uncaught reference error)`() {
        // Prefs.prototype.display_top calls `set_css` unqualified; the
        // read_book.prefs.main RapydScript IIFE must declare it at module
        // scope just like every other IIFE that calls set_css. Without this
        // declaration, opening Preferences throws
        // `ReferenceError: set_css is not defined` and the secondary panel
        // never renders.
        val prefsIife = viewerJs.substringAfter(
            "var __name__ = \"read_book.prefs.main\";",
        )
        val head = prefsIife.substring(0, minOf(prefsIife.length, 2_500))
        assertTrue(
            "var set_css = ρσ_modules.dom.set_css;" in head,
            "read_book.prefs.main IIFE must declare `var set_css = ρσ_modules.dom.set_css;` so Prefs.prototype.display_top can call it; got:\n$head",
        )
    }
}
