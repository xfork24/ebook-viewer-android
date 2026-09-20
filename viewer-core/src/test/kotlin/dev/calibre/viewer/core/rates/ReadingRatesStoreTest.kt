// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// ReadingRatesStore semantics: the page's update_reading_rates payload must
// round-trip verbatim (Timers.start_book copies it back in-memory), reset
// drops the book, rekey survives the inbox mtime-pinning migration, and a
// corrupt store degrades to empty rather than crashing the reader.

package dev.calibre.viewer.core.rates

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class ReadingRatesStoreTest {

    private fun freshDir(): File = createTempDir(prefix = "rates").also {
        Runtime.getRuntime().addShutdownHook(Thread { it.deleteRecursively() })
    }

    private fun payload() = Json.parseToJsonElement(
        """{"rates":[173.4,120.0],"chapter_times":{"ch1.html":412.7}}""",
    ).jsonObject

    @Test
    fun `rates round trip verbatim by book hash`() {
        val f = File(freshDir(), "reading-rates.json")
        ReadingRatesStore(f).apply {
            update("hash1", payload())
            flush()
        }
        assertEquals(payload(), ReadingRatesStore(f).get("hash1"))
        assertNull(ReadingRatesStore(f).get("other"))
    }

    @Test
    fun `reset drops the book and persists`() {
        val f = File(freshDir(), "reading-rates.json")
        val s = ReadingRatesStore(f)
        s.update("hash1", payload())
        s.reset("hash1")
        s.flush()
        assertNull(ReadingRatesStore(f).get("hash1"))
    }

    @Test
    fun `rekey moves the payload to the new hash`() {
        val f = File(freshDir(), "reading-rates.json")
        val s = ReadingRatesStore(f)
        s.update("old", payload())
        s.rekey("old", "new")
        s.flush()
        assertNull(ReadingRatesStore(f).get("old"))
        assertEquals(payload(), ReadingRatesStore(f).get("new"))
        // absent old hash: no-op, no crash
        s.rekey("missing", "x")
    }

    @Test
    fun `corrupt store starts fresh`() {
        val dir = freshDir()
        val f = File(dir, "reading-rates.json").apply { writeText("{not json") }
        assertNull(ReadingRatesStore(f).get("any"))
        // Load must not silently wipe user data: the damaged file is
        // quarantined (renamed, content preserved) instead of being left to
        // be overwritten by a later save.
        val q = File(dir, "reading-rates.json.corrupt")
        assertTrue(q.isFile)
        assertEquals("{not json", q.readText())
    }
}
