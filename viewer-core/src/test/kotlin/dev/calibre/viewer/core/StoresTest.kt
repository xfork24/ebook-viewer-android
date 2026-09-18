// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Semantics tests for the two persistence stores: upstream set_session_data
// rules (dict form, '*' clear, null removes, write-through) and last-read
// round-tripping keyed by book hash.

package dev.calibre.viewer.core

import dev.calibre.viewer.core.lastread.LastReadStore
import dev.calibre.viewer.core.session.SessionStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class StoresTest {

    private fun freshDir(): File = createTempDir(prefix = "stores").also {
        Runtime.getRuntime().addShutdownHook(Thread { it.deleteRecursively() })
    }

    @Test
    fun `session data set remove and dict form`() {
        val dir = freshDir()
        val s = SessionStore(File(dir, "session"))
        s.setSessionData(JsonPrimitive("read_mode"), JsonPrimitive("flow"))
        s.setSessionData(JsonPrimitive("base_font_size"), JsonPrimitive(20))
        s.setSessionData(JsonPrimitive("read_mode"), JsonNull) // null removes
        val data = s.sessionDataJson.jsonObject
        assertFalse("read_mode" in data)
        assertEquals(20, data["base_font_size"]!!.jsonPrimitive.content.toInt())

        s.setSessionData(
            JsonObject(mapOf("a" to JsonPrimitive(1), "b" to JsonPrimitive("x"))),
            JsonNull,
        )
        assertTrue("a" in s.sessionDataJson.jsonObject)

        // '*' + null clears everything
        s.setSessionData(JsonPrimitive("*"), JsonNull)
        assertTrue(s.sessionDataJson.jsonObject.isEmpty())
    }

    @Test
    fun `session data persists across instances`() {
        val dir = freshDir()
        SessionStore(File(dir, "session")).apply {
            setSessionData(JsonPrimitive("read_mode"), JsonPrimitive("paged"))
            flush()
        }
        val reloaded = SessionStore(File(dir, "session"))
        assertEquals("paged", reloaded.sessionDataJson.jsonObject["read_mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun `corrupt session file starts fresh`() {
        val dir = freshDir()
        val sessionDir = File(dir, "session").apply { mkdirs() }
        File(sessionDir, "session_data.json").writeText("{not json")
        val s = SessionStore(sessionDir)
        assertTrue(s.sessionDataJson.jsonObject.isEmpty())
    }

    @Test
    fun `local storage sets clears and persists`() {
        val dir = freshDir()
        val s = SessionStore(File(dir, "session"))
        s.setLocalStorage(JsonPrimitive("recently_opened"), JsonPrimitive("[1,2]"))
        assertEquals("[1,2]", s.localStorageJson.jsonObject["recently_opened"]!!.jsonPrimitive.content)
        s.setLocalStorage(JsonPrimitive("*"), JsonNull)
        assertTrue(s.localStorageJson.jsonObject.isEmpty())
    }

    @Test
    fun `last read round trips by book hash`() {
        val dir = freshDir()
        val f = File(dir, "last-read.json")
        LastReadStore(f).update("hash1", "epubcfi(/6/4!)", 0.42, "/books/x.epub")
        val pos = LastReadStore(f).get("hash1")!!
        assertEquals("epubcfi(/6/4!)", pos.cfi)
        assertEquals(0.42, pos.posFrac, 1e-9)
        assertEquals("/books/x.epub", pos.pathtoebook)
        assertNull(LastReadStore(f).get("other"))
    }

    @Test
    fun `last read ignores empty cfi`() {
        val f = File(freshDir(), "last-read.json")
        val s = LastReadStore(f)
        s.update("h", "", 0.5)
        assertNull(s.get("h"))
    }

    @Test
    fun `last read tolerates corrupt file`() {
        val dir = freshDir()
        val f = File(dir, "last-read.json").apply { writeText("[broken") }
        assertNull(LastReadStore(f).get("any"))
    }
}
