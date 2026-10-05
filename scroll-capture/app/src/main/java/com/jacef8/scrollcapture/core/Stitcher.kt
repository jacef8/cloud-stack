package com.jacef8.scrollcapture.core

/** Where finished rows go. Rows are row-major ARGB ints. */
interface RowSink {
    fun append(px: IntArray, width: Int, fromRow: Int, toRow: Int)
}

sealed class Step {
    /**
     * The picture joined. [shift] is how far the page moved (negative: scrolled back up), [offset] is where
     * this picture now sits, and [added] is how many new rows it brought (0 when it showed nothing new).
     */
    data class Added(val shift: Int, val offset: Int, val added: Int) : Step()
    /** The screen did not change. */
    object End : Step()
    /** Frames could not be lined up (the screen changed in some other way). Nothing was added. */
    data class Lost(val offset: Int) : Step()
    /** Could not line up yet; ask again after the screen settles. */
    object Retry : Step()
}

/**
 * Builds one tall image from frames of a scrolling region [top, bottom).
 *
 * The first frame contributes everything down to [bottom] (so a fixed header is kept once). Every
 * later frame is lined up with the one before it, and only the rows BELOW what the image already
 * reaches are added, so scrolling back up and down again never repeats anything. The last frame's rows
 * below [bottom] close the image (a fixed footer, kept once).
 */
class IncrementalStitcher(
    private val w: Int,
    private val h: Int,
    private val top: Int,
    private val bottom: Int,
    private val sink: RowSink?,
) {
    private val regionH = bottom - top
    private var prevPx: IntArray? = null
    private var prevSig: RowSig? = null
    private var hint = regionH * 3 / 4

    /** Where the latest picture sits, measured from where the first one was. */
    var offset = 0
        private set
    /** The content position just below the last row added to the image. */
    private var reach = regionH
    var lost = 0
        private set
    var consecutiveLost = 0
        private set

    fun start(px: IntArray) {
        prevPx = px
        prevSig = Rows.signature(px, w, h)
        sink?.append(px, w, 0, bottom)
    }

    /** [allowBack]: also line the picture up when the page was scrolled back UP since the last one. */
    fun next(px: IntArray, allowLost: Boolean, allowBack: Boolean = false): Step {
        val sig = Rows.signature(px, w, h)
        val before = prevPx
        var s: Int? = null

        var sh = Rows.findShift(prevSig!!, sig, top, bottom, hint)
        if (sh == null && before != null) {
            sh = Rows.findShiftFuzzy(Rows.profile(before, w, h), Rows.profile(px, w, h), top, bottom, hint)
        }
        if (sh != null) s = sh.s

        if (s == null && allowBack && before != null) {
            // Scrolled back up: this picture shows content ABOVE the last one, so it is the last one that
            // has moved up relative to this one.
            var back = Rows.findShift(sig, prevSig!!, top, bottom, hint)
            if (back == null) back = Rows.findShiftFuzzy(Rows.profile(px, w, h), Rows.profile(before, w, h), top, bottom, hint)
            if (back != null && back.s > 0) s = -back.s
        }

        if (s == null) {
            if (!allowLost) return Step.Retry
            lost++
            consecutiveLost++
            return Step.Lost(offset)
        }
        if (s == 0) return Step.End
        return accept(px, sig, s)
    }

    /** Adds [px] when the scrolled distance [shift] is already known (found another way). */
    fun nextWith(px: IntArray, shift: Int): Step = accept(px, Rows.signature(px, w, h), shift)

    private fun accept(px: IntArray, sig: RowSig, s: Int): Step {
        consecutiveLost = 0
        offset += s
        val add = (offset + regionH - reach).coerceIn(0, regionH)
        if (add > 0) {
            sink?.append(px, w, bottom - add, bottom)
            reach = offset + regionH
        }
        if (s > 0) hint = s
        prevPx = px
        prevSig = sig
        return Step.Added(s, offset, add)
    }

    fun finish() {
        val p = prevPx ?: return
        sink?.append(p, w, bottom, h)
    }
}
