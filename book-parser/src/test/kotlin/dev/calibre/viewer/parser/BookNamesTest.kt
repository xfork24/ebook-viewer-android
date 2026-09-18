// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Tests for the canonical-name <-> href helpers, mirroring
// urllib.parse/calibre container.py behavior including fragment and query
// stripping, %-quoting, and posixpath edge cases.

package dev.calibre.viewer.parser

import dev.calibre.viewer.parser.epub.BookNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BookNamesTest {

    @Test
    fun `normpath collapses separators dots and dot-dots`() {
        assertEquals("a/b", BookNames.normpath("a/./b"))
        assertEquals("b", BookNames.normpath("a/../b"))
        assertEquals("../b", BookNames.normpath("../a/../b"))
        assertEquals("/b", BookNames.normpath("/a/../b"))
        assertEquals(".", BookNames.normpath("a/.."))
        assertEquals("/", BookNames.normpath("//"))
        assertEquals("a/b", BookNames.normpath("a//b"))
    }

    @Test
    fun `href to name strips fragments and queries like urlparse`() {
        assertEquals("text.xhtml", BookNames.hrefToName("text.xhtml#frag", null))
        assertEquals("a", BookNames.hrefToName("a?b#c", null))
        assertEquals("a", BookNames.hrefToName("a#b?c", null))
        assertNull(BookNames.hrefToName("http://x/a#f", "base"))
        assertNull(BookNames.hrefToName("#frag", null))
    }

    @Test
    fun `href to name resolves against a base name`() {
        assertEquals("OEBPS/ch1.xhtml", BookNames.hrefToName("ch1.xhtml", "OEBPS/content.opf"))
        assertEquals("ch1.xhtml", BookNames.hrefToName("ch1.xhtml", null))
        // upstream os.path.join(base, *href.split('/')) drops empty segments,
        // so root-absolute hrefs are BASE-relative (calibre divergence from the
        // EPUB spec's root-relative rule)
        assertEquals("OEBPS/OEBPS/ch1.xhtml", BookNames.hrefToName("/OEBPS/ch1.xhtml", "OEBPS/content.opf"))
        assertEquals("x", BookNames.hrefToName("/x", null))
        // '..' collapses against the base directory...
        assertEquals("out/ch1.xhtml", BookNames.hrefToName("../out/ch1.xhtml", "OEBPS/content.opf"))
        // ...and escaping the book root keeps leading '..' like os.path.relpath
        assertEquals("../ch1.xhtml", BookNames.hrefToName("../../ch1.xhtml", "OEBPS/content.opf"))
    }

    @Test
    fun `href to name unquotes percent escapes`() {
        assertEquals("my file.xhtml", BookNames.hrefToName("my%20file.xhtml", null))
        assertEquals("my+file.xhtml", BookNames.hrefToName("my%2Bfile.xhtml", null))
        // malformed escapes stay verbatim
        assertEquals("a%2.xhtml", BookNames.hrefToName("a%2.xhtml", null))
    }

    @Test
    fun `name to href quotes and relativizes`() {
        assertEquals("my%20file.xhtml", BookNames.nameToHref("my file.xhtml", null))
        assertEquals("ch1.xhtml", BookNames.nameToHref("OEBPS/ch1.xhtml", "OEBPS/content.opf"))
        assertEquals("../ch1.xhtml", BookNames.nameToHref("ch1.xhtml", "OEBPS/content.opf"))
    }

    @Test
    fun `unquote keeps non-ascii as utf-8`() {
        assertEquals("中文.xhtml", BookNames.unquote("%E4%B8%AD%E6%96%87.xhtml"))
    }
}
