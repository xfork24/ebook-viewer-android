// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// The per-book serving state, mirroring the module globals of
// gui2/viewer/web_view.py:set_book_path(): the rendered book directory plus
// the pre-read manifest/metadata bytes that the /manifest route concatenates.
// Immutable by design — opening a new book installs a fresh instance.

package dev.calibre.viewer.serve

import dev.calibre.viewer.parser.epub.MimeTypes
import java.io.File
import java.io.IOException

class ViewerBook private constructor(
    /** Rendered book directory (the RenderBook output tdir). */
    val bookDir: File,
    /** Path of the source ebook as passed to start_book_load. */
    val pathtoebook: String,
    val manifestBytes: ByteArray,
    val metadataBytes: ByteArray,
    val manifestMime: String,
) {

    companion object {

        /**
         * Read the rendered tree's manifest + metadata. Returns null when the
         * manifest is missing — the caller treats that as "no book open yet".
         */
        fun open(bookDir: File, pathtoebook: String): ViewerBook? {
            val manifest = File(bookDir, MANIFEST_NAME)
            if (!manifest.isFile) return null
            val manifestBytes = try {
                manifest.readBytes()
            } catch (e: IOException) {
                return null
            }
            val metadataBytes = try {
                File(bookDir, METADATA_NAME).readBytes()
            } catch (e: IOException) {
                ByteArray(0)
            }
            return ViewerBook(
                bookDir = bookDir,
                pathtoebook = pathtoebook,
                manifestBytes = manifestBytes,
                metadataBytes = metadataBytes,
                manifestMime = MimeTypes.guessType(MANIFEST_NAME),
            )
        }

        const val MANIFEST_NAME = "calibre-book-manifest.json"
        const val METADATA_NAME = "calibre-book-metadata.json"
    }

    /**
     * get_data(): read one book file. Null → the caller answers 404; an
     * IOException propagates → the caller answers 400 (upstream
     * distinguishes UrlNotFound from RequestFailed the same way).
     */
    class BookFile(val mime: String, val bytes: ByteArray)

    fun getData(name: String): BookFile? {
        val path = SafeBookPath.resolve(bookDir, name) ?: return null
        if (!path.isFile) return null
        val raw = path.readBytes()
        // Same font-mime normalization + css charset suffix as the upstream
        // book/ branch — it keeps the Chromium console free of warnings and
        // makes css files decode as UTF-8 (we always write UTF-8).
        var mime = MimeTypes.guessType(name)
        mime = FONT_MIME_FIXES[mime] ?: mime
        if (mime == MimeTypes.CSS) mime = "$mime; charset=utf-8"
        return BookFile(mime, raw)
    }
}

// Prevents console warnings about the mimetype of fonts (upstream map).
private val FONT_MIME_FIXES = mapOf(
    "application/vnd.ms-opentype" to "application/x-font-ttf",
    "application/x-font-truetype" to "application/x-font-ttf",
    "application/font-sfnt" to "application/x-font-ttf",
)
