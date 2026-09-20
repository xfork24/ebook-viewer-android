// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// The per-book serving state, mirroring the module globals of
// gui2/viewer/web_view.py:set_book_path(): the rendered book directory plus
// the pre-read manifest/metadata bytes that the /manifest route concatenates.
// Immutable by design — opening a new book installs a fresh instance.

package dev.calibre.viewer.serve

import dev.calibre.viewer.parser.epub.MimeTypes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileInputStream
import java.io.IOException

class ViewerBook private constructor(
    /** Rendered book directory (the RenderBook output tdir). */
    val bookDir: File,
    /** Path of the source ebook as passed to start_book_load. */
    val pathtoebook: String,
    val manifestBytes: ByteArray,
    val metadataBytes: ByteArray,
    val manifestMime: String,
    /**
     * Spine file names as declared by calibre's `manifest.spine[]`, in
     * reading order. Empty when the manifest lacks a `spine` array — the
     * caller should then skip prefetch but still serve on demand.
     */
    val spine: List<String>,
) {

    companion object {

        /**
         * Read the rendered tree's manifest + metadata. Returns null when the
         * manifest is missing — the caller treats that as "no book open yet".
         * Unreadable/empty metadata also returns null (with [onWarn]): the
         * render pipeline always writes a non-empty metadata JSON, so its
         * absence means a broken render — serving on would concatenate a
         * `[<manifest>,]` trailing comma into /manifest and the viewer would
         * fail to JSON.parse it with a cryptic load error instead of the
         * host's friendly one.
         */
        fun open(bookDir: File, pathtoebook: String, onWarn: (String) -> Unit = {}): ViewerBook? {
            val manifest = File(bookDir, MANIFEST_NAME)
            if (!manifest.isFile) return null
            val manifestBytes = try {
                manifest.readBytes()
            } catch (e: IOException) {
                onWarn("manifest unreadable in $bookDir: $e")
                return null
            }
            val metadataBytes = try {
                File(bookDir, METADATA_NAME).readBytes()
            } catch (e: IOException) {
                onWarn("metadata unreadable in $bookDir: $e")
                return null
            }
            if (metadataBytes.isEmpty()) {
                onWarn("metadata empty in $bookDir")
                return null
            }
            return ViewerBook(
                bookDir = bookDir,
                pathtoebook = pathtoebook,
                manifestBytes = manifestBytes,
                metadataBytes = metadataBytes,
                manifestMime = MimeTypes.guessType(MANIFEST_NAME),
                spine = parseSpine(manifestBytes),
            )
        }

        /**
         * Extract the spine filename list from a calibre manifest JSON blob.
         * Best-effort: malformed JSON or a missing `spine` array returns
         * empty. The list preserves reading order — index N is the Nth
         * spine item the viewer will render.
         */
        private fun parseSpine(manifestBytes: ByteArray): List<String> = try {
            val root = Json.parseToJsonElement(manifestBytes.toString(Charsets.UTF_8))
            val obj = root as? kotlinx.serialization.json.JsonObject ?: return emptyList()
            val spine = obj["spine"] as? JsonArray ?: return emptyList()
            spine.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.jsonPrimitive?.content }
        } catch (e: Exception) {
            emptyList()
        }

        const val MANIFEST_NAME = "calibre-book-manifest.json"
        const val METADATA_NAME = "calibre-book-metadata.json"
    }

    /**
     * get_data(): open one book file for streaming. Null → the caller
     * answers 404; an IOException from the open propagates → the caller
     * answers 400 (upstream distinguishes UrlNotFound from RequestFailed
     * the same way). The file is opened EAGERLY here so a vanished file
     * raises in the router (which knows the error mapping) rather than on
     * Chromium's reader thread. Ownership transfers to Chromium: it reads
     * and closes the stream — the host must never close it.
     */
    class BookFile(val mime: String, val stream: FileInputStream)

    fun getData(name: String): BookFile? {
        val path = SafeBookPath.resolve(bookDir, name) ?: return null
        if (!path.isFile) return null
        val stream = path.inputStream()
        // Same font-mime normalization + css charset suffix as the upstream
        // book/ branch — it keeps the Chromium console free of warnings and
        // makes css files decode as UTF-8 (we always write UTF-8).
        var mime = MimeTypes.guessType(name)
        mime = FONT_MIME_FIXES[mime] ?: mime
        if (mime == MimeTypes.CSS) mime = "$mime; charset=utf-8"
        return BookFile(mime, stream)
    }
}

// Prevents console warnings about the mimetype of fonts (upstream map).
private val FONT_MIME_FIXES = mapOf(
    "application/vnd.ms-opentype" to "application/x-font-ttf",
    "application/x-font-truetype" to "application/x-font-ttf",
    "application/font-sfnt" to "application/x-font-ttf",
)
