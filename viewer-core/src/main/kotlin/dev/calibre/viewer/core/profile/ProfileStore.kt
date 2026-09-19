// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Profiles persistence — the Android equivalent of calibre's
// profiles.json. Each profile is a flat dict of session keys (the same
// shape viewer.js's settings_for_reader_profile builds). Stored globally
// (not per-book) because profiles are reading-mode presets that the user
// applies across books — same scope as desktop calibre.
//
// On-disk format:
//   <filesDir>/profiles.json
//   {"profiles": {"<name>": {<setting_key>: <value>, ...}, ...}}
//
// Write queue is a single-thread executor so saves are ordered and never
// block the main thread. load() returns an empty map on a missing or
// corrupt file rather than throwing — losing the profile list is a small
// UX cost vs. refusing to open a book.

package dev.calibre.viewer.core.profile

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class ProfileStore(profilesFile: File) {

    private val file: File = profilesFile
    private val json = Json { ignoreUnknownKeys = true }

    private val writeExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "profile-store-writer").apply { isDaemon = true }
        }

    @Volatile
    private var cache: Map<String, JsonObject> = load()

    fun list(): Map<String, JsonObject> = cache

    fun get(name: String): JsonObject? = cache[name]

    fun save(name: String, settings: JsonObject) {
        val next = LinkedHashMap(cache)
        next[name] = settings
        cache = next
        writeExecutor.execute { persist(next) }
    }

    fun delete(name: String) {
        val next = LinkedHashMap(cache)
        if (next.remove(name) == null) return
        cache = next
        writeExecutor.execute { persist(next) }
    }

    /** Block until every queued write has landed (tests / onStop). */
    fun flush() {
        val done = CompletableFuture<Unit>()
        writeExecutor.execute { done.complete(Unit) }
        done.get()
    }

    private fun persist(map: Map<String, JsonObject>) {
        val serializer = MapSerializer(String.serializer(), JsonObject.serializer())
        val profilesEl = json.encodeToJsonElement(serializer, map)
        val payload = buildJsonObject { put("profiles", profilesEl) }
        file.parentFile?.mkdirs()
        file.writeText(Json.encodeToString(JsonObject.serializer(), payload))
    }

    private fun load(): Map<String, JsonObject> {
        if (!file.isFile) return LinkedHashMap()
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return LinkedHashMap()
        }
        if (text.isBlank()) return LinkedHashMap()
        return try {
            val obj = json.parseToJsonElement(text).jsonObject
            val raw = obj["profiles"] as? JsonObject ?: return LinkedHashMap()
            LinkedHashMap<String, JsonObject>().also { out ->
                for ((k, v) in raw) {
                    (v as? JsonObject)?.let { out[k] = it }
                }
            }
        } catch (e: Exception) {
            LinkedHashMap()
        }
    }
}
