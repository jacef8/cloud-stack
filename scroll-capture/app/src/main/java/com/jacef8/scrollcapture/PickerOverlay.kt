package com.jacef8.scrollcapture

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.ContextWrapper
import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** The "what do you want?" sheet shown over whatever app is open. */
class PickerOverlay(
    private val svc: AccessibilityService,
    private val prefs: Prefs,
    private val onPick: (Mode) -> Unit,
    private val onSettings: () -> Unit,
) {
    private var root: View? = null
    private val wm get() = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    val isShowing: Boolean get() = root != null

    fun show() {
        if (root != null) return
        val ctx: Context = ContextThemeWrapper(svc, android.R.style.Theme_DeviceDefault)
        val dp = { v: Float -> Ui.dp(ctx, v) }

        val scrim = FrameLayout(ctx).apply {
            setBackgroundColor(0xB3000000.toInt())
            setOnClickListener { dismiss() }
        }
        val sheet = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.card(ctx)
            setPadding(dp(18f), dp(16f), dp(18f), dp(18f))
            isClickable = true
            elevation = dp(12f).toFloat()
        }

        val head = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.text(ctx, "Capture", 24f, C.INK, true), Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Ui.text(ctx, "Settings", 15f, C.ACCENT, true).apply {
            setPadding(dp(12f), dp(8f), 0, dp(8f))
            setOnClickListener { dismiss(); onSettings() }
        })
        sheet.addView(head)
        sheet.addView(Ui.text(ctx, "Pick what you need from this screen.", 14f, C.INK3).apply {
            setPadding(0, 0, 0, dp(12f))
        })

        fun tile(title: String, sub: String, mode: Mode): View {
            val t = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.widget(ctx)
                setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
                minimumHeight = dp(96f)
                elevation = dp(3f).toFloat()
                setOnClickListener { dismiss(); onPick(mode) }
            }
            t.addView(Ui.text(ctx, title, 17f, C.INK, true))
            t.addView(Ui.text(ctx, sub, 13f, C.INK3))
            return t
        }

        fun rowOf(a: View, b: View): LinearLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(a, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(5f) })
            addView(b, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(5f) })
        }

        sheet.addView(rowOf(
            tile("Screenshot", "Just this screen", Mode.SCREENSHOT),
            tile("Scroll capture", "One long image", Mode.SCROLL),
        ))
        sheet.addView(rowOf(
            tile("Text only", "Every word, copyable", Mode.TEXT),
            tile("Image + text", "Long image and its words", Mode.SCROLL_TEXT),
        ), Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })

        // Segmented control sunk into a well: where scrolling starts.
        val seg = LinearLayout(ctx).apply {
            background = Ui.well(ctx)
            setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
        }
        val optTop = pill(ctx, "From the top")
        val optHere = pill(ctx, "From here")
        fun paint() {
            setPill(optTop, prefs.startFromTop)
            setPill(optHere, !prefs.startFromTop)
        }
        optTop.setOnClickListener { prefs.startFromTop = true; paint() }
        optHere.setOnClickListener { prefs.startFromTop = false; paint() }
        seg.addView(optTop, Ui.lp(0, dp(40f), 1f))
        seg.addView(optHere, Ui.lp(0, dp(40f), 1f))
        paint()

        val segRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14f), 0, 0)
        }
        segRow.addView(Ui.label(ctx, "Long captures start"))
        segRow.addView(seg, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6f)
        })
        sheet.addView(segRow)

        scrim.addView(sheet, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        ).apply {
            leftMargin = dp(12f); rightMargin = dp(12f); bottomMargin = dp(40f)
        })

        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        wm.addView(scrim, lp)
        root = scrim
    }

    fun dismiss() {
        val r = root ?: return
        root = null
        try { wm.removeView(r) } catch (_: Exception) { }
    }

    private fun pill(ctx: Context, label: String): TextView = Ui.text(ctx, label, 15f, C.INK3, true).apply {
        gravity = Gravity.CENTER
    }

    private fun setPill(v: TextView, on: Boolean) {
        v.setTextColor(if (on) C.ON_ACCENT else C.INK3)
        v.background = if (on) Ui.accentFill(v.context, 999f) else null
    }

    @Suppress("unused")
    private fun unwrap(c: Context): Context = if (c is ContextWrapper) c.baseContext else c
}
