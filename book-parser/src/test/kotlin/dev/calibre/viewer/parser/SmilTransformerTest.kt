// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Tests for SmilTransformer.parseSmilTime + transformSmil. The data model
// mirrors the upstream {'textref','par','seq','type'} dicts; we round-trip
// it as SmilSeq / SmilPar.

package dev.calibre.viewer.parser

import dev.calibre.viewer.parser.dom.DomParser
import dev.calibre.viewer.parser.epub.BookContainer
import dev.calibre.viewer.parser.transform.SmilTransformer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.xmlpull.v1.XmlPullParserFactory

class SmilTransformerTest {

    private fun newParser(): DomParser {
        val factory = XmlPullParserFactory.newInstance()
        return DomParser { factory.newPullParser() }
    }

    private fun buildContainer(spineName: String, smilName: String, spineBytes: ByteArray, smilBytes: ByteArray): BookContainer {
        val tdir = createTempDir(prefix = "smil-test")
        Runtime.getRuntime().addShutdownHook(Thread { tdir.deleteRecursively() })
        File(tdir, smilName).writeBytes(smilBytes)
        File(tdir, spineName).writeBytes(spineBytes)
        // The container requires an OPF; we provide a minimal one.
        val opf = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="BookId">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>T</dc:title>
                <dc:identifier id="BookId">urn:uuid:x</dc:identifier>
              </metadata>
              <manifest>
                <item id="ch" href="$spineName" media-type="application/xhtml+xml"/>
                <item id="s" href="$smilName" media-type="application/smil+xml"/>
                <item id="a" href="audio.mp3" media-type="audio/mpeg"/>
              </manifest>
              <spine><itemref idref="ch"/></spine>
            </package>
        """.trimIndent().toByteArray()
        File(tdir, "content.opf").writeBytes(opf)
        return BookContainer.open(tdir, "content.opf", newParser())
    }

    private fun spineBytes(body: String = "<p>hi</p>"): ByteArray =
        ("""<?xml version="1.0"?>
            <html xmlns="http://www.w3.org/1999/xhtml"><head><title>x</title></head>
            <body>$body</body></html>
        """).trimIndent().toByteArray()

    @Test
    fun `parseSmilTime handles all four clock-value forms`() {
        assertEquals(3661.5, SmilTransformer.parseSmilTime("1:01:01.5"))
        assertEquals(75.0, SmilTransformer.parseSmilTime("01:15"))
        assertEquals(2.5, SmilTransformer.parseSmilTime("2.5s"))
        // Upstream checks endswith('s') FIRST, so "123ms" strips to "123m"
        // and float() raises — the ms branch is dead code. Bug-for-bug: our
        // parse must fail too, not return 0.123.
        assertFailsWith<NumberFormatException> { SmilTransformer.parseSmilTime("123ms") }
        assertEquals(180.0, SmilTransformer.parseSmilTime("3min"))
        assertEquals(7200.0, SmilTransformer.parseSmilTime("2h"))
    }

    @Test
    fun `parseSmilTime clamps minutes and seconds but not hours`() {
        // 99 minutes → upstream max(0, min(99,59)) = 59 → 3540
        assertEquals(3540.0, SmilTransformer.parseSmilTime("0:99:0"))
        // 90 seconds in the seconds slot → min(90,59) = 59
        assertEquals(59.0, SmilTransformer.parseSmilTime("0:0:90"))
        // Upstream applies abs() BEFORE the clamp, so abs(-1)=1 flows through
        // both the minute and second clamps: 3600 + 60 + 1 = 3661 (not 3600).
        assertEquals(3661.0, SmilTransformer.parseSmilTime("1:-1:-1"))
    }

    @Test
    fun `transformSmil records par with audio and clipBegin`() {
        val smilBytes = """
            <?xml version="1.0"?>
            <smil xmlns="http://www.w3.org/ns/SMIL" xmlns:epub="http://www.idpf.org/2007/ops">
              <body epub:textref="ch.xhtml">
                <seq epub:textref="ch.xhtml">
                  <par>
                    <text src="ch.xhtml#para1"/>
                    <audio src="audio.mp3" clipBegin="1.5s" clipEnd="3s"/>
                  </par>
                </seq>
              </body>
            </smil>
        """.trimIndent().toByteArray()

        val container = buildContainer("ch.xhtml", "audio.smil", spineBytes(), smilBytes)
        val smilMap = HashMap<String?, SmilTransformer.SmilSeq>()
        SmilTransformer.transformSmil(container, "audio.smil", smilMap)

        val root = smilMap["ch.xhtml"]
        assertNotNull(root)
        assertEquals("root", root.type)
        // body is processed first → its seq wraps the inner <seq>; the <par>
        // lives at body.seq[0].seq[0].par, not body.seq[0].par (the recursion
        // is what creates the second seq level — process_seq walks <seq>
        // children by recursing, not by flattening).
        assertEquals(1, root.seq.size)
        val bodySeq = root.seq[0]
        assertEquals("ch.xhtml" to "", bodySeq.textref)
        assertEquals(0, bodySeq.par.size)
        assertEquals(1, bodySeq.seq.size)
        val innerSeq = bodySeq.seq[0]
        assertEquals("ch.xhtml" to "", innerSeq.textref)
        assertEquals(1, innerSeq.par.size)
        val p = innerSeq.par[0]
        assertEquals(1, p["num"])
        assertEquals("para1", p["anchor"])
        assertEquals("audio.mp3", p["audio"])
        assertEquals(1.5, p["start"]!!)
        assertEquals(3.0, p["end"]!!)
    }

    @Test
    fun `par without audio is dropped and counter still advances`() {
        val smilBytes = """
            <?xml version="1.0"?>
            <smil xmlns="http://www.w3.org/ns/SMIL" xmlns:epub="http://www.idpf.org/2007/ops">
              <body epub:textref="ch.xhtml">
                <seq epub:textref="ch.xhtml">
                  <par><text src="ch.xhtml#x"/></par>
                  <par>
                    <text src="ch.xhtml#y"/>
                    <audio src="audio.mp3"/>
                  </par>
                </seq>
              </body>
            </smil>
        """.trimIndent().toByteArray()
        val container = buildContainer("ch.xhtml", "audio.smil", spineBytes(), smilBytes)
        val smilMap = HashMap<String?, SmilTransformer.SmilSeq>()
        SmilTransformer.transformSmil(container, "audio.smil", smilMap)

        val root = smilMap["ch.xhtml"]!!
        assertEquals(1, root.seq.size)
        val bodySeq = root.seq[0]
        assertEquals(1, bodySeq.seq.size)
        val innerSeq = bodySeq.seq[0]
        // The first par (no audio) is dropped; the surviving one is num=2
        // (counter advanced even for the rejected par, matching upstream).
        assertEquals(1, innerSeq.par.size)
        assertEquals(2, innerSeq.par[0]["num"])
        assertEquals("y", innerSeq.par[0]["anchor"])
    }

    @Test
    fun `par whose text targets a different file is dropped`() {
        val smilBytes = """
            <?xml version="1.0"?>
            <smil xmlns="http://www.w3.org/ns/SMIL" xmlns:epub="http://www.idpf.org/2007/ops">
              <body epub:textref="ch.xhtml">
                <seq epub:textref="ch.xhtml">
                  <par>
                    <text src="other.xhtml"/>
                    <audio src="audio.mp3"/>
                  </par>
                </seq>
              </body>
            </smil>
        """.trimIndent().toByteArray()
        val container = buildContainer("ch.xhtml", "audio.smil", spineBytes(), smilBytes)
        val smilMap = HashMap<String?, SmilTransformer.SmilSeq>()
        SmilTransformer.transformSmil(container, "audio.smil", smilMap)

        val root = smilMap["ch.xhtml"]!!
        assertEquals(1, root.seq.size)
        // Walk through body → inner seq and confirm the inner seq has no par.
        val innerSeq = root.seq[0].seq[0]
        assertTrue(innerSeq.par.isEmpty())
    }

    @Test
    fun `nested seq targeting a different file is silently dropped`() {
        val smilBytes = """
            <?xml version="1.0"?>
            <smil xmlns="http://www.w3.org/ns/SMIL" xmlns:epub="http://www.idpf.org/2007/ops">
              <body epub:textref="ch.xhtml">
                <seq epub:textref="ch.xhtml">
                  <par>
                    <text src="ch.xhtml#a"/>
                    <audio src="audio.mp3"/>
                  </par>
                  <seq epub:textref="other.xhtml">
                    <par>
                      <text src="other.xhtml#b"/>
                      <audio src="audio.mp3"/>
                    </par>
                  </seq>
                </seq>
              </body>
            </smil>
        """.trimIndent().toByteArray()
        val container = buildContainer("ch.xhtml", "audio.smil", spineBytes(), smilBytes)
        val smilMap = HashMap<String?, SmilTransformer.SmilSeq>()
        SmilTransformer.transformSmil(container, "audio.smil", smilMap)

        val root = smilMap["ch.xhtml"]!!
        val outer = root.seq[0]
        // The nested <seq textref="other.xhtml"> has a target different from
        // its parent body seq, so process_seq returns early; only the one par
        // in the matching inner <seq textref="ch.xhtml"> is kept.
        val innerSeq = outer.seq[0]
        assertEquals(1, innerSeq.par.size)
        assertEquals(0, innerSeq.seq.size)
        assertNull(smilMap["other.xhtml"])
    }
}