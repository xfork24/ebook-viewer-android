// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Port of calibre/src/calibre/srv/render_book.py:120-160 anchor_map +
// toc_anchor_map + pagelist_anchor_map + check_for_maths. The values feed
// directly into the calibre-book-manifest.json files entry so the viewer's
// goto bar can pick a section by anchor, render the TOC entries with their
// fragments, and selectively load MathJax only when has_maths is set.

package dev.calibre.viewer.parser.transform

import dev.calibre.viewer.parser.dom.DomElement
import dev.calibre.viewer.parser.dom.XHTML_NS
import dev.calibre.viewer.parser.epub.Toc
import dev.calibre.viewer.parser.util.pythonCount

object AnchorMap {

    /**
     * `anchor_map(root)`: document-order list of every @id/@name on every
     * element of the tree INCLUDING [root] (upstream uses a
     * descendant-or-self axis query). For namespaced <a name=...> we promote
     * name -> id in place so the viewer's anchor picker reaches the same
     * target it would after a browser-side getElementById fallback.
     * Duplicates are dropped (first wins).
     */
    fun anchorMap(root: DomElement): List<String> {
        val ans = ArrayList<String>()
        val seen = HashSet<String>()
        for (elem in root.walk()) {
            if (elem !is DomElement) continue
            var id = elem.getAttr("id")
            // upstream: elem.tag.endswith('}a') — any namespace, but NOT a
            // namespace-less <a>.
            if (id.isNullOrEmpty() && elem.namespace.isNotEmpty() && elem.localName == "a") {
                id = elem.getAttr("name")
                if (!id.isNullOrEmpty()) elem.setAttr("id", id)
            }
            if (!id.isNullOrEmpty() && id !in seen) {
                ans.add(id)
                seen.add(id)
            }
        }
        return ans
    }

    /**
     * `toc_anchor_map(toc)`: Toc.toMap assigns ids post-order (children get
     * their id before their parent); we walk that emitted tree and bucket
     * {dest -> [{id, frag}]}. Entries whose dest is null are skipped; under
     * the same dest duplicate ids collapse (first wins).
     */
    fun tocAnchorMap(toc: Toc): Map<String, List<Map<String, Any?>>> {
        val ans = LinkedHashMap<String, MutableList<Map<String, Any?>>>()
        val seen = HashMap<String, HashSet<Int>>()
        walk(toc.toMap(pythonCount()), ans, seen)
        return ans
    }

    private fun walk(
        node: Map<String, Any?>,
        out: LinkedHashMap<String, MutableList<Map<String, Any?>>>,
        seen: HashMap<String, HashSet<Int>>,
    ) {
        val name = node["dest"] as? String
        val id = node["id"]
        if (name != null && id is Int) {
            val s = seen.getOrPut(name) { HashSet() }
            if (id !in s) {
                s.add(id)
                out.getOrPut(name) { ArrayList() }.add(linkedMapOf("id" to id, "frag" to node["frag"]))
            }
        }
        @Suppress("UNCHECKED_CAST")
        val children = node["children"] as? List<Map<String, Any?>> ?: return
        for (c in children) walk(c, out, seen)
    }

    /**
     * `pagelist_anchor_map(page_list)`: mutates each entry in [pageList] to
     * carry its 1-based `id` (upstream does this in place, so the manifest's
     * `page_list` serialization includes the ids too), and buckets deduped
     * {dest -> [{id, pagenum, frag}]} entries. Entries with a falsy dest are
     * skipped but still get their id assigned.
     */
    fun pageListAnchorMap(pageList: List<MutableMap<String, Any?>>): Map<String, List<Map<String, Any?>>> {
        val ans = LinkedHashMap<String, MutableList<Map<String, Any?>>>()
        val seen = HashMap<String, HashSet<String>>()
        for ((i, x) in pageList.withIndex()) {
            x["id"] = i + 1
            val name = x["dest"] as? String
            if (name.isNullOrEmpty()) continue
            val frag = (x["frag"] as? String) ?: ""
            val s = seen.getOrPut(name) { HashSet() }
            if (frag in s) continue
            s.add(frag)
            ans.getOrPut(name) { ArrayList() }.add(
                linkedMapOf("id" to x["id"], "pagenum" to x["pagenum"], "frag" to frag)
            )
        }
        return ans
    }

    /**
     * `check_for_maths(root)`: true iff the document contains a MathML <math>
     * anywhere, or has a <script type="text/x-mathjax-config"> block. The
     * viewer reads manifest.files.<name>.has_maths (and the book's
     * has_maths) to decide whether to load MathJax.
     */
    fun hasMaths(root: DomElement): Boolean {
        for (elem in root.descendants()) {
            if (elem !is DomElement) continue
            if (elem.localName == "math") return true
            if (elem.localName == "script" && elem.namespace == XHTML_NS) {
                if (elem.getAttr("type") == "text/x-mathjax-config") return true
            }
        }
        return false
    }
}
