// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Byte-level contract tests derived from calibre's src/calibre/srv/html_as_json.cpp.
// serializeTree* exercises the raw C serializer semantics; serializeHtmlAsJson*
// exercises the Python wrapper (root validation + body coercion + pruning).
// Expected strings are hand-derived from the C code; once a desktop calibre
// environment is available these become golden-fixture comparisons.

package dev.calibre.viewer.parser

import dev.calibre.viewer.parser.dom.DomComment
import dev.calibre.viewer.parser.dom.DomElement
import dev.calibre.viewer.parser.dom.SVG_NS
import dev.calibre.viewer.parser.dom.XHTML_NS
import dev.calibre.viewer.parser.dom.XLINK_NS
import dev.calibre.viewer.parser.json.serializeHtmlAsJsonString
import dev.calibre.viewer.parser.json.serializeTreeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun el(tag: String, text: String? = null, tail: String? = null): DomElement =
    DomElement(tag).apply { this.text = text; this.tail = tail }

class HtmlAsJsonTest {

    // ---- raw serializer ----

    @Test
    fun `minimal html with empty head and body`() {
        val html = el("html")
        html.append(el("head"))
        html.append(el("body"))
        assertEquals(
            """{"version":1,"tree":{"n":"html","c":[{"n":"head"},{"n":"body"}]},"ns_map":[]}""",
            serializeTreeString(html),
        )
    }

    @Test
    fun `text and tail map to x and l`() {
        val p = el("p", text = "hello")
        val b = el("b", text = "bold", tail = "after")
        p.append(b)
        assertEquals(
            """{"version":1,"tree":{"n":"p","x":"hello","c":[{"n":"b","x":"bold","l":"after"}]},"ns_map":[]}""",
            serializeTreeString(p),
        )
    }

    @Test
    fun `empty string text is emitted while null is omitted`() {
        val p = el("p", text = "")
        assertEquals(
            """{"version":1,"tree":{"n":"p","x":""},"ns_map":[]}""",
            serializeTreeString(p),
        )
    }

    @Test
    fun `attributes keep document order`() {
        val img = el("img")
        img.setAttr("alt", "x")
        img.setAttr("src", "s.png")
        assertEquals(
            """{"version":1,"tree":{"n":"img","a":[["alt","x"],["src","s.png"]]},"ns_map":[]}""",
            serializeTreeString(img),
        )
    }

    @Test
    fun `quotes and backslashes are escaped`() {
        val p = el("p", text = "a\"b\\c")
        assertEquals(
            "{\"version\":1,\"tree\":{\"n\":\"p\",\"x\":\"a\\\"b\\\\c\"},\"ns_map\":[]}",
            serializeTreeString(p),
        )
    }

    @Test
    fun `short escapes for b f n r t`() {
        val p = el("p", text = "\b\u000C\n\r\t")
        assertEquals(
            "{\"version\":1,\"tree\":{\"n\":\"p\",\"x\":\"\\b\\f\\n\\r\\t\"},\"ns_map\":[]}",
            serializeTreeString(p),
        )
    }

    @Test
    fun `control chars below 0x1F use uppercase hex escapes`() {
        val p = el("p", text = "a\u0000\u000B\u001Eb")
        assertEquals(
            "{\"version\":1,\"tree\":{\"n\":\"p\",\"x\":\"a\\u0000\\u000B\\u001Eb\"},\"ns_map\":[]}",
            serializeTreeString(p),
        )
    }

    @Test
    fun `0x1F and 0x7F are written raw`() {
        val p = el("p", text = "a\u001F\u007Fb")
        assertEquals(
            "{\"version\":1,\"tree\":{\"n\":\"p\",\"x\":\"a\u001F\u007Fb\"},\"ns_map\":[]}",
            serializeTreeString(p),
        )
    }

    @Test
    fun `astral plane chars are written raw utf8`() {
        // cpp's surrogate-pair branch is dead code: c < 0x1F is always a
        // single ASCII byte, so every code point >= 0x1F (emoji included) is
        // emitted as raw UTF-8.
        val p = el("p", text = "\uD83D\uDE00")
        assertEquals(
            "{\"version\":1,\"tree\":{\"n\":\"p\",\"x\":\"\uD83D\uDE00\"},\"ns_map\":[]}",
            serializeTreeString(p),
        )
    }

    @Test
    fun `cjk passes through as raw utf8`() {
        val p = el("p", text = "中文测试")
        assertEquals(
            """{"version":1,"tree":{"n":"p","x":"中文测试"},"ns_map":[]}""",
            serializeTreeString(p),
        )
    }

    @Test
    fun `namespaced tag and attrs register ns_map in pre-order`() {
        val svg = el("{$SVG_NS}svg")
        val image = el("{$SVG_NS}image")
        image.setAttr("{$XLINK_NS}href", "x.png")
        image.setAttr("width", "10")
        svg.append(image)
        // svg is ns_map[0] -> "s" omitted; xlink attr gets index 1
        assertEquals(
            """{"version":1,"tree":{"n":"svg","c":[{"n":"image","a":[["href","x.png",1],["width","10"]]}]},""" +
                """"ns_map":["http://www.w3.org/2000/svg","http://www.w3.org/1999/xlink"]}""",
            serializeTreeString(svg),
        )
    }

    @Test
    fun `second namespace on tag gets explicit s index`() {
        val root = el("html")
        val mathNs = "http://www.w3.org/1998/Math/MathML"
        root.append(el("{$mathNs}math"))
        assertEquals(
            """{"version":1,"tree":{"n":"html","c":[{"n":"math"}]},"ns_map":["$mathNs"]}""",
            serializeTreeString(root),
        )
    }

    @Test
    fun `comment and processing instruction`() {
        val html = el("html")
        val c = DomComment(isProcessingInstruction = false)
        c.text = " hi "
        c.tail = "\n"
        html.append(c)
        val pi = DomComment(isProcessingInstruction = true)
        pi.text = "xml-stylesheet"
        html.append(pi)
        assertEquals(
            "{\"version\":1,\"tree\":{\"n\":\"html\",\"c\":[{\"s\":\"c\",\"x\":\" hi \",\"l\":\"\\n\"},{\"s\":\"o\",\"x\":\"xml-stylesheet\"}]},\"ns_map\":[]}",
            serializeTreeString(html),
        )
    }

    // ---- Python wrapper (render_book.py html_as_json) ----

    @Test
    fun `wrapper appends body when missing and prunes non head body children`() {
        val html = el("html")
        html.append(el("div", text = "stray"))   // gets pruned
        html.text = "leading"
        // The synthesized body is XHTML-namespaced (makeelement(XHTML('body'))),
        // so ns_map gains the XHTML URI even for a no-namespace root.
        assertEquals(
            """{"version":1,"tree":{"n":"html","c":[{"n":"body"}]},"ns_map":["$XHTML_NS"]}""",
            serializeHtmlAsJsonString(html),
        )
    }

    @Test
    fun `wrapper merges multiple bodies into divs in first body`() {
        val html = el("{$XHTML_NS}html")
        val b1 = el("{$XHTML_NS}body")
        b1.append(el("p", text = "one"))
        val b2 = el("{$XHTML_NS}body")
        b2.setAttr("class", "second")
        b2.append(el("p", text = "two"))
        html.append(b1)
        html.append(b2)
        // Upstream never removes the surplus <body> — it survives under html
        // with its attrs (the div got a COPY), just emptied of children.
        assertEquals(
            """{"version":1,"tree":{"n":"html","c":[""" +
                """{"n":"body","c":[{"n":"p","x":"one"},""" +
                """{"n":"div","a":[["class","second"]],"c":[{"n":"p","x":"two"}]}]},""" +
                """{"n":"body","a":[["class","second"]]}]},""" +
                """"ns_map":["$XHTML_NS"]}""",
            serializeHtmlAsJsonString(html),
        )
    }

    @Test
    fun `wrapper ignores foreign-namespace bodies`() {
        val html = el("{$XHTML_NS}html")
        val b1 = el("{$XHTML_NS}body")
        b1.append(el("p", text = "one"))
        val fb = el("{urn:f}body")
        fb.append(el("p", text = "two"))
        html.append(b1)
        html.append(fb)
        // Only XHTML bodies count: the f:body is an ordinary element that
        // survives body coercion AND the head/body pruning (localname match).
        assertEquals(
            """{"version":1,"tree":{"n":"html","c":[""" +
                """{"n":"body","c":[{"n":"p","x":"one"}]},""" +
                """{"n":"body","s":1,"c":[{"n":"p","x":"two"}]}]},""" +
                """"ns_map":["$XHTML_NS","urn:f"]}""",
            serializeHtmlAsJsonString(html),
        )
    }

    @Test
    fun `root must be in empty or xhtml namespace`() {
        assertFailsWith<IllegalArgumentException> {
            serializeHtmlAsJsonString(el("{$SVG_NS}svg"))
        }
        // XHTML namespace is fine
        serializeHtmlAsJsonString(el("{$XHTML_NS}html"))
    }

    @Test
    fun `output has no trailing newline and single top level object`() {
        val html = el("html")
        html.append(el("body"))
        val out = serializeHtmlAsJsonString(html)
        assertTrue(!out.endsWith("\n") && out.startsWith("{\"version\":1,") && out.endsWith("}"))
    }
}
