// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Port of calibre/src/calibre/srv/render_book.py create_cover_page + the
// find_cover_image / find_cover_page / find_cover_image_in_page helpers from
// calibre/src/calibre/ebooks/oeb/polish/cover.py:125-296. Mirrors the EPUB
// branch of create_cover_page: when the book has both a title page document
// (guide cover or EPUB 3 landmark) and a raster cover, the title page is
// overwritten with an object-fit:contain template. The viewer's manifest
// carries raster_cover_name + title_page_name so the loading screen can
// render the raster directly while the spine starts on the title page.

package dev.calibre.viewer.parser.transform

import dev.calibre.viewer.parser.dom.DomElement
import dev.calibre.viewer.parser.dom.SVG_NS
import dev.calibre.viewer.parser.dom.XHTML_NS
import dev.calibre.viewer.parser.dom.XLINK_NS
import dev.calibre.viewer.parser.epub.BookContainer
import dev.calibre.viewer.parser.epub.MimeTypes
import dev.calibre.viewer.parser.epub.getLandmarks

object CoverFinder {

    private const val COVER_TEMPLATE = """
        <html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en">
        <head><style>
        html, body, img { height: 100vh; display: block; margin: 0; padding: 0; border-width: 0; }
        img {
            width: 100%; height: 100%;
            object-fit: contain;
            margin-left: auto; margin-right: auto;
            max-width: 100vw; max-height: 100vh;
            top: 50vh; transform: translateY(-50%);
            position: relative;
        }
        body.cover-fill img { object-fit: fill; }
        </style></head><body><img src="%s"/></body></html>
    """

    /** Calibre's COVER_TYPES (cover.py:119-129), verbatim — all lowercase. */
    private val COVER_TYPES = setOf(
        "coverimagestandard",
        "other.ms-coverimage-standard",
        "other.ms-titleimage-standard",
        "other.ms-titleimage",
        "other.ms-coverimage",
        "other.ms-thumbimage-standard",
        "other.ms-thumbimage",
        "thumbimagestandard",
        "cover",
    )

    /**
     * find_epub_cover (render_book.py:168-196): pair of (titlepage, raster).
     * Either side may be null; callers handle that (the viewer's render only
     * needs at least one). An EMPTY SPINE short-circuits to (null, null) —
     * upstream's next(spine_names) raises StopIteration before any branch.
     */
    fun findEpubCover(container: BookContainer): Pair<String?, String?> {
        val coverImage = findCoverImage(container)
        val markedTitlePage = findCoverPage(container)
        val firstPageName = container.spineItemNames.firstOrNull() ?: return null to null
        var coverImageInFirstPage: String? = null
        if (markedTitlePage == null) {
            coverImageInFirstPage = findCoverImageInPage(container, firstPageName)
        }
        val hasEpubCover = coverImage != null || markedTitlePage != null || coverImageInFirstPage != null
        if (!hasEpubCover) return null to null
        if (markedTitlePage != null && coverImage != null) return markedTitlePage to coverImage
        if (markedTitlePage != null) {
            val image = findCoverImageInPage(container, markedTitlePage)
            return if (image != null) markedTitlePage to image else null to null
        }
        return if (coverImageInFirstPage != null) firstPageName to coverImageInFirstPage else null to null
    }

    /** create_cover_page EPUB branch: overwrite [titlepage] with the object-fit template. */
    fun createCoverPage(container: BookContainer): Pair<String?, String?> {
        val (titlepage, raster) = findEpubCover(container)
        if (titlepage != null && raster != null) {
            val href = prepareStringForXml(container.nameToHref(raster, titlepage), attribute = true)
            val raw = COVER_TEMPLATE.replace("%s", href)
            container.replaceFile(titlepage, raw.toByteArray(Charsets.UTF_8))
        }
        return raster to titlepage
    }

    // ---- find_cover_image -------------------------------------------------

    fun findCoverImage(container: BookContainer): String? =
        if (container.opfMajorVersion < 3) findCoverImage2(container)
        else findCoverImage3(container)

    private fun findCoverImage2(container: BookContainer): String? {
        // OPF <meta name="cover" content="manifest-id"> → raster mime.
        // metaElementsExact = the polish container's case-sensitive
        // //opf:meta[@name="cover" and @content] (whole tree, not just
        // <metadata>).
        for (meta in container.opfDocument.metaElementsExact("cover")) {
            val itemId = meta.getAttr("content") ?: continue
            val name = container.manifestIdMap[itemId] ?: continue
            if (isRasterImage(container.mimeMap[name])) return name
        }
        // guide <reference type="cover"> with raster mime
        val mm = container.mimeMap
        for ((type, name) in container.guideTypeMap) {
            if (type.lowercase() == "cover" && isRasterImage(mm[name])) return name
        }
        // largest image among all COVER_TYPES guide entries (non-strict)
        var largest: Pair<String?, Long> = null to 0L
        for ((type, name) in container.guideTypeMap) {
            if (type.lowercase() !in COVER_TYPES) continue
            if (!isRasterImage(mm[name])) continue
            val path = container.namePathMap[name] ?: continue
            val sz = path.length()
            if (sz > largest.second) largest = name to sz
        }
        return largest.first
    }

    private fun findCoverImage3(container: BookContainer): String? {
        // Upstream returns the FIRST yield even when it's None (unresolvable
        // href) — the `for name in ...: return name` shape, bug-for-bug.
        for (name in container.manifestItemsWithProperty("cover-image")) return name
        for (meta in container.opfDocument.metaElementsExact("cover")) {
            val itemId = meta.getAttr("content") ?: continue
            val name = container.manifestIdMap[itemId] ?: continue
            if (isRasterImage(container.mimeMap[name])) return name
        }
        return null
    }

    // ---- find_cover_page --------------------------------------------------

    private fun findCoverPage(container: BookContainer): String? {
        val mm = container.mimeMap
        if (container.opfMajorVersion < 3) {
            for ((type, name) in container.guideTypeMap) {
                if (type.lowercase() == "cover" && (mm[name] ?: "").lowercase() in MimeTypes.OEB_DOCS) {
                    return name
                }
            }
        } else {
            // Same bug-for-bug first-yield semantics as findCoverImage3.
            for (name in container.manifestItemsWithProperty("calibre:title-page")) return name
            for (landmark in getLandmarks(container)) {
                if (landmark["type"] == "cover") {
                    val dest = landmark["dest"] ?: continue
                    if ((mm[dest] ?: "").lowercase() in MimeTypes.OEB_DOCS) return dest
                }
            }
        }
        return null
    }

    // ---- find_cover_image_in_page (cover.py:286-306) ----------------------

    /**
     * XPath('//h:body') must match EXACTLY ONE body; the image union is
     * descendant::h:img[@src] | descendant::svg:svg/descendant::svg:image —
     * note svg:image only counts under an svg:svg ancestor, and href is
     * `img.get('src') or img.get(XLINK('href'))` for BOTH kinds with no
     * bare-href fallback. Unresolvable hrefs still append (a null name
     * counts toward the "more than one image" bail, like upstream).
     */
    private fun findCoverImageInPage(container: BookContainer, coverPage: String): String? {
        val root = container.parsed(coverPage)
        val bodies = root.findElements(XHTML_NS, "body")
        if (bodies.size != 1) return null
        val body = bodies[0]
        val images = ArrayList<String?>()
        collectPageImages(container, body, body, coverPage, images)
        // "Document has more content than a single image" → bail
        if (body.collectText().isNotEmpty()) return null
        if (images.size > 1) return null
        return images.firstOrNull()
    }

    /** The union xpath walk: h:img[@src] anywhere, svg:image only under svg:svg. */
    private fun collectPageImages(
        container: BookContainer,
        scope: DomElement,
        node: DomElement,
        base: String,
        images: MutableList<String?>,
    ) {
        var inSvg = scope.namespace == SVG_NS && scope.localName == "svg"
        for (child in node.children) {
            if (child !is DomElement) continue
            val isImg = child.namespace == XHTML_NS && child.localName == "img" &&
                child.getAttr("src") != null
            val isSvgImage = inSvg && child.namespace == SVG_NS && child.localName == "image"
            if (isImg || isSvgImage) {
                // href = src (truthy) else xlink:href — Python `or` semantics
                val href = child.getAttr("src")?.takeIf { it.isNotEmpty() }
                    ?: child.getAttr("{$XLINK_NS}href")?.takeIf { it.isNotEmpty() }
                if (href != null) images.add(container.hrefToName(href, base))
            }
            collectPageImages(container, if (inSvg) scope else child, child, base, images)
        }
    }

    private fun isRasterImage(mime: String?): Boolean =
        mime != null && mime.lowercase() in MimeTypes.RASTER_IMAGES

    /** XML-attribute-safe escaping (&, <, >, ", '). Mirrors calibre.utils.prepare_string_for_xml(attr=True). */
    internal fun prepareStringForXml(raw: String, attribute: Boolean): String {
        var s = raw
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        if (attribute) {
            s = s.replace("\"", "&quot;").replace("'", "&apos;")
        }
        return s
    }
}

/**
 * xml2text(body) = etree.tostring(method='text', with_tail=False): elements'
 * .text plus every node's .tail — comments/PIs contribute NOTHING (verified
 * against lxml). Collapsed with Python re \s+ which is Unicode-aware (NBSP,
 * ideographic space); kotlin's RegexOption doesn't expose that JDK flag, so
 * compile the Pattern directly. The two whitespace definitions only differ
 * on \x1c-\x1f, which cannot appear inside lxml text anyway (invalid XML).
 */
private val WS_PLUS = java.util.regex.Pattern.compile("\\s+", java.util.regex.Pattern.UNICODE_CHARACTER_CLASS)

private fun DomElement.collectText(): String {
    val sb = StringBuilder()
    for (node in walk()) {
        if (node is DomElement) node.text?.let { sb.append(it) }
        node.tail?.let { sb.append(it) }
    }
    return WS_PLUS.matcher(sb).replaceAll("")
}