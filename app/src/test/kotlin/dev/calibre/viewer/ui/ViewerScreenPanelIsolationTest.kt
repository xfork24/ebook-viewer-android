// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// R-PERF-05 regression guard (source-level — no compose-ui-test dependency in
// this module yet, so we assert the ViewerScreen.kt structure directly, the
// same idiom ViewerAssetsLayoutTest uses for viewer.js/index.html patches).

package dev.calibre.viewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ViewerScreenPanelIsolationTest {

    private fun source(): File =
        sequenceOf(
            File("src/main/kotlin/dev/calibre/viewer/ui/ViewerScreen.kt"),
            File("app/src/main/kotlin/dev/calibre/viewer/ui/ViewerScreen.kt"),
        ).firstOrNull { it.isFile }
            ?: error("ViewerScreen.kt not found from ${File(".").absolutePath}")

    @Test
    fun `each panel gate is wrapped in key of its own visibility`() {
        // Every panel must carry an explicit composition identity tied to its
        // visibility flag: a reopen (false -> true) composes a fresh subtree
        // with reset internal remember state (the panels' designed lifecycle),
        // and the five call sites stay independent.
        val src = source().readText()
        for (flag in listOf(
            "tocVisible", "searchVisible", "bookmarksVisible",
            "highlightsVisible", "profilesVisible",
        )) {
            assertTrue(
                src.contains("key(state.$flag) {"),
                "panel gate $flag must be wrapped in key(state.$flag) { ... }",
            )
        }
    }

    @Test
    fun `hidden panels are gated before composition not merely hidden`() {
        // The key() wrap must keep the if(visible) conditional INSIDE it, so
        // a hidden panel's composable is never invoked at all.
        val src = source().readText()
        assertTrue(src.contains("key(state.searchVisible) {\n        if (state.searchVisible) {"))
    }
}
