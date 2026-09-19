// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Per-book last-read positions: {cfi, pos_frac} keyed by the render-cache
// book hash (sha1 of size+mtime+head/tail — stable for the same source
// file). Feeds the initial_position argument of start_book_load so a
// reopened book (or a process death mid-read) restores the exact spot.

package dev.calibre.viewer.core.lastread

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
        val cfi = obj["cfi"]?.jsonPrimitive?.content ?: return null
        val frac = obj["pos_frac"]?.jsonPrimitive?.double ?: 0.0
        val path = obj["pathtoebook"]?.jsonPrimitive?.content
        return LastReadPosition(cfi, frac, path)
    }

    @Synchronized fun update(bookHash: String, cfi: String, posFrac: Double, pathtoebook: String? = null) {
        if (cfi.isEmpty()) return
        entries[bookHash] = JsonObject(
            mapOf(
                "cfi" to JsonPrimitive(cfi),
                "pos_frac" to JsonPrimitive(posFrac),
                "pathtoebook" to (pathtoebook?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull),
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
            return LinkedHashMap()
        }
        if (text.isBlank()) return LinkedHashMap()
        return try {
            val obj = json.parseToJsonElement(text).jsonObject
            obj.entries.filter { it.value is JsonObject }
                .associate { it.key to it.value as JsonObject }.toMutableMap()
        } catch (e: Exception) {
            LinkedHashMap()
        }
    }

    @Synchronized private fun persist() {
        storeFile.parentFile?.mkdirs()
        storeFile.writeText(Json.encodeToString(JsonObject.serializer(), JsonObject(entries.toMap())))
    }
}
