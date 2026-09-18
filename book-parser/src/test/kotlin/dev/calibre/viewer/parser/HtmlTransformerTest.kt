// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Tests for HtmlTransformer.transformHtml — the standalone-mode HTML rewrite
// that turns XHTML into the byte-exact html_as_json form the viewer.js
// runtime expects. Mirrors the test vectors implied by
// calibre/src/calibre/srv/render_book.py:409-487 and the data shape
// established by calibre/src/calibre/srv/html_as_json.cpp.
//
// After transformHtml, the file holds the html_as_json form
// ({"version":1,"tree":...,"ns_map":[...]}), so we parse the raw bytes as
// JSON and walk the tree rather than re-parsing through DomParser (which
// expects XML/HTML and would fail on JSON).

package dev.calibre.viewer.parser

import dev.calibre.viewer.parser.dom.DomParser
import dev.calibre.viewer.parser.epub.BookContainer
import dev.calibre.viewer.parser.epub.EpubInput
import dev.calibre.viewer.parser.transform.HtmlTransformer
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.xmlpull.v1.XmlPullParserFactory

private typealias LinkToMap = MutableMap<String, MutableMap<String, MutableSet<String>>>

private fun newParser(): DomParser {
    val factory = XmlPullParserFactory.newInstance()
    return DomParser { factory.newPullParser() }
}

private fun buildEpub(entries: List<Pair<String, Any>>): File {
    val f = File.createTempFile("html-transform-test", ".epub")
    f.deleteOnExit()
    ZipOutputStream(f.outputStream()).use { zos ->
        for ((name, content) in entries) {
            zos.putNextEntry(ZipEntry(name))
            when (content) {
                is ByteArray -> zos.write(content)
                is String -> zos.write(content.toByteArray())
            }
            zos.closeEntry()
        }
    }
    return f
}

private fun extractEpub(entries: List<Pair<String, Any>>): BookContainer {
    val tdir = createTempDir(prefix = "html-transform-extract")
    tdir.deleteRecursivelyOnExit()
    EpubInput(newParser()).convert(buildEpub(entries), tdir, false)
    return BookContainer.open(tdir, "content.opf", newParser())
}

private fun File.deleteRecursivelyOnExit(): File {
    Runtime.getRuntime().addShutdownHook(Thread { this.deleteRecursively() })
    return this
}

private val JSON = Json { ignoreUnknownKeys = true }

/** Find every element in the JSON tree whose local name (field "n") matches. */
private fun JsonObject.findAllElements(localName: String): List<JsonObject> {
    val out = ArrayList<JsonObject>()
    fun walk(node: JsonElement) {
        if (node is JsonObject) {
            val n = node["n"]
            if (n is JsonPrimitive && n.contentOrNull == localName) out.add(node)
            for ((_, child) in node) {
                if (child is JsonElement && child !== n) walk(child)
            }
        } else if (node is JsonArray) {
            for (c in node) walk(c)
        }
    }
    walk(this)
    return out
}

/** Read attr value of an element in the html_as_json "a" array. */
private fun JsonObject.attr(name: String): String? {
    val a = this["a"] as? JsonArray ?: return null
    for (entry in a) {
        if (entry !is JsonArray || entry.isEmpty()) continue
        val key = (entry[0] as? JsonPrimitive)?.contentOrNull ?: continue
        if (key == name) {
            // 2-tuple: [name, value]; 3-tuple: [name, value, nsIdx]
            return (entry.getOrNull(1) as? JsonPrimitive)?.contentOrNull
        }
    }
    return null
}

class HtmlTransformerTest {

    private val baseEpub = listOf(
        "mimetype" to "application/epub+zip",
        "META-INF/container.xml" to """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
        """.trimIndent(),
        "content.opf" to """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="BookId">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>T</dc:title>
                <dc:identifier id="BookId">urn:uuid:x</dc:identifier>
              </metadata>
              <manifest>
                <item id="ch1" href="ch1.html" media-type="application/xhtml+xml"/>
                <item id="ch2" href="ch2.html" media-type="application/xhtml+xml"/>
                <item id="img1" href="cover.jpg" media-type="image/jpeg"/>
                <item id="icon" href="favicon.ico"/>
                <item id="css1" href="style.css" media-type="text/css"/>
                <item id="ext" href="external.png" media-type="image/png"/>
              </manifest>
              <spine><itemref idref="ch1"/><itemref idref="ch2"/></spine>
            </package>
        """.trimIndent(),
        "ch1.html" to """
            <?xml version="1.0"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head>
                <title>1</title>
                <link rel="stylesheet" type="text/css" href="style.css"/>
                <link rel="icon" type="image/x-icon" href="favicon.ico"/>
                <style>body { page-break-before: always; font-size: medium; }</style>
              </head>
              <body>
                <p>Hello, <a href="ch2.html#s2">next chapter</a>.</p>
                <p><a href="#local">local anchor</a> <a href="#">empty</a></p>
                <p><a href="missing.html">broken</a></p>
                <p><a href="http://example.com">external</a></p>
                <p><a href="mailto:a@b">mail</a></p>
                <p><a href="/abs">absolute</a></p>
                <p>Inline <span style="font-size: large; page-break-after: avoid;">x</span></p>
                <p><img src="cover.jpg" alt="c"/></p>
                <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">
                  <image xlink:href="external.png"/>
                </svg>
              </body>
            </html>
        """.trimIndent(),
        "ch2.html" to """
            <?xml version="1.0"?>
            <html xmlns="http://www.w3.org/1999/xhtml"><head><title>2</title></head>
            <body><p id="s2">Chapter 2</p></body></html>
        """.trimIndent(),
        "cover.jpg" to "JPGDATA",
        "external.png" to "PNGDATA",
        "favicon.ico" to "ICONDATA",
        "style.css" to "body { color: red; }",
    )

    /** Re-extract a fresh container per test and run transformHtml. */
    private fun transformedTree(): JsonObject {
        val container = extractEpub(baseEpub)
        val linkToMap: LinkToMap = HashMap()
        HtmlTransformer.transformHtml(container, "ch1.html", "abc123", linkToMap)
        val bytes = container.rawData("ch1.html")
        return JSON.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    }

    @Test
    fun `file is rewritten to html-as-json bytes`() {
        val tree = transformedTree()
        // Top-level shape: {"version":1,"tree":<node>,"ns_map":[...]}
        assertEquals(1, (tree["version"] as JsonPrimitive).content.toInt())
        assertNotNull(tree["tree"])
        assertNotNull(tree["ns_map"])
    }

    @Test
    fun `data-calibre-src is added to xhtml img and svg image`() {
        val tree = transformedTree()
        val imgs = tree.findAllElements("img")
        assertTrue(imgs.isNotEmpty(), "no img in transformed JSON")
        assertEquals("cover.jpg", imgs.first().attr("data-calibre-src"))
        val svgImages = tree.findAllElements("image")
        assertTrue(svgImages.isNotEmpty(), "no svg image in transformed JSON")
        assertEquals("external.png", svgImages.first().attr("data-calibre-src"))
    }

    @Test
    fun `non-css link has its attributes stripped and css link preserved`() {
        val tree = transformedTree()
        val links = tree.findAllElements("link")
        // The stylesheet link keeps href, type, rel. The icon link is cleared.
        val css = links.firstOrNull { it.attr("href") == "style.css" }
        assertNotNull(css, "stylesheet link missing")
        assertEquals("text/css", css.attr("type"))
        assertEquals("stylesheet", css.attr("rel"))
        val icon = links.firstOrNull { it !== css }
        assertNotNull(icon, "icon link missing — was it removed entirely?")
        assertEquals(null, icon.attr("href"), "icon link href should have been cleared")
        assertEquals(null, icon.attr("rel"), "icon link rel should have been cleared")
    }

    @Test
    fun `anchor href becomes javascript void plus data linkUid JSON`() {
        val container = extractEpub(baseEpub)
        val linkToMap: LinkToMap = HashMap()
        val uid = "abc123"
        HtmlTransformer.transformHtml(container, "ch1.html", uid, linkToMap)

        val tree = JSON.parseToJsonElement(container.rawData("ch1.html").toString(Charsets.UTF_8)).jsonObject
        val anchors = tree.findAllElements("a")
        // The link to ch2.html#s2 should have href="javascript:void(0)" and a
        // data-<uid> attr whose JSON contains "name":"ch2.html","frag":"s2".
        val next = anchors.firstOrNull {
            it.attr("href") == "javascript:void(0)" &&
                (it.attr("data-$uid") ?: "").contains("\"name\": \"ch2.html\"")
        }
        assertNotNull(next, "expected an anchor rewritten for ch2.html")
        assertTrue("\"frag\": \"s2\"" in (next.attr("data-$uid") ?: ""), "frag not encoded")

        // link_to_map entry: ch2.html → s2 → ch1.html
        val dest = linkToMap["ch2.html"]
        assertNotNull(dest)
        assertTrue("s2" in dest)
        assertTrue("ch1.html" in dest["s2"]!!)
    }

    @Test
    fun `local fragment link records current file as target`() {
        val container = extractEpub(baseEpub)
        val linkToMap: LinkToMap = HashMap()
        val uid = "abc123"
        HtmlTransformer.transformHtml(container, "ch1.html", uid, linkToMap)

        val tree = JSON.parseToJsonElement(container.rawData("ch1.html").toString(Charsets.UTF_8)).jsonObject
        val anchors = tree.findAllElements("a")
        val local = anchors.firstOrNull {
            (it.attr("data-$uid") ?: "").contains("\"frag\": \"local\"")
        }
        assertNotNull(local, "expected a #local anchor rewritten")
        assertEquals("javascript:void(0)", local.attr("href"))
        val dest = linkToMap["ch1.html"]
        assertNotNull(dest)
        assertTrue("local" in dest)
        assertTrue("ch1.html" in dest["local"]!!)
    }

    @Test
    fun `empty fragment href still produces a data linkUid with empty frag`() {
        val container = extractEpub(baseEpub)
        val linkToMap: LinkToMap = HashMap()
        val uid = "abc123"
        HtmlTransformer.transformHtml(container, "ch1.html", uid, linkToMap)

        val tree = JSON.parseToJsonElement(container.rawData("ch1.html").toString(Charsets.UTF_8)).jsonObject
        val anchors = tree.findAllElements("a")
        val empty = anchors.firstOrNull {
            (it.attr("data-$uid") ?: "").contains("\"frag\": \"\"")
        }
        assertNotNull(empty, "expected an anchor rewritten for #")
        assertEquals("javascript:void(0)", empty.attr("href"))
    }

    @Test
    fun `href pointing outside the book is marked missing`() {
        val container = extractEpub(baseEpub)
        val linkToMap: LinkToMap = HashMap()
        val uid = "abc123"
        HtmlTransformer.transformHtml(container, "ch1.html", uid, linkToMap)

        val tree = JSON.parseToJsonElement(container.rawData("ch1.html").toString(Charsets.UTF_8)).jsonObject
        val anchors = tree.findAllElements("a")
        val missing = anchors.firstOrNull {
            (it.attr("data-$uid") ?: "").contains("\"missing\": true")
        }
        assertNotNull(missing, "expected a missing.html anchor rewritten")
        assertEquals("javascript:void(0)", missing.attr("href"))
        // Upstream's missing: branch only sets the data attr — link_to_map
        // has NO entry for an unresolvable target.
        assertTrue("missing.html" !in linkToMap, "missing target must not enter link_to_map")
    }

    @Test
    fun `external http mailto and absolute path href are left untouched`() {
        val container = extractEpub(baseEpub)
        val linkToMap: LinkToMap = HashMap()
        val uid = "abc123"
        HtmlTransformer.transformHtml(container, "ch1.html", uid, linkToMap)

        val tree = JSON.parseToJsonElement(container.rawData("ch1.html").toString(Charsets.UTF_8)).jsonObject
        val anchors = tree.findAllElements("a")
        val http = anchors.firstOrNull { it.attr("href") == "http://example.com" }
        assertNotNull(http, "http anchor missing")
        assertEquals(null, http.attr("data-$uid"))
        val mail = anchors.firstOrNull { it.attr("href") == "mailto:a@b" }
        assertNotNull(mail, "mailto anchor missing")
        assertEquals(null, mail.attr("data-$uid"))
        val abs = anchors.firstOrNull { it.attr("href") == "/abs" }
        assertNotNull(abs, "absolute anchor missing")
        assertEquals(null, abs.attr("data-$uid"))
    }

    @Test
    fun `inline style attrs are rewritten by css transformer`() {
        val tree = transformedTree()
        val spans = tree.findAllElements("span")
        assertTrue(spans.isNotEmpty(), "no span in transformed JSON")
        val style = spans.first().attr("style") ?: ""
        // font-size: large → 1.125rem; page-break-after: avoid → break-after: avoid
        assertTrue("1.125rem" in style, "font-size not normalized: $style")
        assertTrue("break-after" in style, "page-break not rewritten: $style")
    }

    @Test
    fun `head style block is rewritten`() {
        val tree = transformedTree()
        val styles = tree.findAllElements("style")
        assertTrue(styles.isNotEmpty(), "no style in transformed JSON")
        // The text content is in the "x" field of the html_as_json element.
        val text = (styles.first()["x"] as? JsonPrimitive)?.contentOrNull ?: ""
        assertTrue("break-before" in text, "page-break-before not rewritten: $text")
        assertTrue("1rem" in text, "font-size medium not normalized: $text")
    }
}
