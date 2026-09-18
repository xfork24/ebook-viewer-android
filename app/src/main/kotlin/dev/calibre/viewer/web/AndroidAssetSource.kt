// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// AssetSource over the APK's assets/viewer/ tree, with a small LRU cache:
// index.html and viewer.js are hit on every page (re)load and the sandbox
// iframes re-fetch viewer.js per iframe, while AssetManager re-reads zip
// entries each time. shouldInterceptRequest runs on WebView's IO threads,
// so the cache is synchronized.

package dev.calibre.viewer.web

import android.content.res.AssetManager
import dev.calibre.viewer.serve.AssetSource
import java.io.IOException

class AndroidAssetSource(assetManager: AssetManager) : AssetSource {

    private val assets = assetManager
    private val root = "viewer"

    private val cache = object : LinkedHashMap<String, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean =
            size > MAX_CACHED
    }

    @Synchronized
    override fun read(assetPath: String): ByteArray? {
        cache[assetPath]?.let { return it }
        // Defense in depth: BookServer already traversal-checks; keep the
        // asset path from escaping the viewer/ root regardless of caller.
        val parts = assetPath.replace('\\', '/').split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        val bytes = try {
            assets.open("$root/$assetPath").use { it.readBytes() }
        } catch (e: IOException) {
            return null
        }
        cache[assetPath] = bytes
        return bytes
    }

    private companion object {
        // viewer.js (2.7MB) + map (0.5MB) + index + the mathjax chunks
        // MathJax lazy-loads (startup, loader, core, inputs, output) — the
        // whole vendored tree is 1.3MB, so 12 entries bounds it comfortably.
        const val MAX_CACHED = 12
    }
}
