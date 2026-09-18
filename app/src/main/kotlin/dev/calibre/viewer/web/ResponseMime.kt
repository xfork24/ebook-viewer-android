// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Splitting a (possibly parameterised) content-type string into the bare
// media type + charset pair android.webkit.WebResourceResponse wants.
// BookServer speaks Qt-style types ("text/html; charset=utf-8", matching
// QWebEngineUrlRequestJob.setContentType), but WebResourceResponse treats
// its first argument as a bare type and its second as the encoding — and
// several vendor WebViews (observed on an iReader Smart Air Pro, Android 11)
// fail to recognise a parameterised type, fall back to text/plain and render
// the page as scrollable HTML source. Hence this translation at the glue.

package dev.calibre.viewer.web

/**
 * The pair WebResourceResponse consumes: [type] is a bare media type,
 * [encoding] the charset for textual content or null for binary.
 */
data class ResponseMime(val type: String, val encoding: String?)

fun splitResponseMime(mimeWithParams: String): ResponseMime {
    val type = mimeWithParams.substringBefore(';').trim().lowercase()
    val param = mimeWithParams.substringAfter(';', "").trim()
    val charset = if (param.lowercase().startsWith("charset=")) param.substringAfter('=') else null
    val encoding = charset ?: if (isTextual(type)) "utf-8" else null
    return ResponseMime(type, encoding)
}

// Everything the pipeline serves as text is UTF-8 by construction: the
// parser transcodes XHTML at read time and CSS gets a forced @charset.
private fun isTextual(type: String): Boolean =
    type.startsWith("text/") ||
        type == "application/json" ||
        type == "application/javascript" ||
        type.endsWith("+xml") ||
        type == "application/smil"
