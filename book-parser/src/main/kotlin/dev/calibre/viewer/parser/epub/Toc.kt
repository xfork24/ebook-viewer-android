// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Port of calibre/ebooks/oeb/polish/toc.py — the pieces render_book.py uses:
// the TOC tree with to_dict() (note the post-order node ids — children get
// their ids before their parent, faithfully mirroring the Python dict
// literal evaluation order), parse_ncx/parse_nav, get_toc/get_landmarks and
// the from_xpaths h1/h2/h3 fallback with its ensure_id/item_at_top logic.

package dev.calibre.viewer.parser.epub

import dev.calibre.viewer.parser.dom.DomElement
import dev.calibre.viewer.parser.dom.XHTML_NS

/** One TOC node; the root carries the book-level metadata. */
class Toc(title: String?, dest: String?, frag: String?) {
    var title: String? = title?.trim()
    var dest: String? = dest
    var frag: String? = frag

    var destExists: Boolean? = null
    var destError: String? = null

    // root-only book metadata (upstream class attributes set by the parsers)
    var tocTitle: String? = null
    var lang: String? = null
    var uid: String? = null
    var tocFileName: String? = null
    val pageList = ArrayList<Map<String, String?>>()

    var parent: Toc? = null
    val children = ArrayList<Toc>()

    fun add(title: String?, dest: String?, frag: String?): Toc {
        val c = Toc(title, dest, frag)
        children.add(c)
        c.parent = this
        return c
    }

    val lastChild: Toc? get() = children.lastOrNull()

    fun iterDescendants(): Sequence<Toc> = sequence {
        for (child in children) {
            yield(child)
            yieldAll(child.iterDescendants())
        }
    }

    /**
     * to_dict(counter): children serialize before this node claims its id —
     * the Python dict literal evaluates the children comprehension before
     * `ans['id'] = next(counter)` runs, so ids are assigned post-order.
     */
    fun toMap(nodeCounter: Iterator<Int>?): LinkedHashMap<String, Any?> {
        val ans = LinkedHashMap<String, Any?>()
        ans["title"] = title
        ans["dest"] = dest
        ans["frag"] = frag
        ans["children"] = children.map { it.toMap(nodeCounter) }
        if (destExists != null) ans["dest_exists"] = destExists
        if (destError != null) ans["dest_error"] = destError
        if (nodeCounter != null) ans["id"] = nodeCounter.next()
        return ans
    }

    /** to_dict() without a counter (the as_dict shape). */
    fun toMap(): Map<String, Any?> = toMap(null)
}

// ---- helpers ------------------------------------------------------------

private fun childXpath(parent: DomElement, name: String): List<DomElement> =
    parent.children.filterIsInstance<DomElement>().filter { it.localName.equals(name, ignoreCase = true) }

/** urlparse(href).fragment or None. */
private fun fragmentOf(href: String): String? {
    if ('#' !in href) return null
    val f = href.substringAfter('#')
    return f.ifEmpty { null }
}

/** etree.tostring(x, method='text', with_tail=False): subtree text, own tail excluded. */
private fun subtreeText(e: DomElement): String {
    val sb = StringBuilder()
    fun emit(el: DomElement) {
        el.text?.let { sb.append(it) }
        for (child in el.children) {
            if (child is DomElement) emit(child)
            child.tail?.let { sb.append(it) }
        }
    }
    emit(e)
    return sb.toString()
}

// ---- NCX ------------------------------------------------------------------

private fun addFromNavpoint(container: BookContainer, navpoint: DomElement, parent: Toc, ncxName: String): Toc {
    var dest: String? = null
    var frag: String? = null
    var text: String? = null
    val nl = childXpath(navpoint, "navlabel")
    if (nl.isNotEmpty()) {
        text = ""
        for (txt in childXpath(nl[0], "text")) {
            text += subtreeText(txt)
        }
    }
    val content = childXpath(navpoint, "content")
    if (content.isNotEmpty()) {
        val href = content[0].getAttr("src")
        if (href != null) {
            dest = container.hrefToName(href, ncxName)
            frag = fragmentOf(href)
        }
    }
    return parent.add(text?.ifEmpty { null }, dest, frag)
}

private fun processNcxNode(container: BookContainer, node: DomElement, tocParent: Toc, ncxName: String) {
    for (navpoint in childXpath(node, "navpoint")) {
        val child = addFromNavpoint(container, navpoint, tocParent, ncxName)
        processNcxNode(container, navpoint, child, ncxName)
    }
}

fun parseNcx(container: BookContainer, ncxName: String): Toc {
    val root = container.parsed(ncxName)
    val tocRoot = Toc(null, null, null)
    val navMaps = root.walk().filterIsInstance<DomElement>()
        .filter { it.localName.equals("navmap", ignoreCase = true) }.toList()
    if (navMaps.isNotEmpty()) {
        processNcxNode(container, navMaps[0], tocRoot, ncxName)
    }
    tocRoot.lang = null
    tocRoot.uid = null
    for ((attr, value) in root.attrs) {
        if (attr.substringAfter('}').endsWith("lang")) {
            tocRoot.lang = value.value
            break
        }
    }
    for (meta in root.walk().filterIsInstance<DomElement>().filter { it.localName.equals("meta", ignoreCase = true) }) {
        val uid = meta.getAttr("content")
        if (meta.getAttr("name") == "dtb:uid" && !uid.isNullOrEmpty()) {
            tocRoot.uid = uid
            break
        }
    }
    for (pl in root.walk().filterIsInstance<DomElement>().filter { it.localName.equals("pagelist", ignoreCase = true) }) {
        for (pt in pl.walk().filterIsInstance<DomElement>().filter { it.localName.equals("pagetarget", ignoreCase = true) }) {
            // upstream `if pagenum:` / `if href:` are TRUTHY checks — empty
            // strings skip the page-target entirely
            val pagenum = pt.getAttr("value")?.takeIf { it.isNotEmpty() } ?: continue
            val content = pt.walk().filterIsInstance<DomElement>()
                .firstOrNull { it.localName.equals("content", ignoreCase = true) }
            val href = content?.getAttr("src")?.takeIf { it.isNotEmpty() } ?: continue
            val dest = container.hrefToName(href, ncxName)
            val frag = fragmentOf(href)
            tocRoot.pageList.add(linkedMapOf("dest" to dest, "pagenum" to pagenum, "frag" to frag))
        }
    }
    return tocRoot
}

// ---- EPUB 3 nav -------------------------------------------------------------

private fun xhtmlChildren(e: DomElement, name: String): List<DomElement> =
    e.children.filterIsInstance<DomElement>().filter { it.localName == name && it.namespace == XHTML_NS }

private fun firstXhtmlChild(e: DomElement, name: String): DomElement? = xhtmlChildren(e, name).firstOrNull()

private fun addFromLi(container: BookContainer, li: DomElement, parent: Toc, navName: String): Toc {
    var dest: String? = null
    var frag: String? = null
    var text: String? = null
    for (x in li.children.filterIsInstance<DomElement>()) {
        if (!(x.namespace == XHTML_NS && (x.localName == "a" || x.localName == "span"))) continue
        text = subtreeText(x).trim().ifEmpty {
            x.walk().filterIsInstance<DomElement>()
                .mapNotNull { it.getAttr("title") }.joinToString(" ").trim()
        }
        val href = x.getAttr("href")
        if (href != null) {
            dest = if (href.startsWith("#")) navName else container.hrefToName(href, navName)
            frag = fragmentOf(href)
        }
        break
    }
    return parent.add(text?.ifEmpty { null }, dest, frag)
}

private fun processNavNode(container: BookContainer, node: DomElement, tocParent: Toc, navName: String) {
    for (li in xhtmlChildren(node, "li")) {
        val child = addFromLi(container, li, tocParent, navName)
        val ol = firstXhtmlChild(li, "ol")
        if (ol != null) {
            processNavNode(container, ol, child, navName)
        }
    }
}

private val EPUB_TYPE = "{$EPUB_NS}type"
private val HEADING_LOCALS = setOf("h1", "h2", "h3", "h4", "h5", "h6")

fun parseNav(container: BookContainer, navName: String): Toc {
    val root = container.parsed(navName)
    val tocRoot = Toc(null, null, null)
    tocRoot.lang = null
    tocRoot.uid = null
    var seenToc = false
    var seenPagelist = false
    for (nav in root.walk().filterIsInstance<DomElement>().filter { it.localName == "nav" && it.namespace == XHTML_NS }) {
        if (nav.getAttr(EPUB_TYPE) == null) continue
        val nt = nav.getAttr(EPUB_TYPE)
        if (nt == "toc" && !seenToc) {
            val ol = firstXhtmlChild(nav, "ol")
            if (ol != null) {
                seenToc = true
                processNavNode(container, ol, tocRoot, navName)
                // the first h1-h6 direct child supplies the toc title
                // (iterchildren over all six tags at once, document order)
                for (h in nav.children.filterIsInstance<DomElement>()) {
                    if (h.namespace != XHTML_NS || h.localName !in HEADING_LOCALS) continue
                    val text = subtreeText(h).ifEmpty { h.getAttr("title") }
                    if (!text.isNullOrEmpty()) {
                        tocRoot.tocTitle = text
                        break
                    }
                }
            }
        } else if (nt == "page-list" && !seenPagelist) {
            val ol = firstXhtmlChild(nav, "ol")
            if (ol != null) {
                seenPagelist = true
                for (li in xhtmlChildren(ol, "li")) {
                    for (a in xhtmlChildren(li, "a")) {
                        val href = a.getAttr("href") ?: continue
                        val text = subtreeText(a).ifEmpty { a.getAttr("title") }?.trim()
                        if (!text.isNullOrEmpty()) {
                            val dest = if (href.startsWith("#")) navName else container.hrefToName(href, navName)
                            val frag = fragmentOf(href)
                            tocRoot.pageList.add(linkedMapOf("dest" to dest, "pagenum" to text, "frag" to frag))
                        }
                    }
                }
            }
        }
    }
    return tocRoot
}

// ---- get_toc / landmarks ----------------------------------------------------

private fun findExistingNcxToc(container: BookContainer): String? {
    val tocAttrs = container.opfDocument.spineElements().mapNotNull { it.getAttr("toc") }
    var name: String? = null
    if (tocAttrs.isNotEmpty()) {
        name = container.manifestIdMap[tocAttrs[0]]
    }
    if (name == null) {
        name = container.manifestTypeMap[MimeTypes.NCX]?.firstOrNull()
    }
    return name
}

private fun findExistingNavToc(container: BookContainer): String? =
    container.manifestItemsWithProperty("nav").firstOrNull()

private fun emptyToc(): Toc = Toc(null, null, null)

private fun getXtoc(
    container: BookContainer,
    findToc: (BookContainer) -> String?,
    parseToc: (BookContainer, String) -> Toc,
): Toc {
    val toc = findToc(container)
    val ans = if (toc == null || !container.hasName(toc)) emptyToc() else parseToc(container, toc)
    ans.tocFileName = if (toc != null && container.hasName(toc)) toc else null
    return ans
}

/** get_toc(verify_destinations=False): nav for EPUB 3 (NCX when nav is empty), NCX for EPUB 2. */
fun getToc(container: BookContainer): Toc {
    return if (container.opfMajorVersion < 3) {
        getXtoc(container, ::findExistingNcxToc, ::parseNcx)
    } else {
        val ans = getXtoc(container, ::findExistingNavToc, ::parseNav)
        if (ans.children.isEmpty()) {
            getXtoc(container, ::findExistingNcxToc, ::parseNcx)
        } else {
            ans
        }
    }
}

private fun splitFragment(href: String): Pair<String, String> =
    if ('#' in href) href.substringBefore('#') to href.substringAfter('#')
    else href to ""

private fun getGuideLandmarks(container: BookContainer): List<Map<String, String>> {
    val ans = ArrayList<Map<String, String>>()
    for (ref in container.opfDocument.guideReferences()) {
        val href = ref.getAttr("href") ?: continue
        val (before, frag) = splitFragment(href)
        val name = container.hrefToName(before, container.opfName)
        if (name != null && container.hasName(name)) {
            ans.add(
                linkedMapOf(
                    "dest" to name,
                    "frag" to frag,
                    "title" to (ref.getAttr("title") ?: ""),
                    "type" to (ref.getAttr("type") ?: ""),
                )
            )
        }
    }
    return ans
}

private fun getNavLandmarks(container: BookContainer): List<Map<String, String>> {
    val ans = ArrayList<Map<String, String>>()
    val nav = findExistingNavToc(container) ?: return ans
    if (!container.hasName(nav)) return ans
    val root = container.parsed(nav)
    for (elem in root.walk().filterIsInstance<DomElement>().filter { it.localName == "nav" && it.namespace == XHTML_NS }) {
        if (elem.getAttr(EPUB_TYPE) != "landmarks") continue
        for (li in elem.walk().filterIsInstance<DomElement>().filter { it.localName == "li" && it.namespace == XHTML_NS }) {
            for (a in li.walk().filterIsInstance<DomElement>().filter { it.localName == "a" && it.namespace == XHTML_NS }) {
                // upstream breaks out of the a-loop only when href is TRUTHY;
                // an empty href keeps scanning the next a
                val href = a.getAttr("href")?.takeIf { it.isNotEmpty() } ?: continue
                val (before, frag) = splitFragment(href)
                val name = container.hrefToName(before, nav)
                if (name != null && container.hasName(name)) {
                    ans.add(
                        linkedMapOf(
                            "dest" to name,
                            "frag" to frag,
                            "title" to subtreeText(a).trim(),
                            "type" to (a.getAttr(EPUB_TYPE) ?: ""),
                        )
                    )
                }
                break
            }
        }
    }
    return ans
}

fun getLandmarks(container: BookContainer): List<Map<String, String>> {
    if (container.opfMajorVersion < 3) return getGuideLandmarks(container)
    val ans = getNavLandmarks(container)
    return if (ans.isEmpty()) getGuideLandmarks(container) else ans
}

// ---- from_xpaths -------------------------------------------------------------

private fun elemToTocText(elem: DomElement): String {
    var text = subtreeText(elem).trim()
    if (text.isEmpty()) text = elem.getAttr("title") ?: ""
    if (text.isEmpty()) text = elem.getAttr("alt") ?: ""
    text = Regex("\\s+").replace(text.trim(), " ")
    text = text.take(1000).trim()
    return text.ifEmpty { "(Untitled)" }
}

/** ensure_id(): give [elem] an id if it lacks one; returns (dirtied, id). */
private fun ensureId(elem: DomElement, allIds: HashSet<String>): Pair<Boolean, String> {
    // upstream `if elem_id:` / `if anchor:` — empty strings are treated as absent
    val elemId = elem.getAttr("id")
    if (!elemId.isNullOrEmpty()) return false to elemId
    if (elem.namespace == XHTML_NS && elem.localName == "a") {
        val anchor = elem.getAttr("name")
        if (!anchor.isNullOrEmpty()) {
            elem.setAttr("id", anchor)
            return false to anchor
        }
    }
    var c = 0
    while (true) {
        c += 1
        val q = "toc_$c"
        if (q !in allIds) {
            elem.setAttr("id", q)
            allIds.add(q)
            break
        }
    }
    return true to (elem.getAttr("id") ?: "")
}

/** Ancestor chain of [elem] up to (excluding) [root], as an identity set. */
private fun ancestorsOf(root: DomElement, elem: DomElement): Set<DomElement> {
    val ans = HashSet<DomElement>()
    var node = root.parentOf(elem)
    while (node != null && node !== root) {
        ans.add(node)
        node = root.parentOf(node)
    }
    return ans
}

/**
 * item_at_top(): True when no image element and no non-whitespace text occurs
 * before [elem] in body document order (tails of non-ancestors included) —
 * i.e. the heading is visible at the top of its page.
 */
private fun itemAtTop(root: DomElement, body: DomElement, elem: DomElement): Boolean {
    val ancestors = ancestorsOf(root, elem)
    // body.iterdescendants() EXCLUDES body itself, so body's own leading text
    // is never examined
    for (el in body.descendants().filterIsInstance<DomElement>()) {
        if (el === elem) return true
        if (el.tag.endsWith("}img")) return false
        val t = el.text
        if (t != null && t.trim().isNotEmpty()) return false
        if (el !in ancestors) {
            val tail = el.tail
            if (tail != null && tail.trim().isNotEmpty()) return false
        }
    }
    return true
}

/**
 * from_xpaths(): build a TOC from per-level element queries over the spine.
 * Levels with no matches anywhere are dropped and the rest renumbered;
 * parents follow the last-child chain; headings not at the top of the page
 * get a generated id so the viewer can jump to them (this dirties the file).
 */
fun fromXpaths(container: BookContainer, xpaths: List<String>): Toc {
    val tocRoot = Toc(null, null, null)
    val levelNames = xpaths.map { it.substringAfter(':') } // '//h:h1' -> 'h1'

    // per-spine-item level -> matched elements
    val maps = LinkedHashMap<String, Map<Int, List<DomElement>>>()
    val emptyLevels = HashSet<Int>()
    for (i in levelNames.indices) emptyLevels.add(i + 1)
    for (name in container.spineItemNames) {
        val root = container.parsed(name)
        val levelMap = HashMap<Int, List<DomElement>>()
        for ((i, lname) in levelNames.withIndex()) {
            val elems = root.findElements(XHTML_NS, lname)
            if (elems.isNotEmpty()) {
                levelMap[i + 1] = elems
                emptyLevels.remove(i + 1)
            }
        }
        maps[name] = levelMap
    }
    // drop empty levels everywhere, renumber the survivors contiguously
    val renumbered = LinkedHashMap<String, Map<Int, List<DomElement>>>()
    for ((name, lmap) in maps) {
        val kept = lmap.entries.filter { it.key !in emptyLevels }.sortedBy { it.key }
        renumbered[name] = kept.mapIndexed { i, (_, elems) -> (i + 1) to elems }.toMap()
    }

    val nodeLevelMap = HashMap<Toc, Int>(64)
    nodeLevelMap[tocRoot] = 0

    fun parentForLevel(childLevel: Int): Toc {
        var node = tocRoot
        val limit = childLevel - 1
        while (true) {
            val child = node.lastChild ?: return node
            val lvl = nodeLevelMap[child] ?: return node
            if (lvl > limit) return node
            if (lvl == limit) return child
            node = child
        }
    }

    for ((name, levelItemMap) in renumbered) {
        val root = container.parsed(name)
        val itemLevelMap = HashMap<DomElement, Int>()
        for ((lvl, elems) in levelItemMap) {
            for (e in elems) itemLevelMap[e] = lvl
        }
        var itemDirtied = false
        val allIds = HashSet<String>()
        for (e in root.walk().filterIsInstance<DomElement>()) {
            e.getAttr("id")?.let { allIds.add(it) }
        }
        val body = root.findElements(XHTML_NS, "body").firstOrNull()

        for (item in root.walk().filterIsInstance<DomElement>()) {
            val lvl = itemLevelMap[item] ?: continue
            val text = elemToTocText(item)
            val parent = parentForLevel(lvl)
            val elemId: String?
            if (body != null && itemAtTop(root, body, item)) {
                elemId = null
            } else {
                val (dirtied, id) = ensureId(item, allIds)
                itemDirtied = itemDirtied || dirtied
                elemId = id
            }
            val toc = parent.add(text, name, elemId)
            nodeLevelMap[toc] = lvl
            toc.destExists = true
        }

        if (itemDirtied) {
            container.dirty(name)
            container.commitItem(name, keepParsed = true)
        }
    }
    return tocRoot
}
