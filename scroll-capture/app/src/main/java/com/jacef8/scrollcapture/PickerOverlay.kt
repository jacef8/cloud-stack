package com.jacef8.scrollcapture

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The "what do you want?" bar. It is a slim strip, with no dimming, and touches
 * outside it go straight through to the app underneath, so the screen can still be
 * scrolled and lined up before choosing.
 */
class PickerOverlay(
    private val svc: AccessibilityService,
    private val prefs: Prefs,
    private val onPick: (Mode) -> Unit,
    private val onSettings: () -> Unit,
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
            orientation = LinearLayout.VERTICAL
            background = Ui.card(ctx)
            setPadding(dp(10f), dp(10f), dp(10f), dp(6f))
            elevation = dp(10f).toFloat()
        }

        fun tile(title: String, sub: String, mode: Mode): View {
            val t = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = Ui.widget(ctx, 12f)
                setPadding(dp(4f), dp(8f), dp(4f), dp(8f))
                elevation = dp(2f).toFloat()
                setOnClickListener { dismiss(); onPick(mode) }
            }
            t.addView(Ui.text(ctx, title, 15f, C.INK, true).apply { gravity = Gravity.CENTER })
            t.addView(Ui.text(ctx, sub, 12f, C.INK3).apply { gravity = Gravity.CENTER })
            return t
        }

        val tiles = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val entries = listOf(
            Triple("Screenshot", "this screen", Mode.SCREENSHOT),
            Triple("Scroll", "long image", Mode.SCROLL),
            Triple("Text", "every word", Mode.TEXT),
            Triple("Both", "image + text", Mode.SCROLL_TEXT),
        )
        entries.forEachIndexed { i, (title, sub, mode) ->
            tiles.addView(tile(title, sub, mode), Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) leftMargin = dp(6f)
            })
        }
        bar.addView(tiles)

        // Small controls underneath.
        val controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun chip(label: String, color: Int = C.ACCENT, onClick: (TextView) -> Unit): TextView =
            Ui.text(ctx, label, 14f, color, true).apply {
                setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
                setOnClickListener { onClick(this) }
            }

        fun startLabel() = if (prefs.startFromTop) "Starts: from the top" else "Starts: from here"
        controls.addView(chip(startLabel()) { v ->
            prefs.startFromTop = !prefs.startFromTop
            v.text = startLabel()
        })
        controls.addView(View(ctx), Ui.lp(0, 1, 1f))
        controls.addView(chip("Settings", C.INK2) { dismiss(); onSettings() })
        controls.addView(chip("Move") { move(it) })
        controls.addView(chip("Close", C.INK2) { dismiss() })
        bar.addView(controls)

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
            x = 0
            y = dp(28f)
        }
        // Side margins come from wrapping the bar so the window itself stays tight to its content.
        val holder = LinearLayout(ctx).apply {
            setPadding(dp(10f), 0, dp(10f), 0)
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        wm.addView(holder, lp)
        root = holder
    }

    /** Swap between the bottom and the top of the screen, whichever leaves the content visible. */
    private fun move(@Suppress("UNUSED_PARAMETER") v: View) {
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
