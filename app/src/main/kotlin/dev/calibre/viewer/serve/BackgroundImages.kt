// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// reader-background routes, mirroring web_view.py:background_image(). Two
// forms: the raw configured background (bg-image.data = "mime|bytes") and
// named images under <config>/background-images addressed by hex-encoded
// file name. Results are LRU-cached (upstream functools.lru_cache(4))
// because the viewer re-requests the image on every color-scheme change.
// Pure java.io — no android.util.LruCache, so BookServer stays JVM-testable.

package dev.calibre.viewer.serve

import dev.calibre.viewer.parser.epub.MimeTypes
import java.io.File
import java.io.IOException

class BackgroundImages(private val configDir: File) {

    class Image(val mime: String, val bytes: ByteArray)

    private val cache = object : LinkedHashMap<String, Image>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Image>): Boolean =
            size > 4
    }

    @Synchronized
    fun get(encodedFname: String): Image? {
        cache[encodedFname]?.let { return it }
        val image = load(encodedFname) ?: return null
        cache[encodedFname] = image
        return image
    }

    /**
     * Write the raw configured background (bg-image.data = "mime|data") via
     * tmp+rename so a crash mid-write can never leave a truncated file. The
     * cached-empty sentinel must be evicted with it: a get("") before the
     * first store caches load()'s empty Image (missing file returns the
     * sentinel, not null), and without this eviction every later fetch of
     * the raw background would keep 404ing until LRU pressure.
     */
    @Synchronized
    fun store(mime: String, bytes: ByteArray) {
        configDir.mkdirs()
        val mimeBytes = mime.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(mimeBytes.size + 1 + bytes.size)
        mimeBytes.copyInto(payload)
        payload[mimeBytes.size] = '|'.code.toByte()
        bytes.copyInto(payload, mimeBytes.size + 1)
        val target = File(configDir, "bg-image.data")
        val tmp = File(configDir, "bg-image.data.tmp")
        try {
            tmp.writeBytes(payload)
            if (target.isFile) target.delete()
            if (!tmp.renameTo(target)) target.writeBytes(payload)
        } catch (e: IOException) {
            tmp.delete()
            return
        }
        cache.remove("")
    }

    private fun load(encodedFname: String): Image? {
        if (encodedFname.isEmpty()) {
            // bg-image.data: "mime|data" in one file.
            val f = File(configDir, "bg-image.data")
            if (!f.isFile) return Image("image/jpeg", ByteArray(0))
            val raw = try {
                f.readBytes()
            } catch (e: IOException) {
                return Image("image/jpeg", ByteArray(0))
            }
            val sep = raw.indexOf('|'.code.toByte())
            if (sep <= 0) return Image("image/jpeg", ByteArray(0))
            val mime = raw.copyOfRange(0, sep).toString(Charsets.UTF_8)
            return Image(mime, raw.copyOfRange(sep + 1, raw.size))
        }
        // reader-background-<hex of utf-8 file name>
        val fname = hexDecode(encodedFname)?.toString(Charsets.UTF_8) ?: return Image("image/jpeg", ByteArray(0))
        val dir = File(configDir, "background-images")
        val path = SafeBookPath.resolve(dir, fname) ?: return Image("image/jpeg", ByteArray(0))
        val mime = MimeTypes.guessType(fname).takeIf { it != MimeTypes.OCTET_STREAM } ?: "image/jpeg"
        if (!path.isFile) return Image(mime, ByteArray(0))
        val raw = try {
            path.readBytes()
        } catch (e: IOException) {
            return Image(mime, ByteArray(0))
        }
        return Image(mime, raw)
    }

    private fun hexDecode(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val b = s.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            out[i] = b.toByte()
        }
        return out
    }
}
