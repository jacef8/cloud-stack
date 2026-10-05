package com.jacef8.scrollcapture.core

enum class Trigger {
    /** The scroll button was pressed or is being held: scroll one more step. */
    STEP,
    /** The page is scrolling (the person is swiping): take another picture now. */
    MOTION,
    DONE,
    IDLE,
}

/**
 * Lets the person drive a scroll capture: tap the scroll button for a step (hold it to keep going),
 * or swipe the page by hand, and tap Done (or leave it idle) to finish. Letting go never ends it.
 */
class SessionControl(private val clock: () -> Long = { System.currentTimeMillis() }) {
    @Volatile var done = false
        private set
    @Volatile var holding = false
        private set
    /** How many scroll events were heard (for working out what happened afterwards). */
    @Volatile var scrollEvents = 0
        private set
    private var pending = 0
    private var lastActive = clock()
    private var lastScroll = 0L
    private var lastGrab = 0L

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

    /** The page just scrolled (by hand or otherwise). */
    @Synchronized fun noteScroll() {
        scrollEvents++
        lastScroll = clock()
        lastActive = lastScroll
    }

    /** A picture was taken that started at [startedAt]; any scrolling after that is still to be caught. */
    @Synchronized fun markGrab(startedAt: Long = clock()) { lastGrab = startedAt }

    /** True when the page has scrolled since the last picture was taken. */
    @Synchronized fun motionPending(): Boolean = lastScroll > lastGrab

    fun now(): Long = clock()

    /**
     * Waits for the next thing to do: a step the button asks for, the page scrolling since the last
     * picture (a swipe by hand), Done, or [idleMs] with nothing happening (so a forgotten capture is
     * saved, not lost).
     */
    fun awaitTrigger(idleMs: Long, sleepMs: Long = 40): Trigger {
        while (true) {
            if (done) return Trigger.DONE
            synchronized(this) {
                if (pending > 0) { pending--; lastActive = clock(); return Trigger.STEP }
                if (holding) { lastActive = clock(); return Trigger.STEP }
                if (lastScroll > lastGrab) { lastActive = clock(); return Trigger.MOTION }
                if (clock() - lastActive > idleMs) return Trigger.IDLE
            }
            Thread.sleep(sleepMs)
        }
    }
}
