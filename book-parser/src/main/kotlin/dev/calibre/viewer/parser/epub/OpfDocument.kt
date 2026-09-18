// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// The OPF as an editable document, mirroring the element-level operations
// calibre's EPUBInput (ebooks/metadata/opf2.py OPF class) performs on it:
// href unquoting + delta rewrite, rationalize_cover2/3, nav->NCX manifest
// registration, spine cleanup and final render. Queries match elements by
// lowercase local name in the OPF namespace or no namespace, which covers
// both the strict polish-container view and opf2's lenient view of real
// world books.

package dev.calibre.viewer.parser.epub

import dev.calibre.viewer.parser.dom.DomElement
import dev.calibre.viewer.parser.dom.DomParser
import dev.calibre.viewer.parser.dom.XmlWriter

const val OPF_NS = "http://www.idpf.org/2007/opf"
const val DC_NS = "http://purl.org/dc/elements/1.1/"

/** oeb/base.py EPUB_NS — the epub: prefix (epub:type, epub:properties). */
const val EPUB_NS = "http://www.idpf.org/2007/ops"
const val CALIBRE_PREFIX_URI = "https://calibre-ebook.com"
const val NCX_NS = "http://www.daisy.org/z3986/2005/ncx/"

class NotEpubException(message: String) : Exception(message)
class DrmException(message: String) : Exception(message)

/** Parse and edit errors that mean "this book cannot be opened". */
open class InvalidBookException(message: String) : Exception(message)

class OpfDocument(val root: DomElement) {

    companion object {
        fun parse(bytes: ByteArray, parser: DomParser): OpfDocument =
            OpfDocument(parser.parseXml(bytes))
    }

    /** package/@version as a float; 0 when missing or unparsable (like opf2). */
    val packageVersion: Double =
        root.getAttr("version")?.toDoubleOrNull() ?: 0.0

    // ---- queries ----------------------------------------------------------

    private fun matches(e: DomElement, localName: String): Boolean =
        e.localName.equals(localName, ignoreCase = true) &&
            (e.namespace == OPF_NS || e.namespace.isEmpty())

    fun manifestItems(): List<DomElement> = root.walk().filterIsInstance<DomElement>()
        .filter { matches(it, "manifest") }
        .flatMap { m -> m.children.filterIsInstance<DomElement>().filter { matches(it, "item") } }
        .toList()

    fun spineItemrefs(): List<DomElement> = root.walk().filterIsInstance<DomElement>()
        .filter { matches(it, "spine") }
        .flatMap { s -> s.children.filterIsInstance<DomElement>().filter { matches(it, "itemref") } }
        .toList()

    fun spineElements(): List<DomElement> = root.walk().filterIsInstance<DomElement>()
        .filter { matches(it, "spine") }.toList()

    fun guideReferences(): List<DomElement> = root.walk().filterIsInstance<DomElement>()
        .filter { matches(it, "guide") }
        .flatMap { g -> g.children.filterIsInstance<DomElement>().filter { matches(it, "reference") } }
        .toList()

    /** metadata/opf:meta[name=..][content=..] elements (case-insensitive value match). */
    fun metaElements(name: String): List<DomElement> {
        val md = metadataElement() ?: return emptyList()
        return md.walk().filterIsInstance<DomElement>()
            .filter { matches(it, "meta") && it.getAttr("name")?.equals(name, ignoreCase = true) == true }
            .toList()
    }

    /**
     * The polish-container view: `//opf:meta[@name="..." and @content]` —
     * whole-tree (not just under <metadata>), OPF namespace or none, element
     * local name and the @name VALUE matched case-SENSITIVELY like XPath, and
     * @content must exist. This is what cover detection consumes; the lenient
     * [metaElements] above mirrors opf2's re:match(...,"i") view instead.
     */
    fun metaElementsExact(name: String): List<DomElement> =
        root.walk().filterIsInstance<DomElement>().filter {
            it.localName == "meta" && (it.namespace == OPF_NS || it.namespace.isEmpty()) &&
                it.getAttr("name") == name && it.getAttr("content") != null
        }.toList()

    fun metadataElement(): DomElement? =
        root.walk().filterIsInstance<DomElement>().firstOrNull { matches(it, "metadata") }

    /** //dc:<localName> — Dublin Core elements anywhere in the tree, exact namespace. */
    fun dcElements(localName: String): List<DomElement> =
        root.walk().filterIsInstance<DomElement>()
            .filter { it.namespace == DC_NS && it.localName == localName }
            .toList()

    /**
     * Identifier elements under metadata, in document order. opf2's
     * identifier_path is a regex match on name() (prefix included), so this
     * intentionally matches ANY namespace — dc:identifier is the normal case.
     */
    fun identifiers(): List<DomElement> {
        val md = metadataElement() ?: return emptyList()
        return md.walk().filterIsInstance<DomElement>()
            .filter { it.localName.contains("identifier", ignoreCase = true) }
            .toList()
    }

    /** opf2.raw_unique_identifier. */
    fun rawUniqueIdentifier(): String? {
        val uidAttr = root.attrs.keys.firstOrNull { it.substringAfter('}') == "unique-identifier" }
            ?: return null
        val uid = root.getAttr(uidAttr) ?: return null
        val target = root.walk().filterIsInstance<DomElement>()
            .firstOrNull { it.getAttr("id") == uid && !it.text.isNullOrEmpty() }
        return target?.text
    }

    // ---- EPUBInput edits --------------------------------------------------

    /** opf2.unquote_urls(): %-decode manifest and guide hrefs in place. */
    fun unquoteUrls() {
        for (item in manifestItems()) {
            item.getAttr("href")?.let { item.setAttr("href", BookNames.unquote(it)) }
        }
        for (ref in guideReferences()) {
            ref.getAttr("href")?.let { ref.setAttr("href", BookNames.unquote(it)) }
        }
    }

    /**
     * The delta-prefix rewrite from EPUBInput.convert: when the OPF does not
     * sit at the book root, prefix all manifest+guide hrefs with the OPF's
     * directory so every href becomes root-relative.
     */
    fun rewriteHrefsForOpfLocation(opfName: String) {
        val delta = BookNames.dirname(opfName).let { if (it.isEmpty()) "" else "$it/" }
        if (delta.isEmpty()) return
        for (item in manifestItems()) {
            val href = item.getAttr("href") ?: continue
            item.setAttr("href", BookNames.normpath(delta + href))
        }
        for (ref in guideReferences()) {
            val href = ref.getAttr("href") ?: continue
            ref.setAttr("href", BookNames.normpath(delta + href))
        }
    }

    /** EPUBInput.set_guide_type(). */
    fun setGuideType(gtype: String, href: String?, title: String) {
        for (ref in guideReferences()) {
            if (ref.getAttr("type")?.lowercase() == gtype) {
                root.parentOf(ref)?.remove(ref)
            }
        }
        if (href == null) return
        // only direct <guide> children of the package count here (upstream ./*)
        val guide = root.children.filterIsInstance<DomElement>().firstOrNull { matches(it, "guide") }
            ?: DomElement("{$OPF_NS}guide").also { root.append(it) }
        val reference = DomElement("{$OPF_NS}reference")
        reference.setAttr("type", gtype)
        reference.setAttr("title", title)
        reference.setAttr("href", href)
        guide.append(reference)
    }

    /** opf2.create_manifest_item(): unique id1/id2/... appended to the manifest. */
    fun createManifestItem(href: String, mediaType: String): DomElement {
        val ids = manifestItems().mapNotNull { it.getAttr("id") }.toSet()
        var c = 1
        var id = "id$c"
        while (id in ids) {
            c += 1
            id = "id$c"
        }
        val item = DomElement("{$OPF_NS}item")
        item.setAttr("id", id)
        item.setAttr("href", href)
        item.setAttr("media-type", mediaType)
        val manifest = root.walk().filterIsInstance<DomElement>().first { matches(it, "manifest") }
        manifest.append(item)
        return item
    }

    /**
     * Manifest items whose `properties` list contains the given property after
     * prefix expansion (opf3.items_with_property over the root's prefixes).
     */
    fun itemsWithProperty(property: String): List<DomElement> {
        val prefixes = readPrefixes()
        val q = expandPrefix(property, RESERVED_PREFIXES + ("calibre" to CALIBRE_PREFIX_URI))
            .lowercase()
        return manifestItems().filter { item ->
            item.getAttr("properties")?.lowercase()?.split(' ')?.any { prop ->
                expandPrefix(prop, prefixes) == q
            } == true
        }
    }

    // prefixes ---------------------------------------------------------------

    val packagePrefixAttr: String?
        get() = root.getAttr("prefix")

    private fun readPrefixes(): Map<String, String> =
        RESERVED_PREFIXES + parsePrefixes(packagePrefixAttr ?: "")

    /** opf3.expand_prefix(): replace every "prefix:rest" occurrence. */
    private fun expandPrefix(raw: String, prefixes: Map<String, String>): String =
        Regex("(\\S+)\\s*:\\s*(\\S+)").replace(raw) { m ->
            (prefixes[m.groupValues[1]] ?: m.groupValues[1]) + ":" + m.groupValues[2]
        }
}

val RESERVED_PREFIXES = mapOf(
    "dcterms" to "http://purl.org/dc/terms/",
    "epubsc" to "http://idpf.org/epub/vocab/sc/#",
    "marc" to "http://id.loc.gov/vocabulary/",
    "media" to "http://www.idpf.org/epub/vocab/overlays/#",
    "onix" to "http://www.editeur.org/ONIX/book/codelists/current.html#",
    "rendition" to "http://www.idpf.org/vocab/rendition/#",
    "schema" to "http://schema.org/",
    "xsd" to "http://www.w3.org/2001/XMLSchema#",
)

/** package/@prefix parsing (opf3.parse_prefixes, including its ": " quirk). */
fun parsePrefixes(raw: String): Map<String, String> {
    if (raw.isBlank()) return emptyMap()
    val ans = LinkedHashMap<String, String>()
    for (m in Regex("(\\S+): \\s*(\\S+)").findAll(raw)) {
        ans[m.groupValues[1]] = m.groupValues[2]
    }
    return ans
}

/** Serialize for writing back to disk (opf2.render() equivalent). */
fun renderOpf(opf: OpfDocument): ByteArray = XmlWriter.serializeToBytes(opf.root)
