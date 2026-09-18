// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Small Python-parity utilities shared across the render pipeline.
// Both helpers exist so that values we emit match what the desktop
// calibre pipeline would have produced — not because Kotlin lacks
// native equivalents.

package dev.calibre.viewer.parser.util

import java.math.BigInteger
import java.security.SecureRandom

/**
 * Python 3 `re` `\s` for str patterns: ASCII whitespace plus the C0 file
 * separators U+001C-U+001F, NEL U+0085, NBSP U+00A0, U+1680, U+2000-U+200A,
 * U+2028/U+2029, U+202F, U+205F and the ideographic space U+3000. Android's
 * java.util.regex supports neither the JDK-only UNICODE_CHARACTER_CLASS flag
 * nor a Unicode-aware bare `\s` — and the flag crashes at class-init on ART
 * (ExceptionInInitializerError, seen opening any EPUB on-device) — so the
 * class is spelled out; identical behavior on JVM and Android.
 */
val PYTHON_WS_PLUS: Regex =
    Regex("[ \\t\\n\\u000B\\u000C\\r\\u001C-\\u001F\\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]+")

/**
 * itertools.count() equivalent — yields 0, 1, 2, … forever. Toc.toMap ids and
 * the anchor maps must start at 0 because render_book.py:606 seeds them with
 * count() (and the viewer's TOC bookkeeping treats the ids as opaque but
 * stable across the manifest's toc / toc_anchor_map pairs).
 */
fun pythonCount(): Iterator<Int> = object : Iterator<Int> {
    private var n = 0
    override fun hasNext() = true
    override fun next(): Int {
        val ans = n
        n += 1
        return ans
    }
}

/**
 * calibre.utils.short_uuid.uuid4() equivalent — the 128 random bits of a
 * UUID4 encoded little-endian in base 60 over the alphabet
 * `2-9A-Za-z` ('0' and '1' excluded to avoid O/I confusion), padded to
 * ceil(log60(2^128)) = 22 chars with '2'. render_book.py:627 uses this for
 * manifest.link_uid, and the same string becomes the `data-<uid>` attribute
 * suffix on virtualized links, so the exact alphabet keeps our manifests
 * byte-comparable with desktop output.
 */
private val SHORT_UUID_ALPHABET: List<Char> =
    ('2'..'9') + ('A'..'Z') + ('a'..'z') // 60 chars, sorted — mirrors short_uuid.py

private val SHORT_UUID_BASE = BigInteger.valueOf(SHORT_UUID_ALPHABET.size.toLong())
private const val SHORT_UUID_PAD_LEN = 22 // ceil(128 * ln2 / ln60)

private val secureRandom = SecureRandom()

fun shortUuid4(): String {
    val bytes = ByteArray(16)
    secureRandom.nextBytes(bytes)
    var n = BigInteger(1, bytes)
    val digits = ArrayList<Char>(SHORT_UUID_PAD_LEN)
    while (n.signum() > 0) {
        val rem = n.mod(SHORT_UUID_BASE).toInt()
        digits.add(SHORT_UUID_ALPHABET[rem])
        n = n.divide(SHORT_UUID_BASE)
    }
    while (digits.size < SHORT_UUID_PAD_LEN) digits.add(SHORT_UUID_ALPHABET[0])
    return String(digits.toCharArray())
}
