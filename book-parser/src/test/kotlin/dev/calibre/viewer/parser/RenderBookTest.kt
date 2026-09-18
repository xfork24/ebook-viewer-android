// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Integration tests for RenderBook — the book-parser orchestrator that
// mirrors calibre's process_exploded_book + the standalone render_for_viewer
// entry point. Each test builds a minimal EPUB zip, runs the full pipeline
// (cover resolution → TOC → HTML/CSS/SMIL transforms → manifest writer),
// then parses the manifest back as JSON to assert the shape.

package dev.calibre.viewer.parser

import dev.calibre.viewer.parser.dom.DomParser
import dev.calibre.viewer.parser.epub.BookContainer
import dev.calibre.viewer.parser.epub.InvalidBookException
import dev.calibre.viewer.parser.epub.EpubInput
import dev.calibre.viewer.parser.render.RenderBook
import dev.calibre.viewer.parser.render.RenderResult
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.xmlpull.v1.XmlPullParserFactory

private fun newParser(): DomParser {
    val factory = XmlPullParserFactory.newInstance()
    return DomParser { factory.newPullParser() }
}

private fun buildEpub(entries: List<Pair<String, Any>>): File {
    val f = File.createTempFile("render-test", ".epub")
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

private fun extractEpub(entries: List<Pair<String, Any>>): Pair<File, String> {
    val tdir = createTempDir(prefix = "render-extract")
    Runtime.getRuntime().addShutdownHook(Thread { tdir.deleteRecursively() })
    EpubInput(newParser()).convert(buildEpub(entries), tdir, false)
    return tdir to "content.opf"
}

private val JSON = Json { ignoreUnknownKeys = true }

class RenderBookTest {

    private val simpleEpub = listOf(
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
                <meta name="cover" content="cover-img"/>
              </metadata>
              <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="cover-img" href="cover.jpg" media-type="image/jpeg"/>
                <item id="cover-page" href="cover.html" media-type="application/xhtml+xml"/>
                <item id="css" href="style.css" media-type="text/css"/>
                <item id="ch1" href="ch1.html" media-type="application/xhtml+xml"/>
                <item id="ch2" href="ch2.html" media-type="application/xhtml+xml"/>
              </manifest>
              <spine toc="ncx" page-progression-direction="rtl">
                <itemref idref="cover-page"/>
                <itemref idref="ch1"/>
                <itemref idref="ch2"/>
              </spine>
              <guide>
                <reference type="cover" title="Cover" href="cover.html"/>
              </guide>
            </package>
        """.trimIndent(),
        "toc.ncx" to """
            <?xml version="1.0"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <head><meta name="dtb:uid" content="uid"/></head>
              <navMap>
                <navPoint id="n1"><navLabel><text>Chapter 1</text></navLabel>
                  <content src="ch1.html"/>
                </navPoint>
                <navPoint id="n2"><navLabel><text>Chapter 2</text></navLabel>
                  <content src="ch2.html#s2"/>
                </navPoint>
              </navMap>
            </ncx>
        """.trimIndent(),
        "cover.html" to """
            <?xml version="1.0"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head><title>C</title></head>
              <body><img src="cover.jpg"/></body>
            </html>
        """.trimIndent(),
        "cover.jpg" to "JPGDATA",
        "style.css" to "body { page-break-before: always; font-size: medium; color: red; }",
        "ch1.html" to """
            <?xml version="1.0"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head><title>1</title><link rel="stylesheet" type="text/css" href="style.css"/></head>
              <body>
                <h1 id="intro">Chapter 1</h1>
                <p>Hello, <a href="ch2.html#s2">next chapter</a>.</p>
                <p><a href="#local">local anchor</a></p>
              </body>
            </html>
        """.trimIndent(),
        "ch2.html" to """
            <?xml version="1.0"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head><title>2</title></head>
              <body><p id="s2">Chapter 2</p></body>
            </html>
        """.trimIndent(),
    )

    private fun runRender(entries: List<Pair<String, Any>> = simpleEpub): Pair<RenderResult, JsonObject> {
        val (tdir, opfName) = extractEpub(entries)
        val result = RenderBook.render(tdir, opfName, newParser())
        val manifestBytes = result.manifestPath.readBytes()
        val tree = JSON.parseToJsonElement(manifestBytes.toString(Charsets.UTF_8)).jsonObject
        return result to tree
    }

    @Test
    fun `manifest version is RENDER_VERSION`() {
        val (_, tree) = runRender()
        assertEquals(1, (tree["version"] as JsonPrimitive).content.toInt())
    }

    @Test
    fun `spine is the linear order of itemrefs`() {
        val (_, tree) = runRender()
        val spine = tree["spine"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("cover.html", "ch1.html", "ch2.html"), spine)
    }

    @Test
    fun `link_uid is a 22-char short uuid over 2-9A-Za-z`() {
        val (result, _) = runRender()
        assertEquals(22, result.linkUid.length)
        val alphabet = ('2'..'9') + ('A'..'Z') + ('a'..'z')
        assertTrue(result.linkUid.all { it in alphabet }, "bad link_uid: ${result.linkUid}")
    }

    @Test
    fun `cover name and title page name are populated`() {
        val (result, _) = runRender()
        assertEquals("cover.jpg", result.rasterCoverName)
        assertEquals("cover.html", result.titlePageName)
    }

    @Test
    fun `page progression direction flows from spine attribute`() {
        val (_, tree) = runRender()
        assertEquals("rtl", (tree["page_progression_direction"] as JsonPrimitive).content)
    }

    @Test
    fun `manifest files excludes opf NCX mimetype and META-INF`() {
        val (_, tree) = runRender()
        val files = tree["files"]!!.jsonObject.keys
        assertFalse("content.opf" in files)
        assertFalse("toc.ncx" in files)
        assertFalse("mimetype" in files)
        assertFalse(files.any { it.startsWith("META-INF/") })
    }

    @Test
    fun `cover template overwrites cover html in place`() {
        val (result, _) = runRender()
        val text = File(result.tdir, "cover.html").readText(Charsets.UTF_8)
        assertTrue("object-fit: contain" in text, "missing object-fit style")
        assertTrue("cover.jpg" in text, "missing raster href")
    }

    @Test
    fun `css file has @charset prepended and page-break rewritten`() {
        val (result, _) = runRender()
        val css = File(result.tdir, "style.css").readText(Charsets.UTF_8)
        assertTrue(css.startsWith("@charset \"UTF-8\";"), "missing @charset prefix")
        assertTrue("break-before" in css, "page-break not rewritten")
    }

    @Test
    fun `html files are rewritten to html_as_json shape`() {
        val (result, _) = runRender()
        val ch1Bytes = File(result.tdir, "ch1.html").readText(Charsets.UTF_8)
        // After transformHtml the file holds the JSON envelope.
        val parsed = JSON.parseToJsonElement(ch1Bytes).jsonObject
        assertEquals(1, (parsed["version"] as JsonPrimitive).content.toInt())
        assertNotNull(parsed["tree"])
    }

    @Test
    fun `anchor map is captured for each html file`() {
        val (result, tree) = runRender()
        val ch1Entry = tree["files"]!!.jsonObject["ch1.html"]!!.jsonObject
        val anchors = ch1Entry["anchor_map"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("intro" in anchors)
    }

    @Test
    fun `html files report non-zero length`() {
        val (result, tree) = runRender()
        val ch1Entry = tree["files"]!!.jsonObject["ch1.html"]!!.jsonObject
        val length = (ch1Entry["length"] as JsonPrimitive).content.toInt()
        assertTrue(length > 0, "expected non-zero length, got $length")
    }

    @Test
    fun `total_length equals sum of html file lengths`() {
        val (_, tree) = runRender()
        val files = tree["files"]!!.jsonObject
        val totalFromFiles = files.entries
            .filter { (it.value as JsonObject)["is_html"]?.jsonPrimitive?.content == "true" }
            .sumOf { (it.value as JsonObject)["length"]!!.jsonPrimitive.content.toInt() }
        val total = (tree["total_length"] as JsonPrimitive).content.toInt()
        assertEquals(totalFromFiles, total)
    }

    @Test
    fun `spine_length counts only spine files`() {
        val (_, tree) = runRender()
        // Cover page is in the spine; the raster image is not.
        val files = tree["files"]!!.jsonObject
        val spineFromFiles = files.entries
            .filter { (it.value as JsonObject)["is_html"]?.jsonPrimitive?.content == "true" }
            .filter { (it.key) in listOf("cover.html", "ch1.html", "ch2.html") }
            .sumOf { (it.value as JsonObject)["length"]!!.jsonPrimitive.content.toInt() }
        val spine = (tree["spine_length"] as JsonPrimitive).content.toInt()
        assertEquals(spineFromFiles, spine)
    }

    @Test
    fun `link_to_map records the ch2 anchor reference from ch1`() {
        val (_, tree) = runRender()
        val ltm = tree["link_to_map"]!!.jsonObject
        assertNotNull(ltm["ch2.html"])
        val frags = ltm["ch2.html"]!!.jsonObject
        // ch1.html referenced ch2.html#s2 → entries under frags["s2"] = ["ch1.html"].
        val sources = frags["s2"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("ch1.html" in sources)
    }

    @Test
    fun `toc anchor map covers ch1 and ch2 fragments`() {
        val (_, tree) = runRender()
        val tam = tree["toc_anchor_map"]!!.jsonObject
        val ch1List = tam["ch1.html"]?.jsonArray
        assertNotNull(ch1List, "missing toc anchor map entry for ch1.html")
        assertTrue(ch1List.isNotEmpty())
        // ch2.html is referenced with frag=s2 → tam["ch2.html"][0].frag == "s2"
        val ch2List = tam["ch2.html"]!!.jsonArray
        val firstFrag = (ch2List[0] as JsonObject)["frag"]?.jsonPrimitive?.contentOrNull
        assertEquals("s2", firstFrag)
    }

    @Test
    fun `has_maths is false for plain epub`() {
        val (_, tree) = runRender()
        assertEquals(false, tree["has_maths"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `book_hash round-trips from argument`() {
        val (tdir, opfName) = extractEpub(simpleEpub)
        val result = RenderBook.render(tdir, opfName, newParser(), bookHash = "abc123")
        val tree = JSON.parseToJsonElement(result.manifestPath.readText()).jsonObject
        assertEquals("abc123", tree["book_hash"]!!.jsonPrimitive.content)
    }

    @Test
    fun `calibre-book-metadata json is written with the opf title`() {
        val (result, _) = runRender()
        assertTrue(result.metadataPath.isFile, "calibre-book-metadata.json not written")
        val metadata = JSON.parseToJsonElement(result.metadataPath.readText()).jsonObject
        assertEquals("T", metadata["title"]!!.jsonPrimitive.content)
        // metadata_as_dict is_null rule: absent creators → no authors key.
        assertFalse("authors" in metadata)
    }

    @Test
    fun `bookmark data is null without calibre_bookmarks txt`() {
        val (result, _) = runRender()
        assertNull(result.bookmarkData)
    }

    @Test
    fun `bookmark data captures raw bytes before the excluded sweep`() {
        val raw = "encoding=json+base64:\nAAAA"
        val (result, _) = runRender(
            simpleEpub + ("META-INF/calibre_bookmarks.txt" to raw)
        )
        assertEquals(raw, result.bookmarkData?.toString(Charsets.UTF_8))
    }

    @Test
    fun `has_smil lands between page_list_anchor_map and files in key order`() {
        val (_, tree) = runRender()
        assertEquals(false, tree["has_smil"]!!.jsonPrimitive.content.toBoolean())
        val raw = runRender().first.manifestPath.readText()
        val ppam = raw.indexOf("\"page_list_anchor_map\"")
        val smil = raw.indexOf("\"has_smil\"")
        val files = raw.indexOf("\"files\"")
        assertTrue(ppam in 0 until smil && smil in 0 until files, "has_smil out of order")
    }

    @Test
    fun `empty spine raises InvalidBookException`() {
        val tdir = createTempDir(prefix = "spineless")
        Runtime.getRuntime().addShutdownHook(Thread { tdir.deleteRecursively() })
        File(tdir, "content.opf").writeText(
            """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Empty</dc:title>
              </metadata>
              <manifest>
                <item id="ch1" href="ch1.html" media-type="application/xhtml+xml"/>
              </manifest>
              <spine/>
            </package>
            """.trimIndent()
        )
        File(tdir, "ch1.html").writeText(
            """
            <?xml version="1.0"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head><title>1</title></head><body><p>x</p></body>
            </html>
            """.trimIndent()
        )
        val ex = runCatching { RenderBook.render(tdir, "content.opf", newParser()) }.exceptionOrNull()
        assertTrue(ex is InvalidBookException, "expected InvalidBookException, got $ex")
        assertEquals("Book is empty, no content in spine", ex?.message)
    }
}
