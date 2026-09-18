// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// parseRichText covers the subset the viewer's host messages use; the hostile
// cases (book-title-driven) must degrade to text, never throw.

package dev.calibre.viewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RichTextParserTest {

    @Test
    fun `plain text passes through untouched`() {
        assertEquals(RichText("hello 世界", emptyList()), parseRichText("hello 世界"))
    }

    @Test
    fun `italic tag becomes a span`() {
        val r = parseRichText("Loading next section from <i>书名</i>, please wait…")
        assertEquals("Loading next section from 书名, please wait…", r.text)
        assertEquals(listOf(RichSpan(26, 28, RichStyle.ITALIC)), r.spans)
    }

    @Test
    fun `nested tags produce overlapping spans`() {
        val r = parseRichText("<b>bold <i>both</i></b> tail")
        assertEquals("bold both tail", r.text)
        assertEquals(2, r.spans.size)
        assertTrue(r.spans.any { it == RichSpan(0, 9, RichStyle.BOLD) })
        assertTrue(r.spans.any { it == RichSpan(5, 9, RichStyle.ITALIC) })
    }

    @Test
    fun `emphasis synonyms and underline map to styles`() {
        val r = parseRichText("<em>a</em><strong>b</strong><u>c</u>")
        assertEquals("abc", r.text)
        assertEquals(
            listOf(
                RichSpan(0, 1, RichStyle.ITALIC),
                RichSpan(1, 2, RichStyle.BOLD),
                RichSpan(2, 3, RichStyle.UNDERLINE),
            ),
            r.spans,
        )
    }

    @Test
    fun `line breaks and paragraphs become newlines`() {
        assertEquals("a\nb", parseRichText("a<br>b").text)
        // Each closed paragraph emits a break, including the trailing one.
        assertEquals("a\n\nb\n\n", parseRichText("<p>a</p><p>b</p>").text)
    }

    @Test
    fun `entities decode`() {
        assertEquals("a & b < c > d \" e", parseRichText("a &amp; b &lt; c &gt; d &quot; e").text)
        assertEquals("it's", parseRichText("it&#39;s").text)
        assertEquals("x y", parseRichText("x&nbsp;y").text)
    }

    @Test
    fun `unknown tags are stripped and stray angle brackets stay literal`() {
        assertEquals("t", parseRichText("<span>t</span>").text)
        assertEquals("a < b", parseRichText("a < b").text)
        assertEquals("a & b", parseRichText("a & b").text)
    }

    @Test
    fun `hostile input from book titles degrades to text`() {
        // unclosed tag → styles through to the end, no throw
        assertEquals(RichText("x", listOf(RichSpan(0, 1, RichStyle.ITALIC))), parseRichText("<i>x"))
        // mismatched close → inner tags end at the close, no throw
        val r = parseRichText("<b><i>y</b>")
        assertEquals("y", r.text)
        assertEquals(2, r.spans.size)
        // unterminated tag → literal text
        assertEquals("<x y", parseRichText("<x y").text)
        // entity without a terminator → literal ampersand
        assertEquals("a & b", parseRichText("a & b").text)
    }
}
