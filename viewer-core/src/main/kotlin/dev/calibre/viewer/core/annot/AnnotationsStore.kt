// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Per-book annotations persistence — the Android host's source of truth for
// bookmarks and highlights. Mirrors calibre's calibre-bookmark format so the
// JSON we write here is the same shape the desktop viewer reads back:
//
//   <filesDir>/annot/<sha256(bookUri)>.json
//     {
//       "bookmarks": [{type, timestamp, pos_type, pos, title}, ...],
//       "highlights": [{type, timestamp, uuid, start_cfi, end_cfi,
//                       highlighted_text, style, spine_name, spine_index,
//                       notes?, toc_family_titles?}, ...]
//     }
//
// The store does not talk to the page; the controller pulls a snapshot at
// openBook time and pushes a snapshot back on newBookmark / highlights_changed.
// (See M2 plan, section on why the host does not push bookmark lists to JS —
// `set_bookmarks` slot, so the in-page annotations_manager is a write-only
// mirror; native panels always read the host store.)
// Write queue is a single-thread executor so write-through is ordered and
// never blocks the main thread (SessionStore.kt:37-40 has the same pattern).

package dev.calibre.viewer.core.annot

import dev.calibre.viewer.core.internal.atomicWriteText
import dev.calibre.viewer.core.internal.quarantineCorrupt
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class BookmarkRecord(
    val type: String = "bookmark",
    val timestamp: String,
    val posType: String = "epubcfi",
    val pos: String,
    val title: String,
    /** Calibre uses a tombstone `{removed:true, ...}` to record a delete. */
    val removed: Boolean = false,
)

data class HighlightRecord(
    val type: String = "highlight",
    val timestamp: String,
    val uuid: String,
    val highlightedText: String,
    val startCfi: String,
    val endCfi: String,
    /** Style is opaque to the host; round-trip the JSON as-is. */
    val style: JsonObject,
    val spineName: String,
    val spineIndex: Int,
    val notes: String? = null,
    val tocFamilyTitles: List<String> = emptyList(),
    val removed: Boolean = false,
)

class AnnotationsStore(annotDir: File) {

    private val dir: File = annotDir.also { it.mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    private val writeExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "annot-store-writer").apply { isDaemon = true }
        }

    /**
     * In-memory snapshots, keyed by book hash. Saves publish here
     * synchronously, so a save→load→save sequence on the caller thread
     * always reads back its own earlier write even though the disk write
     * is still queued — without this, a new_bookmark immediately followed
     * by highlights_changed computes the second save from the stale file
     * and silently drops the first change.
     */
    private val cache = ConcurrentHashMap<String, Snapshot>()

    private fun fileFor(bookUri: String): File = File(dir, "${sha256(bookUri)}.json")

    fun loadBookmarks(bookUri: String): List<BookmarkRecord> = snapshotFor(bookUri).bookmarks
    fun loadHighlights(bookUri: String): List<HighlightRecord> = snapshotFor(bookUri).highlights

    private fun snapshotFor(bookUri: String): Snapshot =
        cache.computeIfAbsent(bookUri) { load(fileFor(bookUri)) }

    /**
     * Save both lists atomically — writes a single JSON object so a partial
     * crash mid-write doesn't desync the two lists, through a tmp+rename so
     * a process kill mid-write can never leave a truncated file. The
     * in-memory cache is updated synchronously so a subsequent load returns
     * the new value. A failed disk write is logged and retried by the next
     * save (the queue holds full snapshots) — it must not kill the process
     * and take every other queued write with it.
     */
    fun save(bookUri: String, bookmarks: List<BookmarkRecord>, highlights: List<HighlightRecord>) {
        val file = fileFor(bookUri)
        cache[bookUri] = Snapshot(bookmarks.toList(), highlights.toList())
        val payload = buildJsonObject {
            put("bookmarks", bookmarksToJson(bookmarks))
            put("highlights", highlightsToJson(highlights))
        }
        writeExecutor.execute {
            try {
                dir.mkdirs()
                atomicWriteText(file, Json.encodeToString(JsonObject.serializer(), payload))
            } catch (e: Exception) {
                LOG.warning("annotations write failed for ${file.name}: $e")
            }
        }
    }

    /** Block until every queued write has landed (tests / onStop). */
    fun flush() {
        val done = CompletableFuture<Unit>()
        writeExecutor.execute { done.complete(Unit) }
        done.get()
    }

    /**
     * One-time migration (inbox mtime pinning): move a book's annotations
     * file from the old render hash to the new one. The cache entry moves
     * synchronously (so loads under the new key can never observe the
     * mid-rename window); the file move runs on the write executor so it
     * can't race a queued save. No-op when absent.
     */
    fun rekey(oldBookUri: String, newBookUri: String) {
        if (oldBookUri == newBookUri) return
        val from = fileFor(oldBookUri)
        val to = fileFor(newBookUri)
        // Carry over ONLY when the old key actually holds something — a
        // cached snapshot (its queued disk write may not have landed yet) or
        // an on-disk file. Seeding cache[new] from a wholly absent old key
        // would shadow any existing new-key file with an empty snapshot, and
        // the next read-modify-write save would persist that empty state
        // over the new key's real data.
        if (!from.isFile && !cache.containsKey(oldBookUri)) return
        cache[newBookUri] = snapshotFor(oldBookUri)
        cache.remove(oldBookUri)
        writeExecutor.execute {
            try {
                if (!from.isFile) return@execute
                if (to.isFile) to.delete()
                if (!from.renameTo(to)) {
                    to.writeText(from.readText(Charsets.UTF_8))
                    from.delete()
                }
            } catch (e: Exception) {
                // Unmigratable — the old file stays and is simply orphaned.
                LOG.warning("annotations rekey ${from.name} -> ${to.name} failed: $e")
            }
        }
    }

    private data class Snapshot(val bookmarks: List<BookmarkRecord>, val highlights: List<HighlightRecord>)

    private fun load(file: File): Snapshot {
        if (!file.isFile) return Snapshot(emptyList(), emptyList())
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            quarantineCorrupt(file)
            return Snapshot(emptyList(), emptyList())
        }
        if (text.isBlank()) {
            quarantineCorrupt(file)
            return Snapshot(emptyList(), emptyList())
        }
        val obj = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            // Quarantine instead of just ignoring: the next save must not
            // overwrite the (possibly partially recoverable) damaged file
            // with state derived from empty lists.
            quarantineCorrupt(file)
            return Snapshot(emptyList(), emptyList())
        }
        val bookmarks = obj["bookmarks"]?.jsonArray.orEmpty().mapNotNull(::bookmarkFromJson)
        val highlights = obj["highlights"]?.jsonArray.orEmpty().mapNotNull(::highlightFromJson)
        return Snapshot(bookmarks, highlights)
    }

    private fun bookmarkFromJson(el: JsonElement): BookmarkRecord? {
        val o = el as? JsonObject ?: return null
        val timestamp = o["timestamp"]?.jsonPrimitive?.contentOrNull ?: return null
        val pos = o["pos"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return null
        return BookmarkRecord(
            type = o["type"]?.jsonPrimitive?.contentOrNull ?: "bookmark",
            timestamp = timestamp,
            posType = o["pos_type"]?.jsonPrimitive?.contentOrNull ?: "epubcfi",
            pos = pos,
            title = title,
            removed = o["removed"]?.jsonPrimitive?.contentOrNull == "true",
        )
    }

    private fun highlightFromJson(el: JsonElement): HighlightRecord? {
        val o = el as? JsonObject ?: return null
        val timestamp = o["timestamp"]?.jsonPrimitive?.contentOrNull ?: return null
        val uuid = o["uuid"]?.jsonPrimitive?.contentOrNull ?: return null
        val start: String = o["start_cfi"]?.jsonPrimitive?.contentOrNull ?: return null
        val end: String = o["end_cfi"]?.jsonPrimitive?.contentOrNull ?: start
        val text = o["highlighted_text"]?.jsonPrimitive?.contentOrNull ?: ""
        val spineName = o["spine_name"]?.jsonPrimitive?.contentOrNull ?: ""
        val spineIdx = o["spine_index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val style = o["style"] as? JsonObject ?: JsonObject(emptyMap())
        val notes = o["notes"]?.jsonPrimitive?.contentOrNull
        val toc = o["toc_family_titles"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        return HighlightRecord(
            type = o["type"]?.jsonPrimitive?.contentOrNull ?: "highlight",
            timestamp = timestamp,
            uuid = uuid,
            highlightedText = text,
            startCfi = start,
            endCfi = end,
            style = style,
            spineName = spineName,
            spineIndex = spineIdx,
            notes = notes,
            tocFamilyTitles = toc,
            removed = o["removed"]?.jsonPrimitive?.contentOrNull == "true",
        )
    }

    private fun bookmarksToJson(items: List<BookmarkRecord>): JsonArray = buildJsonArray {
        for (record in items) {
            add(buildJsonObject {
                put("type", record.type)
                put("timestamp", record.timestamp)
                put("pos_type", record.posType)
                put("pos", record.pos)
                put("title", record.title)
                if (record.removed) put("removed", true)
            })
        }
    }

    private fun highlightsToJson(items: List<HighlightRecord>): JsonArray = buildJsonArray {
        for (record in items) {
            add(buildJsonObject {
                put("type", record.type)
                put("timestamp", record.timestamp)
                put("uuid", record.uuid)
                put("highlighted_text", record.highlightedText)
                put("start_cfi", record.startCfi)
                put("end_cfi", record.endCfi)
                put("style", record.style)
                put("spine_name", record.spineName)
                put("spine_index", record.spineIndex)
                record.notes?.let { put("notes", it) }
                if (record.tocFamilyTitles.isNotEmpty()) {
                    put("toc_family_titles", buildJsonArray {
                        record.tocFamilyTitles.forEach { add(JsonPrimitive(it)) }
                    })
                }
                if (record.removed) put("removed", true)
            })
        }
    }

    companion object {
        private val LOG = java.util.logging.Logger.getLogger("dev.calibre.viewer.core.stores")

        private fun sha256(s: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** Exposed for tests so they can predict filenames without recomputing SHA-256. */
        @JvmStatic
        fun sha256Of(s: String): String = sha256(s)
    }
}
