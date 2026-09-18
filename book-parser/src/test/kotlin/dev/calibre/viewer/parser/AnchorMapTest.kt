// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Tests for AnchorMap — anchor_map + toc_anchor_map + pagelist_anchor_map +
// check_for_maths. Each test drives the helpers directly against a small
// DOM (no full container needed), which keeps failures localised.

package dev.calibre.viewer.parser

import dev.calibre.viewer.parser.dom.DomParser
import dev.calibre.viewer.parser.epub.Toc
import dev.calibre.viewer.parser.transform.AnchorMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.xmlpull.v1.XmlPullParserFactory

private fun newParser(): DomParser {
    val factory = XmlPullParserFactory.newInstance()
    return DomParser { factory.newPullParser() }
}

class AnchorMapTest {

    @Test
    fun `anchorMap collects ids in document order`() {
        val root = newParser().parseXml(
            """
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body>
                <h1 id="intro">Intro</h1>
                <p id="p1" name="fallback">x</p>
                <h2 id="more">More</h2>
                <p>no id</p>
              </body>
            </html>
            """.trimIndent().toByteArray()
        )
        assertEquals(listOf("intro", "p1", "more"), AnchorMap.anchorMap(root))
    }

    @Test
    fun `anchorMap promotes a name attr on anchors to id`() {
        val root = newParser().parseXml(
            """
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body><a name="legacy">x</a></body>
            </html>
            """.trimIndent().toByteArray()
        )
        assertEquals(listOf("legacy"), AnchorMap.anchorMap(root))
        // The id attr was set in place so the viewer can find the anchor.
        val a = root.findElements("http://www.w3.org/1999/xhtml", "a").first()
        assertEquals("legacy", a.getAttr("id"))
    }

    @Test
    fun `anchorMap drops duplicates keeping first`() {
        val root = newParser().parseXml(
            """
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body><p id="x">1</p><p id="y">2</p><p id="x">3</p></body>
            </html>
            """.trimIndent().toByteArray()
        )
        assertEquals(listOf("x", "y"), AnchorMap.anchorMap(root))
    }

    @Test
    fun `tocAnchorMap buckets nodes by dest with post-order ids`() {
        // Build a tiny toc: root -> (ch1 -> [sec1, sec2]), ch2
        val root = Toc(null, null, null)
        val ch1 = root.add("Chapter 1", "ch1.html", "")
        ch1.add("Sec 1", "ch1.html", "s1")
        ch1.add("Sec 2", "ch1.html", "s2")
        root.add("Chapter 2", "ch2.html", "")

        val map = AnchorMap.tocAnchorMap(root)
        // count() starts at 0 and ids are post-order (children before parents):
        // sec1=0, sec2=1, ch1=2, ch2=3, root=4. The walk itself is pre-order,
        // so ch1.html's bucket starts with ch1's own {id:2, frag:""}.
        val ch1List = map["ch1.html"]!!
        assertEquals(3, ch1List.size)
        assertEquals(2, ch1List[0]["id"])
        assertEquals("", ch1List[0]["frag"])
        assertEquals(0, ch1List[1]["id"])
        assertEquals("s1", ch1List[1]["frag"])
        assertEquals(1, ch1List[2]["id"])
        assertEquals("s2", ch1List[2]["frag"])
        assertEquals(listOf(3), map["ch2.html"]!!.map { it["id"] })
    }

    @Test
    fun `pageListAnchorMap assigns 1-based ids and dedupes frags`() {
        val list = listOf(
            linkedMapOf<String, Any?>("dest" to "ch1.html", "frag" to "p1", "pagenum" to "1"),
            linkedMapOf<String, Any?>("dest" to "ch1.html", "frag" to "p2", "pagenum" to "2"),
            // duplicate frag for ch1.html — should be dropped (but still id'd)
            linkedMapOf<String, Any?>("dest" to "ch1.html", "frag" to "p1", "pagenum" to "1b"),
            linkedMapOf<String, Any?>("dest" to "ch2.html", "frag" to "", "pagenum" to "9"),
        )
        val map = AnchorMap.pageListAnchorMap(list)
        assertEquals(listOf(1, 2), map["ch1.html"]!!.map { it["id"] })
        assertEquals("1", map["ch1.html"]!![0]["pagenum"])
        assertEquals("2", map["ch1.html"]!![1]["pagenum"])
        assertEquals(listOf(4), map["ch2.html"]!!.map { it["id"] })
        // The mutation side-effect: every entry carries its id, including the
        // deduped one (upstream assigns x['id'] before the dup check).
        assertEquals(3, list[2]["id"])
    }

    @Test
    fun `hasMaths detects math element`() {
        val root = newParser().parseXml(
            """
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body><p>x<math><mi>y</mi></math></p></body>
            </html>
            """.trimIndent().toByteArray()
        )
        assertTrue(AnchorMap.hasMaths(root))
    }

    @Test
    fun `hasMaths detects mathjax config script`() {
        val root = newParser().parseXml(
            """
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head><script type="text/x-mathjax-config">{}</script></head>
              <body></body>
            </html>
            """.trimIndent().toByteArray()
        )
        assertTrue(AnchorMap.hasMaths(root))
    }

    @Test
    fun `hasMaths returns false when neither is present`() {
        val root = newParser().parseXml(
            """
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body><p>plain text</p></body>
            </html>
            """.trimIndent().toByteArray()
        )
        assertFalse(AnchorMap.hasMaths(root))
    }
}
