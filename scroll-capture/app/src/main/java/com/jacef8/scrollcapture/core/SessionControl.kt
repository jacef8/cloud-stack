package com.jacef8.scrollcapture.core

/**
 * Lets the person drive a scroll capture: hold the scroll button to keep scrolling, let go to pause,
 * press again to carry on, and tap Done (or leave it idle) to finish. Letting go never ends the capture.
 */
enum class Trigger {
    /** The scroll button was pressed or is being held: scroll one more step. */
    STEP,
    /** The person scrolled by hand and the page has stopped moving: take a picture and add what is new. */
    SETTLED,
    DONE,
    IDLE,
}

class SessionControl(private val clock: () -> Long = { System.currentTimeMillis() }) {
    private var lastScroll = 0L

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

    /** The page just scrolled (by hand or otherwise): remember when, so we can wait for it to stop. */
    @Synchronized fun noteScroll() {
        lastScroll = clock()
        lastActive = lastScroll
    }

    /** A picture was just taken, so scrolling seen up to now has been dealt with. */
    @Synchronized fun clearScroll() { lastScroll = 0 }

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

    /**
     * Waits for the next thing to do: a step the button asks for, a hand-scroll that has settled for
     * [settleMs], Done, or [idleMs] with nothing happening (so a forgotten capture is saved, not lost).
     */
    fun awaitTrigger(settleMs: Long, idleMs: Long, sleepMs: Long = 50): Trigger {
        while (true) {
            if (done) return Trigger.DONE
            synchronized(this) {
                if (pending > 0) { pending--; lastActive = clock(); return Trigger.STEP }
                if (holding) { lastActive = clock(); return Trigger.STEP }
                if (lastScroll != 0L && clock() - lastScroll >= settleMs) {
                    lastScroll = 0
                    lastActive = clock()
                    return Trigger.SETTLED
                }
                if (clock() - lastActive > idleMs) return Trigger.IDLE
            }
            Thread.sleep(sleepMs)
        }
    }
}
