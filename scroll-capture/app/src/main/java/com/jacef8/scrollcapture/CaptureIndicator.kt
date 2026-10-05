package com.jacef8.scrollcapture

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A small "Capturing…" pill shown near the top while a scroll capture runs, so it is obvious
 * something is happening. Tap it to stop. It sits above the scrolling area and is only added after
 * the first frame (it is hidden while that picture is taken), so it never ends up in the finished image.
 */
class CaptureIndicator(
    private val svc: AccessibilityService,
    private val onStop: () -> Unit,
) {
    private var view: LinearLayout? = null
    private var labelView: TextView? = null
    private var hiddenForPicture = false
    private val wm get() = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /** Shows the pill with [label], or changes its text if it is already up. */
    fun show(label: String) {
        view?.let {
            labelView?.text = label
            it.visibility = if (hiddenForPicture) android.view.View.INVISIBLE else android.view.View.VISIBLE
            return
        }
        val ctx = ContextThemeWrapper(svc, android.R.style.Theme_DeviceDefault)
        val dp = { v: Float -> Ui.dp(ctx, v) }
        val text = TextView(ctx).apply {
            this.text = label
            textSize = 14f
            setTextColor(C.INK)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        }
        // A clear Done button: the capture ends when you say so, and everything so far is saved.
        val done = TextView(ctx).apply {
            this.text = "Done ✓"
            textSize = 14f
            setTextColor(0xFF0B0C0E.toInt())
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            background = GradientDrawable().apply {
                setColor(C.ACCENT)
                cornerRadius = dp(18f).toFloat()
            }
            setOnClickListener { onStop() }
        }
        val tv = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(6f), dp(6f), dp(6f))
            background = GradientDrawable().apply {
                setColor(0xE6141518.toInt())
                cornerRadius = dp(24f).toFloat()
                setStroke(dp(1f), C.ACCENT)
            }
            elevation = dp(6f).toFloat()
            addView(text)
            addView(done, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(14f) })
        }
        labelView = text
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(44f)
        }
        try {
            wm.addView(tv, lp)
            view = tv
        } catch (e: Exception) {
            DebugLog.error("indicator", e)
        }
    }

    /** Hide or show it without removing it, so it can be kept out of one picture. */
    fun setVisible(visible: Boolean) {
        hiddenForPicture = !visible
        view?.visibility = if (visible) android.view.View.VISIBLE else android.view.View.INVISIBLE
    }

    fun hide() {
        val v = view ?: return
        view = null
        labelView = null
        try { wm.removeView(v) } catch (_: Exception) { }
    }
}
