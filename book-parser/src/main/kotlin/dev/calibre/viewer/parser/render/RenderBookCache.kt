// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Per-book render cache. Mirrors calibre/src/calibre/convert/__init__.py:228-290
// (cache_path_for_book / calculate_book_hash) plus the local mirror of
// calibre-book-manifest.json that lets the Android shell skip a re-render
// when the source file hasn't changed.
//
// Layout under [rootDir]:
//   ev2/
//     index.json                 — { bookHash -> {path, sourcePath, sourceMtime, sourceSize, accessed} }
//     t/                         — transient (in-progress) renders
//     f/                         — finished renders (ready to serve)
//       <bookHash>/
//         content.opf
//         …extracted files…
//         calibre-book-manifest.json
//
// bookHash = sha1(size + mtime_ns + first 1MB + last 1MB). The hash depends
// only on file content + modification time — opening the same EPUB twice in
// a row hits the cache, but extracting it once, editing one byte in another
// app and reopening will rebuild it.
//
// Android-side storage: pass `cacheDir` (the per-app Context.cacheDir). The
// shell layer is responsible for sweeping ev2/ when low on disk; this class
// only does the read/write/evict of individual entries.

package dev.calibre.viewer.parser.render

import dev.calibre.viewer.parser.util.pyJsonString
import java.io.File
import java.security.MessageDigest

class RenderBookCache(private val rootDir: File) {

    private val ev2Dir = File(rootDir, "ev2")
    private val finishedDir = File(ev2Dir, "f")
    private val transientDir = File(ev2Dir, "t")
    private val indexFile = File(ev2Dir, "index.json")

    init {
        finishedDir.mkdirs()
        transientDir.mkdirs()
    }

    /**
     * Look up an already-rendered book by [sourceFile]'s hash. Returns null
     * when the cache is cold OR the entry was previously evicted (the on-disk
     * `f/<hash>/` directory disappears with the index entry).
     *
     * Synchronized: a concurrent trim (evictToSize) must not observe a
     * half-committed entry.
     */
    @Synchronized
    fun lookup(sourceFile: File): File? {
        val hash = calculateBookHash(sourceFile)
        val dir = File(finishedDir, hash)
        val manifest = File(dir, RenderBook.MANIFEST_NAME)
        if (!manifest.isFile) return null
        // Touch the entry so a background LRU sweep can prune oldest first.
        bumpAccess(hash)
        return dir
    }

    /**
     * Reserve a transient directory for a fresh render. The caller does all
     * the work in there, then calls [commit] on success or [discardTransient]
     * on failure.
     */
    fun createTransient(): File {
        val name = "r-${System.currentTimeMillis()}-${(Math.random() * 1e9).toLong().toString(16)}"
        return File(transientDir, name).apply { mkdirs() }
    }

    /**
     * Move [transientDir] into the finished cache under [sourceFile]'s hash
     * and record the index entry. Returns the finished directory.
     *
     * Synchronized against a concurrent trim: evictToSize reads the index
     * and the finished tree together; without the lock it could delete a
     * directory whose rename completed but whose index entry it never saw.
     */
    @Synchronized
    fun commit(sourceFile: File, transientDir: File): File {
        val hash = calculateBookHash(sourceFile)
        val target = File(finishedDir, hash)
        if (target.exists()) target.deleteRecursively()
        val moved = transientDir.renameTo(target)
        if (!moved) {
            // renameTo can fail across mount points; fall back to copy.
            target.mkdirs()
            transientDir.copyRecursively(target, overwrite = true)
            transientDir.deleteRecursively()
        }
        writeIndexEntry(hash, sourceFile, target, transient = false)
        return target
    }

    /** Remove a transient directory without making it visible. */
    fun discardTransient(transientDir: File) {
        transientDir.deleteRecursively()
    }

    /** Evict a finished entry by hash. */
    fun evict(bookHash: String) {
        File(finishedDir, bookHash).deleteRecursively()
        removeIndexEntry(bookHash)
    }

    /** Drop every finished entry whose last access is older than [olderThanMillis]. */
    @Synchronized
    fun evictOlderThan(olderThanMillis: Long) {
        val now = System.currentTimeMillis()
        val idx = readIndex()
        for ((hash, entry) in idx.toMap()) {
            val acc = entry["accessed"] as? Long ?: 0L
            if (now - acc > olderThanMillis) evict(hash)
        }
    }

    /**
     * Evict finished entries until the finished tree is at most [maxBytes]
     * on disk. Orphaned `f/<hash>/` directories (index entry lost to a crash
     * between rename and index write) go first — nothing else can reclaim
     * them — then entries evict oldest-accessed first. [protect] (the
     * currently open book's hash) is never evicted; when it alone exceeds
     * the budget the loop stops rather than delete the book being read.
     */
    @Synchronized
    fun evictToSize(maxBytes: Long, protect: String? = null) {
        val idx = readIndex()
        val sizes = HashMap<String, Long>()
        var total = 0L
        val onDisk = finishedDir.listFiles()?.filter { it.isDirectory } ?: return
        for (dir in onDisk) {
            val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            sizes[dir.name] = size
            if (dir.name !in idx.keys) {
                // Orphan: no index entry, unreachable by lookup.
                dir.deleteRecursively()
            } else {
                total += size
            }
        }
        val byAge = idx.keys
            .filter { it != protect && File(finishedDir, it).isDirectory }
            .sortedBy { idx[it]?.get("accessed") as? Long ?: 0L }
        for (hash in byAge) {
            if (total <= maxBytes) break
            if (File(finishedDir, hash).deleteRecursively()) {
                removeIndexEntry(hash)
                total -= sizes[hash] ?: 0L
            }
        }
    }

    /** Recompute every entry; entries whose source file no longer matches get evicted. */
    fun sweep(sourceResolver: (String) -> File?) {
        val idx = readIndex()
        for ((hash, entry) in idx.toMap()) {
            val src = entry["sourcePath"] as? String ?: continue
            val f = sourceResolver(src) ?: continue
            if (!f.isFile || calculateBookHash(f) != hash) evict(hash)
        }
    }

    /** Free bytes reclaimed by the most recent evict call (for telemetry). */
    val finishedDirBytes: Long get() = finishedDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // ---- hashing ---------------------------------------------------------

    /**
     * sha1(size + mtime_ns + first 1MB + last 1MB). The Python source reads
     * raw bytes; here we stay on JVM without a native file API by using
     * `FileInputStream` and `FileChannel`.
     */
    fun calculateBookHash(file: File): String {
        if (!file.isFile) throw IllegalArgumentException("Not a file: $file")
        val md = MessageDigest.getInstance("SHA-1")
        val size = file.length()
        md.update(size.toString().toByteArray(Charsets.US_ASCII))
        md.update(0)
        // mtime_ns — File.lastModified() returns ms, padded with trailing 0s
        // to match the byte width of stat.st_mtime_ns on a 64-bit POSIX.
        md.update(file.lastModified().toString().toByteArray(Charsets.US_ASCII))
        md.update(0)
        val len = size
        if (len <= 0) return hex(md.digest())
        val oneMb = 1024L * 1024L
        file.inputStream().use { ins ->
            if (len <= oneMb * 2) {
                md.update(ins.readBytes())
            } else {
                val head = ByteArray(oneMb.toInt())
                var read = 0
                while (read < head.size) {
                    val n = ins.read(head, read, head.size - read)
                    if (n <= 0) break
                    read += n
                }
                md.update(head, 0, read)
                // InputStream.skip may short-read; loop until the gap is gone.
                var toSkip = len - oneMb - read
                while (toSkip > 0) {
                    val s = ins.skip(toSkip)
                    if (s <= 0) break
                    toSkip -= s
                }
                val tail = ByteArray(oneMb.toInt())
                var tailRead = 0
                while (tailRead < tail.size) {
                    val n = ins.read(tail, tailRead, tail.size - tailRead)
                    if (n <= 0) break
                    tailRead += n
                }
                md.update(tail, 0, tailRead)
            }
        }
        return hex(md.digest())
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append("%02x".format(b.toInt() and 0xff))
        }
        return sb.toString()
    }

    // ---- index -----------------------------------------------------------

    /**
     * Tiny JSON-by-hand read/write for { hash: {path, sourcePath, sourceMtime,
     * sourceSize, accessed} }. We avoid a JSON dependency for two 4-field
     * records — keep it boring and predictable.
     */
    @Suppress("UNCHECKED_CAST")
    private fun readIndex(): MutableMap<String, Map<String, Any?>> {
        if (!indexFile.isFile) return HashMap()
        val text = indexFile.readText(Charsets.UTF_8)
        if (text.isBlank()) return HashMap()
        // Hand-rolled parse: {"hash":{"path":"...","sourcePath":"..."}}.
        // The values come from our own writer so we control the escaping;
        // we still JSON-decode them for forward-compat (string/number/null).
        val ans = HashMap<String, Map<String, Any?>>()
        // Use a simple regex-style split since the file is tiny and well-formed.
        val outerRegex = Regex("\"([0-9a-f]{40})\"\\s*:\\s*\\{([^}]*)\\}")
        for (m in outerRegex.findAll(text)) {
            val hash = m.groupValues[1]
            val body = m.groupValues[2]
            val entry = HashMap<String, Any?>()
            val kvRegex = Regex("\"([^\"]+)\"\\s*:\\s*(?:\"([^\"]*)\"|(-?\\d+)|(null))")
            for (kv in kvRegex.findAll(body)) {
                val k = kv.groupValues[1]
                when {
                    kv.groupValues[2] != "" -> entry[k] = kv.groupValues[2]
                    kv.groupValues[3] != "" -> entry[k] = kv.groupValues[3].toLong()
                    else -> entry[k] = null
                }
            }
            ans[hash] = entry
        }
        return ans
    }

    private fun writeIndexEntry(hash: String, sourceFile: File, renderDir: File, transient: Boolean) {
        val idx = readIndex()
        idx[hash] = linkedMapOf(
            "path" to renderDir.path,
            "sourcePath" to sourceFile.path,
            "sourceMtime" to sourceFile.lastModified(),
            "sourceSize" to sourceFile.length(),
            "accessed" to System.currentTimeMillis(),
            "transient" to transient,
        )
        indexFile.parentFile?.mkdirs()
        indexFile.writeText(serializeIndex(idx), Charsets.UTF_8)
    }

    private fun removeIndexEntry(hash: String) {
        val idx = readIndex()
        if (idx.remove(hash) != null) {
            indexFile.writeText(serializeIndex(idx), Charsets.UTF_8)
        }
    }

    private fun bumpAccess(hash: String) {
        val idx = readIndex()
        val entry = idx[hash]?.toMutableMap() ?: return
        entry["accessed"] = System.currentTimeMillis()
        idx[hash] = entry
        indexFile.writeText(serializeIndex(idx), Charsets.UTF_8)
    }

    private fun serializeIndex(idx: Map<String, Map<String, Any?>>): String {
        val sb = StringBuilder()
        sb.append('{')
        var first = true
        for ((hash, entry) in idx) {
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(hash).append("\":{")
            var firstField = true
            for ((k, v) in entry) {
                if (!firstField) sb.append(',')
                firstField = false
                sb.append(pyJsonString(k)).append(':')
                sb.append(jsonValue(v))
            }
            sb.append('}')
        }
        sb.append('}')
        return sb.toString()
    }

    private fun jsonValue(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> if (v) "true" else "false"
        is Number -> v.toString()
        else -> pyJsonString(v.toString())
    }
}
