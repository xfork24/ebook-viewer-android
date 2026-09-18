// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Port of calibre/src/calibre/srv/fast_css_transform.cpp — the CSS transform
// the viewer's preprocessing applies to every stylesheet, <style> block and
// style="" attribute:
//   font-size/font             keyword sizes and absolute units -> rem
//   page-break-*               -> break-* plus a duplicated
//                              -webkit-column-* declaration (multi-column paging)
//   -webkit/-epub-writing-mode -> writing-mode
//   url(...)/@import strings   optionally rewritten through a callback
//
// The tokenizer is a faithful state-machine port of the C++ one. Output is a
// codepoint-by-codepoint copy of the input; only token groups whose transforms
// actually changed are re-serialized (truncating the output back to the group
// start), so untouched CSS stays byte-identical and comments inside a
// rewritten group are dropped — exactly as upstream.

package dev.calibre.viewer.parser.transform

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.pow

private enum class TokenType {
    WHITESPACE, DELIMITER, IDENT, AT_KEYWORD, HASH, STRING, URL,
    FUNCTION_START, NUMBER, DIMENSION, CDO, CDC,
}

private enum class PropertyType { FONT_SIZE, PAGE_BREAK, NON_STANDARD_WRITING_MODE }

private val KNOWN_PROPERTIES = mapOf(
    "font-size" to PropertyType.FONT_SIZE,
    "font" to PropertyType.FONT_SIZE,
    "page-break-before" to PropertyType.PAGE_BREAK,
    "page-break-after" to PropertyType.PAGE_BREAK,
    "page-break-inside" to PropertyType.PAGE_BREAK,
    "-webkit-writing-mode" to PropertyType.NON_STANDARD_WRITING_MODE,
    "-epub-writing-mode" to PropertyType.NON_STANDARD_WRITING_MODE,
)

private val FONT_SIZE_KEYWORDS = mapOf(
    "xx-small" to "0.5rem",
    "x-small" to "0.625rem",
    "small" to "0.8rem",
    "medium" to "1rem",
    "large" to "1.125rem",
    "x-large" to "1.5rem",
    "xx-large" to "2rem",
    "xxx-large" to "2.55rem",
)

private val ABSOLUTE_LENGTH_UNITS = mapOf(
    "mm" to 2.8346456693,
    "cm" to 28.346456693,
    "in" to 72.0,
    "pc" to 12.0,
    "q" to 0.708661417325,
    "px" to 0.0,
    "pt" to 1.0,
)

private const val BASE_FONT_SIZE = 16.0
private const val PT_TO_REM = (96.0 / 72.0) / BASE_FONT_SIZE

private fun convertFontSize(value: Double, factor: Double): Double =
    if (factor == 0.0) value / BASE_FONT_SIZE else value * factor * PT_TO_REM

// ---- character classes (CSS syntax, exactly the C++ ones) ----------------

private fun isWhitespaceCp(cp: Int): Boolean = cp == ' '.code || cp == '\n'.code || cp == '\t'.code
private fun isSurrogateCp(cp: Int): Boolean = cp in 0xD800..0xDFFF
private fun isHexDigitCp(cp: Int): Boolean =
    cp in '0'.code..'9'.code || cp in 'a'.code..'f'.code || cp in 'A'.code..'F'.code
private fun isLetterCp(cp: Int): Boolean = cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code
private fun isDigitCp(cp: Int): Boolean = cp in '0'.code..'9'.code
private fun isNameStartCp(cp: Int): Boolean = isLetterCp(cp) || cp == '_'.code || cp >= 0x80
private fun isNameBodyCp(cp: Int): Boolean = isNameStartCp(cp) || isDigitCp(cp) || cp == '-'.code
private fun isNameCp(cp: Int): Boolean = isNameStartCp(cp) || isDigitCp(cp) || cp == '-'.code
private fun isPrintableAsciiCp(cp: Int): Boolean = cp in ' '.code..'~'.code

// ---- number parsing --------------------------------------------------------

internal class ParsedNumber(val isInteger: Boolean, val integer: Long, val float: Double) {
    fun asDouble(): Double = if (isInteger) integer.toDouble() else float
}

/** ipow(): integer power with wrapping Long arithmetic (mirrors long long). */
private fun ipow(baseIn: Long, expIn: Long): Long {
    var base = baseIn
    var exp = expIn
    var result = 1L
    while (true) {
        if (exp and 1L == 1L) result *= base
        exp = exp shr 1
        if (exp == 0L) break
        base *= base
    }
    return result
}

/** parse_integer(): digit accumulation mod 2^64, last digit backwards. */
private fun parseInteger(src: String, first: Int, lastIn: Int): Long {
    var last = lastIn
    var ans = 0L
    var base = 1L
    while (true) {
        val digit = (src[last] - '0').toLong()
        ans += digit * base
        if (last == first) break
        last--
        base *= 10
    }
    return ans
}

internal fun parseCssNumberParsed(src: String, limit: Int = 0): ParsedNumber {
    var sign = 1
    var exponentSign = 1
    var integerPart = 0L
    var fractionalPart = 0L
    var exponentPart = 0L
    var numOfFractionalDigits = 0
    var firstDigit = 0
    var lastDigit = 0
    val srcSz = if (limit != 0) limit else src.length
    var pos = 0

    fun readSign(): Boolean {
        var neg = false
        if (pos < srcSz && (src[pos] == '+' || src[pos] == '-')) {
            neg = src[pos] == '-'
            pos++
        }
        return neg
    }

    fun readInteger(): Long {
        // C++ is_digit is ASCII-only; Char.isDigit() would admit non-ASCII
        // decimal digits (e.g. Arabic-Indic) and misparse the number.
        if (pos < srcSz && src[pos] in '0'..'9') {
            firstDigit = pos
            while (pos + 1 < srcSz && src[pos + 1] in '0'..'9') pos++
            lastDigit = pos++
            return parseInteger(src, firstDigit, lastDigit)
        }
        return 0L
    }

    if (readSign()) sign = -1
    integerPart = readInteger()
    if (pos < srcSz && src[pos] == '.') {
        pos++
        fractionalPart = readInteger()
        if (fractionalPart != 0L) numOfFractionalDigits = lastDigit - firstDigit + 1
    }
    if (pos < srcSz && (src[pos] == 'e' || src[pos] == 'E')) {
        pos++
        if (readSign()) exponentSign = -1
        exponentPart = readInteger()
    }
    if (fractionalPart != 0L || (exponentPart != 0L && exponentSign == -1)) {
        var ans = integerPart.toDouble()
        if (fractionalPart != 0L) ans += fractionalPart.toDouble() / ipow(10, numOfFractionalDigits.toLong()).toDouble()
        if (exponentPart != 0L) {
            ans = if (exponentSign == -1) {
                ans / ipow(10, exponentPart).toDouble()
            } else {
                ans * ipow(10, exponentPart).toDouble()
            }
        }
        return ParsedNumber(false, 0L, sign.toDouble() * ans)
    }
    return ParsedNumber(true, sign.toLong() * integerPart * ipow(10, exponentPart), 0.0)
}

// ---- token -----------------------------------------------------------------

private class Token(var type: TokenType, firstCp: Int = 0, var outPos: Int = 0) {
    val text = StringBuilder()
    var unitAt = 0

    init {
        if (firstCp != 0) text.appendCodePoint(firstCp)
    }

    fun copy(): Token = Token(type, 0, outPos).also {
        it.text.append(text)
        it.unitAt = unitAt
    }

    fun isType(t: TokenType) = type == t
    fun isSignificant(): Boolean = type != TokenType.WHITESPACE && type != TokenType.CDO && type != TokenType.CDC

    fun isDelimiter(cp: Int): Boolean =
        type == TokenType.DELIMITER && text.length == 1 && text[0].code == cp

    fun isPropertyTerminator(): Boolean = when (type) {
        TokenType.WHITESPACE -> text.isNotEmpty() && text.indexOf('\n') != -1
        TokenType.DELIMITER -> text.length == 1 && (text[0] == ';' || text[0] == '}')
        else -> false
    }

    fun appendCp(cp: Int) {
        text.appendCodePoint(cp)
    }

    fun markUnit() {
        unitAt = text.length
    }

    fun clearText() = text.setLength(0)

    fun trimTrailingWhitespace() {
        while (text.isNotEmpty() && isWhitespaceCp(text[text.length - 1].code)) text.deleteCharAt(text.length - 1)
    }

    fun eraseTextSubstring(pos: Int, len: Int) = text.delete(pos, pos + len)

    fun prepend(s: String) = text.insert(0, s)

    fun setText(s: String) {
        text.setLength(0)
        text.append(s)
    }

    fun textEqualsCaseInsensitive(lowercase: String): Boolean {
        if (text.length != lowercase.length) return false
        val delta = 'a' - 'A'
        for (i in 0 until lowercase.length) {
            val c = text[i]
            val q = lowercase[i]
            if (c != q && c + delta != q) return false
        }
        return true
    }

    /** ASCII-lowercased text; null when any codepoint is outside printable ASCII. */
    fun textAsAsciiLowercase(): String? {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (!isPrintableAsciiCp(cp)) return null
            sb.append(if (cp in 'A'.code..'Z'.code) (cp + 32).toChar() else cp.toChar())
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /** Convert an absolute-unit dimension to rem; false when not applicable. */
    fun convertAbsoluteFontSize(): Boolean {
        if (unitAt == 0) return false
        val lower = textAsAsciiLowercase() ?: return false
        val factor = ABSOLUTE_LENGTH_UNITS[lower.substring(unitAt)] ?: return false
        val value = parseCssNumberParsed(lower, unitAt).asDouble()
        val newVal = convertFontSize(value, factor)
        if (value == newVal) return false
        setText(formatGeneralCssDouble(newVal) + "rem")
        return true
    }

    private fun serializeEscapedChar(cp: Int, out: StringBuilder) {
        out.append('\\')
        if (isWhitespaceCp(cp) || isHexDigitCp(cp)) {
            out.append(cp.toString(16))
            out.append(' ')
        } else {
            out.appendCodePoint(cp)
        }
    }

    private fun serializeIdent(out: StringBuilder) {
        var oneHyphen = false
        var started = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                !started && isNameStartCp(cp) -> {
                    out.appendCodePoint(cp); started = true
                }
                !started && cp == '-'.code -> out.appendCodePoint(cp).let { oneHyphen = true }
                oneHyphen && !started -> when {
                    isNameStartCp(cp) || cp == '-'.code -> {
                        out.appendCodePoint(cp); started = true; oneHyphen = false
                    }
                    else -> serializeEscapedChar(cp, out)
                }
                !isNameBodyCp(cp) -> serializeEscapedChar(cp, out)
                else -> out.appendCodePoint(cp)
            }
        }
    }

    private fun serializeHash(out: StringBuilder) {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (isNameBodyCp(cp)) out.appendCodePoint(cp) else serializeEscapedChar(cp, out)
        }
    }

    private fun serializeString(out: StringBuilder) {
        val delim = if (text.indexOf('"') == -1) '"' else '\''
        out.append(delim)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == '\n'.code -> out.append('\\').append('\n')
                cp == delim.code || cp == '\\'.code -> serializeEscapedChar(cp, out)
                else -> out.appendCodePoint(cp)
            }
        }
        out.append(delim)
    }

    fun serialize(out: StringBuilder) {
        when (type) {
            TokenType.WHITESPACE, TokenType.DELIMITER, TokenType.NUMBER, TokenType.DIMENSION -> out.append(text)
            TokenType.IDENT -> serializeIdent(out)
            TokenType.AT_KEYWORD -> {
                out.append('@'); serializeIdent(out)
            }
            TokenType.HASH -> {
                out.append('#'); serializeHash(out)
            }
            TokenType.STRING -> serializeString(out)
            TokenType.URL -> {
                out.append("url("); serializeString(out); out.append(')')
            }
            TokenType.FUNCTION_START -> {
                serializeIdent(out); out.append('(')
            }
            TokenType.CDO -> out.append("<!--")
            TokenType.CDC -> out.append("-->")
        }
    }
}

// ---- double formatting -----------------------------------------------------

/**
 * Byte-parity port of `std::to_chars(buf, end, d, std::chars_format::general)`
 * as implemented in the libc++ used to build calibre's fast_css_transform
 * (verified empirically against clang++/libc++):
 *
 *   1. Shortest round-trip decimal digits (closest, ties-to-even).
 *   2. Let X be the exponent of the leading digit (d = D.DDD × 10^X): fixed
 *      notation iff -4 <= X < 6, scientific otherwise — a constant threshold,
 *      NOT printf %g's "X < significant digits" rule (verified by an X×P
 *      sweep: 1234567 → "1.234567e+06" but 999999.5 → "999999.5").
 *   3. Fixed form has no trailing ".0" ("5", "100000"); scientific form is
 *      "D[.DDD]e±XX" with a signed exponent of at least two digits
 *      ("1e+06", "4.9999999999999996e-05").
 *   4. ±0 → "0"/"-0", infinities → "inf"/"-inf" (unreachable from CSS
 *      numbers, kept for to_chars parity).
 */
internal fun formatGeneralCssDouble(d: Double): String {
    if (d.isNaN()) return "nan"
    if (d == Double.POSITIVE_INFINITY) return "inf"
    if (d == Double.NEGATIVE_INFINITY) return "-inf"
    // sign bit, not `< 0`: -0.0 must print as "-0" like to_chars
    val neg = java.lang.Double.doubleToRawLongBits(d) < 0
    val (digits, x) = shortestDecimalDigits(abs(d))
    val body = if (x >= -4 && x < 6) {
        val k = x + 1 // digits before the decimal point
        when {
            k >= digits.length -> digits + "0".repeat(k - digits.length)
            k > 0 -> digits.substring(0, k) + "." + digits.substring(k)
            else -> "0." + "0".repeat(-k) + digits
        }
    } else {
        val mantissa = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
        mantissa + "e" + (if (x < 0) "-" else "+") + abs(x).toString().padStart(2, '0')
    }
    return (if (neg) "-" else "") + body
}

/**
 * Shortest round-trip decimal for a finite non-negative double: digits D (no
 * sign, no trailing zeros, "0" for zero) plus the leading-digit exponent X
 * with value = D × 10^(X - D.length + 1). Ryu-equivalent: rounds the exact
 * binary value to p significant digits (HALF_EVEN = closest, ties-to-even)
 * for p = 1..17 and returns the first result that parses back to the same
 * double — the same digits libc++'s shortest to_chars emits.
 */
private fun shortestDecimalDigits(a: Double): Pair<String, Int> {
    if (a == 0.0) return "0" to 0
    val exact = BigDecimal(a)
    for (precision in 1..17) {
        val b = exact.round(MathContext(precision, RoundingMode.HALF_EVEN))
        var digits = b.unscaledValue().toString()
        var scale = b.scale()
        while (digits.length > 1 && digits.last() == '0') {
            digits = digits.dropLast(1)
            scale-- // 120×10^-1 == 12×10^0: value is unchanged
        }
        val x = digits.length - 1 - scale
        if (("${digits}e${x - digits.length + 1}").toDouble() == a) return digits to x
    }
    // Unreachable — 17 significant digits always round-trip a double — but
    // keep a deterministic answer rather than looping forever.
    val b = exact.round(MathContext(17, RoundingMode.HALF_EVEN))
    val digits = b.unscaledValue().toString()
    return digits to (digits.length - 1 - b.scale())
}

// ---- input normalization ---------------------------------------------------

/**
 * Decode + normalize exactly like InputStream::peek_one: \r\n|\r|0x0c -> \n,
 * NUL and surrogates -> U+FFFD. After this, rewind is always one array slot.
 */
private fun normalizeCssInput(src: String): IntArray {
    val out = IntArray(src.length)
    var n = 0
    var i = 0
    while (i < src.length) {
        val c = src[i]
        var cp: Int
        if (c.isHighSurrogate() && i + 1 < src.length && src[i + 1].isLowSurrogate()) {
            cp = Character.toCodePoint(c, src[i + 1])
            i += 2
        } else {
            cp = c.code
            i++
            if (Character.isSurrogate(c)) cp = 0xFFFD
        }
        when (cp) {
            0x0C -> cp = '\n'.code
            0x0D -> {
                if (i < src.length && src[i] == '\n') i++
                cp = '\n'.code
            }
            0 -> cp = 0xFFFD
        }
        out[n++] = cp
    }
    return out.copyOf(n)
}

// ---- parser states -----------------------------------------------------------

private enum class ParseState {
    NORMAL, ESCAPE, COMMENT, STRING, HASH, NUMBER, DIGITS, DIMENSION, IDENT,
    URL, URL_START, URL_STRING, URL_AFTER_STRING, AT_KEYWORD,
}

private class BlockTypeFlags(
    declarationsAllowed: Boolean,
    qualifiedRulesAllowed: Boolean,
    atRulesAllowed: Boolean,
    topLevel: Boolean,
) {
    var declarationsAllowed = declarationsAllowed
    var qualifiedRulesAllowed = qualifiedRulesAllowed
    var atRulesAllowed = atRulesAllowed
    var topLevel = topLevel
}

// ---- parser -----------------------------------------------------------------

private class CssParser(
    private val input: IntArray,
    private val urlCallback: ((String) -> String?)?,
    isDeclaration: Boolean,
) {
    private val tokens = ArrayList<Token>()
    private val out = StringBuilder(input.size * 2)
    private val states = ArrayDeque<ParseState>()
    private val blockTypes = ArrayDeque<BlockTypeFlags>()
    private var ch = 0
    private var prevCh = 0
    private var endStringWith = '"'.code
    private var escapeBuf = 0L
    private var escapeBufPos = 0
    private var pos = 0

    init {
        if (isDeclaration) pushBlockType(true, false, false, false)
        else pushBlockType(true, true, true, true)
        states.addLast(ParseState.NORMAL)
    }

    // ---- input stream ------------------------------------------------------

    private fun next(): Int {
        if (pos >= input.size) return 0
        return input[pos++]
    }

    private fun rewind() {
        if (pos > 0) pos--
    }

    /** peek(which): which < 0 -> the current ch, else the (normalized) char ahead. */
    private fun peek(which: Int = 0): Int {
        if (which < 0) return ch
        val i = pos + which
        return if (i < input.size) input[i] else 0
    }

    // ---- token queue ---------------------------------------------------------

    private fun newToken(type: TokenType, firstCp: Int = 0) {
        tokens.add(Token(type, firstCp, out.length))
    }

    private fun addCharOfType(type: TokenType, cp: Int) {
        val last = tokens.lastOrNull()
        if (last != null && last.isType(type)) last.appendCp(cp) else newToken(type, cp)
    }

    private fun addWhitespace(cp: Int) = addCharOfType(TokenType.WHITESPACE, cp)

    private fun startString() {
        if (tokens.isEmpty() || !tokens.last().isType(TokenType.STRING)) newToken(TokenType.STRING)
    }

    private fun addChar(cp: Int) {
        if (tokens.isEmpty()) throw IllegalStateException("addChar with no token")
        // invalid (>0x10FFFF) escaped codepoints become U+FFFD like the C++
        // UTF-8 conversion of the final output does
        tokens.last().appendCp(if (cp in 0x110000..Int.MAX_VALUE) 0xFFFD else cp)
    }

    private fun makeFunctionStart(isUrl: Boolean) {
        if (tokens.isEmpty()) throw IllegalStateException("makeFunctionStart with no token")
        tokens.last().type = if (isUrl) TokenType.URL else TokenType.FUNCTION_START
        if (isUrl) tokens.last().clearText()
    }

    private fun markUnit() {
        if (tokens.isEmpty()) throw IllegalStateException("markUnit with no token")
        tokens.last().markUnit()
        tokens.last().type = TokenType.DIMENSION
    }

    private fun trimTrailingWhitespace() {
        tokens.lastOrNull()?.trimTrailingWhitespace()
    }

    private fun currentTokenTextEqualsCaseInsensitive(lowercase: String): Boolean =
        tokens.lastOrNull()?.textEqualsCaseInsensitive(lowercase) == true

    private fun leadingAtKeyword(): Token? {
        for (t in tokens) if (t.isSignificant()) return if (t.isType(TokenType.AT_KEYWORD)) t else null
        return null
    }

    private fun startsWithAtKeyword(): Boolean = leadingAtKeyword() != null

    // ---- transforms ------------------------------------------------------------

    /**
     * process_urls(). Upstream distinguishes "unchanged" by CPython object
     * identity (returning the same str means unchanged); Kotlin's String
     * helpers return the same instance on no-op, so the contract here is:
     * null (or a thrown exception) = leave unchanged, any non-null string =
     * rewrite (forcing re-serialization of the token group).
     */
    private fun processUrls(type: TokenType = TokenType.URL): Boolean {
        if (urlCallback == null) return false
        var changed = false
        for (t in tokens) {
            if (!t.isType(type)) continue
            val url = t.text.toString()
            val newUrl = runCatching { urlCallback(url) }.getOrNull() ?: continue
            t.setText(newUrl)
            changed = true
        }
        return changed
    }

    private fun processFontSizes(fromIndex: Int): Boolean {
        var changed = false
        for (i in fromIndex until tokens.size) {
            val t = tokens[i]
            when {
                t.isType(TokenType.IDENT) -> {
                    val lower = t.textAsAsciiLowercase()
                    if (lower != null) {
                        val repl = FONT_SIZE_KEYWORDS[lower]
                        if (repl != null) {
                            t.setText(repl)
                            t.type = TokenType.DIMENSION
                            changed = true
                        }
                    }
                }
                t.isType(TokenType.DIMENSION) -> if (t.convertAbsoluteFontSize()) changed = true
            }
        }
        return changed
    }

    private fun processDeclaration(): Boolean {
        var changed = false
        var colonFound = false
        var keyFound = false
        var keepGoing = true
        var scanFontSizes = false
        var i = 0
        while (keepGoing && i < tokens.size) {
            val t = tokens[i]
            if (!t.isSignificant()) { i++; continue }
            if (keyFound) {
                if (colonFound) {
                    // upstream scans from the first significant value token to
                    // the end of the queue, then the loop terminates
                    if (scanFontSizes && processFontSizes(i)) changed = true
                    keepGoing = false
                } else {
                    if (!t.isDelimiter(':'.code)) break
                    colonFound = true
                }
            } else {
                if (t.isType(TokenType.IDENT)) {
                    keyFound = true
                    val lower = t.textAsAsciiLowercase() ?: break
                    when (KNOWN_PROPERTIES[lower] ?: break) {
                        PropertyType.FONT_SIZE -> scanFontSizes = true
                        PropertyType.PAGE_BREAK -> {
                            t.eraseTextSubstring(0, 5) // "page-" -> ""
                            val copies = ArrayList<Token>()
                            var j = i
                            while (j < tokens.size && !tokens[j].isPropertyTerminator()) {
                                copies.add(tokens[j].copy())
                                j++
                            }
                            if (copies.isNotEmpty()) {
                                copies.add(Token(TokenType.DELIMITER, ';'.code, 0))
                                copies.add(Token(TokenType.WHITESPACE, ' '.code, 0))
                                tokens.addAll(i, copies)
                                tokens[i + copies.size].prepend("-webkit-column-")
                            }
                            changed = true
                            keepGoing = false
                        }
                        PropertyType.NON_STANDARD_WRITING_MODE -> {
                            t.setText("writing-mode")
                            changed = true
                            keepGoing = false
                        }
                    }
                } else break
            }
            i++
        }
        return changed
    }

    // ---- commit ------------------------------------------------------------

    private fun commitTokens(flushCp: Int) {
        var changed = false
        if (flushCp == ';'.code) {
            val att = leadingAtKeyword()
            if (processUrls()) changed = true
            if (att != null) {
                if (att.textEqualsCaseInsensitive("import")) {
                    if (processUrls(TokenType.STRING)) changed = true
                }
            } else {
                if (processDeclaration()) changed = true
            }
        } else if (flushCp == '{'.code) {
            if (processUrls()) changed = true
            val att = leadingAtKeyword()
            if (att != null && att.textEqualsCaseInsensitive("import")) {
                if (processUrls(TokenType.STRING)) changed = true
            }
        } else {
            if (processUrls()) changed = true
            if (processDeclaration()) changed = true
        }
        if (changed && tokens.isNotEmpty()) {
            // token.outPos = index of its first char + 1 (recorded after that
            // char was written), so truncating to outPos - 1 drops exactly the
            // token group. Upstream truncates a u32string (one slot per
            // codepoint); StringBuilder is UTF-16, so when the group's first
            // codepoint is astral the cut lands between its surrogates —
            // step back one more char to keep the pair intact.
            val startPos = tokens[0].outPos
            var cut = if (startPos > 0) startPos - 1 else 0
            if (cut > 0 && out[cut - 1].isHighSurrogate() && out[cut].isLowSurrogate()) cut--
            out.setLength(cut)
            for (t in tokens) t.serialize(out)
        }
        tokens.clear()
    }

    // ---- block types -----------------------------------------------------------

    private fun qualifiedRulesAllowed() = blockTypes.last().qualifiedRulesAllowed
    private fun atRulesAllowed() = blockTypes.last().atRulesAllowed
    private fun isTopLevel() = blockTypes.last().topLevel

    private fun pushBlockType(d: Boolean, q: Boolean, a: Boolean, t: Boolean) =
        blockTypes.addLast(BlockTypeFlags(d, q, a, t))

    private fun popBlockType() {
        if (blockTypes.size > 1) blockTypes.removeLast()
    }

    // ---- stream testing ----------------------------------------------------------

    private fun popState() {
        if (states.size > 1) states.removeLast()
    }

    private fun pushState(s: ParseState) = states.addLast(s)

    private fun rewindOutput() {
        // drop the last codepoint (may be a surrogate pair)
        if (out.isNotEmpty()) {
            out.deleteCharAt(out.length - 1)
            if (out.isNotEmpty() && out[out.length - 1].isHighSurrogate()) out.deleteCharAt(out.length - 1)
        }
    }

    private fun reconsume() {
        rewind()
        rewindOutput()
    }

    private fun startingComment(): Boolean = ch == '/'.code && peek() == '*'.code
    private fun startingString(): Boolean = ch == '"'.code || ch == '\''.code

    private fun hasValidEscapeNext(offset: Int = 0): Boolean {
        if (peek(offset) != '\\'.code) return false
        val second = peek(offset + 1)
        return second > 0 && second != '\n'.code
    }

    private fun hasValidEscape(): Boolean = hasValidEscapeNext(-1)

    private fun hasIdentifierNext(offset: Int = 0): Boolean {
        val first = peek(offset)
        when (first) {
            0 -> return false
            '\\'.code -> return hasValidEscapeNext(offset)
            '-'.code -> {
                val second = peek(offset + 1)
                if (isNameStartCp(second) || second == '-'.code) return true
                if (second == '\\'.code) {
                    val third = peek(offset + 2)
                    return third > 0 && third != '\n'.code
                }
                return false
            }
            else -> return isNameStartCp(first)
        }
    }

    private fun hasIdentifier(): Boolean = hasIdentifierNext(-1)

    // ---- escape ----------------------------------------------------------------

    private fun enterEscapeMode() {
        pushState(ParseState.ESCAPE)
        escapeBufPos = 0
        escapeBuf = 0
    }

    private fun handleEscape() {
        if (escapeBufPos == 0) {
            when {
                ch == '\n'.code -> { reconsume(); popState() }
                !isHexDigitCp(ch) -> { popState(); addChar(ch) }
                else -> { escapeBuf = hexValue(ch); escapeBufPos = 1 }
            }
            return
        }
        if (isHexDigitCp(ch) && escapeBufPos < 6) {
            escapeBuf = escapeBuf * 16 + hexValue(ch)
            escapeBufPos++
            return
        }
        if (isWhitespaceCp(ch)) return // a single whitespace char is absorbed
        reconsume()
        popState()
        if (escapeBuf > 0 && !isSurrogateCp(escapeBuf.toInt())) addChar(escapeBuf.toInt())
        escapeBufPos = 0
    }

    private fun hexValue(cp: Int): Long = when (cp) {
        in '0'.code..'9'.code -> (cp - '0'.code).toLong()
        in 'a'.code..'f'.code -> (cp - 'a'.code + 10).toLong()
        in 'A'.code..'F'.code -> (cp - 'A'.code + 10).toLong()
        else -> 0L
    }

    // ---- string ------------------------------------------------------------------

    private fun enterStringMode() {
        pushState(ParseState.STRING)
        endStringWith = ch
        startString()
    }

    private fun handleString() {
        when {
            ch == '\\'.code -> if (peek() == '\n'.code) next() else enterEscapeMode()
            ch == endStringWith -> popState()
            else -> addChar(ch)
        }
    }

    // ---- comment -----------------------------------------------------------------

    private fun enterCommentMode() = pushState(ParseState.COMMENT)

    private fun handleComment() {
        if (ch == '/'.code && prevCh == '*'.code) popState()
    }

    // ---- hash / at-keyword (name states) ----------------------------------------

    private fun handleName() {
        when {
            isNameCp(ch) -> addChar(ch)
            hasValidEscape() -> enterEscapeMode()
            startingComment() -> enterCommentMode()
            else -> { reconsume(); popState() }
        }
    }

    private fun enterHashMode() {
        pushState(ParseState.HASH)
        newToken(TokenType.HASH)
    }

    // ---- number --------------------------------------------------------------------

    private fun enterNumberMode() {
        pushState(ParseState.NUMBER)
        newToken(TokenType.NUMBER, ch)
    }

    private fun handleNumber() {
        if (isDigitCp(ch) || (ch == '.'.code && isDigitCp(peek()))) {
            addChar(ch)
            return
        }
        if (startingComment()) {
            pushState(ParseState.COMMENT)
            return
        }
        if (ch == 'e'.code || ch == 'E'.code) {
            val nx = peek()
            if (isDigitCp(nx) || ((nx == '+'.code || nx == '-'.code) && isDigitCp(peek(1)))) {
                addChar(next())
                addChar(next())
                popState()
                pushState(ParseState.DIGITS)
                return
            }
        }
        reconsume()
        popState()
        if (hasIdentifierNext()) enterDimensionMode()
    }

    private fun handleDigits() {
        when {
            isDigitCp(ch) -> addChar(ch)
            startingComment() -> pushState(ParseState.COMMENT)
            else -> {
                reconsume()
                popState()
                if (hasIdentifierNext()) enterDimensionMode()
            }
        }
    }

    // ---- dimension -------------------------------------------------------------------

    private fun enterDimensionMode() {
        markUnit()
        pushState(ParseState.DIMENSION)
    }

    private fun handleDimension() {
        when {
            isNameCp(ch) -> addChar(ch)
            hasValidEscape() -> enterEscapeMode()
            startingComment() -> pushState(ParseState.COMMENT)
            else -> { reconsume(); popState() }
        }
    }

    // ---- ident ------------------------------------------------------------------------

    private fun enterIdentMode(startingCp: Int = 0) {
        newToken(TokenType.IDENT, startingCp)
        pushState(ParseState.IDENT)
    }

    private fun handleIdent() {
        when {
            isNameCp(ch) -> addChar(ch)
            hasValidEscape() -> enterEscapeMode()
            startingComment() -> pushState(ParseState.COMMENT)
            else -> {
                popState()
                if (ch == '('.code) {
                    if (currentTokenTextEqualsCaseInsensitive("url")) enterUrlStartMode()
                    else makeFunctionStart(false)
                } else reconsume()
            }
        }
    }

    // ---- url ---------------------------------------------------------------------------

    private fun enterUrlStartMode() {
        makeFunctionStart(true)
        pushState(ParseState.URL_START)
    }

    private fun handleUrlStart() {
        when {
            isWhitespaceCp(ch) -> { /* skipped */ }
            startingString() -> {
                popState()
                endStringWith = ch
                pushState(ParseState.URL_STRING)
            }
            ch == ')'.code -> popState()
            startingComment() -> pushState(ParseState.COMMENT)
            else -> {
                popState()
                pushState(ParseState.URL)
                addChar(ch)
            }
        }
    }

    private fun handleUrlString() {
        handleString()
        val top = states.last()
        if (top != ParseState.URL_STRING && top != ParseState.ESCAPE) pushState(ParseState.URL_AFTER_STRING)
    }

    private fun handleUrlAfterString() {
        if (startingComment()) {
            pushState(ParseState.COMMENT)
            return
        }
        if (!isWhitespaceCp(ch)) exitUrlMode(false)
    }

    private fun handleUrl() {
        when {
            ch == '\\'.code && hasValidEscape() -> enterEscapeMode()
            ch == ')'.code -> exitUrlMode(true)
            startingComment() -> pushState(ParseState.COMMENT)
            else -> addChar(ch)
        }
    }

    private fun exitUrlMode(trim: Boolean = false) {
        popState()
        if (trim) trimTrailingWhitespace()
    }

    // ---- at-keyword ------------------------------------------------------------------------

    private fun enterAtKeyword() {
        pushState(ParseState.AT_KEYWORD)
        newToken(TokenType.AT_KEYWORD)
    }

    // ---- normal ------------------------------------------------------------------------------

    private fun handleNormal() {
        if (startingComment()) {
            pushState(ParseState.COMMENT)
            return
        }
        if (isWhitespaceCp(ch)) {
            addWhitespace(ch)
            return
        }
        if (isDigitCp(ch)) {
            enterNumberMode()
            return
        }
        if (isNameStartCp(ch)) {
            enterIdentMode(ch)
            return
        }
        when (ch) {
            '"'.code, '\''.code -> enterStringMode()
            '#'.code -> if (isNameCp(peek()) || hasValidEscapeNext()) enterHashMode() else addDelimiter(ch)
            '('.code, ')'.code, '['.code, ']'.code, ','.code, ':'.code -> addDelimiter(ch)
            ';'.code -> {
                addDelimiter(ch)
                commitTokens(ch)
            }
            '{'.code -> {
                if (atRulesAllowed() || qualifiedRulesAllowed()) {
                    val isAtRule = startsWithAtKeyword()
                    pushBlockType(true, isAtRule, isAtRule, false)
                }
                addDelimiter(ch)
                commitTokens(ch)
            }
            '}'.code -> {
                popBlockType()
                addDelimiter(ch)
                commitTokens(ch)
            }
            '+'.code -> if (isDigitCp(peek()) || (peek() == '.'.code && isDigitCp(peek(1)))) {
                enterNumberMode()
            } else addDelimiter(ch)
            '-'.code -> when {
                isDigitCp(peek()) || (peek() == '.'.code && isDigitCp(peek(1))) -> enterNumberMode()
                isTopLevel() && peek() == '-'.code && peek(1) == '>'.code -> {
                    newToken(TokenType.CDC)
                    out.appendCodePoint(next())
                    out.appendCodePoint(next())
                }
                hasIdentifier() -> enterIdentMode(ch)
                else -> addDelimiter(ch)
            }
            '.'.code -> if (isDigitCp(peek())) enterNumberMode() else addDelimiter(ch)
            '<'.code -> if (isTopLevel() && peek() == '!'.code && peek(1) == '-'.code && peek(2) == '-'.code) {
                newToken(TokenType.CDO)
                out.appendCodePoint(next())
                out.appendCodePoint(next())
                out.appendCodePoint(next())
            } else addDelimiter(ch)
            '@'.code -> if (atRulesAllowed() && hasIdentifierNext()) enterAtKeyword() else addDelimiter(ch)
            '\\'.code -> if (hasValidEscape()) {
                enterIdentMode()
                enterEscapeMode()
            } else addDelimiter(ch)
            else -> addDelimiter(ch)
        }
    }

    private fun addDelimiter(cp: Int) = newToken(TokenType.DELIMITER, cp)

    // ---- main loop --------------------------------------------------------------------------

    fun parse(): String {
        while (true) {
            ch = next()
            if (ch == 0) break
            out.appendCodePoint(ch)
            dispatch()
            prevCh = ch
        }
        commitTokens(';'.code)
        return out.toString()
    }

    private fun dispatch() {
        when (states.last()) {
            ParseState.NORMAL -> handleNormal()
            ParseState.COMMENT -> handleComment()
            ParseState.ESCAPE -> handleEscape()
            ParseState.STRING -> handleString()
            ParseState.HASH -> handleName()
            ParseState.NUMBER -> handleNumber()
            ParseState.DIGITS -> handleDigits()
            ParseState.DIMENSION -> handleDimension()
            ParseState.IDENT -> handleIdent()
            ParseState.URL_START -> handleUrlStart()
            ParseState.URL_STRING -> handleUrlString()
            ParseState.URL -> handleUrl()
            ParseState.URL_AFTER_STRING -> handleUrlAfterString()
            ParseState.AT_KEYWORD -> handleName()
        }
    }
}

// ---- public API --------------------------------------------------------------

object CssTransformer {

    /** transform_properties(): rewrite a stylesheet or a style declaration. */
    fun transformProperties(
        src: String,
        urlCallback: ((String) -> String?)? = null,
        isDeclaration: Boolean = false,
    ): String = CssParser(normalizeCssInput(src), urlCallback, isDeclaration).parse()

    /** parse_css_number(): CSS number grammar, Long for integral results. */
    fun parseCssNumber(src: String, limit: Int = 0): Number {
        val parsed = parseCssNumberParsed(src, limit)
        return if (parsed.isInteger) parsed.integer else parsed.float
    }
}
