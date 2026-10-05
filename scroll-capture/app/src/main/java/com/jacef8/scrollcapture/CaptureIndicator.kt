package com.jacef8.scrollcapture

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView

/**
 * A small "Capturing…" pill shown near the top while a scroll capture runs, so it is obvious
 * something is happening. Tap it to stop. It sits above the scrolling area and is only added after
 * the first frame, so it never ends up in the finished image.
 */
class CaptureIndicator(
    private val svc: AccessibilityService,
    private val onStop: () -> Unit,
) {
    private var view: TextView? = null
    private val wm get() = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    fun showOrUpdate(pages: Int) {
        val label = "Capturing… screen $pages  ·  tap to stop"
        view?.let { it.text = label; return }
        val ctx = ContextThemeWrapper(svc, android.R.style.Theme_DeviceDefault)
        val dp = { v: Float -> Ui.dp(ctx, v) }
        val tv = TextView(ctx).apply {
            text = label
            textSize = 14f
            setTextColor(C.INK)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            background = GradientDrawable().apply {
                setColor(0xE6141518.toInt())
                cornerRadius = dp(22f).toFloat()
                setStroke(dp(1f), C.ACCENT)
            }
            elevation = dp(6f).toFloat()
            setOnClickListener { onStop() }
        }
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

    fun hide() {
        val v = view ?: return
        view = null
        try { wm.removeView(v) } catch (_: Exception) { }
    }
}
