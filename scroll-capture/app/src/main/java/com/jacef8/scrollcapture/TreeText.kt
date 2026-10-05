package com.jacef8.scrollcapture

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.jacef8.scrollcapture.core.Line

/** Reads the words an app puts on screen, straight from its accessibility tree. */
object TreeText {
    fun collect(root: AccessibilityNodeInfo): List<Line> {
        val out = ArrayList<Line>()
        val r = Rect()
        var budget = 8000

        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (budget-- <= 0 || depth > 70) return
            if (!n.isVisibleToUser) return
            n.getBoundsInScreen(r)
            if (r.width() <= 0 || r.height() <= 0) return
            if (!n.isPassword && !n.isShowingHintText) {
                val text = n.text?.toString()?.trim().orEmpty()
                val line = if (text.isNotEmpty()) text else describe(n)
                if (!line.isNullOrEmpty()) out.add(Line(line, r.top, r.bottom, r.left))
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                walk(c, depth + 1)
            }
        }
        walk(root, 0)
        return out
    }

    /** Some apps put their words in a description instead of a text field. */
    private fun describe(n: AccessibilityNodeInfo): String? {
        val d = n.contentDescription?.toString()?.trim().orEmpty()
        if (d.isEmpty()) return null
        val cls = n.className?.toString().orEmpty()
        val isImage = cls.contains("ImageView") || cls.contains("ImageButton")
        return if (d.length >= 25 || (!n.isClickable && !isImage)) d else null
    }
}

/** Finds the part of the screen that scrolls up and down, and scrolls it. */
object ScrollTarget {
    fun find(root: AccessibilityNodeInfo, screenH: Int): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestVertical = false
        var bestArea = 0L
        val r = Rect()
        var budget = 8000

        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (budget-- <= 0 || depth > 70) return
            if (!n.isVisibleToUser) return
            if (n.isScrollable) {
                val actions = n.actionList
                val vertical = actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN) ||
                    actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP)
                n.getBoundsInScreen(r)
                val hgt = r.height()
                if (hgt >= screenH * 0.25) {
                    val area = r.width().toLong() * hgt
                    val better = (vertical && !bestVertical) ||
                        (vertical == bestVertical && area >= bestArea)
                    if (better) {
                        best = n
                        bestVertical = vertical
                        bestArea = area
                    }
                }
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                walk(c, depth + 1)
            }
        }
        walk(root, 0)
        return best
    }

    fun scrollDown(n: AccessibilityNodeInfo): Boolean {
        n.refresh()
        val has = n.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN)
        return if (has) {
            n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id)
        } else {
            n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
    }

    fun scrollUp(n: AccessibilityNodeInfo): Boolean {
        n.refresh()
        val has = n.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP)
        return if (has) {
            n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id)
        } else {
            n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        }
    }
}
