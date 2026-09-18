// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// session_data + local_storage persistence — the Android equivalent of the
// viewer's vprefs keys. The whole session_data object is handed to
// create_view on startup and mutated write-through via the set_session_data
// / set_local_storage signals, with web_view.py's exact semantics:
//
//   set_session_data(key, val):
//     key is a dict → apply each entry;
//     key == '*' and val == null → clear everything;
//     otherwise set/remove the single key (null removes).
//
// The JSON files are desktop-calibre-compatible: a session_data.json
// exported from the desktop viewer can be dropped in and just works.

package dev.calibre.viewer.core.session

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class SessionStore(sessionDir: File) {

    private val sessionFile = File(sessionDir, "session_data.json")
    private val localStorageFile = File(sessionDir, "local_storage.json")
    private val json = Json { ignoreUnknownKeys = true }

    private var sessionData: MutableMap<String, JsonElement> = load(sessionFile)
    private var localStorage: MutableMap<String, JsonElement> = load(localStorageFile)

    private val writeExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "session-store-writer").apply { isDaemon = true }
        }

    val sessionDataJson: JsonObject
        get() = JsonObject(sessionData.toMap())

    val localStorageJson: JsonObject
        get() = JsonObject(localStorage.toMap())

    /** set_session_data signal handler (write-through). */
    fun setSessionData(key: JsonElement, value: JsonElement) {
        when {
            key is JsonObject -> for ((k, v) in key) changeSession(k, v)
            key.stringOrNull() == "*" && value is JsonNull -> clearAll()
            else -> {
                val k = key.stringOrNull() ?: return
                if (k != "*") changeSession(k, value)
            }
        }
        save()
    }

    /** set_local_storage signal handler (write-through). */
    fun setLocalStorage(key: JsonElement, value: JsonElement) {
        val k = key.stringOrNull() ?: return
        if (k == "*" && value is JsonNull) {
            localStorage.clear()
        } else if (k != "*") {
            localStorage[k] = value
        }
        save()
    }

    private fun changeSession(key: String, value: JsonElement) {
        if (value is JsonNull) sessionData.remove(key) else sessionData[key] = value
    }

    private fun clearAll() {
        sessionData.clear()
    }

    /**
     * In-memory mutation is synchronous (caller thread, ordered); the disk
     * write is queued to a single writer thread so set_session_data signals
     * never block the main thread — they arrive in bursts when a panel
     * closes, and e-ink flash is slow. Text snapshots are taken here, before
     * the hand-off, so the written state is always internally consistent.
     */
    private fun save() {
        val sessionText = Json.encodeToString(JsonObject.serializer(), sessionDataJson)
        val localText = Json.encodeToString(JsonObject.serializer(), localStorageJson)
        writeExecutor.execute {
            sessionFile.parentFile?.mkdirs()
            sessionFile.writeText(sessionText)
            localStorageFile.writeText(localText)
        }
    }

    /** Block until every queued write has landed (durability points, tests). */
    fun flush() {
        val done = CompletableFuture<Unit>()
        writeExecutor.execute { done.complete(Unit) }
        done.get()
    }

    private fun load(file: File): MutableMap<String, JsonElement> {
        if (!file.isFile) return LinkedHashMap()
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return LinkedHashMap()
        }
        if (text.isBlank()) return LinkedHashMap()
        return try {
            json.parseToJsonElement(text).jsonObject.toMutableMap()
        } catch (e: Exception) {
            // Corrupt store: start fresh rather than refuse to open books.
            LinkedHashMap()
        }
    }

    private fun JsonElement.stringOrNull(): String? =
        (this as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is JsonNull }?.content
}
