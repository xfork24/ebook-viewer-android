// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Main-looper TaskScheduler for the bridge watchdog.

package dev.calibre.viewer.web

import android.os.Handler
import android.os.Looper
import dev.calibre.viewer.bridge.BridgeWatchdog

class HandlerTaskScheduler : BridgeWatchdog.TaskScheduler {

    private val handler = Handler(Looper.getMainLooper())

    override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
        val runnable = Runnable(action)
        handler.postDelayed(runnable, delayMs)
        return { handler.removeCallbacks(runnable) }
    }
}
