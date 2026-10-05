package com.jacef8.scrollcapture

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.jacef8.scrollcapture.core.RawRows
import java.io.File
import java.util.Properties
import java.util.concurrent.Executors

/**
 * What appears right after a capture: a floating row of buttons over the live app, with a small
 * thumbnail. The bar itself has no background and touches outside the buttons go straight through
 * to the app, so the app stays visible and can still be scrolled and navigated. Tap the thumbnail
 * for the full-screen viewer.
 */
class ResultBar(
    private val svc: AccessibilityService,
    private val id: String,
    private val onMore: (Mode) -> Unit,
) {
    private var root: View? = null
    private var atTop = false
    private val ui = Handler(Looper.getMainLooper())
    private val autoHide = Runnable { dismiss() }
    private val wm get() = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    val isShowing: Boolean get() = root != null

    fun show() {
        if (root != null) return
        val dir = File(svc.cacheDir, "cap/$id")
        val meta = Properties()
        try { File(dir, "meta.properties").inputStream().use { meta.load(it) } } catch (e: Exception) { return }
        val hasImage = File(dir, "image.png").exists()

        val ctx: Context = ContextThemeWrapper(svc, android.R.style.Theme_DeviceDefault)
        val dp = { v: Float -> Ui.dp(ctx, v) }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
        }

        // Thumbnail: tap for the full-screen viewer, long-press to move the bar to the other edge.
        val thumb = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(0xCC101216.toInt())
                cornerRadius = dp(10f).toFloat()
                setStroke(dp(1.5f), 0x88FFFFFF.toInt())
            }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            contentDescription = "Open full screen"
            setOnClickListener { open() }
            setOnLongClickListener { move(); true }
            if (!hasImage) {
                setImageResource(R.drawable.ic_cap_text)
                scaleType = ImageView.ScaleType.CENTER
            }
        }
        row.addView(thumb, LinearLayout.LayoutParams(dp(40f), dp(72f)).apply { rightMargin = dp(8f) })
        if (hasImage) loadThumb(dir, meta, thumb)

        fun button(icon: Int, label: String, onClick: () -> Unit) {
            val b = FrameLayout(ctx).apply {
                background = LayerDrawable(arrayOf(circle(0xB3101216.toInt(), 0x66FFFFFF), pressedOverlay(ctx)))
                contentDescription = label
                tooltipText = label
                setOnClickListener { onClick() }
            }
            b.addView(ImageView(ctx).apply { setImageResource(icon) }, FrameLayout.LayoutParams(dp(22f), dp(22f), Gravity.CENTER))
            row.addView(b, LinearLayout.LayoutParams(dp(40f), dp(40f)).apply { leftMargin = dp(3f); rightMargin = dp(3f) })
        }

        // Go on from this screen: scroll it, read it, or both.
        button(R.drawable.ic_cap_scroll, "Scroll capture") { dismiss(); onMore(Mode.SCROLL) }
        button(R.drawable.ic_cap_text, "Text of the whole page") { dismiss(); onMore(Mode.TEXT) }
        button(R.drawable.ic_cap_both, "Image and text") { dismiss(); onMore(Mode.SCROLL_TEXT) }
        button(R.drawable.ic_share, "Share") { Actions.share(svc, dir, hasImage) }
        if (hasImage) button(R.drawable.ic_edit, "Edit") { Actions.edit(svc, dir) }
        button(R.drawable.ic_cap_close, "Close") { dismiss() }

        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Not focusable, not touch-modal: only the buttons take touches; everything else reaches the app.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(28f)
        }
        wm.addView(row, lp)
        root = row
        ui.postDelayed(autoHide, AUTO_HIDE_MS)
    }

    private fun circle(fill: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setStroke(1, stroke)
    }

    private fun pressedOverlay(ctx: Context): android.graphics.drawable.Drawable =
        android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x44FFFFFF)
            })
            addState(intArrayOf(), android.graphics.drawable.ColorDrawable(0))
        }

    private fun loadThumb(dir: File, meta: Properties, view: ImageView) {
        Executors.newSingleThreadExecutor().execute {
            try {
                val w = meta.getProperty("width").toInt()
                val rows = meta.getProperty("rows").toInt()
                val take = minOf(rows, (w * 2.2f).toInt())          // the top of a long capture is enough
                val px = RawRows.read(File(dir, "image.raw"), w, 0, take)
                val src = Bitmap.createBitmap(px, w, take, Bitmap.Config.ARGB_8888)
                val tw = 160
                val th = (take * tw.toFloat() / w).toInt().coerceAtLeast(1)
                val small = Bitmap.createScaledBitmap(src, tw, th, true)
                if (small !== src) src.recycle()
                ui.post { view.setImageBitmap(small) }
            } catch (e: Exception) {
                DebugLog.error("thumbnail", e)
            }
        }
    }

    private fun open() {
        dismiss()
        svc.startActivity(
            Intent(svc, ResultActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(ResultActivity.EXTRA_ID, id)
        )
    }

    private fun move() {
        val r = root ?: return
        atTop = !atTop
        val lp = r.layoutParams as WindowManager.LayoutParams
        lp.gravity = (if (atTop) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
        lp.y = Ui.dp(r.context, if (atTop) 48f else 28f)
        try { wm.updateViewLayout(r, lp) } catch (_: Exception) { }
        ui.removeCallbacks(autoHide)
        ui.postDelayed(autoHide, AUTO_HIDE_MS)
    }

    fun dismiss() {
        ui.removeCallbacks(autoHide)
        val r = root ?: return
        root = null
        try { wm.removeView(r) } catch (_: Exception) { }
    }

    private companion object {
        const val AUTO_HIDE_MS = 15_000L
    }
}
