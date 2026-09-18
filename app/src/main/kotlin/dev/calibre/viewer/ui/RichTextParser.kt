// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// The loading/error messages the standalone viewer pushes to the host are Qt
// rich text — e.g. "Loading next section from <i>{title}</i>, please wait…"
// (read_book/view.pyj show_loading). Upstream renders them in a QLabel;
// parsing them here (instead of showing literal tags) keeps the e-ink
// overlays faithful. Only the meaningful subset is carried over: emphasis
// tags, <br>/<p> line breaks and the basic entities — everything else is
// stripped to plain text. Pure Kotlin, no Compose dependency, so the parser
// is unit-testable on the JVM (the AnnotatedString mapping lives separately
// in RichTextAnnotated.kt).

package dev.calibre.viewer.ui

/** Parsed rich text: plain [text] plus [RichSpan] runs over it. */
data class RichText(val text: String, val spans: List<RichSpan>)

/** Half-open range [start, end) into [RichText.text]. */
data class RichSpan(val start: Int, val end: Int, val style: RichStyle)

enum class RichStyle { ITALIC, BOLD, UNDERLINE }

private val STYLES = mapOf(
    "i" to RichStyle.ITALIC, "em" to RichStyle.ITALIC,
    "b" to RichStyle.BOLD, "strong" to RichStyle.BOLD,
    "u" to RichStyle.UNDERLINE,
)

private val ENTITIES = mapOf(
    "amp" to '&', "lt" to '<', "gt" to '>', "quot" to '"',
    "apos" to '\'', "nbsp" to ' ',
)

/**
 * Parse the rich-text subset the viewer's host messages use. Tolerates the
 * hostile cases a book metadata title can produce (unclosed/mismatched tags,
 * stray `<`/`&`): anything unparsable degrades to literal text, never to an
 * exception. Overlapping spans from nested tags are preserved.
 */
fun parseRichText(html: String): RichText {
    if ('<' !in html && '&' !in html) return RichText(html, emptyList())
    val sb = StringBuilder()
    val spans = mutableListOf<RichSpan>()
    // Open emphasis tags awaiting their closing tag: name -> offset in sb.
    val open = ArrayDeque<Pair<String, Int>>()
    var i = 0
    while (i < html.length) {
        val c = html[i]
        if (c == '<' && i + 1 < html.length && (html[i + 1].isLetter() || html[i + 1] == '/')) {
            val close = html.indexOf('>', i + 1)
            if (close < 0) {
                sb.append(c)
                i++
                continue
            }
            val inner = html.substring(i + 1, close)
            i = close + 1
            val isClose = inner.startsWith("/")
            val name = inner.trimStart('/').substringBefore(' ').lowercase()
            when {
                name == "br" && !isClose -> sb.append('\n')
                name == "p" && isClose ->
                    if (sb.isNotEmpty() && sb.last() != '\n') sb.append("\n\n")
                isClose -> closeTag(name, sb, open, spans)
                STYLES.containsKey(name) -> open.addLast(name to sb.length)
                // Unknown tags (including html/head/body wrappers) drop out.
            }
        } else if (c == '&') {
            i = decodeEntity(html, i, sb)
        } else {
            sb.append(c)
            i++
        }
    }
    // Unclosed tags style through to the end of the text.
    while (open.isNotEmpty()) {
        val (name, start) = open.removeLast()
        spans += RichSpan(start, sb.length, STYLES.getValue(name))
    }
    return RichText(sb.toString(), spans)
}

/** Close [name]; anything opened after it (still unclosed) ends here too. */
private fun closeTag(
    name: String,
    sb: StringBuilder,
    open: ArrayDeque<Pair<String, Int>>,
    spans: MutableList<RichSpan>,
) {
    val depth = open.indexOfFirst { it.first == name }
    if (depth < 0) return
    while (open.size > depth) {
        val (n, start) = open.removeLast()
        spans += RichSpan(start, sb.length, STYLES.getValue(n))
    }
}

/** Decode the entity at [i] (which points at '&') into [sb]; return the next index. */
private fun decodeEntity(html: String, i: Int, sb: StringBuilder): Int {
    val semi = html.indexOf(';', i + 1)
    if (semi < 0 || semi - i > 10) {
        sb.append('&')
        return i + 1
    }
    val body = html.substring(i + 1, semi)
    val decoded = when {
        body in ENTITIES -> ENTITIES.getValue(body)
        body.startsWith("#") -> body.drop(1).toIntOrNull()?.let { code ->
            // Surrogate pairs for astral code points arrive as two entities;
            // lone invalid codes degrade to the replacement char.
            if (code in 1..0x10FFFF) code.toChar() else null
        }
        else -> null
    }
    sb.append(decoded ?: "&$body;")
    return semi + 1
}
