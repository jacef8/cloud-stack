package com.jacef8.scrollcapture.core

/**
 * Lets the person drive a scroll capture: hold the scroll button to keep scrolling, let go to pause,
 * press again to carry on, and tap Done (or leave it idle) to finish. Letting go never ends the capture.
 */
class SessionControl(private val clock: () -> Long = { System.currentTimeMillis() }) {
    @Volatile var done = false
        private set
    @Volatile var holding = false
        private set
    private var pending = 0
    private var lastActive = clock()

    /** The scroll button went down: one step at least, and steps keep coming while it stays down. */
    @Synchronized fun press() {
        holding = true
        pending++
        lastActive = clock()
    }

    /** The scroll button came up: pause. This is not the end. */
    @Synchronized fun release() {
        holding = false
        lastActive = clock()
    }

    fun finish() { done = true }

    /** The step that started the session has been done, so it is not owed again. */
    @Synchronized fun consumeInitial() {
        if (pending > 0) pending--
        lastActive = clock()
    }

    /**
     * Waits until another step is wanted. Returns false when the person is done, or has left it idle
     * for [idleMs] (so a forgotten capture is saved rather than lost).
     */
    fun waitForStep(idleMs: Long, sleepMs: Long = 60): Boolean {
        while (true) {
            if (done) return false
            synchronized(this) {
                if (pending > 0) { pending--; lastActive = clock(); return true }
                if (holding) { lastActive = clock(); return true }
                if (clock() - lastActive > idleMs) return false
            }
            Thread.sleep(sleepMs)
        }
    }
}
