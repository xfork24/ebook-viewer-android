// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Encoding detection mirroring calibre's detect_xml_encoding +
// strip_encoding_declarations (src/calibre/ebooks/chardet.py), reduced to the
// paths that matter for EPUB payloads: BOM sniffing, <?xml?>/meta charset
// declarations, gb2312->gbk repair, and assume-utf8 fallback.

package dev.calibre.viewer.parser.dom

import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

private val META_CHARSET_PATS = listOf(
    // Any PI carrying an encoding pseudo-attribute (<?xml ...?> included) —
    // upstream matches any `<?…encoding=…?>`, case-insensitively.
    Regex("""<\?[^<>]*?encoding\s*=\s*['"]([-_a-zA-Z0-9]+)['"]""", RegexOption.IGNORE_CASE),
    // HTML 5: <meta charset="...">
    Regex("""<meta\s+charset=['"]([-_a-z0-9]+)['"][^<>]*>(?:\s*</meta>){0,1}""", RegexOption.IGNORE_CASE),
    // HTML 4 pragma: <meta content="...charset=...">
    Regex("""<meta\s+?[^<>]*?content\s*=\s*['"][^'"]*?charset=([-_a-z0-9]+)[^'"]*?['"][^<>]*>(?:\s*</meta>){0,1}""", RegexOption.IGNORE_CASE),
)

private val ENCODING_DECL_PATS = listOf(
    Regex("""<\?xml\s+[^>]*?encoding\s*=\s*['"][^'"]*['"][^>]*\?>""", RegexOption.IGNORE_CASE),
    Regex("""<meta\s+charset=['"][^'"]*['"][^<>]*>(?:\s*</meta>){0,1}""", RegexOption.IGNORE_CASE),
    Regex("""<meta\s+?[^<>]*?content\s*=\s*['"][^'"]*?charset=[^'"]*?['"][^<>]*>(?:\s*</meta>){0,1}""", RegexOption.IGNORE_CASE),
)

private val CHARSET_ALIASES = mapOf(
    "macintosh" to "MacRoman",
    "gb2312" to "GBK", "chinese" to "GBK", "csiso58gb231280" to "GBK", "euc-cn" to "GBK",
    "euccn" to "GBK", "eucgb2312-cn" to "GBK", "gb2312-1980" to "GBK", "gb2312-80" to "GBK",
    "iso-ir-58" to "GBK",
    "windows-1252" to "windows-1252", "cp1252" to "windows-1252",
    "latin1" to "ISO-8859-1", "latin-1" to "ISO-8859-1", "iso8859-1" to "ISO-8859-1",
    "us-ascii" to "UTF-8", "ascii" to "UTF-8",
    "utf8" to "UTF-8",
)

/**
 * decode_xml (polish/parsing.py:18-50): BOM → strict decode; then strict
 * UTF-8; then the declared encoding; NFC is applied only on this final
 * recovery path (upstream's xml_to_unicode branch). Every path normalizes
 * CRLF/CR to LF like fix_data().
 */
internal fun decodeBytes(data: ByteArray): String {
    fun fixData(s: String) = s.replace("\r\n", "\n").replace("\r", "\n")

    // BOM sniffing — UTF-32 first: its BOMs start with the UTF-16 patterns.
    val bom: Pair<Int, Charset>? = when {
        data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
            data[2] == 0xFE.toByte() && data[3] == 0xFF.toByte() -> 4 to Charsets.UTF_32BE
        data.size >= 4 && data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte() &&
            data[2] == 0.toByte() && data[3] == 0.toByte() -> 4 to Charsets.UTF_32LE
        data.size >= 2 && data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte() -> 2 to Charsets.UTF_16BE
        data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte() -> 2 to Charsets.UTF_16LE
        data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte() ->
            3 to Charsets.UTF_8
        else -> null
    }
    if (bom != null) {
        // Upstream falls THROUGH to the next strategy on a decode error.
        strictDecode(data, bom.first, bom.second)?.let { return fixData(it) }
    }
    strictDecode(data, 0, Charsets.UTF_8)?.let { return fixData(it) }

    // Recovery: declared encoding anywhere in the buffer (no size cap),
    // else UTF-8 with replacement. NFC only on this path.
    val declared = detectDeclaredEncoding(data)
    val recovered = declared?.let { replaceDecode(data, 0, resolveCharset(it)) }
        ?: replaceDecode(data, 0, Charsets.UTF_8)
    return Normalizer.normalize(fixData(recovered), Normalizer.Form.NFC)
}

private fun detectDeclaredEncoding(data: ByteArray): String? {
    // Declarations are ASCII around the encoding name; a non-UTF8 charset
    // still spells its own name in ASCII inside the declaration.
    val ascii = String(data, Charsets.ISO_8859_1)
    for (pat in META_CHARSET_PATS) {
        val m = pat.find(ascii, 0) ?: continue
        if (m.range.last < data.size) return m.groupValues[1]
    }
    return null
}

private fun resolveCharset(name: String): Charset {
    val lowered = name.lowercase().trim()
    val canonical = CHARSET_ALIASES[lowered] ?: lowered
    return runCatching { Charset.forName(canonical) }.getOrDefault(Charsets.UTF_8)
}

private fun strictDecode(data: ByteArray, skip: Int, charset: Charset): String? {
    val decoder = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return runCatching {
        decoder.decode(java.nio.ByteBuffer.wrap(data, skip, data.size - skip)).toString()
    }.getOrNull()
}

private fun replaceDecode(data: ByteArray, skip: Int, charset: Charset): String {
    val decoder = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    return decoder.decode(java.nio.ByteBuffer.wrap(data, skip, data.size - skip)).toString()
}

/** strip_encoding_declarations(): remove xml-decl/meta charset stanzas (first 50 KiB). */
internal fun stripEncodingDeclarations(text: String): String {
    val limit = 50 * 1024
    val prefixEnd = minOf(text.length, limit)
    var prefix = text.substring(0, prefixEnd)
    for (pat in ENCODING_DECL_PATS) {
        prefix = pat.replace(prefix, "")
    }
    return prefix + text.substring(prefixEnd)
}
