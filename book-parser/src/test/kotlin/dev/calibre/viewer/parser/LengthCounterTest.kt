// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Tests for LengthCounter, mirroring calibre/src/calibre/library/page_count.py
// test_line_counting. Sources are wrapped in <html><body>...</body></html> so
// DomParser.parseHtml takes the no-wrap fast path — html5_parser upstream
// always produces that shape anyway.

package dev.calibre.viewer.parser

import dev.calibre.viewer.parser.dom.DomElement
import dev.calibre.viewer.parser.dom.DomParser
import dev.calibre.viewer.parser.transform.LengthCounter
import kotlin.test.Test
import kotlin.test.assertEquals
import org.xmlpull.v1.XmlPullParserFactory

class LengthCounterTest {

    private val line70: String get() = "a ".repeat(LengthCounter.CHARS_PER_LINE) // 70 sig chars
    private val h1Line: String get() = "h ".repeat(30) // 30 sig chars

    private fun html(body: String): String = "<html><body>$body</body></html>"

    private fun parse(src: String): DomElement {
        val f = XmlPullParserFactory.newInstance()
        return DomParser { f.newPullParser() }.parseHtml(src.toByteArray())
    }

    private fun firstElement(root: DomElement, localName: String): DomElement =
        root.findHtmlElements(localName).first()

    @Test
    fun `getLineCount with comment, inline br, and block p`() {
        // mirror of <p><!--{line}-->{line}<br>{line} → 2
        val root = parse(html("<p><!--$line70-->$line70<br>$line70</p>"))
        assertEquals(2, LengthCounter.getLineCount(root))
    }

    @Test
    fun `script element content is ignored`() {
        // <body>{line}<script>{line}</script> → 1
        val root = parse(html("$line70<script>$line70</script>"))
        assertEquals(1, LengthCounter.getLineCount(root))
    }

    @Test
    fun `span does not add lines itself`() {
        // <body>{line}<span>{line}<p> → 2 (body=1 from line in body.text,
        // p=1; span is not a block)
        val root = parse(html("$line70<span>$line70<p></p></span>"))
        assertEquals(2, LengthCounter.getLineCount(root))
    }

    @Test
    fun `h1 adds 2-line margin`() {
        // <h1>{30}<p>{70} → ceil(30/30)+2 + ceil(70/70) = 3 + 1 = 4
        val root = parse(html("<h1>$h1Line<p>$line70</p></h1>"))
        assertEquals(4, LengthCounter.getLineCount(root))
    }

    @Test
    fun `empty elements contribute zero`() {
        assertEquals(0, LengthCounter.getLineCount(parse(html("<p></p>"))))
        assertEquals(0, LengthCounter.getLineCount(parse(html("<h1></h1>"))))
    }

    @Test
    fun `countChar inline text only`() {
        // <p><i>abc → 3 (p.text=NULL, i.text="abc"; tail of i is empty)
        val root = parse(html("<p><i>abc</i></p>"))
        assertEquals(3, LengthCounter.countChar(firstElement(root, "p")))
    }

    @Test
    fun `countChar img adds 1000`() {
        val root = parse(html("<p><img/></p>"))
        assertEquals(1000, LengthCounter.countChar(firstElement(root, "p")))
    }

    @Test
    fun `countChar svg adds 1000 and counts text`() {
        // svg is a block so it stops recursion from p's countChar; count it
        // directly to exercise the bonus + text rule.
        val root = parse(html("<p><svg>abc</svg></p>"))
        assertEquals(1003, LengthCounter.countChar(firstElement(root, "svg")))
    }

    @Test
    fun `countChar video adds 2000`() {
        val root = parse(html("<p><video>abc</video></p>"))
        assertEquals(2003, LengthCounter.countChar(firstElement(root, "video")))
    }

    @Test
    fun `countChar style text is ignored but tail counted`() {
        // style is a block — recurse directly into it for the rule check
        val root = parse(html("<p>$line70<style>$line70</style>$line70</p>"))
        val style = firstElement(root, "style")
        // style.text ignored, style.tail=line(70)
        assertEquals(70, LengthCounter.countChar(style))
    }

    @Test
    fun `uppercase block tags are recognized`() {
        // <P>{line} → 1; barename "P" is in BLOCKS via UPPERCASE variant
        val root = parse(html("<P>$line70</P>"))
        assertEquals(1, LengthCounter.getLineCount(root))
    }

    @Test
    fun `getLength floors at one page`() {
        assertEquals(LengthCounter.CHARS_PER_PAGE, LengthCounter.getLength(parse(html(""))))
    }

    @Test
    fun `getLength grows beyond floor with enough content`() {
        // 40 lines × 70 chars each = 2800 sig chars in one <p>
        val content = line70.repeat(40)
        val root = parse(html("<p>$content</p>"))
        assertEquals(2800, LengthCounter.getLength(root))
    }
}