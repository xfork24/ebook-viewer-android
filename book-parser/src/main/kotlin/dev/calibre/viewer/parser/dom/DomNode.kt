// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// DOM model mirroring the lxml trees calibre's render_book pipeline operates on.
// Text lives on elements (lxml semantics: .text/.tail), there are no standalone
// text nodes, which is exactly what html_as_json's {"x","l"} fields encode.
//
// Deliberately mutable: the transform pipeline (img data-calibre-src, link
// virtualization, inline CSS rewrite) mutates in place exactly like lxml does
// upstream. Trees never escape the parser module boundary — the public API is
// bytes-in/bytes-out — so the mutation is contained to a single-threaded
// pipeline on low-power e-ink CPUs, where copy-on-write trees would only burn
// memory bandwidth.

package dev.calibre.viewer.parser.dom

const val XHTML_NS = "http://www.w3.org/1999/xhtml"
const val SVG_NS = "http://www.w3.org/2000/svg"
const val MATHML_NS = "http://www.w3.org/1998/Math/MathML"
const val XLINK_NS = "http://www.w3.org/1999/xlink"
const val XML_NAMESPACE = "http://www.w3.org/XML/1998/namespace"
const val OEB_DOC_NS = "http://openebook.org/namespaces/oeb-document/1.0"

sealed class DomNode {
    /** lxml .text — text between this node's start tag and its first child. null = absent. */
    var text: String? = null

    /** lxml .tail — text between this node's end tag and the next sibling/parent's end tag. */
    var tail: String? = null
}

/** Comments and processing instructions (lxml: tag is a callable factory). */
class DomComment(val isProcessingInstruction: Boolean = false) : DomNode()

class DomAttr(val name: String, val value: String) {
    /** Clark-notation namespace of the attribute, "" when un-namespaced. */
    val namespace: String
    val localName: String

    init {
        val i = name.lastIndexOf('}')
        if (name.startsWith("{") && i > 0) {
            namespace = name.substring(1, i)
            localName = name.substring(i + 1)
        } else {
            namespace = ""
            localName = name
        }
    }
}

class DomElement(tag: String) : DomNode() {
    /**
     * Clark notation "{ns}local" or bare "local", identical to lxml's .tag.
     * Setting a bare name puts the element in no namespace.
     */
    var tag: String = tag
        set(value) {
            field = value
            cachedNs = null
            cachedLocal = null
        }

    /** Document-ordered attributes (lxml attrib preserves order). */
    val attrs = LinkedHashMap<String, DomAttr>()

    val children = mutableListOf<DomNode>()

    private var cachedNs: String? = null
    private var cachedLocal: String? = null

    val namespace: String
        get() {
            if (cachedNs == null) splitTag()
            return cachedNs ?: ""
        }

    val localName: String
        get() {
            if (cachedLocal == null) splitTag()
            return cachedLocal ?: tag
        }

    private fun splitTag() {
        val t = tag
        if (t.startsWith("{")) {
            val i = t.lastIndexOf('}')
            if (i > 0) {
                cachedNs = t.substring(1, i)
                cachedLocal = t.substring(i + 1)
                return
            }
        }
        cachedNs = ""
        cachedLocal = t
    }

    fun getAttr(name: String): String? = attrs[name]?.value

    fun setAttr(name: String, value: String) {
        attrs[name] = DomAttr(name, value)
    }

    fun removeAttr(name: String) {
        attrs.remove(name)
    }

    fun clearAttrs() = attrs.clear()

    fun append(child: DomNode) {
        children.add(child)
    }

    fun insert(index: Int, child: DomNode) {
        children.add(index.coerceIn(0, children.size), child)
    }

    fun remove(child: DomNode): Boolean = children.remove(child)

    /** Index of [child] among element children (lxml index() counts elements only). */
    fun indexOf(child: DomNode): Int {
        var i = 0
        for (c in children) {
            if (c === child) return i
            if (c is DomElement) i++
        }
        return -1
    }

    val elementChildren: List<DomElement>
        get() = children.filterIsInstance<DomElement>()

    fun parentOf(target: DomNode): DomElement? = iterator().asSequence()
        .mapNotNull { (parent, node) -> if (node === target) parent else null }
        .firstOrNull()

    /** Depth-first pre-order over the whole subtree (root included). */
    fun walk(): Sequence<DomNode> = sequence {
        val stack = ArrayDeque<DomNode>()
        stack.addLast(this@DomElement)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            yield(n)
            if (n is DomElement) for (i in n.children.indices.reversed()) stack.addLast(n.children[i])
        }
    }

    /** Pre-order (parent, child) pairs for the subtree; root's parent is null. */
    operator fun iterator(): Iterator<Pair<DomElement?, DomNode>> = ParentChildIterator(this)

    fun descendants(): Sequence<DomNode> = walk().drop(1)

    /** //ns:name descendant-or-self element query with lxml doc order. */
    fun findElements(ns: String, localName: String): List<DomElement> =
        walk().filterIsInstance<DomElement>().filter { it.localName == localName && it.namespace == ns }.toList()

    /** //h:name where h = XHTML namespace, or un-namespaced fallback per calibre's barename(). */
    fun findHtmlElements(localName: String): List<DomElement> {
        val a = findElements(XHTML_NS, localName)
        if (a.isNotEmpty()) return a
        return findElements("", localName)
    }

    /** /h:html/h:head or /h:html/h:body — direct element children only. */
    fun directChild(ns: String, localName: String): DomElement? =
        children.filterIsInstance<DomElement>().firstOrNull { it.localName == localName && it.namespace == ns }
}

private class ParentChildIterator(root: DomElement) : Iterator<Pair<DomElement?, DomNode>> {
    // Stack of (parent, node) pending in reverse document order.
    private val stack = ArrayDeque<Pair<DomElement?, DomNode>>()

    init {
        stack.addLast(null to root)
    }

    override fun hasNext() = stack.isNotEmpty()

    override fun next(): Pair<DomElement?, DomNode> {
        val (parent, node) = stack.removeLast()
        if (node is DomElement) {
            for (i in node.children.indices.reversed()) {
                stack.addLast(node to node.children[i])
            }
        }
        return parent to node
    }
}

/** lxml barename(): local part of a Clark-notation tag. */
fun barename(tag: String): String = tag.substringAfter('}', tag)

/** lxml namespace(): namespace URI of a Clark-notation tag, "" if none. */
fun namespaceOf(tag: String): String =
    if (tag.startsWith("{")) tag.substringBefore('}', "").removePrefix("{") else ""
