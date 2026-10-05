package com.jacef8.scrollcapture

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The capture toolbar: a slim dark rounded bar of icons, like the screenshot toolbar
 * Samsung shows. There is no dimming, and touches outside it go straight through to the
 * app underneath, so the screen can still be scrolled and lined up before choosing.
 */
class PickerOverlay(
    private val svc: AccessibilityService,
    private val prefs: Prefs,
    private val onPick: (Mode) -> Unit,
) {
    private var root: View? = null
    private var atTop = false
    private val wm get() = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    val isShowing: Boolean get() = root != null

    fun show() {
        if (root != null) return
        val ctx: Context = ContextThemeWrapper(svc, android.R.style.Theme_DeviceDefault)
        val dp = { v: Float -> Ui.dp(ctx, v) }

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(0xEB1B1D22.toInt())
                cornerRadius = dp(28f).toFloat()
                setStroke(dp(1f), C.EDGE_HI)
            }
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            elevation = dp(10f).toFloat()
        }

        fun item(icon: Int, label: String, onClick: (ImageView, TextView) -> Unit): Triple<View, ImageView, TextView> {
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(7f), 0, dp(6f))
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
                        setColor(0x33FFFFFF)
                        cornerRadius = dp(22f).toFloat()
                    })
                    addState(intArrayOf(), ColorDrawable(0))
                }
                contentDescription = label
            }
            val img = ImageView(ctx).apply { setImageResource(icon) }
            col.addView(img, LinearLayout.LayoutParams(dp(26f), dp(26f)))
            val txt = Ui.text(ctx, label, 12f, C.INK2, false).apply { gravity = Gravity.CENTER; maxLines = 1 }
            col.addView(txt)
            col.setOnClickListener { onClick(img, txt) }
            return Triple(col, img, txt)
        }

        fun add(t: Triple<View, ImageView, TextView>) {
            bar.addView(t.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        add(item(R.drawable.ic_cap_screen, "Screen") { _, _ -> dismiss(); onPick(Mode.SCREENSHOT) })
        add(item(R.drawable.ic_cap_scroll, "Scroll") { _, _ -> dismiss(); onPick(Mode.SCROLL) })
        add(item(R.drawable.ic_cap_text, "Text") { _, _ -> dismiss(); onPick(Mode.TEXT) })
        add(item(R.drawable.ic_cap_both, "Both") { _, _ -> dismiss(); onPick(Mode.SCROLL_TEXT) })

        bar.addView(View(ctx).apply { setBackgroundColor(0x33FFFFFF) },
            LinearLayout.LayoutParams(dp(1f), dp(30f)).apply { leftMargin = dp(2f); rightMargin = dp(2f) })

        // Where long captures start: tapping flips between the two.
        val start = item(
            if (prefs.startFromTop) R.drawable.ic_cap_top else R.drawable.ic_cap_here,
            if (prefs.startFromTop) "Top" else "Here",
        ) { img, txt ->
            prefs.startFromTop = !prefs.startFromTop
            img.setImageResource(if (prefs.startFromTop) R.drawable.ic_cap_top else R.drawable.ic_cap_here)
            txt.text = if (prefs.startFromTop) "Top" else "Here"
        }
        add(start)
        add(item(R.drawable.ic_cap_move, "Move") { _, _ -> move() })
        add(item(R.drawable.ic_cap_close, "Close") { _, _ -> dismiss() })

        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Not focusable and not touch-modal: everything outside the bar still reaches the app below.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            y = dp(28f)
        }
        // Side margins come from a wrapper so the window itself stays tight to its content.
        val holder = LinearLayout(ctx).apply {
            setPadding(dp(10f), 0, dp(10f), 0)
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        wm.addView(holder, lp)
        root = holder
    }

    /** Swap between the bottom and the top of the screen, whichever leaves the content visible. */
    private fun move() {
        val r = root ?: return
        val ctx = r.context
        atTop = !atTop
        val lp = r.layoutParams as WindowManager.LayoutParams
        lp.gravity = if (atTop) Gravity.TOP else Gravity.BOTTOM
        lp.y = if (atTop) Ui.dp(ctx, 40f) else Ui.dp(ctx, 28f)
        try { wm.updateViewLayout(r, lp) } catch (_: Exception) { }
    }

    fun dismiss() {
        val r = root ?: return
        root = null
        try { wm.removeView(r) } catch (_: Exception) { }
    }
}
