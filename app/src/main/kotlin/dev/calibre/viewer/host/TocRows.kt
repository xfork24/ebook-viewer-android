// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// gui2/viewer/toc.py, the Android half. In standalone mode the toolbar's
// Table of Contents button delegates to the HOST (Overlay.show_toc →
// ui_operations.toggle_toc → to_python.toggle_toc) — the Qt viewer answers
// with its native toc_dock fed from the rendered book's manifest. This is
// the model behind the Android panel: the flat depth-first row list, the
// "currently viewed" set for update_current_toc_nodes (leaf ids plus their
// ancestors), and TOCItem's title cleanup rules.

package dev.calibre.viewer.host

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

data class TocRow(
    val id: Long,
    val title: String,
    val depth: Int,
    val parentId: Long?,
)

object TocRows {

    /** manifest['toc'] → flat rows, depth-first, exactly how TOCItem builds the Qt model. */
    fun fromManifest(toc: JsonObject?): List<TocRow> {
        val roots = (toc?.get("children") as? JsonArray)?.filterIsInstance<JsonObject>() ?: return emptyList()
        val out = ArrayList<TocRow>()
        fun walk(node: JsonObject, depth: Int, parentId: Long?) {
            val id = (node["id"] as? JsonPrimitive)?.longOrNull ?: return
            out.add(TocRow(id, cleanTitle(node), depth, parentId))
            val children = (node["children"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: return
            for (child in children) walk(child, depth + 1, id)
        }
        for (root in roots) walk(root, 0, null)
        return out
    }

    /**
     * update_current_toc_nodes: the page reports the LAST leaf id of every
     * visible TOC family; those leaves and all their ancestors are being
     * viewed (TOC.update_current_toc_nodes).
     */
    fun viewedWithAncestors(rows: List<TocRow>, leafIds: Collection<Long>): Set<Long> {
        if (leafIds.isEmpty() || rows.isEmpty()) return emptySet()
        val byId = HashMap<Long, TocRow>(rows.size * 2)
        for (row in rows) byId[row.id] = row
        val viewed = HashSet<Long>()
        for (leaf in leafIds) {
            var node = byId[leaf] ?: continue
            while (viewed.add(node.id)) {
                node = node.parentId?.let { byId[it] } ?: break
            }
        }
        return viewed
    }

    /** TOCItem: '--pipe-worker' → Untitled; every whitespace char becomes a space. */
    private fun cleanTitle(node: JsonObject): String {
        var text = (node["title"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()
        if (text == PIPE_WORKER) text = UNTITLED
        val sb = StringBuilder(text.length)
        for (ch in text) sb.append(if (Character.isWhitespace(ch)) ' ' else ch)
        return sb.toString()
    }

    private const val PIPE_WORKER = "--pipe-worker"
    private const val UNTITLED = "Untitled"
}
