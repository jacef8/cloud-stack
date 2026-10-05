package com.jacef8.scrollcapture.core

import kotlin.math.abs

/**
 * Works out how far a page scrolled from where its words sit on screen, when matching the pictures
 * row by row cannot (a playing video, images still loading, sub-pixel drawing). The same words at
 * the same left edge, just lower or higher, give the distance.
 */
object TreeAlign {
    /** Returns how far the content moved up, or null when the words do not agree on one distance. */
    fun shift(prev: List<Line>, cur: List<Line>, top: Int, bottom: Int, minShift: Int, maxShift: Int): Int? {
        val before = prev.filter { it.text.length >= 3 }.groupBy { it.text }
        val votes = HashMap<Int, Int>()
        for (c in cur) {
            if (c.text.length < 3) continue
            val matches = before[c.text] ?: continue
            for (p in matches) {
                if (abs(p.left - c.left) > 6) continue
                // A tall post can run off the screen: use whichever of its edges is inside the area in both pictures.
                val d = when {
                    p.top in top..bottom && c.top in top..bottom -> p.top - c.top
                    p.bottom in top..bottom && c.bottom in top..bottom -> p.bottom - c.bottom
                    else -> continue
                }
                if (d in minShift..maxShift) votes.merge(d, 1, Int::plus)
            }
        }
        if (votes.isEmpty()) return null
        // Group distances within a couple of pixels, and take the group with the most words behind it.
        fun weight(d: Int) = (d - 2..d + 2).sumOf { votes[it] ?: 0 }
        val best = votes.keys.maxWithOrNull(compareBy({ weight(it) }, { votes[it] ?: 0 })) ?: return null
        val bestWeight = weight(best)
        val runnerUp = votes.keys.filter { abs(it - best) > 2 }.maxOfOrNull { weight(it) } ?: 0
        if (bestWeight < 2 || bestWeight < runnerUp * 2) return null
        // The exact distance is the most voted one inside the winning group.
        return (best - 2..best + 2).maxByOrNull { votes[it] ?: 0 }
    }
}
