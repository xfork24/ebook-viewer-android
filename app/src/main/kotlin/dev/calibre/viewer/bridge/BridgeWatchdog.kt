// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Watchdog polling for the title-ping bridge. OnReceivedTitle is the primary
// trigger, but old Android WebViews may coalesce identical title changes;
// python_comm._poll() is idempotent (collect-and-clear) and costs nothing
// when the queue is empty, so a periodic poll is a safe backstop.
//
// Pacing: 120ms while traffic is flowing (matches the plan's watchdog
// budget), backing off to 1.2s after ~3s of consecutive empty polls, and
// tightening again the moment a poll returns messages. The scheduler seam
// keeps this JVM-testable — the shell passes a Handler-based implementation.

package dev.calibre.viewer.bridge

class BridgeWatchdog(
    private val poll: () -> Unit,
    private val scheduler: TaskScheduler,
) {

    /** schedule(action, delayMs) → cancel; implementations must run [action] on the main thread. */
    fun interface TaskScheduler {
        fun schedule(delayMs: Long, action: () -> Unit): () -> Unit
    }

    private var running = false
    private var emptyStreak = 0
    private var cancelNext: (() -> Unit)? = null

    fun start() {
        if (running) return
        running = true
        scheduleTick()
    }

    fun stop() {
        running = false
        cancelNext?.invoke()
        cancelNext = null
    }

    /** Called by the bridge after every poll with whether the queue was empty. */
    fun recordPoll(empty: Boolean) {
        emptyStreak = if (empty) emptyStreak + 1 else 0
    }

    private fun scheduleTick() {
        val delay = if (emptyStreak >= SLOW_AFTER_EMPTY) SLOW_INTERVAL_MS else FAST_INTERVAL_MS
        cancelNext = scheduler.schedule(delay) {
            if (running) {
                poll()
                scheduleTick()
            }
        }
    }

    companion object {
        const val FAST_INTERVAL_MS = 120L
        const val SLOW_INTERVAL_MS = 1_200L

        /** ~3s of empty fast polls before backing off. */
        const val SLOW_AFTER_EMPTY = 25
    }
}
