package com.jacef8.scrollcapture.core

import kotlin.math.abs

/** One hash per pixel row, plus whether the row has any detail (more than one colour). */
class RowSig(val hash: LongArray, val info: BooleanArray) {
    val height: Int get() = hash.size
}

/** Content moved up by [s] rows: next[r] == prev[r + s]. */
data class Shift(val s: Int, val matches: Int, val informative: Int)

/** A short fingerprint of each row (average brightness across 24 slices), for matching that tolerates small differences. */
class RowProfile(val data: ByteArray, val info: BooleanArray, val buckets: Int)

object Rows {
    /** Details of the latest alignment attempt, for the log when it fails. */
    @Volatile var lastNote: String = ""

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
        val counts = IntArray(h - minOverlap + 1)
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
            counts[s] = m
            if (m > bestM || (m == bestM && abs(s - hint) < abs(bestS - hint))) {
                bestM = m
                bestS = s
            }
        }
        if (bestS < 0) return null
        // The best match must clearly beat every other position (not just the one next to it).
        var runnerUp = 0
        for (s in 1..(h - minOverlap)) if (abs(s - bestS) > 2 && counts[s] > runnerUp) runnerUp = counts[s]
        val overlapInfo = pref[h - bestS]
        lastNote = "shift=$bestS matches=$bestM of $overlapInfo runnerUp=$runnerUp region=$top-$bottom"
        // A video, ad or counter animating on screen costs matches (and its rows still count as detail),
        // so the share of rows is not used. What matters is how many rows lined up and that this
        // position stands clearly apart from every other one.
        val ok = bestM >= maxOf(12, h / 25) && bestM >= runnerUp * 1.5
        return if (ok) Shift(bestS, bestM, overlapInfo) else null
    }

    fun profile(px: IntArray, width: Int, height: Int): RowProfile {
        val buckets = 24
        val x0 = width / 50
        val x1 = width - width / 25
        val bw = (x1 - x0) / buckets
        val data = ByteArray(height * buckets)
        val info = BooleanArray(height)
        for (y in 0 until height) {
            var mn = 255
            var mx = 0
            val base = y * width + x0
            for (k in 0 until buckets) {
                var sum = 0
                val start = base + k * bw
                for (i in 0 until bw) {
                    val p = px[start + i]
                    sum += (((p shr 16) and 255) * 77 + ((p shr 8) and 255) * 150 + (p and 255) * 29) shr 8
                }
                val v = sum / bw
                data[y * buckets + k] = v.toByte()
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
            info[y] = mx - mn > 12
        }
        return RowProfile(data, info, buckets)
    }

    /**
     * Like [findShift] but a row counts as the same when its brightness profile is close (about 3 levels on
     * average), so a page that redraws slightly differently when it moves (sub-pixel positions, smoothing)
     * can still be lined up.
     */
    fun findShiftFuzzy(a: RowProfile, b: RowProfile, top: Int, bottom: Int, hint: Int): Shift? {
        val h = bottom - top
        if (h < 40) return null
        val nb = a.buckets
        val tolerance = nb * 3
        val minOverlap = maxOf(40, h / 8)
        val counts = IntArray(h - minOverlap + 1)
        var bestS = -1
        var bestM = -1
        for (s in 1..(h - minOverlap)) {
            var m = 0
            var r = top
            val end = bottom - s
            while (r < end) {
                if (b.info[r] && a.info[r + s]) {
                    var d = 0
                    var k = 0
                    val ib = r * nb
                    val ia = (r + s) * nb
                    while (k < nb) {
                        d += abs((b.data[ib + k].toInt() and 255) - (a.data[ia + k].toInt() and 255))
                        if (d > tolerance) break
                        k++
                    }
                    if (d <= tolerance) m++
                }
                r++
            }
            counts[s] = m
            if (m > bestM || (m == bestM && abs(s - hint) < abs(bestS - hint))) {
                bestM = m
                bestS = s
            }
        }
        if (bestS < 0) return null
        var runnerUp = 0
        for (s in 1..(h - minOverlap)) if (abs(s - bestS) > 2 && counts[s] > runnerUp) runnerUp = counts[s]
        lastNote += "; loose: shift=$bestS matches=$bestM runnerUp=$runnerUp"
        val ok = bestM >= maxOf(12, h / 25) && bestM >= runnerUp * 1.5
        return if (ok) Shift(bestS, bestM, bestM) else null
    }

    /**
     * How much of the middle of the region is exactly the same in both pictures, 0..1. The top and
     * bottom tenth are left out (headers, bars and our own small pill change without the page moving).
     */
    fun unchangedShare(a: RowSig, b: RowSig, top: Int, bottom: Int): Float {
        val margin = (bottom - top) / 10
        var info = 0
        var same = 0
        for (r in top + margin until bottom - margin) {
            if (!b.info[r]) continue
            info++
            if (b.hash[r] == a.hash[r]) same++
        }
        return if (info == 0) 1f else same.toFloat() / info
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
