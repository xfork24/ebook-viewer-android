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
}
