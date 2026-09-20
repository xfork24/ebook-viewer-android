// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// A stream the host hands to Chromium and can still take back. close() is
// idempotent and thread-safe: the browser side closes the streams it drains,
// the reclaim path (BookServer.setBook / closeAllStreams) closes the ones it
// never did, and the two can race — a double close must be a no-op, not a
// second fd free. Reads after close throw the underlying stream's
// IOException, which surfaces inside Chromium's reader thread as an aborted
// fetch (console noise at worst) and never as a host error path.

package dev.calibre.viewer.serve

import java.io.FilterInputStream
import java.io.InputStream

class CloseOnceInputStream(
    delegate: InputStream,
    private val onClose: (CloseOnceInputStream) -> Unit = {},
) : FilterInputStream(delegate) {

    private val lock = Any()
    private var closed = false

    /** True once [close] ran — the reclaim path and tests poll this. */
    val isClosed: Boolean get() = synchronized(lock) { closed }

    override fun close() {
        val doClose = synchronized(lock) {
            if (closed) {
                false
            } else {
                closed = true
                true
            }
        }
        if (!doClose) return
        try {
            super.close()
        } finally {
            onClose(this)
        }
    }
}
