// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// The TOC panel model must mirror gui2/viewer/toc.py: manifest['toc']
// walked depth-first like TOCItem, leaf-ids-plus-ancestors for
// update_current_toc_nodes, and the '--pipe-worker' / whitespace title
// rules.

package dev.calibre.viewer.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class TocRowsTest {

    private fun toc(vararg json: String) =
        Json.parseToJsonElement("""{"children":[${json.joinToString()}]}""").jsonObject

    @Test
    fun `manifest toc flattens depth first with parent links`() {
        // ids are post-order (children before parents), as toMap assigns
        // them; the row list itself is document order, like Qt's all_items.
        val rows = TocRows.fromManifest(
            toc(
                """{"title":"第一章","dest":"ch1","frag":null,"children":[],"id":0}""",
                """{"title":"第二章","dest":"ch2","frag":null,"children":[
                    {"title":"第二节","dest":"ch2","frag":"s1","children":[],"id":1}
                ],"id":2}""",
            ),
        )
        assertEquals(
            listOf(
                TocRow(0, "第一章", 0, null),
                TocRow(2, "第二章", 0, null),
                TocRow(1, "第二节", 1, 2),
            ),
            rows,
        )
    }

    @Test
    fun `viewed set is leaves plus ancestors`() {
        val rows = TocRows.fromManifest(
            toc(
                """{"title":"A","dest":"a","children":[],"id":0}""",
                """{"title":"P","dest":"p","children":[
                    {"title":"C","dest":"c","children":[],"id":1}
                ],"id":2}""",
                """{"title":"B","dest":"b","children":[],"id":3}""",
            ),
        )
        assertEquals(setOf(1L, 2L), TocRows.viewedWithAncestors(rows, listOf(1)))
        assertEquals(setOf(0L), TocRows.viewedWithAncestors(rows, listOf(0)))
        // several families at once, unknown ids ignored
        assertEquals(setOf(0L, 1L, 2L, 3L), TocRows.viewedWithAncestors(rows, listOf(0, 1, 99, 3)))
        assertTrue(TocRows.viewedWithAncestors(rows, emptyList()).isEmpty())
        assertTrue(TocRows.viewedWithAncestors(emptyList(), listOf(1)).isEmpty())
    }

    @Test
    fun `titles follow the TOCItem cleanup rules`() {
        val rows = TocRows.fromManifest(
            toc(
                """{"title":"--pipe-worker","children":[],"id":0}""",
                """{"title":"多  空\t白\n字符","children":[],"id":1}""",
                """{"title":null,"children":[],"id":2}""",
            ),
        )
        assertEquals("Untitled", rows[0].title)
        assertEquals("多  空 白 字符", rows[1].title)
        assertEquals("", rows[2].title)
    }

    @Test
    fun `malformed toc degrades to empty`() {
        assertEquals(emptyList(), TocRows.fromManifest(null))
        assertEquals(emptyList(), TocRows.fromManifest(Json.parseToJsonElement("""{}""").jsonObject))
        assertEquals(emptyList(), TocRows.fromManifest(Json.parseToJsonElement("""{"children":[42,"x"]}""").jsonObject))
        // a node without an id is skipped, its id-less siblings too
        val rows = TocRows.fromManifest(
            toc("""{"title":"no id","children":[]}""", """{"title":"ok","children":[],"id":7}"""),
        )
        assertEquals(listOf(TocRow(7, "ok", 0, null)), rows)
    }
}
