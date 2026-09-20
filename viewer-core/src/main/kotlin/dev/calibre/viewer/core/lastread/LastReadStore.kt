// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Per-book last-read positions: {cfi, pos_frac} keyed by the render-cache
// book hash (sha1 of size+mtime+head/tail — stable for the same source
// file). Feeds the initial_position argument of start_book_load so a
// reopened book (or a process death mid-read) restores the exact spot.

package dev.calibre.viewer.core.lastread

import dev.calibre.viewer.core.internal.atomicWriteText
import dev.calibre.viewer.core.internal.quarantineCorrupt
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

class LastReadStore(file: File) {

    data class LastReadPosition(val cfi: String, val posFrac: Double, val pathtoebook: String?)

    private val storeFile = file
    private val json = Json { ignoreUnknownKeys = true }
    private var entries: MutableMap<String, JsonObject>

    init {
        entries = load()
    }

    @Synchronized fun get(bookHash: String): LastReadPosition? {
        val obj = entries[bookHash] ?: return null
        val cfi = obj["cfi"] as? JsonPrimitive ?: return null
        if (cfi is JsonNull) return null
        val frac = obj["pos_frac"]?.let { (it as? JsonPrimitive)?.doubleOrNull } ?: 0.0
        val path = (obj["pathtoebook"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        return LastReadPosition(cfi.content, frac, path)
    }

    @Synchronized fun update(bookHash: String, cfi: String, posFrac: Double, pathtoebook: String? = null) {
        if (cfi.isEmpty()) return
        entries[bookHash] = JsonObject(
            mapOf(
                "cfi" to JsonPrimitive(cfi),
                "pos_frac" to JsonPrimitive(posFrac),
                "pathtoebook" to (pathtoebook?.let { JsonPrimitive(it) } ?: JsonNull),
            )
        )
        persist()
    }

    /**
     * One-time migration (inbox mtime pinning): move a book's entry from the
     * old render hash to the new one. No-op when the old entry is absent.
     */
    @Synchronized fun rekey(oldBookHash: String, newBookHash: String) {
        val entry = entries.remove(oldBookHash) ?: return
        entries[newBookHash] = entry
        persist()
    }

    @Synchronized private fun load(): MutableMap<String, JsonObject> {
        if (!storeFile.isFile) return LinkedHashMap()
        val text = try {
            storeFile.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            quarantineCorrupt(storeFile)
            return LinkedHashMap()
        }
        if (text.isBlank()) {
            quarantineCorrupt(storeFile)
            return LinkedHashMap()
        }
        return try {
            val obj = json.parseToJsonElement(text).jsonObject
            obj.entries.filter { (_, v) -> v is JsonObject && entryIsValid(v) }
                .associate { it.key to it.value as JsonObject }.toMutableMap()
        } catch (e: Exception) {
            quarantineCorrupt(storeFile)
            LinkedHashMap()
        }
    }

    /**
     * A single malformed entry (schema drift, hand edit) must not crash
     * openBook: cfi must be a string primitive, pos_frac — when present —
     * a number. Malformed entries are dropped at load; valid ones survive.
     */
    private fun entryIsValid(v: JsonObject): Boolean {
        val cfi = v["cfi"] as? JsonPrimitive ?: return false
        if (cfi is JsonNull) return false
        val frac = v["pos_frac"] ?: return true
        if (frac !is JsonPrimitive || frac is JsonNull) return false
        return frac.doubleOrNull != null
    }

    @Synchronized private fun persist() {
        storeFile.parentFile?.mkdirs()
        atomicWriteText(storeFile, Json.encodeToString(JsonObject.serializer(), JsonObject(entries.toMap())))
    }
}
