// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Tests for StandaloneCss — the standalone-mode (virtualize_resources=False)
// pass that pre-pends `@charset "UTF-8";` and runs the CssTransformer over
// the raw stylesheet. Only the prefix behavior needs explicit testing here;
// the CssTransformer itself is covered by CssTransformerTest.

package dev.calibre.viewer.parser

import dev.calibre.viewer.parser.dom.DomParser
import dev.calibre.viewer.parser.epub.BookContainer
import dev.calibre.viewer.parser.transform.StandaloneCss
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.xmlpull.v1.XmlPullParserFactory

private fun newParser(): DomParser {
    val factory = XmlPullParserFactory.newInstance()
    return DomParser { factory.newPullParser() }
}

class StandaloneCssTest {

    private fun containerWithCss(content: String): Pair<File, BookContainer> {
        val tdir = createTempDir(prefix = "standalone-css")
        Runtime.getRuntime().addShutdownHook(Thread { tdir.deleteRecursively() })
        File(tdir, "content.opf").writeText(
            """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="BookId">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>T</dc:title><dc:identifier id="BookId">uid</dc:identifier>
              </metadata>
              <manifest>
                <item id="css" href="style.css" media-type="text/css"/>
              </manifest>
              <spine/>
            </package>
            """.trimIndent()
        )
        File(tdir, "style.css").writeText(content)
        return tdir to BookContainer.open(tdir, "content.opf", newParser())
    }

    @Test
    fun `transformStyleSheet prepends @charset when missing`() {
        val (_, container) = containerWithCss("body { color: red; }")
        val changed = StandaloneCss.transformStyleSheet(container, "style.css")
        assertTrue(changed)
        val css = container.namePathMap["style.css"]!!.readText()
        assertTrue(css.startsWith("@charset \"UTF-8\";\n"), "missing charset prefix: $css")
        assertTrue("color: red" in css)
    }

    @Test
    fun `transformStyleSheet leaves file alone when already prefixed and unchanged`() {
        val original = "@charset \"UTF-8\";\nbody { color: blue; }"
        val (_, container) = containerWithCss(original)
        val changed = StandaloneCss.transformStyleSheet(container, "style.css")
        // The CssTransformer rewrites page-break-* and font-size keywords; this
        // file has neither, so it should round-trip unchanged.
        assertFalse(changed)
        assertEquals(original, container.namePathMap["style.css"]!!.readText())
    }

    @Test
    fun `transformStyleSheet rewrites page-break via CssTransformer`() {
        val (_, container) = containerWithCss("h1 { page-break-before: always; }")
        StandaloneCss.transformStyleSheet(container, "style.css")
        val css = container.namePathMap["style.css"]!!.readText()
        assertTrue("break-before" in css, "page-break not rewritten: $css")
    }
}
