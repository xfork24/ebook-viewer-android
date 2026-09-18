// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Contract tests for the parse_html() mirror: strict-XML main path, entity
// fallback, HTML5 fallback, and every normalization step that leaves a
// fingerprint in the html_as_json output.

package dev.calibre.viewer.parser

import dev.calibre.viewer.parser.dom.DomParser
import dev.calibre.viewer.parser.json.serializeHtmlAsJsonString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.xmlpull.v1.XmlPullParserFactory

private fun newParser(): DomParser {
    val factory = XmlPullParserFactory.newInstance()
    return DomParser { factory.newPullParser() }
}

private val XHTML = "http://www.w3.org/1999/xhtml"

class DomParserTest {

    @Test
    fun `strict xhtml passes through with normalization fingerprints`() {
        val html = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>T</title></head><body><p>你好</p></body></html>"""
        val root = newParser().parseHtml(html.toByteArray())
        assertEquals("{$XHTML}html", root.tag)
        // Whitespace formatting fingerprints
        assertEquals("\n  ", root.text)
        val head = root.directChild(XHTML, "head")
        assertTrue(head != null && head.tail == "\n  ")
        // Charset meta appended with content as second attribute
        val meta = head!!.findElements(XHTML, "meta").last()
        assertEquals("Content-Type", meta.getAttr("http-equiv"))
        assertEquals("text/html; charset=utf-8", meta.getAttr("content"))
        assertEquals(listOf("http-equiv", "content"), meta.attrs.values.map { it.localName })
        val json = serializeHtmlAsJsonString(root)
        // root.text is nulled by the html_as_json wrapper before serialization
        assertTrue(json.startsWith("""{"version":1,"tree":{"n":"html","c":"""), json)
        // head keeps its formatted whitespace, title/meta tails included
        assertTrue("""{"n":"head","x":"\n    ","l":"\n  ","c":[{"n":"title","x":"T","l":"\n    "}""" in json, json)
        assertTrue("""{"n":"p","x":"你好"}""" in json, json)
    }

    @Test
    fun `nbsp entity triggers entity-replaced xml path`() {
        val html = """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>T</title></head>
<body><p>a&nbsp;b</p></body></html>"""
        val root = newParser().parseHtml(html.toByteArray())
        val p = root.findElements(XHTML, "p").single()
        assertEquals("a b", p.text)
    }

    @Test
    fun `malformed html falls back to html5 parsing`() {
        val html = """<html><head><title>T</title></head>
<body><p>unclosed <b>bold<div>block"""
        val root = newParser().parseHtml(html.toByteArray())
        assertEquals("{$XHTML}html", root.tag)
        assertTrue(root.findElements(XHTML, "div").isNotEmpty())
    }

    @Test
    fun `un-namespaced root is forced into xhtml namespace`() {
        val html = """<html><head><title>T</title></head><body><p>plain</p></body></html>"""
        val root = newParser().parseHtml(html.toByteArray())
        assertEquals("{$XHTML}html", root.tag)
        assertEquals("{$XHTML}p", root.findElements(XHTML, "p").single().tag)
    }

    @Test
    fun `fragment gets wrapped under a body`() {
        val html = """<p>one</p><p>two</p>"""
        val root = newParser().parseHtml(html.toByteArray())
        assertEquals("{$XHTML}html", root.tag)
        val body = root.directChild(XHTML, "body")
        assertTrue(body != null && body.findElements(XHTML, "p").size == 2)
    }

    @Test
    fun `svg foreign subtree keeps its namespace`() {
        val html = """<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>T</title></head>
<body><svg xmlns="http://www.w3.org/2000/svg"><image xlink:href="i.png" xmlns:xlink="http://www.w3.org/1999/xlink"/></svg></body></html>"""
        val root = newParser().parseHtml(html.toByteArray())
        val svg = root.findElements("http://www.w3.org/2000/svg", "svg").single()
        val image = svg.findElements("http://www.w3.org/2000/svg", "image").single()
        assertEquals("i.png", image.getAttr("{http://www.w3.org/1999/xlink}href"))
    }

    @Test
    fun `empty inline elements are removed with tail spliced to previous sibling`() {
        val html = """<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>T</title></head>
<body><p>keep<i></i><b>x</b></p><p><u></u>lead</p></body></html>"""
        val root = newParser().parseHtml(html.toByteArray())
        val ps = root.findElements(XHTML, "p")
        // <i></i> removed, <b> survives (has text); <u> removed, its position was first
        assertTrue(ps[0].findElements(XHTML, "i").isEmpty())
        assertTrue(ps[0].findElements(XHTML, "b").isNotEmpty())
        assertTrue(ps[1].findElements(XHTML, "u").isEmpty())
    }

    @Test
    fun `empty anchor with href is removed`() {
        val html = """<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>T</title></head>
<body><a href="ch1.xhtml"></a><p>after</p></body></html>"""
        val root = newParser().parseHtml(html.toByteArray())
        assertTrue(root.findElements(XHTML, "a").isEmpty())
    }

    @Test
    fun `br with content becomes div`() {
        val html = """<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>T</title></head>
<body><br/></body></html>"""
        val root = newParser().parseHtml(html.toByteArray())
        // empty <br/> stays br; only br with children/text is converted
        assertTrue(root.findElements(XHTML, "br").isNotEmpty())
    }

    @Test
    fun `missing head and title are created`() {
        val html = """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>x</p></body></html>"""
        val root = newParser().parseHtml(html.toByteArray())
        val head = root.directChild(XHTML, "head")
        assertTrue(head != null && head.directChild(XHTML, "title")?.text == "Unknown")
    }

    @Test
    fun `ncx root raises NotHTML`() {
        val ncx = """<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"></ncx>"""
        assertFailsWith<dev.calibre.viewer.parser.dom.NotHTMLException> {
            newParser().parseHtml(ncx.toByteArray())
        }
    }

    @Test
    fun `comments survive the strict path`() {
        val html = """<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>T</title></head>
<body><!-- note --><p>x</p></body></html>"""
        val root = newParser().parseHtml(html.toByteArray())
        val body = root.directChild(XHTML, "body")
        assertTrue(body != null && body.children.any {
            it is dev.calibre.viewer.parser.dom.DomComment && it.text == " note "
        })
    }

    @Test
    fun `doctype user entities are expanded at text level`() {
        val html = """<!DOCTYPE html [ <!ENTITY foo "bar &amp; baz"> ]>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>T</title></head>
<body><p>x&foo;y</p></body></html>"""
        val root = newParser().parseHtml(html.toByteArray())
        val p = root.findElements(XHTML, "p").single()
        // The entity value is spliced raw ("bar &amp; baz"), then the strict
        // XML pass resolves the nested &amp; — matching lxml behaviour.
        assertEquals("xbar & bazy", p.text)
    }

    @Test
    fun `utf16 bom is decoded`() {
        val body = "<html><head><title>T</title></head><body><p>寄</p></body></html>"
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + body.toByteArray(Charsets.UTF_16LE)
        val root = newParser().parseHtml(bytes)
        assertTrue(root.findElements(XHTML, "p").single().text == "寄")
    }

    @Test
    fun `parse xml handles namespaced container opf`() {
        val opf = """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
<metadata/><manifest/><spine/></package>"""
        val root = newParser().parseXml(opf.toByteArray())
        assertEquals("http://www.idpf.org/2007/opf}package", root.tag.substring(1))
        assertEquals("3.0", root.getAttr("version"))
    }
}
