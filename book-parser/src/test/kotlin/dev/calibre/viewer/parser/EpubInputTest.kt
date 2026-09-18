// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// End-to-end tests for EpubInput.convert() over synthetic EPUB zips: OPF
// location rewrite, spine cleanup, cover rationalization (for_viewer
// semantics), DRM detection, IDPF font deobfuscation and the EPUB 3
// nav->NCX conversion.

package dev.calibre.viewer.parser

import dev.calibre.viewer.parser.dom.DomParser
import dev.calibre.viewer.parser.epub.BookContainer
import dev.calibre.viewer.parser.epub.DrmException
import dev.calibre.viewer.parser.epub.EpubInput
import dev.calibre.viewer.parser.epub.NotEpubException
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.xmlpull.v1.XmlPullParserFactory

private fun newParser(): DomParser {
    val factory = XmlPullParserFactory.newInstance()
    return DomParser { factory.newPullParser() }
}

/** Minimal EPUB2 book: NCX toc, HTML cover first in spine, raster cover. */
private val EPUB2_ENTRIES = listOf(
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
            <dc:title>Test Book</dc:title>
            <dc:identifier id="BookId">urn:uuid:12345678-1234-5678-1234-567812345678</dc:identifier>
            <meta name="cover" content="cover-image"/>
          </metadata>
          <manifest>
            <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
            <item id="ch1" href="ch1.html" media-type="application/xhtml+xml"/>
            <item id="ch2" href="ch2.html" media-type="application/xhtml+xml"/>
            <item id="css" href="style.css" media-type="text/css"/>
            <item id="cover-image" href="cover.jpg" media-type="image/jpeg"/>
          </manifest>
          <spine toc="ncx">
            <itemref idref="ch1" linear="yes"/>
            <itemref idref="ch2"/>
            <itemref idref="ch1"/>
            <itemref idref="missing"/>
          </spine>
          <guide>
            <reference type="cover" title="Cover" href="ch1.html"/>
          </guide>
        </package>
    """.trimIndent(),
    "toc.ncx" to """
        <?xml version="1.0"?>
        <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1" xml:lang="en">
          <head><meta name="dtb:uid" content="urn:uuid:12345678-1234-5678-1234-567812345678"/></head>
          <navMap>
            <navPoint id="np1" playOrder="1"><navLabel><text>Chapter 1</text></navLabel><content src="ch1.html"/></navPoint>
            <navPoint id="np2" playOrder="2"><navLabel><text>Chapter 2</text></navLabel><content src="ch2.html#s1"/></navPoint>
          </navMap>
        </ncx>
    """.trimIndent(),
    "ch1.html" to """
        <?xml version="1.0"?>
        <html xmlns="http://www.w3.org/1999/xhtml"><head><title>C1</title></head>
        <body><h1>One</h1><p>text</p></body></html>
    """.trimIndent(),
    "ch2.html" to """
        <?xml version="1.0"?>
        <html xmlns="http://www.w3.org/1999/xhtml"><head><title>C2</title></head>
        <body><h1 id="s1">Two</h1><p>more</p></body></html>
    """.trimIndent(),
    "style.css" to "p { color: black; }\n",
    "cover.jpg" to "JPEGDATA",
)

private fun buildEpub(entries: List<Pair<String, Any>>): File {
    val f = File.createTempFile("test-book", ".epub")
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

private fun convertToTmpDir(entries: List<Pair<String, Any>>, isKepub: Boolean = false): File {
    val tdir = createTempDir(prefix = "test-extract")
    tdir.deleteRecursivelyOnExit()
    EpubInput(newParser()).convert(buildEpub(entries), tdir, isKepub)
    return tdir
}

private fun File.deleteRecursivelyOnExit(): File {
    // best effort for JVM tests; the OS temp cleaner handles the rest
    File(this.path).also { Runtime.getRuntime().addShutdownHook(Thread { it.deleteRecursively() }) }
    return this
}

class EpubInputTest {

    @Test
    fun `epub2 with html cover rationalizes guide and dedups spine`() {
        val tdir = convertToTmpDir(EPUB2_ENTRIES)
        val contentOpf = File(tdir, "content.opf")
        assertTrue(contentOpf.isFile)
        val opf = newParser().parseXml(contentOpf.readBytes())

        // guide cover moved to the raster image, titlepage added for the html cover
        val refs = opf.walk().filterIsInstance<dev.calibre.viewer.parser.dom.DomElement>()
            .filter { it.localName == "reference" }
        val coverRef = refs.first { it.getAttr("type") == "cover" }
        assertEquals("cover.jpg", coverRef.getAttr("href"))
        val titlepageRef = refs.first { it.getAttr("type") == "titlepage" }
        assertEquals("ch1.html", titlepageRef.getAttr("href"))

        // duplicate itemref removed; a missing-manifest idref is KEPT in the OPF
        // (upstream only drops null idrefs / not-for-spine / duplicates — the
        // container's spine_names filters the dangling one later)
        val itemrefs = opf.walk().filterIsInstance<dev.calibre.viewer.parser.dom.DomElement>()
            .filter { it.localName == "itemref" }.toList()
        assertEquals(listOf("ch1", "ch2", "missing"), itemrefs.map { it.getAttr("idref") })
        assertEquals(null, itemrefs[0].getAttr("linear"))

        // the container view does drop the dangling idref (manifest lookup fails)
        val container = BookContainer.open(tdir, "content.opf", newParser())
        assertEquals(listOf("ch1.html", "ch2.html"), container.spineItemNames)
    }

    @Test
    fun `container fallback finds opf without container xml`() {
        val entries = EPUB2_ENTRIES.filter { it.first != "META-INF/container.xml" }
        val tdir = convertToTmpDir(entries)
        assertTrue(File(tdir, "content.opf").isFile)
    }

    @Test
    fun `non-epub zip raises NotEpub`() {
        assertFailsWith<NotEpubException> {
            convertToTmpDir(listOf("readme.txt" to "not a book"))
        }
    }

    @Test
    fun `opf in subdirectory gets root-relative href rewrite`() {
        val relocated = EPUB2_ENTRIES.map { (name, content) ->
            when (name) {
                "mimetype", "META-INF/container.xml" -> name to content
                    .replace("content.opf", "OEBPS/content.opf")
                else -> "OEBPS/$name" to content
            }
        }
        val tdir = convertToTmpDir(relocated)
        val opf = newParser().parseXml(File(tdir, "content.opf").readBytes())
        val hrefs = opf.walk().filterIsInstance<dev.calibre.viewer.parser.dom.DomElement>()
            .filter { it.localName == "item" }.map { it.getAttr("href") }
        assertTrue("OEBPS/ch1.html" in hrefs, "hrefs=$hrefs")
        assertTrue("OEBPS/toc.ncx" in hrefs, "hrefs=$hrefs")
        // original OPF is still on disk (untouched), normalized copy at root
        assertTrue(File(tdir, "OEBPS/content.opf").isFile)
    }

    @Test
    fun `unknown encryption algorithm means drm`() {
        val entries = EPUB2_ENTRIES + (
            "META-INF/encryption.xml" to """
                <?xml version="1.0"?>
                <enc:encryption xmlns:enc="urn:oasis:names:tc:opendocument:xmlns:container">
                  <enc:EncryptedData>
                    <enc:EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes128-cbc"/>
                    <enc:CipherData><enc:CipherReference URI="ch1.html"/></enc:CipherData>
                  </enc:EncryptedData>
                </enc:encryption>
            """.trimIndent()
            )
        assertFailsWith<DrmException> { convertToTmpDir(entries) }
    }

    @Test
    fun `idpf font obfuscation is decrypted in place`() {
        val uid = "urn:uuid:12345678-1234-5678-1234-567812345678"
        val key = MessageDigest.getInstance("SHA-1")
            .digest(uid.replace(Regex("[ \\t\\r\\n]"), "").toByteArray())
        val original = ByteArray(1500) { (it * 7 and 0xff).toByte() }
        val obfuscated = original.copyOf()
        for (i in 0 until minOf(1040, obfuscated.size)) {
            obfuscated[i] = (obfuscated[i].toInt() xor key[i % key.size].toInt()).toByte()
        }

        val entries = EPUB2_ENTRIES + listOf<Pair<String, Any>>(
            "font.ttf" to obfuscated,
            "META-INF/encryption.xml" to """
                <?xml version="1.0"?>
                <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"
                            xmlns:enc="http://www.idpf.org/2008/embedding">
                  <enc:EncryptedData>
                    <enc:EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding"/>
                    <enc:CipherData><enc:CipherReference URI="font.ttf"/></enc:CipherData>
                  </enc:EncryptedData>
                </encryption>
            """.trimIndent()
        )
        val tdir = convertToTmpDir(entries)
        assertTrue(File(tdir, "font.ttf").readBytes().contentEquals(original))
    }

    @Test
    fun `epub3 nav generates ncx manifest item and spine toc`() {
        val nav = """
            <?xml version="1.0"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
            <head><title>Nav</title></head>
            <body>
              <nav epub:type="toc" id="toc">
                <h1>Contents</h1>
                <ol>
                  <li><a href="ch1.html">Chapter 1</a></li>
                  <li><span>No link</span>
                    <ol><li><a href="ch2.html#s1">Chapter 2.1</a></li></ol>
                  </li>
                </ol>
              </nav>
              <nav epub:type="page-list"><ol>
                <li><a href="ch1.html#p1">1</a></li>
              </ol></nav>
            </body></html>
        """.trimIndent()
        val opf = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="BookId">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>EPUB3 Book</dc:title>
                <dc:identifier id="BookId">urn:uuid:abcdef-abcdef</dc:identifier>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="ch1" href="ch1.html" media-type="application/xhtml+xml"/>
                <item id="ch2" href="ch2.html" media-type="application/xhtml+xml"/>
                <item id="cover" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>
              </manifest>
              <spine>
                <itemref idref="ch1"/>
                <itemref idref="ch2"/>
              </spine>
            </package>
        """.trimIndent()
        val entries = listOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to EPUB2_ENTRIES[1].second,
            "content.opf" to opf,
            "nav.xhtml" to nav,
            "ch1.html" to EPUB2_ENTRIES.first { it.first == "ch1.html" }.second,
            "ch2.html" to EPUB2_ENTRIES.first { it.first == "ch2.html" }.second,
            "cover.jpg" to "JPEGDATA",
        )
        val tdir = convertToTmpDir(entries)

        // generated NCX exists at the book root next to the nav
        val ncxFile = File(tdir, "nav-generated.ncx")
        assertTrue(ncxFile.isFile, "generated ncx missing")

        val opfOut = newParser().parseXml(File(tdir, "content.opf").readBytes())
        val items = opfOut.walk().filterIsInstance<dev.calibre.viewer.parser.dom.DomElement>()
            .filter { it.localName == "item" }
        val ncxItem = items.first { it.getAttr("href") == "nav-generated.ncx" }
        assertEquals("application/x-dtbncx+xml", ncxItem.getAttr("media-type"))
        val spines = opfOut.walk().filterIsInstance<dev.calibre.viewer.parser.dom.DomElement>()
            .filter { it.localName == "spine" }
        assertEquals(ncxItem.getAttr("id"), spines.first().getAttr("toc"))

        // EPUB3 raster cover (cover-image property) lands in the guide
        val refs = opfOut.walk().filterIsInstance<dev.calibre.viewer.parser.dom.DomElement>()
            .filter { it.localName == "reference" }
        assertEquals("cover.jpg", refs.first { it.getAttr("type") == "cover" }.getAttr("href"))

        // the generated NCX itself parses back through the NCX reader
        val container = BookContainer.open(tdir, "content.opf", newParser())
        val ncx = dev.calibre.viewer.parser.epub.parseNcx(container, "nav-generated.ncx")
        assertEquals(listOf("Chapter 1", "No link"), ncx.children.map { it.title })
        assertEquals("ch1.html", ncx.children[0].dest)
        assertEquals(null, ncx.children[1].dest)
        val nested = ncx.children[1].children.single()
        assertEquals("Chapter 2.1", nested.title)
        assertEquals("ch2.html", nested.dest)
        assertEquals("s1", nested.frag)
    }
}
