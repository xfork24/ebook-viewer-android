// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Tests for CoverFinder, mirroring the find_cover_image / find_cover_page /
// create_cover_page paths in calibre/src/calibre/srv/render_book.py +
// calibre/src/calibre/ebooks/oeb/polish/cover.py. Each test builds a minimal
// EPUB zip, extracts it through the existing EpubInput pipeline, then runs
// the cover finder on the populated BookContainer.

package dev.calibre.viewer.parser

import dev.calibre.viewer.parser.dom.DomParser
import dev.calibre.viewer.parser.epub.BookContainer
import dev.calibre.viewer.parser.epub.EpubInput
import dev.calibre.viewer.parser.transform.CoverFinder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.xmlpull.v1.XmlPullParserFactory

private fun newParser(): DomParser {
    val factory = XmlPullParserFactory.newInstance()
    return DomParser { factory.newPullParser() }
}

private fun buildEpub(entries: List<Pair<String, Any>>): File {
    val f = File.createTempFile("cover-test", ".epub")
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
    val tdir = createTempDir(prefix = "cover-extract")
    tdir.deleteRecursivelyOnExit()
    EpubInput(newParser()).convert(buildEpub(entries), tdir, false)
    return BookContainer.open(tdir, "content.opf", newParser())
}

private fun File.deleteRecursivelyOnExit(): File {
    Runtime.getRuntime().addShutdownHook(Thread { this.deleteRecursively() })
    return this
}

class CoverFinderTest {

    private val baseEpub2 = listOf(
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
                <item id="cover-img" href="cover.jpg" media-type="image/jpeg"/>
                <item id="cover-page" href="cover.html" media-type="application/xhtml+xml"/>
                <item id="ch1" href="ch1.html" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="cover-page"/>
                <itemref idref="ch1"/>
              </spine>
              <guide>
                <reference type="cover" title="Cover" href="cover.html"/>
              </guide>
            </package>
        """.trimIndent(),
        "cover.html" to """
            <?xml version="1.0"?>
            <html xmlns="http://www.w3.org/1999/xhtml"><head><title>C</title></head>
            <body><img src="cover.jpg"/></body></html>
        """.trimIndent(),
        "cover.jpg" to "JPEGDATA",
        "ch1.html" to """
            <?xml version="1.0"?>
            <html xmlns="http://www.w3.org/1999/xhtml"><head><title>1</title></head>
            <body><p>x</p></body></html>
        """.trimIndent(),
    )

    @Test
    fun `epub2 with guide cover and meta cover picks both`() {
        val container = extractEpub(baseEpub2)
        val (titlepage, raster) = CoverFinder.findEpubCover(container)
        assertEquals("cover.html", titlepage)
        assertEquals("cover.jpg", raster)
    }

    @Test
    fun `createCoverPage overwrites the titlepage file`() {
        val container = extractEpub(baseEpub2)
        val (raster, titlepage) = CoverFinder.createCoverPage(container)
        assertEquals("cover.jpg", raster)
        assertEquals("cover.html", titlepage)

        // The cover.html file now contains the object-fit template referencing
        // the raster via a relative href (./cover.jpg from cover.html).
        val newBytes = container.rawData("cover.html")
        val text = newBytes.toString(Charsets.UTF_8)
        assertTrue("object-fit: contain" in text, "missing object-fit style")
        assertTrue("img src=\"" in text, "missing img tag")
        assertTrue("cover.jpg" in text, "missing raster href")
    }

    @Test
    fun `epub2 with no cover returns null null`() {
        val entries = baseEpub2
            .filter { it.first != "content.opf" }
            .plus("content.opf" to """
                <?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="BookId">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>T</dc:title>
                    <dc:identifier id="BookId">urn:uuid:x</dc:identifier>
                  </metadata>
                  <manifest>
                    <item id="ch1" href="ch1.html" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="ch1"/></spine>
                </package>
            """.trimIndent())
        val container = extractEpub(entries)
        val (titlepage, raster) = CoverFinder.findEpubCover(container)
        assertNull(titlepage)
        assertNull(raster)
    }

    @Test
    fun `epub3 cover-image property is found via manifest property`() {
        val entries = listOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
            """.trimIndent(),
            "content.opf" to """
                <?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="BookId">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>T</dc:title>
                    <dc:identifier id="BookId">urn:uuid:x</dc:identifier>
                  </metadata>
                  <manifest>
                    <item id="cover" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                    <item id="ch1" href="ch1.html" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="ch1"/></spine>
                </package>
            """.trimIndent(),
            "ch1.html" to """
                <?xml version="1.0"?>
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>1</title></head>
                <body><p>x</p></body></html>
            """.trimIndent(),
            "cover.jpg" to "JPEGDATA",
        )
        val container = extractEpub(entries)
        assertEquals("cover.jpg", CoverFinder.findCoverImage(container))
    }

    @Test
    fun `prepareStringForXml escapes attribute value`() {
        assertEquals("a&amp;b&lt;c&gt;d&quot;e&apos;f", CoverFinder.prepareStringForXml("a&b<c>d\"e'f", attribute = true))
        // Non-attribute mode skips " '
        assertEquals("a&amp;b&lt;c&gt;d\"e'f", CoverFinder.prepareStringForXml("a&b<c>d\"e'f", attribute = false))
    }
}