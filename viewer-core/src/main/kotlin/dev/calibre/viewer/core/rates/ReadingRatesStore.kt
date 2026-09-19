// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Per-book reading-rate persistence. The page's Timers module reports
// {"rates": [chars/sec, ...], "chapter_times": {spine-name: seconds}} on
// every qualifying page turn (update_reading_rates) and the same object is
// fed back as start_book_load's reading_rates argument on reopen, which
// Timers.start_book copies into its in-memory state. Storing the payload
// verbatim (instead of decoding into typed fields) keeps the two sides
// immune to upstream schema drift.
//
// Keyed by the render-cache book hash, like LastReadStore. Updates arrive
// on every human page turn with >2s spacing, so the disk write goes through
// the same single-writer write-behind pattern as SessionStore: in-memory
// mutation synchronous and ordered, the file write queued off-thread.

package dev.calibre.viewer.core.rates

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class ReadingRatesStore(file: File) {

    private val storeFile = file
    private val json = Json { ignoreUnknownKeys = true }

    private var entries: MutableMap<String, JsonObject> = load()

    private val writeExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "reading-rates-writer").apply { isDaemon = true }
        }

    /** The stored payload for [bookHash], ready for start_book_load. */
    @Synchronized fun get(bookHash: String): JsonObject? = entries[bookHash]

    /** update_reading_rates signal handler: replace the book's payload. */
    fun update(bookHash: String, payload: JsonObject) {
        val snapshot: String
        synchronized(this) {
            if (entries[bookHash] == payload) return
            entries[bookHash] = payload
            snapshot = serialize()
        }
        writeExecutor.execute { write(snapshot) }
    }

    /** reset_reading_rates signal handler: drop the book's payload. */
    fun reset(bookHash: String) {
        val snapshot: String
        synchronized(this) {
            if (entries.remove(bookHash) == null) return
            snapshot = serialize()
        }
        writeExecutor.execute { write(snapshot) }
    }

    /**
     * One-time migration (inbox mtime pinning): move a book's payload from
     * the old render hash to the new one. No-op when the old entry is absent.
     */
    fun rekey(oldBookHash: String, newBookHash: String) {
        val snapshot: String
        synchronized(this) {
            val entry = entries.remove(oldBookHash) ?: return
            entries[newBookHash] = entry
            snapshot = serialize()
        }
        writeExecutor.execute { write(snapshot) }
    }

    /** Block until every queued write has landed (durability points, tests). */
    fun flush() {
        val done = CompletableFuture<Unit>()
        writeExecutor.execute { done.complete(Unit) }
        done.get()
    }

    private fun serialize(): String =
        Json.encodeToString(JsonObject.serializer(), JsonObject(entries.toMap()))

    private fun write(text: String) {
        try {
            storeFile.parentFile?.mkdirs()
            storeFile.writeText(text)
        } catch (e: Exception) {
            // A failed rates write must never take the reader down; the next
            // update retries and the worst case is re-measuring the rates.
        }
    }

    @Synchronized
    private fun load(): MutableMap<String, JsonObject> {
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
            // Corrupt store: start fresh (rates re-measure after 5 page turns).
            LinkedHashMap()
        }
    }
}
