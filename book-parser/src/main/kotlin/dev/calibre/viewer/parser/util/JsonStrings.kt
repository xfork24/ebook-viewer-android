// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Shared JSON string literal encoder for the manifest / index / link-attr
// writers. Matches Python json.dumps(ensure_ascii=False): quote, backslash
// and the C0 controls get escapes, everything else (including CJK and
// astral code points) is written through as-is and UTF-8 encoded later.
//
// NOTE: html_as_json output does NOT use this — its escape table mirrors
// the C++ write_string_as_json (adds \b \f, uppercase \uXXXX hex, different
// control range) and lives in json/HtmlAsJson.kt on purpose.

package dev.calibre.viewer.parser.util

fun pyJsonString(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (c in s) {
        when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c.code < 0x20 -> sb.append("\\u").append(String.format("%04x", c.code))
            else -> sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}
