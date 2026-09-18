// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Tests for the TOC layer: post-order to_dict ids, get_toc branch selection
// (EPUB2 NCX vs EPUB3 nav vs fallback), landmarks, page lists, and the
// from_xpaths h1/h2 fallback with its ensure_id dirtiness.

package dev.calibre.viewer.parser

import dev.calibre.viewer.parser.dom.DomParser
import dev.calibre.viewer.parser.epub.BookContainer
import dev.calibre.viewer.parser.epub.Toc
import dev.calibre.viewer.parser.epub.fromXpaths
import dev.calibre.viewer.parser.epub.getLandmarks
import dev.calibre.viewer.parser.epub.getToc
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.xmlpull.v1.XmlPullParserFactory

private fun newParser(): DomParser {
    val factory = XmlPullParserFactory.newInstance()
    return DomParser { factory.newPullParser() }
}

/** Lay a book out on disk as if it had just been extracted + normalized. */
private fun extractedBook(files: Map<String, String>, opfName: String = "content.opf"): File {
    val tdir = createTempDir(prefix = "test-toc")
    Runtime.getRuntime().addShutdownHook(Thread { tdir.deleteRecursively() })
    for ((name, content) in files) {
        val f = File(tdir, name)
        f.parentFile?.mkdirs()
        f.writeText(content)
    }
    return tdir
}

private fun containerFor(files: Map<String, String>, opfName: String = "content.opf"): BookContainer =
    BookContainer.open(extractedBook(files, opfName), opfName, newParser())

private val NCX_BOOK = mapOf(
    "content.opf" to """
        <?xml version="1.0"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="BookId">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>T</dc:title><dc:identifier id="BookId">uid</dc:identifier>
          </metadata>
          <manifest>
            <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
            <item id="ch1" href="ch1.html" media-type="application/xhtml+xml"/>
            <item id="ch2" href="ch2.html" media-type="application/xhtml+xml"/>
          </manifest>
          <spine toc="ncx"><itemref idref="ch1"/><itemref idref="ch2"/></spine>
          <guide><reference type="text" title="Start" href="ch1.html"/></guide>
        </package>
    """.trimIndent(),
    "toc.ncx" to """
        <?xml version="1.0"?>
        <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1" xml:lang="zh">
          <head><meta name="dtb:uid" content="the-uid"/></head>
          <navMap>
            <navPoint><navLabel><text>Part 1</text></navLabel><content src="ch1.html"/>
              <navPoint><navLabel><text>  Ch 1.1  </text></navLabel><content src="ch2.html#frag"/></navPoint>
            </navPoint>
          </navMap>
          <pageList>
            <pageTarget value="3"><navLabel><text>3</text></navLabel><content src="ch1.html#p1"/></pageTarget>
          </pageList>
        </ncx>
    """.trimIndent(),
    "ch1.html" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>a</title></head><body><p>x</p></body></html>""",
    "ch2.html" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>b</title></head><body><p id="frag">y</p></body></html>""",
)

private val NAV_BOOK = mapOf(
    "content.opf" to """
        <?xml version="1.0"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="BookId">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>T3</dc:title><dc:identifier id="BookId">uid3</dc:identifier>
          </metadata>
          <manifest>
            <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
            <item id="ch1" href="ch1.html" media-type="application/xhtml+xml"/>
          </manifest>
          <spine><itemref idref="ch1"/></spine>
          <guide><reference type="toc" title="TOC" href="nav.xhtml"/></guide>
        </package>
    """.trimIndent(),
    "nav.xhtml" to """
        <?xml version="1.0"?>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/epub">
        <head><title>nav</title></head><body>
          <nav epub:type="toc">
            <h1>  Contents  </h1>
            <ol><li><a href="ch1.html">Start</a></li></ol>
          </nav>
          <nav epub:type="landmarks"><ol>
            <li><a epub:type="bodymatter" href="ch1.html#start">Begin</a></li>
          </ol></nav>
          <nav epub:type="page-list"><ol>
            <li><a href="ch1.html#p1">5</a></li>
          </ol></nav>
        </body></html>
    """.trimIndent(),
    "ch1.html" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>c</title></head><body><h1 id="start">S</h1></body></html>""",
)

class TocTest {

    @Test
    fun `to_dict assigns ids post-order like the python dict literal`() {
        val root = Toc("root", "x", null)
        val a = root.add("a", "a", null)
        root.add("b", "b", null)
        a.add("a1", "a1", null)
        val counter = generateSequence(0) { it + 1 }.iterator()
        val d = root.toMap(counter)
        @Suppress("UNCHECKED_CAST")
        val children = d["children"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val aChildren = children[0]["children"] as List<Map<String, Any?>>
        assertEquals(0, aChildren[0]["id"])
        assertEquals(1, children[0]["id"])
        assertEquals(2, children[1]["id"])
        assertEquals(3, d["id"])
        // key order matches upstream: title, dest, frag, children, id
        assertEquals(listOf("title", "dest", "frag", "children", "id"), d.keys.toList())
    }

    @Test
    fun `epub2 reads ncx with page list and metadata`() {
        val c = containerFor(NCX_BOOK)
        val toc = getToc(c)
        assertEquals("toc.ncx", toc.tocFileName)
        assertEquals("zh", toc.lang)
        assertEquals("the-uid", toc.uid)
        val part = toc.children.single()
        assertEquals("Part 1", part.title)
        assertEquals("ch1.html", part.dest)
        assertNull(part.frag)
        val nested = part.children.single()
        assertEquals("Ch 1.1", nested.title) // TOC() strips titles
        assertEquals("ch2.html", nested.dest)
        assertEquals("frag", nested.frag)
        assertEquals(
            listOf(mapOf<String, String?>("dest" to "ch1.html", "pagenum" to "3", "frag" to "p1")),
            toc.pageList,
        )
    }

    @Test
    fun `epub2 landmarks come from the guide`() {
        val c = containerFor(NCX_BOOK)
        val lm = getLandmarks(c)
        assertEquals(1, lm.size)
        assertEquals("ch1.html", lm[0]["dest"])
        assertEquals("text", lm[0]["type"])
        assertEquals("", lm[0]["frag"])
    }

    @Test
    fun `epub3 prefers nav with title page-list and landmarks`() {
        val c = containerFor(NAV_BOOK)
        val toc = getToc(c)
        assertEquals("nav.xhtml", toc.tocFileName)
        assertEquals(1, toc.children.size)
        assertEquals("Start", toc.children[0].title)
        assertEquals("ch1.html", toc.children[0].dest)
        // toc_title is NOT stripped upstream (raw subtree text of the heading)
        assertEquals("  Contents  ", toc.tocTitle)
        assertEquals(
            listOf(mapOf<String, String?>("dest" to "ch1.html", "pagenum" to "5", "frag" to "p1")),
            toc.pageList,
        )

        val lm = getLandmarks(c)
        assertEquals(1, lm.size)
        assertEquals("bodymatter", lm[0]["type"])
        assertEquals("start", lm[0]["frag"])
        assertEquals("Begin", lm[0]["title"])
    }

    @Test
    fun `from_xpaths builds levels and assigns ids only when needed`() {
        val files = mapOf(
            "content.opf" to NCX_BOOK.getValue("content.opf"),
            // no NCX file on disk -> get_toc finds spine@toc but has_name fails
            "ch1.html" to """
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>a</title></head>
                <body><h1>First Heading</h1><p>para</p><h2>Second</h2></body></html>
            """.trimIndent(),
            "ch2.html" to """
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>b</title></head>
                <body><p>lead paragraph</p><h1>Hidden Heading</h1></body></html>
            """.trimIndent(),
        )
        val c = containerFor(files)
        assertTrue(getToc(c).children.isEmpty(), "ncx is absent so the fallback kicks in")

        val toc = fromXpaths(c, listOf("//h:h1", "//h:h2", "//h:h3"))
        val names = toc.iterDescendants().map { it.dest }.toSet()
        assertEquals(setOf("ch1.html", "ch2.html"), names)

        val h1First = toc.children[0] // ch1 h1 at top of body -> no frag
        assertNull(h1First.frag)
        assertEquals("First Heading", h1First.title)

        // h2 in ch1 and h1 in ch2 are NOT at top -> got generated ids
        val withIds = toc.iterDescendants().filter { it.dest == "ch1.html" && it.frag != null }.toList()
        assertEquals(listOf("toc_1"), withIds.map { it.frag })
        val ch2H1 = toc.iterDescendants().filter { it.dest == "ch2.html" }.single()
        assertEquals("toc_1", ch2H1.frag)

        // level 3 (h3) matched nothing anywhere and was dropped; the surviving
        // levels are 1 (h1) and 2 (h2): the h2 nests under the last h1
        assertEquals("Second", toc.children[0].children.single().title)

        // the dirtied files were committed with the generated ids
        assertTrue(File(c.tdir, "ch1.html").readText().contains("""id="toc_1""""))
        assertTrue(File(c.tdir, "ch2.html").readText().contains("""id="toc_1""""))
    }
}
