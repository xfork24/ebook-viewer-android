// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Tests for the calibre-book-metadata.json producer — the SERIALIZABLE_FIELDS
// subset extractable from an EPUB OPF, with the metadata_as_dict is_null rule
// (empty values omit the key entirely).

package dev.calibre.viewer.parser

import dev.calibre.viewer.parser.dom.DomParser
import dev.calibre.viewer.parser.epub.BookContainer
import dev.calibre.viewer.parser.render.BookMetadata
import dev.calibre.viewer.parser.render.BookMetadata.normalizeIsoDate
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.xmlpull.v1.XmlPullParserFactory

class BookMetadataTest {

    private fun containerOf(opf: String): BookContainer {
        val tdir = createTempDir(prefix = "meta-test")
        Runtime.getRuntime().addShutdownHook(Thread { tdir.deleteRecursively() })
        File(tdir, "content.opf").writeText(opf)
        val factory = XmlPullParserFactory.newInstance()
        return BookContainer.open(tdir, "content.opf", DomParser { factory.newPullParser() })
    }

    private val richOpf = """
        <?xml version="1.0"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="2.0"
                 xmlns:dc="http://purl.org/dc/elements/1.1/"
                 xmlns:opf="http://www.idpf.org/2007/opf" unique-identifier="BookId">
          <metadata>
            <dc:title>A &amp; B</dc:title>
            <dc:creator opf:file-as="Doe, Jane" opf:role="aut">Jane Doe</dc:creator>
            <dc:creator>John Roe</dc:creator>
            <dc:contributor opf:role="bkp">Producer Inc</dc:contributor>
            <dc:language>en</dc:language>
            <dc:language>de</dc:language>
            <dc:publisher>Small Press</dc:publisher>
            <dc:date>2020-03-01T00:00:00+00:00</dc:date>
            <dc:subject>Fiction</dc:subject>
            <dc:subject>Sci-fi</dc:subject>
            <dc:description>A book about things</dc:description>
            <dc:rights>Public domain</dc:rights>
            <dc:identifier id="BookId" opf:scheme="UUID">urn:uuid:1234</dc:identifier>
            <dc:identifier scheme="ISBN">978-1-4028-9462-6</dc:identifier>
            <dc:identifier>no-scheme-no-urn</dc:identifier>
            <meta name="calibre:series" content="Trilogy"/>
            <meta name="calibre:series_index" content="2.5"/>
            <meta name="calibre:title_sort" content="B, A &amp;"/>
          </metadata>
          <manifest/>
          <spine/>
        </package>
    """.trimIndent()

    @Test
    fun `extracts the full field set`() {
        val m = BookMetadata.extract(containerOf(richOpf))
        assertEquals("A & B", m["title"])
        assertEquals(listOf("Jane Doe", "John Roe"), m["authors"])
        assertEquals(mapOf("Jane Doe" to "Doe, Jane"), m["author_sort_map"])
        assertEquals("Producer Inc", m["book_producer"])
        assertEquals(listOf("en", "de"), m["languages"])
        assertEquals("Small Press", m["publisher"])
        assertEquals("2020-03-01", m["pubdate"])
        assertEquals(listOf("Fiction", "Sci-fi"), m["tags"])
        assertEquals("A book about things", m["comments"])
        assertEquals("Public domain", m["rights"])
        assertEquals(mapOf("uuid" to "urn:uuid:1234", "isbn" to "978-1-4028-9462-6"), m["identifiers"])
        assertEquals("Trilogy", m["series"])
        assertEquals(2.5, m["series_index"])
        assertEquals("B, A &", m["title_sort"])
    }

    @Test
    fun `empty opf omits every key`() {
        val m = BookMetadata.extract(containerOf("<package xmlns=\"http://www.idpf.org/2007/opf\"/>"))
        assertTrue(m.isEmpty(), "expected empty metadata, got $m")
    }

    @Test
    fun `empty-valued elements are treated as null`() {
        val m = BookMetadata.extract(containerOf("""
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0"
                     xmlns:dc="http://purl.org/dc/elements/1.1/">
              <metadata>
                <dc:title></dc:title>
                <dc:creator>   </dc:creator>
                <dc:date>not a date at all</dc:date>
                <meta name="calibre:series" content=""/>
              </metadata>
            </package>
        """.trimIndent()))
        assertFalse(m.containsKey("title"))
        assertFalse(m.containsKey("authors"))
        assertFalse(m.containsKey("pubdate"))
        assertFalse(m.containsKey("series"))
    }

    @Test
    fun `isbn identifier without scheme attribute still maps via urn prefix`() {
        val m = BookMetadata.extract(containerOf("""
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0"
                     xmlns:dc="http://purl.org/dc/elements/1.1/">
              <metadata>
                <dc:identifier>urn:isbn:978-1-4028-9462-6</dc:identifier>
              </metadata>
            </package>
        """.trimIndent()))
        assertEquals(mapOf("isbn" to "urn:isbn:978-1-4028-9462-6"), m["identifiers"])
    }

    @Test
    fun `normalizeIsoDate accepts year month day and datetime prefixes`() {
        assertEquals("2011-01-01", normalizeIsoDate("2011"))
        assertEquals("2011-05-01", normalizeIsoDate("2011-05"))
        assertEquals("2011-05-30", normalizeIsoDate("2011-05-30"))
        assertEquals("2011-05-30", normalizeIsoDate("2011-05-30T14:22:00Z"))
        assertNull(normalizeIsoDate("Spring of 2011"))
        assertNull(normalizeIsoDate("2011-13-01"))
        assertNull(normalizeIsoDate("2011-05-32"))
    }
}
