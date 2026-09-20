// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Shared durability helpers for the five JSON stores. Every store writes
// user data (bookmarks, highlights, reading positions, session settings)
// to flash on a device class where the process can be killed at any
// instant (LMK on 1-2GB e-ink readers) — an in-place `writeText` truncates
// the file first, so a kill mid-write leaves a partial file that parses as
// corrupt on next launch and the store would silently wipe it. Writing
// through a tmp file + rename makes the on-disk file always be either the
// old or the new complete content; quarantining a file that fails to parse
// (instead of just ignoring it) preserves the damaged original so a later
// read-modify-write cannot compound the loss.

package dev.calibre.viewer.core.internal

import java.io.File
import java.util.logging.Logger

private val LOG = Logger.getLogger("dev.calibre.viewer.core.stores")

/**
 * Atomically replace [file]'s content with [text]: write a sibling tmp
 * file, then rename over the target. POSIX rename within a directory is
 * atomic, so a process kill at any point leaves the target holding either
 * the complete old or the complete new content — never a truncation.
 * Falls back to an in-place write only if the rename itself fails (e.g. a
 * vendor filesystem without rename support), matching the old behavior.
 */
internal fun atomicWriteText(file: File, text: String) {
    val dir = file.parentFile
    dir?.mkdirs()
    val tmp = File(dir, file.name + ".tmp")
    try {
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
    } catch (e: Exception) {
        tmp.delete()
        throw e
    }
}

/**
 * Quarantine a store file that failed to load: rename it to
 * `<name>.corrupt` (replacing any earlier quarantine so at most one is
 * kept) and return an empty marker so the caller can start fresh. The
 * damaged original survives on disk — the next save writes a fresh file
 * over the target while the user's previous data stays recoverable in the
 * quarantine copy instead of being silently overwritten from empty.
 */
internal fun quarantineCorrupt(file: File) {
    if (!file.isFile) return
    val corrupt = File(file.parentFile, file.name + ".corrupt")
    try {
        corrupt.delete()
        if (!file.renameTo(corrupt)) {
            file.delete()
        }
        LOG.warning("store file failed to parse; quarantined as ${corrupt.name}")
    } catch (e: Exception) {
        // Quarantine is best-effort; the caller already degrades to empty.
    }
}
