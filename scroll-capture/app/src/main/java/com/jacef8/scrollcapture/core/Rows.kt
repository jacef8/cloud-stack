package com.jacef8.scrollcapture.core

import kotlin.math.abs

/** One hash per pixel row, plus whether the row has any detail (more than one colour). */
class RowSig(val hash: LongArray, val info: BooleanArray) {
    val height: Int get() = hash.size
}

/** Content moved up by [s] rows: next[r] == prev[r + s]. */
data class Shift(val s: Int, val matches: Int, val informative: Int)

object Rows {
    /** Signature of a full frame (row-major ARGB). The scrollbar edge is left out. */
    fun signature(px: IntArray, width: Int, height: Int): RowSig {
        val x0 = width / 50
        val x1 = width - width / 25
        val hash = LongArray(height)
        val info = BooleanArray(height)
        for (y in 0 until height) {
            val base = y * width
            val first = px[base + x0]
            var h = -3750763034362895579L
            var distinct = false
            for (x in x0 until x1) {
                val p = px[base + x]
                if (p != first) distinct = true
                h = (h xor p.toLong()) * 1099511628211L
            }
            hash[y] = h
            info[y] = distinct
        }
        return RowSig(hash, info)
    }

    /**
     * How far the content of rows [top, bottom) moved up between [a] and [b].
     * Returns shift 0 when the two frames are the same (nothing left to scroll),
     * or null when no shift lines up well enough to trust.
     */
    fun findShift(a: RowSig, b: RowSig, top: Int, bottom: Int, hint: Int): Shift? {
        val h = bottom - top
        if (h < 40) return null

        val pref = IntArray(h + 1)
        for (i in 0 until h) pref[i + 1] = pref[i] + if (b.info[top + i]) 1 else 0
        val totalInfo = pref[h]
        if (totalInfo == 0) return null

        var same = 0
        for (r in top until bottom) if (b.info[r] && b.hash[r] == a.hash[r]) same++
        if (same >= totalInfo * 0.97) return Shift(0, same, totalInfo)

        val minOverlap = maxOf(40, h / 8)
        var bestS = -1
        var bestM = -1
        for (s in 1..(h - minOverlap)) {
            var m = 0
            val end = bottom - s
            var r = top
            while (r < end) {
                if (b.info[r] && b.hash[r] == a.hash[r + s]) m++
                r++
            }
            if (m > bestM || (m == bestM && abs(s - hint) < abs(bestS - hint))) {
                bestM = m
                bestS = s
            }
        }
        if (bestS < 0) return null
        val overlapInfo = pref[h - bestS]
        val ok = bestM >= 6 && bestM >= overlapInfo * 0.85
        return if (ok) Shift(bestS, bestM, overlapInfo) else null
    }

    /**
     * The band of rows that changed between two frames (the part that scrolled).
     * Used when the app gives no scroll area. Returns [top, bottom) or null.
     */
    fun movingRegion(a: RowSig, b: RowSig): IntArray? {
        val n = minOf(a.height, b.height)
        var bestLo = -1
        var bestHi = -1
        var lo = -1
        var hi = -1
        val maxGap = 40
        for (r in 0 until n) {
            if (a.hash[r] != b.hash[r]) {
                if (lo < 0) {
                    lo = r
                } else if (r - hi > maxGap) {
                    if (bestLo < 0 || hi - lo > bestHi - bestLo) { bestLo = lo; bestHi = hi }
                    lo = r
                }
                hi = r
            }
        }
        if (lo >= 0 && (bestLo < 0 || hi - lo > bestHi - bestLo)) { bestLo = lo; bestHi = hi }
        if (bestLo < 0) return null
        return intArrayOf(bestLo, bestHi + 1)
    }
}
