// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// SAF book → rendered-view directory, the Android equivalent of
// gui2/viewer/ui.py:open_book followed by the web_view render handshake.
//
// Staging: Android gives content:// URIs, not stable paths, and every copy
// gets a fresh mtime — which calculateBookHash mixes into its digest. So a
// book is staged ONCE into filesDir/books/inbox.epub and reused while a
// content fingerprint (sha1 of size + first/last 1MB — the same fields the
// cache hashes, minus mtime) is unchanged. That keeps the staged file's
// mtime stable across re-opens, which is what makes cache.lookup() hit.
//
// Rendering: cache.lookup() → hit means done; miss extracts via EpubInput,
// transforms via RenderBook.render, then cache.commit() moves the transient
// directory into ev2/f/<hash>/.

package dev.calibre.viewer.host

import dev.calibre.viewer.parser.dom.DomParser
import dev.calibre.viewer.parser.epub.EpubInput
import dev.calibre.viewer.parser.render.RenderBook
import dev.calibre.viewer.parser.render.RenderBookCache
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class BookOpener(
    private val cache: RenderBookCache,
    private val booksDir: File,
    private val domParserFactory: () -> DomParser,
    private val log: (String) -> Unit = {},
) {

    data class OpenedBook(
        /** Staged EPUB in app storage — pathtoebook for start_book_load. */
        val sourceFile: File,
        /** Rendered view directory (manifest at its root). */
        val bookDir: File,
        /** Render-cache key — also keys LastReadStore positions. */
        val bookHash: String,
    )

    /**
     * Copy a book into the staging slot via [copy] (the activity drains the
     * SAF stream into the temp file). Reuses the existing staged file when
     * the content fingerprint matches, so unchanged books keep their mtime
     * (and cache entry). The previous copy is only discarded after the new
     * one is fully in place — a disk-full failure mid-restage must not
     * destroy the only local copy of the old book.
     */
    fun stage(copy: (File) -> Unit): File {
        booksDir.mkdirs()
        val inbox = File(booksDir, INBOX_NAME)
        val staged = File(booksDir, STAGED_NAME)
        deleteQuietly(staged)
        try {
            copy(staged)
        } catch (e: Exception) {
            deleteQuietly(staged)
            throw e
        }
        if (!staged.isFile || staged.length() == 0L) {
            deleteQuietly(staged)
            throw IOException("Empty or missing book file")
        }
        val fingerprint = contentFingerprint(staged)
        if (inbox.isFile && readFingerprint() == fingerprint) {
            deleteQuietly(staged)
            return inbox
        }
        val backup = File(booksDir, BACKUP_NAME)
        deleteQuietly(backup)
        val hadOld = inbox.isFile && inbox.renameTo(backup)
        val renamed = staged.renameTo(inbox)
        if (!renamed) {
            try {
                staged.copyTo(inbox, overwrite = true)
                deleteQuietly(staged)
            } catch (e: Exception) {
                if (hadOld && !inbox.isFile) backup.renameTo(inbox)
                deleteQuietly(staged)
                throw e
            }
        }
        deleteQuietly(backup)
        File(booksDir, FINGERPRINT_NAME).writeText(fingerprint)
        return inbox
    }

    /** Resolve a staged book to its rendered directory, rendering if needed. */
    fun open(sourceFile: File, force: Boolean = false): OpenedBook {
        val bookHash = cache.calculateBookHash(sourceFile)
        val existing = if (force) null else cache.lookup(sourceFile)
        val bookDir = existing ?: renderFresh(sourceFile, bookHash)
        return OpenedBook(sourceFile, bookDir, bookHash)
    }

    private fun renderFresh(sourceFile: File, bookHash: String): File {
        val transient = cache.createTransient()
        try {
            val parser = domParserFactory()
            val opfName = EpubInput(parser, log).convert(sourceFile, transient)
            RenderBook.render(
                tdir = transient,
                opfName = opfName,
                parser = parser,
                bookFormat = "application/epub+zip",
                bookHash = bookHash,
            )
            return cache.commit(sourceFile, transient)
        } catch (e: Exception) {
            cache.discardTransient(transient)
            throw e
        }
    }

    /**
     * sha1(size + first 1MB + last 1MB) — RenderBookCache.calculateBookHash
     * over the same byte ranges (mtime deliberately excluded so re-copies of
     * identical content don't invalidate the staged file).
     */
    internal fun contentFingerprint(file: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        val size = file.length()
        md.update(size.toString().toByteArray(Charsets.US_ASCII))
        val oneMb = 1024L * 1024L
        file.inputStream().use { ins ->
            if (size <= oneMb * 2) {
                md.update(ins.readBytes())
            } else {
                val head = ByteArray(oneMb.toInt())
                md.update(readFully(ins, head))
                var toSkip = size - oneMb * 2
                while (toSkip > 0) {
                    val skipped = ins.skip(toSkip)
                    if (skipped <= 0) break
                    toSkip -= skipped
                }
                val tail = ByteArray(oneMb.toInt())
                md.update(readFully(ins, tail))
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readFully(ins: java.io.InputStream, buffer: ByteArray): ByteArray {
        var read = 0
        while (read < buffer.size) {
            val n = ins.read(buffer, read, buffer.size - read)
            if (n <= 0) break
            read += n
        }
        return buffer.copyOf(read)
    }

    private fun readFingerprint(): String? {
        val f = File(booksDir, FINGERPRINT_NAME)
        return if (f.isFile) f.readText().trim().takeIf { it.isNotEmpty() } else null
    }

    private fun deleteQuietly(f: File) {
        if (f.exists() && !f.delete()) f.deleteOnExit()
    }

    private companion object {
        const val INBOX_NAME = "inbox.epub"
        const val STAGED_NAME = "inbox.epub.new"
        const val BACKUP_NAME = "inbox.epub.old"
        const val FINGERPRINT_NAME = "inbox.fingerprint"
    }
}
