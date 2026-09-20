// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors

package dev.calibre.viewer.core.annot

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AnnotationsStoreTest {

    private lateinit var dir: File
    private lateinit var store: AnnotationsStore

    @BeforeTest
    fun setUp() {
        dir = createTempDir(prefix = "annot-store")
        store = AnnotationsStore(dir)
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `empty store returns empty lists`() {
        assertEquals(emptyList(), store.loadBookmarks("anybook"))
        assertEquals(emptyList(), store.loadHighlights("anybook"))
    }

    @Test
    fun `bookmarks round-trip`() {
        val uri = "file:///sdcard/test.epub"
        val input = listOf(
            BookmarkRecord(timestamp = "2026-09-19T00:00:00Z", pos = "epubcfi(/6/4!)", title = "Chapter 1"),
            BookmarkRecord(timestamp = "2026-09-19T00:01:00Z", pos = "epubcfi(/6/8!)", title = "Chapter 2"),
        )
        store.save(uri, input, emptyList())
        store.flush()

        assertEquals(input, store.loadBookmarks(uri))
        assertEquals(emptyList(), store.loadHighlights(uri))
    }


    @Test
    fun `highlights round-trip with style and toc titles`() {
        val uri = "file:///sdcard/test.epub"
        val style = buildJsonObject {
            put("kind", "highlight")
            put("color", "#FFEB3B")
        }
        val input = listOf(
            HighlightRecord(
                timestamp = "2026-09-19T00:02:00Z",
                uuid = "uuid-1",
                highlightedText = "胡适的口述自传",
                startCfi = "epubcfi(/6/4[chap01])",
                endCfi = "epubcfi(/6/4[chap01]/re3)",
                style = style,
                spineName = "ch1.xhtml",
                spineIndex = 0,
                notes = "重要引用",
                tocFamilyTitles = listOf("第一章", "故乡和家庭"),
            ),
        )
        store.save(uri, emptyList(), input)
        store.flush()

        val loaded = store.loadHighlights(uri).single()
        assertEquals("uuid-1", loaded.uuid)
        assertEquals("胡适的口述自传", loaded.highlightedText)
        assertEquals("ch1.xhtml", loaded.spineName)
        assertEquals(0, loaded.spineIndex)
        assertEquals("重要引用", loaded.notes)
        assertEquals(listOf("第一章", "故乡和家庭"), loaded.tocFamilyTitles)
        assertEquals(style, loaded.style)
    }

    @Test
    fun `overwriting an existing book replaces both lists atomically`() {
        val uri = "file:///sdcard/test.epub"
        store.save(uri, listOf(BookmarkRecord(timestamp = "t1", pos = "p1", title = "Chapter 1")), emptyList())
        store.flush()
        // second save with different bookmarks & highlights replaces
        store.save(
            uri,
            listOf(BookmarkRecord(timestamp = "t2", pos = "p2", title = "Chapter 2")),
            listOf(
                HighlightRecord(timestamp = "t3", uuid = "uuid-2", highlightedText = "text", startCfi = "s", endCfi = "e", style = JsonObject(emptyMap()), spineName = "x", spineIndex = 1),
            ),
        )
        store.flush()
        assertEquals(1, store.loadBookmarks(uri).size)
        assertEquals("Chapter 2", store.loadBookmarks(uri).single().title)
        assertEquals(1, store.loadHighlights(uri).size)
    }

    @Test
    fun `corrupt file recovers to empty`() {
        val uri = "file:///sdcard/test.epub"
        store.save(uri, listOf(BookmarkRecord(timestamp = "t1", pos = "p1", title = "OK")), emptyList())
        store.flush()
        // Now stomp the file with garbage. A live instance keeps serving its
        // cache; corruption is discovered by a FRESH instance (next launch),
        // which must tolerate it rather than crash.
        File(dir, "${AnnotationsStore.sha256Of(uri)}.json").writeText("{ this is not json")
        assertEquals(emptyList(), AnnotationsStore(dir).loadBookmarks(uri))
    }

    @Test
    fun `corrupt file is quarantined so a later save cannot compound the loss`() {
        val uri = "file:///sdcard/test.epub"
        val fileName = "${AnnotationsStore.sha256Of(uri)}.json"
        File(dir, fileName).writeText("""{"bookmarks":[{"title":"precious"}""")
        val fresh = AnnotationsStore(dir)
        assertEquals(emptyList(), fresh.loadBookmarks(uri))
        // The damaged original survives as .corrupt...
        assertEquals(
            """{"bookmarks":[{"title":"precious"}""",
            File(dir, "$fileName.corrupt").readText(),
        )
        // ...and a read-modify-write save derived from the empty state writes
        // a NEW file rather than overwriting the quarantined data.
        fresh.save(uri, listOf(BookmarkRecord(timestamp = "t1", pos = "p1", title = "New")), emptyList())
        fresh.flush()
        assertEquals(
            """{"bookmarks":[{"title":"precious"}""",
            File(dir, "$fileName.corrupt").readText(),
        )
        assertEquals("New", AnnotationsStore(dir).loadBookmarks(uri).single().title)
    }

    @Test
    fun `save leaves no tmp file`() {
        val uri = "file:///sdcard/test.epub"
        store.save(uri, listOf(BookmarkRecord(timestamp = "t1", pos = "p1", title = "One")), emptyList())
        store.flush()
        assertEquals(
            setOf("${AnnotationsStore.sha256Of(uri)}.json"),
            dir.listFiles()!!.map { it.name }.toSet(),
        )
    }

    @Test
    fun `rekey with absent old file leaves the new key untouched`() {
        val old = "file:///sdcard/absent.epub"
        val new = "file:///sdcard/new.epub"
        // An existing new-key file must NOT be shadowed by an empty snapshot
        // seeded from a missing old-key file.
        store.save(new, listOf(BookmarkRecord(timestamp = "t1", pos = "p1", title = "Existing")), emptyList())
        store.flush()
        val fresh = AnnotationsStore(dir)
        fresh.rekey(old, new)
        assertEquals("Existing", fresh.loadBookmarks(new).single().title)
    }

    @Test
    fun `save then read-modify-write without flush loses nothing`() {
        val uri = "file:///sdcard/test.epub"
        // The controller's pattern: two annotations events in quick
        // succession, each a read-modify-write, while the first disk write
        // may still be queued. The second read MUST observe the first save
        // or the earlier change is silently dropped.
        store.save(uri, listOf(BookmarkRecord(timestamp = "t1", pos = "p1", title = "One")), emptyList())
        store.save(
            uri,
            store.loadBookmarks(uri) + BookmarkRecord(timestamp = "t2", pos = "p2", title = "Two"),
            store.loadHighlights(uri),
        )
        assertEquals(listOf("One", "Two"), store.loadBookmarks(uri).map { it.title })
        store.flush()
        assertEquals(listOf("One", "Two"), store.loadBookmarks(uri).map { it.title })
    }

    @Test
    fun `rekey moves cached data synchronously and the file eventually`() {
        val old = "file:///sdcard/old.epub"
        val new = "file:///sdcard/new.epub"
        store.save(old, listOf(BookmarkRecord(timestamp = "t1", pos = "p1", title = "One")), emptyList())
        store.rekey(old, new)
        // Visible under the new key immediately, before the file move lands.
        assertEquals("One", store.loadBookmarks(new).single().title)
        store.flush()
        // And durable: a fresh instance reads it back, old file is gone.
        assertEquals("One", AnnotationsStore(dir).loadBookmarks(new).single().title)
        assertFalse(File(dir, "${AnnotationsStore.sha256Of(old)}.json").exists())
    }

    @Test
    fun `different books are stored in separate files`() {
        val a = "file:///a.epub"
        val b = "file:///b.epub"
        store.save(a, listOf(BookmarkRecord(timestamp = "t", pos = "p", title = "BookA")), emptyList())
        store.save(b, listOf(BookmarkRecord(timestamp = "t", pos = "p", title = "BookB")), emptyList())
        store.flush()
        assertEquals("BookA", store.loadBookmarks(a).single().title)
        assertEquals("BookB", store.loadBookmarks(b).single().title)
        // Both files exist on disk
        assertTrue(File(dir, "${AnnotationsStore.sha256Of(a)}.json").isFile)
        assertTrue(File(dir, "${AnnotationsStore.sha256Of(b)}.json").isFile)
        assertFalse(File(dir, "${AnnotationsStore.sha256Of("nonexistent")}.json").exists())
    }
}
