package com.jacef8.scrollcapture.core

/** Where finished rows go. Rows are row-major ARGB ints. */
interface RowSink {
    fun append(px: IntArray, width: Int, fromRow: Int, toRow: Int)
}

sealed class Step {
    /** New content was found; [offset] is the total scrolled distance so far. */
    data class Added(val shift: Int, val offset: Int) : Step()
    /** The screen did not change: the end of the content. */
    object End : Step()
    /** Frames could not be lined up (the screen changed in some other way). Nothing was added. */
    data class Lost(val offset: Int) : Step()
    /** Could not line up yet; ask again after the screen settles. */
    object Retry : Step()
}

/**
 * Builds one tall image from frames of a scrolling region [top, bottom).
 * The first frame contributes everything down to [bottom] (so a fixed header is
 * kept once), each later frame only its newly revealed rows, and the last
 * frame's rows below [bottom] close the image (a fixed footer, kept once).
 */
class IncrementalStitcher(
    private val w: Int,
    private val h: Int,
    private val top: Int,
    private val bottom: Int,
    private val sink: RowSink?,
) {
    private var prevPx: IntArray? = null
    private var prevSig: RowSig? = null
    private var hint = (bottom - top) * 3 / 4

    var offset = 0
        private set
    var lost = 0
        private set
    var consecutiveLost = 0
        private set

    fun start(px: IntArray) {
        prevPx = px
        prevSig = Rows.signature(px, w, h)
        sink?.append(px, w, 0, bottom)
    }

    fun next(px: IntArray, allowLost: Boolean): Step {
        val sig = Rows.signature(px, w, h)
        val sh = Rows.findShift(prevSig!!, sig, top, bottom, hint)
        if (sh == null) {
            if (!allowLost) return Step.Retry
            lost++
            consecutiveLost++
            return Step.Lost(offset)
        }
        if (sh.s == 0) return Step.End
        consecutiveLost = 0
        sink?.append(px, w, bottom - sh.s, bottom)
        offset += sh.s
        hint = sh.s
        prevPx = px
        prevSig = sig
        return Step.Added(sh.s, offset)
    }

    /** Adds [px] when the scrolled distance [shift] is already known (found from the words on screen). */
    fun nextWith(px: IntArray, shift: Int): Step {
        consecutiveLost = 0
        sink?.append(px, w, bottom - shift, bottom)
        offset += shift
        hint = shift
        prevPx = px
        prevSig = Rows.signature(px, w, h)
        return Step.Added(shift, offset)
    }

    fun finish() {
        val p = prevPx ?: return
        sink?.append(p, w, bottom, h)
    }
}
