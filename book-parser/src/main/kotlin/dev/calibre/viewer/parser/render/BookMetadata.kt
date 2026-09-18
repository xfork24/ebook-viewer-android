// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// calibre-book-metadata.json producer. Mirrors the viewer slice of
// srv/render_book.py:render(serialize_metadata=True):
//   get_metadata(epub) → metadata_as_dict(mi) → pop cover_data →
//   serialize_datetimes → calibre-book-metadata.json
// We extract the SERIALIZABLE_FIELDS subset an EPUB OPF can actually carry;
// the is_null() rule from metadata_as_dict applies — empty lists / empty
// strings / null simply omit the key, exactly like upstream.
//
// Known simplifications (display-only impact):
//   - pubdate accepts ISO-shaped dates only; calibre's parse_date is far more
//     liberal (bare years, month names). Unparsable dates omit the field.
//   - identifiers without a scheme and without a urn: prefix are dropped
//     instead of getting a synthesized scheme.
//   - key order is fixed here; upstream iterates a frozenset whose order is
//     hash-randomized per process, so no stable byte order exists anyway.

package dev.calibre.viewer.parser.render

import dev.calibre.viewer.parser.dom.DomElement
import dev.calibre.viewer.parser.epub.BookContainer
import dev.calibre.viewer.parser.epub.OpfDocument
import dev.calibre.viewer.parser.epub.DC_NS

object BookMetadata {

    /** calibre-book-metadata.json filename (the /manifest route appends it). */
    const val METADATA_NAME = "calibre-book-metadata.json"

    fun extract(container: BookContainer): Map<String, Any?> {
        val opf = container.opfDocument
        val ans = LinkedHashMap<String, Any?>()

        firstText(opf, "title")?.let { ans["title"] = it }
        metaContent(opf, "calibre:title_sort")?.let { ans["title_sort"] = it }

        val authors = allTexts(opf, "creator")
        if (authors.isNotEmpty()) {
            ans["authors"] = authors
            // author_sort_map: {author: file-as} for creators that carry one
            val sortMap = LinkedHashMap<String, String>()
            for (elem in opf.dcElements("creator")) {
                val name = elem.text?.trim().orEmpty()
                val fileAs = elem.suffixAttr("file-as")?.takeIf { it.isNotEmpty() }
                if (name.isNotEmpty() && fileAs != null) sortMap[name] = fileAs
            }
            if (sortMap.isNotEmpty()) ans["author_sort_map"] = sortMap
        }

        // book_producer: contributor (or creator) with opf:role="bkp"
        (opf.dcElements("contributor") + opf.dcElements("creator"))
            .firstOrNull { it.suffixAttr("role") == "bkp" }
            ?.text?.takeIf { it.isNotBlank() }?.let { ans["book_producer"] = it.trim() }

        val languages = allTexts(opf, "language")
        if (languages.isNotEmpty()) ans["languages"] = languages

        val identifiers = LinkedHashMap<String, String>()
        for (elem in opf.dcElements("identifier")) {
            val value = elem.text?.trim().orEmpty()
            if (value.isEmpty()) continue
            val scheme = elem.suffixAttr("scheme")?.lowercase()?.takeIf { it.isNotEmpty() }
                ?: urnScheme(value) ?: continue
            identifiers[scheme] = value
        }
        if (identifiers.isNotEmpty()) ans["identifiers"] = identifiers

        firstText(opf, "publisher")?.let { ans["publisher"] = it }

        // pubdate: first dc:date that parses as an ISO-shaped date
        for (elem in opf.dcElements("date")) {
            val raw = elem.text?.trim() ?: continue
            val iso = normalizeIsoDate(raw)
            if (iso != null) {
                ans["pubdate"] = iso
                break
            }
        }

        val tags = allTexts(opf, "subject")
        if (tags.isNotEmpty()) ans["tags"] = tags

        firstText(opf, "description")?.let { ans["comments"] = it }
        firstText(opf, "rights")?.let { ans["rights"] = it }

        metaContent(opf, "calibre:series")?.let { ans["series"] = it }
        metaContent(opf, "calibre:series_index")?.let {
            it.toDoubleOrNull()?.let { d -> ans["series_index"] = d }
        }

        return ans
    }

    // ---- helpers -----------------------------------------------------------

    /**
     * Attribute lookup by local suffix — `scheme`, `file-as` and `role` show
     * up both bare and under the `opf:` prefix (a namespace of their own in
     * Clark form), and either spelling must match.
     */
    private fun DomElement.suffixAttr(suffix: String): String? {
        for (key in attrs.keys) {
            if (key.substringAfter('}') == suffix) return getAttr(key)
        }
        return null
    }

    private fun firstText(opf: OpfDocument, localName: String): String? =
        opf.dcElements(localName).firstOrNull()?.text?.trim()?.takeIf { it.isNotEmpty() }

    private fun allTexts(opf: OpfDocument, localName: String): List<String> =
        opf.dcElements(localName).mapNotNull { e -> e.text?.trim()?.takeIf { it.isNotEmpty() } }

    /** <meta name="..."/> content in the opf2 re:match("i") view. */
    private fun metaContent(opf: OpfDocument, name: String): String? =
        opf.metaElements(name).firstOrNull()?.getAttr("content")?.takeIf { it.isNotEmpty() }

    /** "urn:uuid:xxx" → "uuid", "urn:isbn:yyy" → "isbn", else null. */
    private fun urnScheme(value: String): String? {
        if (!value.startsWith("urn:", ignoreCase = true)) return null
        val rest = value.substring(4)
        val i = rest.indexOf(':')
        if (i <= 0) return null
        return rest.substring(0, i).lowercase()
    }

    private val ISO_DATE = Regex("^(\\d{4})(?:-(\\d{2}))?(?:-(\\d{2}))?")

    /**
     * Accept YYYY, YYYY-MM, YYYY-MM-DD (an ISO date-time prefix is fine, the
     * date part is kept). Returns the normalized YYYY-MM-DD form or null.
     */
    internal fun normalizeIsoDate(raw: String): String? {
        val m = ISO_DATE.find(raw) ?: return null
        val (y, mo, d) = m.destructured
        val month = mo.ifEmpty { "01" }
        val day = d.ifEmpty { "01" }
        val monthN = month.toInt()
        val dayN = day.toInt()
        if (monthN !in 1..12 || dayN !in 1..31) return null
        return "$y-$month-$day"
    }
}
