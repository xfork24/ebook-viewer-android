// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// The coercion + normalization tail of parse_utils.parse_html() (everything
// after the parser cascade). Order matters and every step leaves fingerprints
// in the html_as_json output, so this mirrors upstream statement by statement:
//
//   force XHTML namespace (or rewrite OEB_DOC_NS documents)
//   -> ensure_namespace_prefixes({None: XHTML_NS})
//   -> merge multiple head/body
//   -> guarantee <head>/<title> and the charset <meta>
//   -> guarantee <body>
//   -> microsoft-com tags -> <span>
//   -> drop empty <a href>/<i>/<b>/<u>
//   -> <br> with content -> <div>
//   -> pretty-print whitespace in html/head

package dev.calibre.viewer.parser.dom

private const val TITLE_UNKNOWN = "Unknown"

internal fun coerceAndNormalize(root: DomElement): DomElement {
    val ns = namespaceOf(root.tag)

    var data = root
    if (ns.isEmpty()) {
        // "Force into the XHTML namespace": upstream re-serializes with an
        // xmlns attribute and re-parses, which (a) puts the root and every
        // prefix-less descendant into XHTML, (b) drops the xmlns attribute
        // from attrib. We do it structurally.
        forceXhtmlNamespace(data)
    } else if (ns != XHTML_NS) {
        // OEB_DOC_NS and friends: new XHTML root, same attributes, children
        // of the old namespace retagged.
        val nroot = DomElement("{$XHTML_NS}html")
        for ((k, v) in data.attrs) nroot.setAttr(k, v.value)
        for (elem in data.descendants().filterIsInstance<DomElement>()) {
            if (elem.namespace == ns) elem.tag = "{$XHTML_NS}${elem.localName}"
        }
        for (child in data.children.toList()) {
            data.remove(child)
            nroot.append(child)
        }
        data = nroot
    }
    // else: already XHTML — nothing to do (upstream: no branch taken)

    mergeMultipleHeadsAndBodies(data)
    ensureHeadAndTitle(data)
    ensureCharsetMeta(data)
    ensureBodyElement(data)
    retagMicrosoftMarkup(data)
    removeEmptyInlineElements(data)
    brWithContentBecomesDiv(data)
    formatWhitespace(data)
    return data
}

/**
 * Put [root] and every prefix-less element under it into the XHTML namespace.
 * xmlns/xmlns:* attributes disappear (they become namespace bindings, which
 * our Clark-notation tags already carry); namespaced foreign subtrees
 * (svg, mathml, xlink attributes) keep their namespaces.
 */
private fun forceXhtmlNamespace(root: DomElement) {
    // The xmlns attr is dropped from attrib on re-parse upstream, so remove
    // any that the strict path recorded (kxml hides them already; jsoup does not).
    root.removeAttr("xmlns")
    for (node in root.walk()) {
        if (node is DomElement) {
            if (node.namespace.isEmpty()) node.tag = "{$XHTML_NS}${node.localName}"
            node.removeAttr("xmlns")
            // xmlns:prefix declarations are bindings, not attributes
            val declKeys = node.attrs.keys.filter { it == "xmlns" || it.startsWith("xmlns:") }
            for (k in declKeys) node.removeAttr(k)
        }
    }
}

private fun mergeMultipleHeadsAndBodies(root: DomElement) {
    val heads = root.findElements(XHTML_NS, "head")
    val bodies = root.findElements(XHTML_NS, "body")
    if (heads.size <= 1 && bodies.size <= 1) return
    for (child in root.children.toList()) root.remove(child)
    val head = DomElement("{$XHTML_NS}head")
    val body = DomElement("{$XHTML_NS}body")
    for (h in heads) for (x in h.children.toList()) head.append(x)
    for (b in bodies) for (x in b.children.toList()) body.append(x)
    root.append(head)
    root.append(body)
}

private fun ensureHeadAndTitle(root: DomElement) {
    var head = root.directChild(XHTML_NS, "head")
    if (head == null) {
        head = DomElement("{$XHTML_NS}head")
        root.insert(0, head)
    }
    // Upstream XPath is a direct-child, case-sensitive /h:html/h:head/h:title
    // (parse_utils.py:298): a title nested inside e.g. <noscript> doesn't count.
    val title = head.elementChildren.firstOrNull {
        it.namespace == XHTML_NS && it.localName == "title"
    } ?: DomElement("{$XHTML_NS}title").also { head.append(it) }
    if (title.text.isNullOrBlank()) title.text = TITLE_UNKNOWN
}

/**
 * Remove encoding <meta>s, then append the utf-8 pragma meta (content =
 * second attr). Upstream META_XP is a direct-child, case-sensitive XPath:
 * /h:html/h:head/h:meta[@http-equiv="Content-Type"] (parse_utils.py:37).
 */
private fun ensureCharsetMeta(root: DomElement) {
    val head = root.directChild(XHTML_NS, "head") ?: return
    for (meta in head.elementChildren.toList()) {
        if (meta.namespace == XHTML_NS && meta.localName == "meta" &&
            meta.getAttr("http-equiv") == "Content-Type"
        ) {
            head.remove(meta)
        }
    }
    val meta = DomElement("{$XHTML_NS}meta")
    meta.setAttr("http-equiv", "Content-Type")
    meta.setAttr("content", "text/html; charset=utf-8")
    head.append(meta)
}

private fun parentOfIn(scope: DomElement, target: DomNode): DomElement? {
    for (node in scope.walk()) {
        if (node is DomElement) {
            for (c in node.children) if (c === target) return node
        }
    }
    return null
}

private fun ensureBodyElement(root: DomElement) {
    val direct = root.directChild(XHTML_NS, "body")
    if (direct != null) return
    val anyBody = root.findElements(XHTML_NS, "body").firstOrNull()
    if (anyBody != null) {
        val parent = parentOfIn(root, anyBody) ?: return
        parent.remove(anyBody)
        root.append(anyBody)
    } else {
        root.append(DomElement("{$XHTML_NS}body"))
    }
}

/** "Remove microsoft office markup": mso-prefixed elements become <span>. */
private fun retagMicrosoftMarkup(root: DomElement) {
    for (node in root.walk()) {
        if (node is DomElement && node.namespace.contains("microsoft-com")) {
            node.tag = "{$XHTML_NS}span"
        }
    }
}

/**
 * Empty <a href>/<i>/<b>/<u> (no id, no name, no children, no text) cause
 * rendering artifacts and are removed; tails are spliced back into the
 * previous sibling or the parent text.
 */
private fun removeEmptyInlineElements(root: DomElement) {
    val victims = ArrayList<DomElement>()
    for (node in root.walk()) {
        if (node !is DomElement || node.namespace != XHTML_NS) continue
        val relevant = (node.localName == "a" && node.getAttr("href") != null) ||
            node.localName == "i" || node.localName == "b" || node.localName == "u"
        if (!relevant) continue
        if (node.getAttr("id") == null && node.getAttr("name") == null &&
            node.children.isEmpty() && node.text.isNullOrEmpty()
        ) {
            victims.add(node)
        }
    }
    for (v in victims) {
        val parent = parentOfIn(root, v) ?: continue
        val tail = v.tail
        // lxml p.index(a) counts ALL children — comments included — so the
        // tail lands on whatever node (element or comment) precedes it.
        val idx = parent.children.indexOf(v)
        parent.remove(v)
        if (!tail.isNullOrEmpty()) {
            if (idx > 0) {
                val before = parent.children[idx - 1]
                before.tail = (before.tail ?: "") + tail
            } else {
                parent.text = (parent.text ?: "") + tail
            }
        }
    }
}

/** <br> with children or text becomes <div> (ADE compat, kept for parity). */
private fun brWithContentBecomesDiv(root: DomElement) {
    for (node in root.walk()) {
        if (node is DomElement && node.namespace == XHTML_NS && node.localName == "br") {
            if (node.children.isNotEmpty() || !node.text.isNullOrEmpty()) {
                node.tag = "{$XHTML_NS}div"
            }
        }
    }
}

/** The trailing whitespace formatting of parse_html — visible in "x"/"l" fields. */
private fun formatWhitespace(root: DomElement) {
    root.text = "\n  "
    val head = root.directChild(XHTML_NS, "head") ?: return
    head.text = "\n    "
    head.tail = "\n  "
    val kids = head.children
    for (i in 0 until kids.size) {
        kids[i].tail = if (i == kids.size - 1) "\n  " else "\n    "
    }
}
