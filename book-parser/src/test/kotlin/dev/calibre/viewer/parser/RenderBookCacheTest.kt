// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Tests for RenderBookCache — file-hash based cache layer in front of
// RenderBook. Each test writes a fake "source" file to a temp dir, builds
// a cache, and asserts the commit/lookup/evict lifecycle.

package dev.calibre.viewer.parser

import dev.calibre.viewer.parser.render.RenderBookCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RenderBookCacheTest {

    private fun tempRoot(): File {
        val d = createTempDir(prefix = "render-cache-test")
        Runtime.getRuntime().addShutdownHook(Thread { d.deleteRecursively() })
        return d
    }

    private fun makeFile(size: Int = 1024, seed: Int = 0): File {
        val f = File.createTempFile("src-", ".bin")
        f.deleteOnExit()
        val bytes = ByteArray(size)
        for (i in bytes.indices) bytes[i] = ((i + seed) and 0xff).toByte()
        f.writeBytes(bytes)
        return f
    }

    @Test
    fun `lookup returns null when nothing is cached`() {
        val cache = RenderBookCache(tempRoot())
        assertNull(cache.lookup(makeFile()))
    }

    @Test
    fun `calculateBookHash is stable across two reads of the same file`() {
        val cache = RenderBookCache(tempRoot())
        val f = makeFile(seed = 42)
        val h1 = cache.calculateBookHash(f)
        val h2 = cache.calculateBookHash(f)
        assertEquals(h1, h2)
        assertEquals(40, h1.length) // sha1 hex = 40 chars
    }

    @Test
    fun `calculateBookHash differs when file content changes`() {
        val cache = RenderBookCache(tempRoot())
        val f1 = makeFile(seed = 1)
        val f2 = makeFile(seed = 2)
        assertTrue(cache.calculateBookHash(f1) != cache.calculateBookHash(f2))
    }

    @Test
    fun `calculateBookHash differs when size changes (sentinel)`() {
        val cache = RenderBookCache(tempRoot())
        val f1 = makeFile(size = 1024)
        val f2 = makeFile(size = 2048)
        assertTrue(cache.calculateBookHash(f1) != cache.calculateBookHash(f2))
    }

    @Test
    fun `commit then lookup returns the finished dir`() {
        val cache = RenderBookCache(tempRoot())
        val src = makeFile()
        val trans = cache.createTransient()
        File(trans, "calibre-book-manifest.json").writeText("{}")
        val finished = cache.commit(src, trans)
        assertTrue(finished.isDirectory)
        assertTrue(File(finished, "calibre-book-manifest.json").isFile)
        val looked = cache.lookup(src)
        assertEquals(finished, looked)
    }

    @Test
    fun `commit moves a transient dir out of transient`() {
        val cache = RenderBookCache(tempRoot())
        val src = makeFile()
        val trans = cache.createTransient()
        File(trans, "manifest.json").writeText("{}")
        val finished = cache.commit(src, trans)
        assertFalse(trans.exists())
        assertTrue(finished.exists())
    }

    @Test
    fun `discardTransient removes the transient dir without committing`() {
        val cache = RenderBookCache(tempRoot())
        val src = makeFile()
        val trans = cache.createTransient()
        File(trans, "manifest.json").writeText("{}")
        cache.discardTransient(trans)
        assertFalse(trans.exists())
        assertNull(cache.lookup(src))
    }

    @Test
    fun `evict removes the finished entry`() {
        val cache = RenderBookCache(tempRoot())
        val src = makeFile()
        val trans = cache.createTransient()
        File(trans, "manifest.json").writeText("{}")
        val finished = cache.commit(src, trans)
        val hash = cache.calculateBookHash(src)
        cache.evict(hash)
        assertFalse(finished.exists())
        assertNull(cache.lookup(src))
    }

    @Test
    fun `commit overwrites a previous entry for the same hash`() {
        val cache = RenderBookCache(tempRoot())
        val src = makeFile()
        // First commit
        val t1 = cache.createTransient()
        File(t1, "manifest.json").writeText("{\"v\":1}")
        val f1 = cache.commit(src, t1)
        // Second commit over the same source — same hash, must replace.
        val t2 = cache.createTransient()
        File(t2, "manifest.json").writeText("{\"v\":2}")
        val f2 = cache.commit(src, t2)
        assertEquals(f1, f2)
        assertEquals("{\"v\":2}", File(f2, "manifest.json").readText())
    }

    // ---- evictToSize ------------------------------------------------------

    private fun commitBook(cache: RenderBookCache, payloadBytes: Int): File {
        val src = makeFile()
        val trans = cache.createTransient()
        File(trans, "calibre-book-manifest.json").writeText("{}")
        File(trans, "payload.bin").writeBytes(ByteArray(payloadBytes))
        return cache.commit(src, trans)
    }

    /** Rewrite an entry's accessed stamp directly in index.json (age control). */
    private fun backdateAccess(root: File, cache: RenderBookCache, src: File, finishedDir: File, ageMillis: Long) {
        val hash = cache.calculateBookHash(src)
        check(finishedDir.name == hash) { "dir ${finishedDir.name} != hash $hash" }
        val idxFile = File(root, "ev2/index.json")
        val text = decodeIndexText(idxFile)
        val entry = Regex("\"$hash\":\\{[^{}]*\\}").find(text)?.value ?: error("no index entry for $hash")
        val stamped = System.currentTimeMillis() - ageMillis
        val updated = entry.replaceFirst(Regex("\"accessed\":\\d+"), "\"accessed\":$stamped")
        idxFile.writeBytes(encodeIndexText(text.replace(entry, updated)))
    }

    /**
     * Decode index.json on-disk bytes to UTF-8 text, transparently handling
     * both v1 (plain JSON) and v2 (magic + gzip). Mirrors the private helper
     * inside RenderBookCache — duplicated here so the test surface stays
     * pure-JVM with no need for a `@VisibleForTesting` accessor on the
     * production class.
     */
    private val magicV2 = byteArrayOf(0x45, 0x56, 0x32, 0x43) // "EV2C"

    private fun decodeIndexText(file: File): String {
        val raw = file.readBytes()
        if (raw.size >= magicV2.size &&
            raw.copyOfRange(0, magicV2.size).contentEquals(magicV2)
        ) {
            val payload = raw.copyOfRange(magicV2.size, raw.size)
            return GZIPInputStream(payload.inputStream()).use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        }
        return raw.toString(Charsets.UTF_8)
    }

    private fun encodeIndexText(text: String): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return magicV2 + bos.toByteArray()
    }

    @Test
    fun `evictToSize is a no-op when the cache is under budget`() {
        val root = tempRoot()
        val cache = RenderBookCache(root)
        val a = commitBook(cache, payloadBytes = 10_000)
        val b = commitBook(cache, payloadBytes = 10_000)
        cache.evictToSize(maxBytes = 1024L * 1024 * 1024)
        assertTrue(a.isDirectory)
        assertTrue(b.isDirectory)
    }

    @Test
    fun `evictToSize evicts the oldest accessed entry first`() {
        val root = tempRoot()
        val cache = RenderBookCache(root)
        val oldSrc = makeFile()
        val oldTrans = cache.createTransient()
        File(oldTrans, "calibre-book-manifest.json").writeText("{}")
        File(oldTrans, "payload.bin").writeBytes(ByteArray(100_000))
        val old = cache.commit(oldSrc, oldTrans)
        backdateAccess(root, cache, oldSrc, old, ageMillis = 10L * 24 * 60 * 60 * 1000)
        val recent = commitBook(cache, payloadBytes = 100_000)
        // Budget only fits ~one payload: the old entry must go, recent stays.
        cache.evictToSize(maxBytes = 120_000)
        assertFalse(old.isDirectory, "oldest entry should have been evicted")
        assertTrue(recent.isDirectory, "recent entry should survive")
    }

    @Test
    fun `evictToSize never evicts the protected book`() {
        val root = tempRoot()
        val cache = RenderBookCache(root)
        val oldSrc = makeFile()
        val oldTrans = cache.createTransient()
        File(oldTrans, "calibre-book-manifest.json").writeText("{}")
        File(oldTrans, "payload.bin").writeBytes(ByteArray(100_000))
        val old = cache.commit(oldSrc, oldTrans)
        backdateAccess(root, cache, oldSrc, old, ageMillis = 10L * 24 * 60 * 60 * 1000)
        // Zero budget, but the old book IS the protected (currently open) one.
        cache.evictToSize(maxBytes = 0, protect = old.name)
        assertTrue(old.isDirectory, "protected book must never be evicted")
    }

    @Test
    fun `evictToSize removes orphaned finished dirs`() {
        val root = tempRoot()
        val cache = RenderBookCache(root)
        val book = commitBook(cache, payloadBytes = 10_000)
        // A directory under f/ with no index entry — e.g. a crash between
        // rename and index write. Unreachable through lookup(); only the
        // size sweep can reclaim it.
        val orphan = File(root, "ev2/f/${"f".repeat(40)}")
        orphan.mkdirs()
        File(orphan, "junk.bin").writeBytes(ByteArray(50_000))
        cache.evictToSize(maxBytes = 1024L * 1024 * 1024)
        assertFalse(orphan.isDirectory, "orphan dir should be reclaimed")
        assertTrue(book.isDirectory, "indexed entry should be untouched")
    }

    @Test
    fun `evictToSize purges stale index entries whose dir is gone`() {
        val root = tempRoot()
        val cache = RenderBookCache(root)
        val src = makeFile()
        val trans = cache.createTransient()
        File(trans, "calibre-book-manifest.json").writeText("{}")
        cache.commit(src, trans)
        // Simulate an evict that died between delete-dir and index rewrite:
        // dir removed out-of-band, index entry left behind. Without the
        // purge, index.json keeps the dead hash forever.
        val hash = cache.calculateBookHash(src)
        File(root, "ev2/f/$hash").deleteRecursively()
        val idxBefore = decodeIndexText(File(root, "ev2/index.json"))
        assertTrue(hash in idxBefore)
        cache.evictToSize(maxBytes = 1024L * 1024 * 1024)
        val idxAfter = decodeIndexText(File(root, "ev2/index.json"))
        assertFalse(hash in idxAfter, "stale index entry must be purged")
    }

    @Test
    fun `concurrent commit and evict leave the index consistent`() {
        val root = tempRoot()
        val cache = RenderBookCache(root)
        val sources = (0 until 12).map { makeFile(seed = it) }
        // Hammer: one thread commits books while another evicts them. The
        // index must never end up referencing a deleted directory (an
        // unlocked evict interleaving with evictToSize used to be able to
        // resurrect entries) nor lose a committed book's entry while its
        // dir survives as an orphan.
        val committer = Thread {
            for (src in sources) {
                val trans = cache.createTransient()
                File(trans, "calibre-book-manifest.json").writeText("{}")
                cache.commit(src, trans)
            }
        }
        val evictor = Thread {
            for (src in sources) {
                cache.evict(cache.calculateBookHash(src))
            }
        }
        committer.start(); evictor.start()
        committer.join(); evictor.join()
        cache.evictToSize(maxBytes = 1024L * 1024 * 1024)
        // After a settling sweep: every surviving f/<hash> dir has an index
        // entry, and every index entry has a surviving dir (stale purged).
        val idxText = decodeIndexText(File(root, "ev2/index.json"))
        val indexed = Regex("\"([0-9a-f]{40})\"\\s*:").findAll(idxText).map { it.groupValues[1] }.toSet()
        val dirs = File(root, "ev2/f").listFiles()!!.map { it.name }.toSet()
        for (hash in indexed) assertTrue(hash in dirs, "index entry $hash has no dir")
        for (dir in dirs) assertTrue(dir in indexed, "dir $dir has no index entry")
    }

    // ---- gzip v2 format (R-PERF-03) --------------------------------------

    private val MAGIC_V2 = byteArrayOf(0x45, 0x56, 0x32, 0x43) // "EV2C"
    private val indexFile = { root: File -> File(root, "ev2/index.json") }

    @Test
    fun `index file round-trips through gzip with v2 magic`() {
        val root = tempRoot()
        val cache = RenderBookCache(root)
        val src = makeFile()
        val trans = cache.createTransient()
        File(trans, "calibre-book-manifest.json").writeText("{}")
        cache.commit(src, trans)
        // On-disk bytes start with the magic prefix.
        val bytes = indexFile(root).readBytes()
        assertTrue(bytes.size > MAGIC_V2.size, "index must have payload after magic")
        assertTrue(
            bytes.copyOfRange(0, MAGIC_V2.size).contentEquals(MAGIC_V2),
            "index must be written with v2 magic prefix",
        )
        // The bytes are NOT plain JSON — the gzip wrapper must shrink them.
        val rawJson = "{\"hash\":"
        assertFalse(rawJson.toByteArray().contentEquals(bytes.copyOfRange(0, rawJson.length)),
            "v2 index must not be plain JSON")
        // lookup() still works after commit (gzip read path).
        assertNotNull(cache.lookup(src))
    }

    @Test
    fun `v1 index parses via fallback and rewrites in v2 on next bump`() {
        val root = tempRoot()
        // Seed a legacy v1 index.json directly: no magic, plain JSON.
        val src = makeFile(seed = 7)
        val cache = RenderBookCache(root)
        val trans = cache.createTransient()
        File(trans, "calibre-book-manifest.json").writeText("{}")
        val finished = cache.commit(src, trans) // this writes v2; undo it
        val hash = cache.calculateBookHash(src)
        // Force a v1 file into place and confirm the cache can still read it.
        val v1Text = "{\"$hash\":{\"path\":\"${finished.path}\"," +
            "\"sourcePath\":\"${src.path}\",\"sourceMtime\":${src.lastModified()}," +
            "\"sourceSize\":${src.length()},\"accessed\":${System.currentTimeMillis()}," +
            "\"transient\":false}}"
        indexFile(root).writeText(v1Text)
        // lookup must transparently parse the v1 file.
        assertEquals(finished, cache.lookup(src))
        // A subsequent write (bumpAccess inside lookup already triggered one)
        // upgrades the file to v2 format.
        val afterBytes = indexFile(root).readBytes()
        assertTrue(
            afterBytes.copyOfRange(0, MAGIC_V2.size).contentEquals(MAGIC_V2),
            "v1 index must be promoted to v2 on next write",
        )
    }

    @Test
    fun `v2 index with truncated gzip blob quarantines and starts fresh`() {
        val root = tempRoot()
        val src = makeFile()
        val cache = RenderBookCache(root)
        val trans = cache.createTransient()
        File(trans, "calibre-book-manifest.json").writeText("{}")
        cache.commit(src, trans)
        // Forge a v2-magic file with a truncated gzip stream after the magic.
        // \x1f\x8b is the gzip magic but \x00\x00 is not a valid CM byte, so
        // GZIPInputStream throws ZipException -> quarantine branch in Renderer.
        val truncated = MAGIC_V2 + byteArrayOf(0x1f, 0x8b.toByte(), 0x00, 0x00)
        indexFile(root).writeBytes(truncated)
        // lookup must NOT throw: the corrupt index is moved aside and the
        // returned dir reflects that the manifest is still on disk under
        // f/<hash>/. What we're verifying is graceful recovery, not absence
        // of the entry (the manifest lives independently of the index).
        val found = cache.lookup(src)
        assertNotNull(found, "lookup must not throw on a corrupt index")
        // The bad file must be moved aside.
        assertTrue(
            File(root, "ev2/index.json.corrupt").isFile,
            "corrupt index must be quarantined to index.json.corrupt",
        )
        // The next commit recreates a valid v2 index (cache "starts fresh").
        val trans2 = cache.createTransient()
        File(trans2, "calibre-book-manifest.json").writeText("{}")
        cache.commit(makeFile(), trans2)
        val writtenBytes = File(root, "ev2/index.json").readBytes()
        assertTrue(
            writtenBytes.copyOfRange(0, MAGIC_V2.size).contentEquals(MAGIC_V2),
            "next write must produce a fresh v2 (magic + gzip) index",
        )
    }
}
