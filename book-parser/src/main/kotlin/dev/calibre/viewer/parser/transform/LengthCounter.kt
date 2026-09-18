// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Port of calibre/src/calibre/library/page_count.py + the
// get_num_of_significant_chars/count_chars from
// calibre/src/calibre/utils/speedup.c (lines 630-730).
//
// Estimates the line count the viewer would render for a document, used by
// the manifest's per-file length and the book-wide total_length / position
// fraction. Algorithm:
//   getLength(root) = max(CHARS_PER_PAGE, getLineCount(root) * CHARS_PER_LINE)
//   getLineCount(root) = Σ countLine(block) over block-barename descendants
//   countLine(block)   = ceil(countChar(block) / char_num) + margin
//   countChar(root)    = Σ sig(node) + recurse into non-block descendants
//   sig(node)          = codepoints > 32 in node.text + node.tail, with
//                        bonus/ignore rules for script/style/noscript/title/
//                        svg/img/video. Comment nodes count only their tail
//                        (lxml stores comment body in elem.text, but the C
//                        helper does not fetch it for non-element tags).

package dev.calibre.viewer.parser.transform

import dev.calibre.viewer.parser.dom.DomComment
import dev.calibre.viewer.parser.dom.DomElement
import dev.calibre.viewer.parser.dom.DomNode

object LengthCounter {

    const val CHARS_PER_LINE = 70
    const val LINES_PER_PAGE = 36
    const val CHARS_PER_PAGE = CHARS_PER_LINE * LINES_PER_PAGE

    /** (chars_per_line, margin) for headings; default applies to all other blocks. */
    private val HEAD_MAP: Map<String, Pair<Int, Int>> = mapOf(
        "h1" to (30 to 2),
        "h2" to (40 to 2),
        "h3" to (50 to 2),
        "h4" to (60 to 2),
        "h5" to (70 to 2),
        "h6" to (70 to 1),
    )
    private val DEFAULT_HEAD = CHARS_PER_LINE to 0

    /** NON_NAMESPACED_BLOCK_TAGS from calibre/ebooks/oeb/polish/pretty.py:87. */
    private val NON_NAMESPACED_BLOCK_TAGS: Set<String> = linkedSetOf(
        "address", "article", "aside", "audio", "blockquote", "body",
        "canvas", "col", "colgroup", "dd", "div", "dl", "dt", "fieldset",
        "figcaption", "figure", "footer", "form",
        "h1", "h2", "h3", "h4", "h5", "h6",
        "header", "hgroup", "hr", "li", "svg", "noscript", "ol", "output",
        "p", "pre", "script", "section", "style", "table", "tbody", "td",
        "tfoot", "th", "thead", "tr", "ul", "video", "img",
    )

    /**
     * page_count.py:102-104: img/video are subtracted FIRST, then uppercase
     * barenames are derived from what remains — so IMG/VIDEO are excluded in
     * both cases (an <IMG> is inline filler, not a block).
     */
    val BLOCKS: Set<String> = buildSet(NON_NAMESPACED_BLOCK_TAGS.size * 2) {
        for (t in NON_NAMESPACED_BLOCK_TAGS) {
            if (t == "img" || t == "video") continue
            add(t)
            add(t.uppercase())
        }
    }

    /** Length used for the viewer's position/length display. */
    fun getLength(root: DomElement): Int =
        maxOf(CHARS_PER_PAGE, getLineCount(root) * CHARS_PER_LINE)

    /** Number of "lines" the reader would render. Iterates all block descendants. */
    fun getLineCount(root: DomElement): Int {
        var ans = 0
        for (node in root.descendants()) {
            if (node is DomElement && node.localName in BLOCKS) {
                ans += countLine(node)
            }
        }
        return ans
    }

    private fun countLine(block: DomElement): Int {
        val (charNum, margin) = HEAD_MAP[block.localName] ?: DEFAULT_HEAD
        var lines = (countChar(block) + charNum - 1) / charNum
        if (lines > 0) lines += margin
        return lines
    }

    /**
     * Significant chars directly on [root] and its non-block descendants.
     * Block descendants are excluded so a <p>'s text doesn't double-count with
     * its <body> parent's tally; comments are folded inline (they have no
     * children to recurse into).
     */
    internal fun countChar(root: DomElement): Int {
        var ans = 0
        val stack = ArrayDeque<DomElement>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            ans += significantChars(node)
            for (child in node.children) when (child) {
                is DomElement -> if (child.localName !in BLOCKS) stack.addLast(child)
                is DomComment -> ans += significantChars(child)
                // No text nodes — DomNode.text holds inline text on elements
            }
        }
        return ans
    }

    /**
     * Mirrors speedup.c count_chars(): the barename is ASCII-FOLDED (A-Z →
     * a-z, length-preserving, exactly like the C `+= 32`) inside the 16-byte
     * buffer guard, and the folded name drives BOTH the bonus switch and the
     * ignored-tag decision — so <SCRIPT>/<Style> are ignored just like their
     * lowercase forms. +1000 for svg/img, +2000 for video; codepoints > 32 in
     * tail always and in text unless the tag is ignored. Comments have no
     * string tag in lxml, so their text is never fetched — only tail counts.
     */
    internal fun significantChars(node: DomNode): Int {
        val tagName = (node as? DomElement)?.localName
        var ans = 0
        var ignoreText = false
        if (tagName != null && tagName.length < 16) {
            val folded = asciiFold(tagName)
            when (folded[0]) {
                's' -> when (folded) {
                    "script", "style" -> ignoreText = true
                    "svg" -> ans += 1000
                }
                'n' -> if (folded == "noscript") ignoreText = true
                't' -> if (folded == "title") ignoreText = true
                'i' -> if (folded == "img") ans += 1000
                'v' -> if (folded == "video") ans += 2000
            }
        }
        ans += countCodepoints(node.tail)
        // Comments/PIs: lxml puts the body in .text, but the C helper only
        // fetches .text when the tag is a string — comment bodies never count.
        if (!ignoreText && node is DomElement) ans += countCodepoints(node.text)
        return ans
    }

    /** C-style ASCII lowercase: only A-Z shift, everything else untouched. */
    private fun asciiFold(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c in 'A'..'Z') c + 32 else c)
        return sb.toString()
    }

    /** Count codepoints strictly greater than 32 (whitespace ignored). */
    private fun countCodepoints(s: String?): Int {
        if (s.isNullOrEmpty()) return 0
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val cp: Int
            if (c.isHighSurrogate() && i + 1 < s.length) {
                val c2 = s[i + 1]
                if (c2.isLowSurrogate()) {
                    cp = Character.toCodePoint(c, c2)
                    i += 2
                } else {
                    cp = c.code
                    i++
                }
            } else {
                cp = c.code
                i++
            }
            if (cp > 32) n++
        }
        return n
    }
}