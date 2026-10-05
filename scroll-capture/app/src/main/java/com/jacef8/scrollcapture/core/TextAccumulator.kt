package com.jacef8.scrollcapture.core

import kotlin.math.abs

/** One piece of on-screen text and where it sat in that frame (screen pixels). */
data class Line(val text: String, val top: Int, val bottom: Int, val left: Int)

/**
 * Collects the text of every frame into one document, in reading order, with
 * nothing repeated where frames overlap.
 *
 * When the scroll distance between frames is known ([add] with an offset), a
 * line is the same line if it has the same words at the same place on the page.
 * When the distance is unknown, it falls back to matching the end of what is
 * collected against the start of the new frame.
 */
class TextAccumulator {
    private class Row(val text: String, var top: Int, var bottom: Int, val left: Int)

    private val rows = ArrayList<Row>()
    private var sequential = false

    val isEmpty: Boolean get() = rows.isEmpty()

    fun add(lines: List<Line>, offset: Int?) {
        if (offset == null) sequential = true
        if (!sequential) {
            for (l in lines) {
                val t = l.top + offset!!
                if (rows.none { it.text == l.text && abs(it.top - t) <= TOLERANCE }) {
                    rows.add(Row(l.text, t, l.bottom + offset, l.left))
                }
            }
        } else {
            val sorted = lines.sortedWith(compareBy({ it.top }, { it.left }))
            val maxK = minOf(rows.size, sorted.size)
            var k = maxK
            while (k > 0) {
                var same = true
                for (i in 0 until k) {
                    if (rows[rows.size - k + i].text != sorted[i].text) { same = false; break }
                }
                if (same) break
                k--
            }
            var y = (rows.maxOfOrNull { it.bottom } ?: 0) + 1
            for (i in k until sorted.size) {
                val l = sorted[i]
                val hgt = (l.bottom - l.top).coerceAtLeast(1)
                rows.add(Row(l.text, y, y + hgt, l.left))
                y += hgt + 4
            }
        }
    }

    /** The collected text, one visual row per line (a timestamp and its words share a line). */
    fun render(): String {
        val sorted = rows.sortedWith(compareBy({ it.top }, { it.left }))
        val sb = StringBuilder()
        var lastTop = 0
        var lastBottom = 0
        var first = true
        for (r in sorted) {
            if (first) {
                sb.append(r.text)
                first = false
            } else {
                val overlap = minOf(lastBottom, r.bottom) - maxOf(lastTop, r.top)
                val smaller = minOf(lastBottom - lastTop, r.bottom - r.top).coerceAtLeast(1)
                if (overlap * 2 >= smaller) sb.append(' ') else sb.append('\n')
                sb.append(r.text)
            }
            lastTop = r.top
            lastBottom = r.bottom
        }
        return sb.toString()
    }

    private companion object {
        const val TOLERANCE = 10
    }
}
