// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors

package dev.calibre.viewer.core.profile

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ProfileStoreTest {

    private lateinit var file: File
    private lateinit var store: ProfileStore

    @BeforeTest
    fun setUp() {
        file = File(createTempDir(prefix = "profile-store"), "profiles.json")
        store = ProfileStore(file)
    }

    @AfterTest
    fun tearDown() {
        file.parentFile?.deleteRecursively()
    }

    @Test
    fun `empty store returns empty list`() {
        assertEquals(emptyMap(), store.list())
        assertNull(store.get("any"))
    }

    @Test
    fun `save then get then list`() {
        val daySettings = buildJsonObject {
            put("base_font_size", 24)
            put("read_mode", "paged")
        }
        val nightSettings = buildJsonObject {
            put("base_font_size", 16)
            put("read_mode", "scrolled")
            put("override_book_colors", "always")
        }
        store.save("day", daySettings)
        store.save("night", nightSettings)
        store.flush()

        assertEquals(daySettings, store.get("day"))
        assertEquals(nightSettings, store.get("night"))
        assertEquals(setOf("day", "night"), store.list().keys)
    }

    @Test
    fun `save with same name overwrites`() {
        store.save("p", buildJsonObject { put("base_font_size", 18) })
        store.save("p", buildJsonObject { put("base_font_size", 32) })
        store.flush()
        assertEquals(32, store.get("p")!!["base_font_size"]!!.toString().toInt())
        assertEquals(1, store.list().size)
    }

    @Test
    fun `delete removes the profile`() {
        store.save("a", buildJsonObject { put("k", 1) })
        store.save("b", buildJsonObject { put("k", 2) })
        store.delete("a")
        store.flush()
        assertNull(store.get("a"))
        assertEquals(setOf("b"), store.list().keys)
    }

    @Test
    fun `delete of unknown name is a no-op`() {
        store.save("a", buildJsonObject { put("k", 1) })
        store.delete("nonexistent")
        store.flush()
        assertEquals(setOf("a"), store.list().keys)
    }

    @Test
    fun `corrupt file recovers to empty`() {
        file.parentFile?.mkdirs()
        file.writeText("{ this is not json")
        val corrupt = ProfileStore(file)
        assertEquals(emptyMap(), corrupt.list())
        // The damaged original is quarantined, not silently discarded.
        assertEquals("{ this is not json", File(file.parentFile, "profiles.json.corrupt").readText())
    }

    @Test
    fun `concurrent saves keep every profile`() {
        // Two threads racing save() must not drop each other's entries via
        // lost read-modify-write on the cache snapshot.
        val threads = (0 until 8).map { i ->
            Thread { store.save("profile-$i", buildJsonObject { put("k", i) }) }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        store.flush()
        assertEquals((0 until 8).map { "profile-$it" }.toSet(), store.list().keys)
        assertEquals(8, ProfileStore(file).list().size)
    }
}
