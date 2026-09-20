// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// fonts.xml parsing for the Fonts preferences panel: named families only
// (nameless lang fallback entries dropped), emoji families filtered,
// case-insensitive dedupe, generic families pinned to the head.

package dev.calibre.viewer.host

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class SystemFontFamiliesTest {

    @Test
    fun `parse extracts named families and skips nameless fallback entries`() {
        val xml = """
            <familyset>
                <family name="sans-serif-medium">
                    <font weight="500" style="normal">Roboto-Medium.ttf</font>
                </family>
                <family name="Noto Serif CJK SC">
                    <font weight="400" style="normal">NotoSerifCJKsc-Regular.otf</font>
                </family>
                <family lang="zh">
                    <font weight="400" style="normal">DroidSansFallback.ttf</font>
                </family>
            </familyset>
        """.trimIndent()
        assertEquals(
            listOf("sans-serif-medium", "Noto Serif CJK SC"),
            SystemFontFamilies.parse(xml.byteInputStream()),
        )
    }

    @Test
    fun `parse dedupes case-insensitively and drops emoji families`() {
        val xml = """
            <familyset>
                <family name="sans-serif-medium"></family>
                <family name="Sans-Serif-Medium"></family>
                <family name="Noto Color Emoji"></family>
                <family name="emoji"></family>
            </familyset>
        """.trimIndent()
        assertEquals(
            listOf("sans-serif-medium"),
            SystemFontFamilies.parse(xml.byteInputStream()),
        )
    }

    @Test
    fun `ordered pins generics first and sorts the rest`() {
        val out = SystemFontFamilies.ordered(
            listOf("Noto Serif", "monospace", "cursive", "sans-serif-medium", "sans-serif"),
        )
        assertEquals(
            listOf(
                "serif", "sans-serif", "monospace", "serif-monospace",
                "sans-serif-condensed", "cursive", "Noto Serif", "sans-serif-medium",
            ),
            out,
        )
    }

    @Test
    fun `deviceFamilies merges files and falls back when none parse`() {
        val dir = createTempDir(prefix = "fontxml")
        Runtime.getRuntime().addShutdownHook(Thread { dir.deleteRecursively() })
        File(dir, "system.xml").writeText("""<familyset><family name="sans-serif"/></familyset>""")
        File(dir, "vendor.xml").writeText("""<familyset><family name="VendorSerif"/></familyset>""")

        val merged = SystemFontFamilies.deviceFamilies(
            fallback = listOf("serif"),
            // sans-serif collapses into the generic head; VendorSerif joins;
            // the missing file contributes nothing.
            fontXmls = listOf(File(dir, "system.xml"), File(dir, "vendor.xml"), File(dir, "missing.xml")),
        )
        assertEquals(
            listOf(
                "serif", "sans-serif", "monospace", "serif-monospace",
                "sans-serif-condensed", "cursive", "VendorSerif",
            ),
            merged,
        )

        // No readable file at all: the caller's fallback passes through.
        val fallback = listOf("serif", "Noto Serif")
        assertEquals(
            fallback,
            SystemFontFamilies.deviceFamilies(fallback, fontXmls = listOf(File(dir, "nope.xml"))),
        )
    }
}
