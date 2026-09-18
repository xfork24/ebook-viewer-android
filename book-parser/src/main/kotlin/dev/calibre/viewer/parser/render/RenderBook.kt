// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// The book-parser orchestrator. Mirrors
// calibre/src/calibre/srv/render_book.py:563-726 process_exploded_book +
// the standalone `render_for_viewer` entry point. Walks a freshly extracted
// EPUB through the full pipeline and writes the calibre-book-manifest.json
// that viewer.js consumes as its starting point.
//
// What this does, in order:
//   1. Build a BookContainer over the extracted tree.
//   2. Compute the cover (title page + raster) and overwrite the title page
//      with the object-fit:contain template via CoverFinder.createCoverPage.
//   3. Resolve the TOC (nav → NCX → h1/h2/h3 fallback) and the landmarks
//      (EPUB 3 nav landmarks, or EPUB 2 guide references). Strip landmarks
//      whose dest is outside the spine.
//   4. Detect page-progression-direction from the spine.
//   5. For every OEB_DOCS file: capture length + has_maths + anchor_map,
//      then run HtmlTransformer (link virtualization + html_as_json).
//      For every OEB_STYLES file: run StandaloneCss (charset + page-break
//      rewrite). SMIL files feed SmilTransformer. SVG images are no-ops in
//      standalone mode.
//   6. Build the manifest as a nested Map<String, Any?>, including the
//      toc/length/anchor/link_to_map aggregates, and write it as UTF-8 JSON
//      to <tdir>/calibre-book-manifest.json (ensure_ascii=False → UTF-8).
//   7. Remove the manifest-internal files (OPF, NCX, META-INF/*, mimetype,
//      zero-byte files) from disk; the manifest's `files` map already lists
//      them as excluded.
//
// Standalone (virtualize_resources=False) is the only mode this Android
// shell uses — the viewer fetches its own resources through the embedded
// content server, so we never rewrite asset URLs into tokens.

package dev.calibre.viewer.parser.render

import dev.calibre.viewer.parser.dom.DomParser
import dev.calibre.viewer.parser.epub.BookContainer
import dev.calibre.viewer.parser.epub.InvalidBookException
import dev.calibre.viewer.parser.epub.MimeTypes
import dev.calibre.viewer.parser.epub.fromXpaths
import dev.calibre.viewer.parser.epub.getLandmarks
import dev.calibre.viewer.parser.epub.getToc
import dev.calibre.viewer.parser.transform.AnchorMap
import dev.calibre.viewer.parser.transform.CoverFinder
import dev.calibre.viewer.parser.transform.HtmlTransformer
import dev.calibre.viewer.parser.transform.LengthCounter
import dev.calibre.viewer.parser.transform.SmilTransformer
import dev.calibre.viewer.parser.transform.StandaloneCss
import dev.calibre.viewer.parser.util.pyJsonString
import dev.calibre.viewer.parser.util.pythonCount
import dev.calibre.viewer.parser.util.shortUuid4
import java.io.File

object RenderBook {

    /** Mirrors RENDER_VERSION = 1 in render_book.py — bumped on manifest breaks. */
    const val RENDER_VERSION = 1

    /** Manifest filename — viewer.js fetch + the content server route both key on this. */
    const val MANIFEST_NAME = "calibre-book-manifest.json"

    /**
     * @param tdir extracted EPUB directory (from EpubInput.convert)
     * @param opfName POSIX-style name of the OPF inside [tdir]
     * @param parser DomParser factory shared with the EPUB input pipeline
     * @param bookFormat MIME of the source format, e.g. "application/epub+zip"
     * @param bookHash opaque per-book hash the cache layer uses to look up an
     *   already-rendered copy; pass empty for "force re-render"
     * @param isComic true for image-collection formats (not used by the EPUB
     *   reader, but the manifest field is kept for compatibility)
     */
    fun render(
        tdir: File,
        opfName: String,
        parser: DomParser,
        bookFormat: String = "application/epub+zip",
        bookHash: String = "",
        isComic: Boolean = false,
    ): RenderResult {
        val container = BookContainer.open(tdir, opfName, parser)

        // excluded: opf/NCX/META-INF/mimetype + zero-byte files (the viewer's
        // IndexedDB has no way to distinguish a missing file from a load
        // failure, so calibre never hands it a zero-byte entry).
        val excluded = LinkedHashSet<String>()
        val present = LinkedHashSet<String>()
        for ((name, _) in container.mimeMap) {
            if (container.hasNameAndIsNotEmpty(name)) {
                present.add(name)
                if (name == container.opfName ||
                    container.mimeMap[name] == MimeTypes.NCX ||
                    name.startsWith("META-INF/") ||
                    name == "mimetype"
                ) {
                    excluded.add(name)
                }
            } else {
                excluded.add(name)
            }
        }

        val (rasterCover, titlePage) = CoverFinder.createCoverPage(container)

        // render_for_viewer runs with extract_annotations=True: capture the
        // raw bookmark bytes BEFORE the excluded-file sweep deletes META-INF.
        // Parsing them into annotations is the M3 annotations store's job.
        val bookmarkData = container.namePathMap["META-INF/calibre_bookmarks.txt"]
            ?.takeIf { it.isFile }?.readBytes()

        // serialize_metadata=True: extract while the OPF tree is still around
        // (the OPF file itself is deleted with the other excluded names later).
        val metadata = BookMetadata.extract(container)

        // TOC + landmarks (EPUB 3 nav → EPUB 2 NCX → h1/h2/h3 fallback)
        var toc = getToc(container)
        if (toc.children.isEmpty()) {
            toc = fromXpaths(container, listOf("//h:h1", "//h:h2", "//h:h3"))
        }
        val tocMap = toc.toMap(pythonCount())
        // page_list entries are mutated by pageListAnchorMap (ids get added
        // in place, exactly like upstream pagelist_anchor_map), so hand it
        // mutable copies that also become the manifest value.
        val pageList = toc.pageList.map { LinkedHashMap<String, Any?>(it) }
        val spine = container.spineItemNames
        val spineSet = spine.toHashSet()
        val landmarks = getLandmarks(container).filter { (it["dest"] ?: "") in spineSet }

        // Upstream raises Spineless('Book is empty, no content in spine')
        if (spine.isEmpty()) {
            throw InvalidBookException("Book is empty, no content in spine")
        }

        val ppd = container.opfDocument.spineElements().firstOrNull()?.let { spine ->
            // //opf:spine/@page-progression-direction: lxml matches UNPREFIXED
            // attributes, which live in no namespace even under a default
            // xmlns — kxml reports those with an empty attribute namespace,
            // so the bare key is the correct (and only) lookup.
            spine.getAttr("page-progression-direction")
        }

        // short_uuid.uuid4() — 22 chars over 2-9A-Za-z, see PyUtils.
        val linkUid = shortUuid4()
        val linkToMap = HashMap<String, MutableMap<String, MutableSet<String>>>()

        // Per-file HTML / CSS / SMIL work, sequenced like upstream but without
        // a worker pool — Android's storage is flash, IO is fast enough for the
        // typical novel on a 4×A55 e-ink CPU, and parallel DOM parses would
        // just contend for the same kxml2 XmlPullParserFactory.
        val htmlData = HashMap<String, HtmlFileData>()
        // Keys are nullable: an unresolvable textref targets smil_map[None].
        val finalSmilMap = HashMap<String?, SmilTransformer.SmilSeq>()
        var hasMaths = false
        val smilFileNames = ArrayList<String>()

        for (name in present) {
            val mt = (container.mimeMap[name] ?: "").lowercase()
            when {
                mt in MimeTypes.OEB_DOCS -> {
                    val root = container.parsed(name)
                    val length = LengthCounter.getLength(root)
                    val maths = AnchorMap.hasMaths(root)
                    if (maths) hasMaths = true
                    val anchors = AnchorMap.anchorMap(root)
                    htmlData[name] = HtmlFileData(length, maths, anchors)

                    HtmlTransformer.transformHtml(container, name, linkUid, linkToMap, present)
                }
                mt in MimeTypes.OEB_STYLES -> {
                    StandaloneCss.transformStyleSheet(container, name)
                }
                mt == MimeTypes.SVG -> {
                    StandaloneCss.transformSvgImage(container, name)
                }
                // upstream needs_work() matches BOTH smil spellings — the
                // media-type in the OPF manifest is author-written
                mt == MimeTypes.SMIL || mt == MimeTypes.SMIL_LEGACY -> {
                    val smilMap = HashMap<String?, SmilTransformer.SmilSeq>()
                    SmilTransformer.transformSmil(container, name, smilMap)
                    smilFileNames.add(name)
                    // upstream merge_smil_map has an `if d:` truthiness filter,
                    // but every seq dict it merges carries textref+type and is
                    // therefore never empty — nothing to filter in practice.
                    for ((k, v) in smilMap) finalSmilMap[k] = v
                }
            }
        }

        // Drop SMIL files themselves from `files` — they only exist to feed
        // smil_map, the viewer never fetches them.
        for (n in smilFileNames) excluded.add(n)

        // Per-file manifest entries.
        var totalLength = 0
        var spineLength = 0
        val files = LinkedHashMap<String, Map<String, Any?>>()
        for (name in container.namePathMap.keys) {
            if (name in excluded) continue
            val mt = (container.mimeMap[name] ?: MimeTypes.OCTET_STREAM).lowercase()
            val isHtml = mt in MimeTypes.OEB_DOCS
            val size = container.namePathMap[name]?.length() ?: 0L
            val entry = LinkedHashMap<String, Any?>()
            entry["size"] = size
            entry["is_virtualized"] = false
            entry["mimetype"] = mt
            entry["is_html"] = isHtml
            if (isHtml) {
                // Upstream does html_data[name] and would KeyError — an
                // OEB_DOCS file that skipped the transform loop is a bug we
                // want loud, not a silently truncated manifest.
                val d = htmlData[name] ?: error("OEB_DOCS file missing from html_data: $name")
                entry["length"] = d.length
                totalLength += d.length
                if (name in spineSet) spineLength += d.length
                entry["has_maths"] = d.hasMaths
                entry["anchor_map"] = d.anchorMap
                finalSmilMap[name]?.let { entry["smil_map"] = smilSeqToMap(it) }
            }
            files[name] = entry
        }

        // link_to_map: convert the inner MutableSet<String> → List<String> so
        // the JSON encoder produces a JSON array (Python upstream does the same
        // tuple() conversion at line 720).
        val linkToMapOut = LinkedHashMap<String, Map<String, List<String>>>()
        for ((dest, frags) in linkToMap) {
            val m = LinkedHashMap<String, List<String>>()
            for ((frag, sources) in frags) m[frag] = sources.toList()
            linkToMapOut[dest] = m
        }

        // Build the full manifest.
        val manifest = LinkedHashMap<String, Any?>()
        manifest["version"] = RENDER_VERSION
        manifest["toc"] = tocMap
        manifest["book_format"] = bookFormat
        manifest["spine"] = spine
        manifest["link_uid"] = linkUid
        manifest["book_hash"] = bookHash
        manifest["is_comic"] = isComic
        manifest["raster_cover_name"] = rasterCover
        manifest["title_page_name"] = titlePage
        manifest["has_maths"] = hasMaths
        manifest["total_length"] = totalLength
        manifest["spine_length"] = spineLength
        manifest["toc_anchor_map"] = AnchorMap.tocAnchorMap(toc)
        manifest["landmarks"] = landmarks
        manifest["link_to_map"] = linkToMapOut
        manifest["page_progression_direction"] = ppd
        manifest["page_list"] = pageList
        manifest["page_list_anchor_map"] = AnchorMap.pageListAnchorMap(pageList)
        // has_smil/files are appended AFTER the literal above upstream
        // (book_render_data['has_smil'] = … / ['files'] = …), so they land
        // at the end of the serialized dict — keep that key order.
        manifest["has_smil"] = finalSmilMap.isNotEmpty()
        manifest["files"] = files

        container.commit()

        // Remove the excluded files now that the manifest no longer references them.
        for (name in excluded) container.namePathMap[name]?.delete()

        // Write the manifest as UTF-8 JSON with no \uXXXX escapes (matches
        // calibre's ensure_ascii=False output byte-for-byte for ASCII / CJK).
        val bytes = jsonEncode(manifest).toByteArray(Charsets.UTF_8)
        File(tdir, MANIFEST_NAME).writeBytes(bytes)

        // serialize_metadata=True: calibre-book-metadata.json beside it.
        val metadataPath = File(tdir, BookMetadata.METADATA_NAME)
        metadataPath.writeBytes(jsonEncode(metadata).toByteArray(Charsets.UTF_8))

        return RenderResult(
            container = container,
            tdir = tdir,
            manifestPath = File(tdir, MANIFEST_NAME),
            metadataPath = metadataPath,
            manifest = manifest,
            metadata = metadata,
            bookmarkData = bookmarkData,
            linkUid = linkUid,
            rasterCoverName = rasterCover,
            titlePageName = titlePage,
            totalLength = totalLength,
            spineLength = spineLength,
        )
    }

    private fun smilSeqToMap(seq: SmilTransformer.SmilSeq): Map<String, Any?> {
        // Upstream process_seq builds {'textref', 'par': [], 'seq': []}, adds
        // 'type' last, and DELETES empty 'par'/'seq' keys — key order and
        // omission are part of the byte format. Par dicts are already
        // insertion-ordered by makePar.
        val ans = LinkedHashMap<String, Any?>()
        ans["textref"] = seq.textref.toList()
        if (seq.par.isNotEmpty()) ans["par"] = seq.par.toList()
        if (seq.seq.isNotEmpty()) ans["seq"] = seq.seq.map { smilSeqToMap(it) }
        seq.type?.let { ans["type"] = it }
        return ans
    }

    /** Internal data class for the HTML-only fields accumulated during pipeline. */
    private data class HtmlFileData(val length: Int, val hasMaths: Boolean, val anchorMap: List<String>)

    // ---- JSON encoding -----------------------------------------------------

    /**
     * Recursive encoder for nested Any? trees. Matches Python's
     * json.dumps(ensure_ascii=False) by emitting UTF-8 bytes directly —
     * including Python's float repr, which always carries a decimal point
     * (3600.0, never 3600) so SMIL start/end times serialize identically.
     */
    fun jsonEncode(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> if (value) "true" else "false"
        is Double -> {
            // Python repr() for doubles: shortest round-trip form with a
            // mandatory '.' for integral values.
            if (value.isFinite() && value == value.toLong().toDouble() && kotlin.math.abs(value) < 1e16) {
                "${value.toLong()}.0"
            } else {
                value.toString()
            }
        }
        is Number -> {
            val d = value.toDouble()
            if (d.isFinite() && d == d.toLong().toDouble() && d >= Long.MIN_VALUE.toDouble() &&
                d <= Long.MAX_VALUE.toDouble()
            ) {
                value.toLong().toString()
            } else {
                d.toString()
            }
        }
        is String -> jsonString(value)
        is Map<*, *> -> buildString {
            append('{')
            var first = true
            for ((k, v) in value) {
                if (k !is String) continue
                if (!first) append(',')
                first = false
                append(jsonString(k)).append(':').append(jsonEncode(v))
            }
            append('}')
        }
        is Iterable<*> -> buildString {
            append('[')
            var first = true
            for (v in value) {
                if (!first) append(',')
                first = false
                append(jsonEncode(v))
            }
            append(']')
        }
        else -> jsonString(value.toString())
    }

    /** Compact JSON string literal — matches Python's json.dumps ensure_ascii=False. */
    private fun jsonString(s: String): String = pyJsonString(s)
}

/** Returned to the caller so the app layer can locate manifest + cover. */
data class RenderResult(
    val container: BookContainer,
    val tdir: File,
    val manifestPath: File,
    val metadataPath: File,
    val manifest: Map<String, Any?>,
    val metadata: Map<String, Any?>,
    /** Raw META-INF/calibre_bookmarks.txt bytes (extract_annotations=True), null when absent. */
    val bookmarkData: ByteArray?,
    val linkUid: String,
    val rasterCoverName: String?,
    val titlePageName: String?,
    val totalLength: Int,
    val spineLength: Int,
)
