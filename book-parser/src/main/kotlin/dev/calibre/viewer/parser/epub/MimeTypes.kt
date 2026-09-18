// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Extension -> media type mapping mirroring calibre's guess_type() (Python
// stdlib mimetypes + calibre's resources/mime.types overrides), restricted to
// the extensions that occur inside EPUB books. Unknown extensions fall back
// to application/octet-stream exactly like polish/utils.guess_type().

package dev.calibre.viewer.parser.epub

object MimeTypes {
    const val NCX = "application/x-dtbncx+xml"
    const val OPF = "application/oebps-package+xml"
    const val XHTML = "application/xhtml+xml"
    const val HTML = "text/html"
    const val CSS = "text/css"
    const val SMIL = "application/smil+xml"

    /** The legacy spelling some books carry in their OPF media-type attributes. */
    const val SMIL_LEGACY = "application/smil"
    const val SVG = "image/svg+xml"
    const val OCTET_STREAM = "application/octet-stream"

    // Values where calibre's bundled mime.types overrides the stdlib table.
    private val BY_EXTENSION = mapOf(
        "xhtml" to XHTML,
        "html" to HTML, "htm" to HTML,
        "css" to CSS,
        "js" to "application/javascript",
        "json" to "application/json",
        "ncx" to NCX,
        "opf" to OPF,
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "svg" to SVG, "svgz" to SVG,
        "bmp" to "image/bmp",
        "tif" to "image/tiff", "tiff" to "image/tiff",
        "webp" to "image/webp",
        "avif" to "image/avif",
        "heic" to "image/heic",
        "jxl" to "image/jxl",
        "djv" to "image/vnd.djvu", "djvu" to "image/vnd.djvu",
        "ttf" to "font/ttf",
        "otf" to "font/otf",
        "woff" to "font/woff", "woff2" to "font/woff2",
        "eot" to "application/vnd.ms-fontobject",
        "pfb" to "application/x-font-type1", "afm" to "application/x-font-type1",
        "xpgt" to "application/adobe-page-template+xml",
        "mathml" to "application/mathml+xml",
        "smil" to SMIL,
        "mp3" to "audio/mpeg",
        "mp4" to "video/mp4",
        "ogg" to "application/ogg", "oga" to "audio/ogg", "opus" to "audio/ogg",
        "wav" to "audio/x-wav",
        "flac" to "application/x-flac",
        "aiff" to "audio/aiff", "aif" to "audio/aiff",
        "m4a" to "audio/mp4", "m4b" to "audio/mp4",
        "txt" to "text/plain", "text" to "text/plain",
        "rtf" to "text/rtf",
        "pdf" to "application/pdf",
        "xml" to "text/xml",
        "epub" to "application/epub+zip",
        "zip" to "application/zip",
    )

    /** polish/utils.guess_type(): mime for a filename, octet-stream if unknown. */
    fun guessType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return OCTET_STREAM
        return BY_EXTENSION[ext] ?: OCTET_STREAM
    }

    /** calibre.ebooks.oeb.base.OEB_DOCS: the document types in the spine. */
    val OEB_DOCS = setOf(XHTML, HTML, "text/x-oeb1-document", "text/x-oeb-document")

    /** calibre.ebooks.oeb.base.OEB_STYLES. */
    val OEB_STYLES = setOf(CSS, "text/x-oeb1-css", "text/x-oeb-css", "xhtml/css")

    /** Raster image mimes — used by cover detection (svg is treated separately). */
    val RASTER_IMAGES = setOf(
        "image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp",
        "image/tiff", "image/avif", "image/heic", "image/jxl",
        "image/vnd.djvu",
    )
}
