// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// The slice of calibre's polish Container (ebooks/oeb/polish/container.py)
// that render_book.py's process_exploded_book() actually uses: the file map
// of the extracted book, the mime map (extension guess + OPF manifest
// refresh), spine iteration (linear items first), the parsed-object cache
// with dirty tracking, and commit. Everything the TOC builder, the transform
// pipeline and the manifest writer consume goes through this class.

package dev.calibre.viewer.parser.epub

import dev.calibre.viewer.parser.dom.DomElement
import dev.calibre.viewer.parser.dom.DomParser
import dev.calibre.viewer.parser.dom.XmlWriter
import java.io.File

class BookContainer private constructor(
    val tdir: File,
    var opfName: String,
    private val parser: DomParser,
) {
    companion object {
        /** SimpleContainer(root, opf_path): scan the extracted tree. */
        fun open(tdir: File, opfName: String, parser: DomParser): BookContainer {
            val c = BookContainer(tdir, opfName, parser)
            c.scanFiles()
            c.refreshMimeMap()
            return c
        }
    }

    /** name -> file, POSIX-style relative names from the book root. */
    val namePathMap = LinkedHashMap<String, File>()

    /** name -> media type (guess_type + OPF manifest refresh). */
    val mimeMap = HashMap<String, String>()

    private val parsedCache = HashMap<String, DomElement>()
    private val dirtied = HashSet<String>()

    val opf: DomElement
        get() = parsed(opfName)

    val opfDocument: OpfDocument by lazy { OpfDocument(parsed(opfName)) }

    /** package/@version major component, 0 when missing/unparsable. Lazy so
     *  construction never needs the (not-yet-scanned) file map. */
    val opfMajorVersion: Int by lazy {
        opf.getAttr("version")?.substringBefore('.')?.toIntOrNull() ?: 0
    }

    // ---- file map ---------------------------------------------------------

    private fun scanFiles() {
        tdir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
            val rel = f.relativeTo(tdir).invariantSeparatorsPath
            namePathMap[rel] = f
            mimeMap[rel] = MimeTypes.guessType(f.path)
        }
        mimeMap[opfName] = MimeTypes.OPF
    }

    /** refresh_mime_map(): manifest media-types win (except for the OPF itself). */
    private fun refreshMimeMap() {
        for (item in manifestItems()) {
            val href = item.getAttr("href") ?: continue
            val mt = item.getAttr("media-type") ?: continue
            val name = hrefToName(href, opfName) ?: continue
            if (name in mimeMap && name != opfName && mt.isNotEmpty()) {
                // some epubs include the opf in the manifest with an incorrect mime type
                mimeMap[name] = mt
            }
        }
    }

    fun hrefToName(href: String, base: String?): String? = BookNames.hrefToName(href, base)

    fun nameToHref(name: String, base: String?): String = BookNames.nameToHref(name, base)

    fun hasName(name: String?): Boolean = name != null && name in namePathMap

    fun hasNameAndIsNotEmpty(name: String): Boolean {
        val p = namePathMap[name] ?: return false
        return p.length() > 0
    }

    fun rawData(name: String): ByteArray = namePathMap.getValue(name).readBytes()

    /**
     * Write [bytes] over the existing file and drop any cached parsed copy.
     * Used by cover/title-page generation that bypasses the DOM tree, and
     * by HtmlTransformer's html_as_json replacement pass.
     */
    fun replaceFile(name: String, bytes: ByteArray) {
        namePathMap.getValue(name).writeBytes(bytes)
        parsedCache.remove(name)
        dirtied.add(name)
    }

    // ---- manifest / spine views (strict OPF-namespace, like opf_xpath) ----

    /** //opf:manifest/opf:item[@href and @id] */
    private fun manifestItems(): List<DomElement> =
        opfDocument.manifestItems().filter { it.getAttr("href") != null && it.getAttr("id") != null }

    /**
     * id -> name (manifest_id_map). Values are nullable on purpose — upstream
     * assigns unconditionally, so an unresolvable href maps the id to None
     * (key present, value None) rather than omitting the key.
     */
    val manifestIdMap: Map<String, String?> by lazy {
        val m = HashMap<String, String?>()
        for (item in manifestItems()) {
            val id = item.getAttr("id") ?: continue
            val href = item.getAttr("href") ?: continue
            m[id] = hrefToName(href, opfName)
        }
        m
    }

    /**
     * media-type -> names in document order (manifest_type_map). Upstream
     * appends href_to_name() unconditionally — an unresolvable href appends
     * None, so consumers taking [0] see the None like upstream would.
     */
    val manifestTypeMap: Map<String, List<String?>> by lazy {
        val m = HashMap<String, MutableList<String?>>()
        for (item in opfDocument.manifestItems()) {
            val href = item.getAttr("href") ?: continue
            val mt = item.getAttr("media-type") ?: continue
            m.getOrPut(mt.lowercase()) { ArrayList() }.add(hrefToName(href, opfName))
        }
        m
    }

    /** guide/@type -> name (raw type casing preserved; callers lowercase the type for matching). */
    val guideTypeMap: Map<String, String> by lazy {
        val m = LinkedHashMap<String, String>()
        for (ref in opfDocument.guideReferences()) {
            val type = ref.getAttr("type") ?: continue
            val href = ref.getAttr("href") ?: continue
            val name = hrefToName(href, opfName) ?: continue
            m[type] = name
        }
        m
    }

    /**
     * manifest_items_with_property(): names of items carrying [property].
     * Nullable on purpose — upstream yields href_to_name() unconditionally
     * when an href exists, and an unresolvable href yields None (callers
     * like find_cover_image3 return the FIRST yield, bug-for-bug).
     */
    fun manifestItemsWithProperty(property: String): List<String?> =
        opfDocument.itemsWithProperty(property)
            .mapNotNull { it.getAttr("href") }
            .map { hrefToName(it, opfName) }

    /**
     * spine_names: (name, isLinear) for every itemref whose manifest target
     * exists on disk, linear items first (in OPF order), then non-linear.
     */
    val spineNames: List<Pair<String, Boolean>> by lazy {
        val ans = ArrayList<Pair<String, Boolean>>()
        val nonLinear = ArrayList<Pair<String, Boolean>>()
        for (item in opfDocument.spineItemrefs()) {
            val name = manifestIdMap[item.getAttr("idref")] ?: continue
            if (name !in namePathMap) continue
            val isLinear = (item.getAttr("linear") ?: "yes") == "yes"
            val pair = name to isLinear
            if (pair.second) ans.add(pair) else nonLinear.add(pair)
        }
        ans.addAll(nonLinear)
        ans
    }

    val spineItemNames: List<String> get() = spineNames.map { it.first }

    // ---- parsed-object cache ----------------------------------------------

    /**
     * parsed(): cached tree for HTML (OEB_DOCS via parseHtml) and XML (any
     * +xml//xml mime via parseXml). CSS is handled by the transform pipeline,
     * not here. Mutating the returned tree requires [dirty].
     */
    fun parsed(name: String): DomElement {
        parsedCache[name]?.let { return it }
        val mime = mimeMap[name] ?: MimeTypes.guessType(name)
        val bytes = namePathMap.getValue(name).readBytes()
        val ans = if (mime in MimeTypes.OEB_DOCS) parser.parseHtml(bytes) else parser.parseXml(bytes)
        parsedCache[name] = ans
        return ans
    }

    fun dirty(name: String) {
        dirtied.add(name)
    }

    /** commit_item(): serialize a dirtied tree back to its file. */
    fun commitItem(name: String, keepParsed: Boolean = false) {
        if (name !in dirtied) return
        val tree = parsedCache[name] ?: return
        namePathMap.getValue(name).writeBytes(XmlWriter.serializeToBytes(tree))
        dirtied.remove(name)
        if (!keepParsed) parsedCache.remove(name)
    }

    /** commit(): flush every dirtied item. */
    fun commit() {
        for (name in dirtied.toList()) commitItem(name)
    }
}
