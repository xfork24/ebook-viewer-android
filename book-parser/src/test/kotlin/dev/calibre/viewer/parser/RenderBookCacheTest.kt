// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Tests for RenderBookCache — file-hash based cache layer in front of
// RenderBook. Each test writes a fake "source" file to a temp dir, builds
// a cache, and asserts the commit/lookup/evict lifecycle.

package dev.calibre.viewer.parser

import dev.calibre.viewer.parser.render.RenderBookCache
import java.io.File
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
        val text = idxFile.readText()
        val entry = Regex("\"$hash\":\\{[^{}]*\\}").find(text)?.value ?: error("no index entry for $hash")
        val stamped = System.currentTimeMillis() - ageMillis
        val updated = entry.replaceFirst(Regex("\"accessed\":\\d+"), "\"accessed\":$stamped")
        idxFile.writeText(text.replace(entry, updated))
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
}
