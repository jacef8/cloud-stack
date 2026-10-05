package com.jacef8.scrollcapture

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.jacef8.scrollcapture.core.RawRows
import java.io.File
import java.util.Properties
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * What appears right after a capture, laid out like Samsung's: a thumbnail at the bottom-left and a
 * wide, rounded, dark bar at the bottom-centre (Edit, Text, Share, then a white Scroll-capture circle), floating over the live app.
 *
 * It fades in, then fades away by itself after about five seconds (holding a finger on it keeps it a
 * little longer). Swipe the thumbnail aside to dismiss it sooner. They are two small windows, so
 * touches anywhere else go straight through to the app, which can still be scrolled and used.
 * Tap the thumbnail for the full-screen viewer.
 */
class ResultBar(
    private val svc: AccessibilityService,
    private val id: String,
    private val message: String,
    private val onMore: (Mode) -> Unit,
) {
    private val views = ArrayList<View>()
    private val ui = Handler(Looper.getMainLooper())
    private val fade = Runnable { fadeOut() }
    private var gone = false
    private val wm get() = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    val isShowing: Boolean get() = views.isNotEmpty() && !gone

    fun show() {
        if (views.isNotEmpty()) return
        val dir = File(svc.cacheDir, "cap/$id")
        val meta = Properties()
        try { File(dir, "meta.properties").inputStream().use { meta.load(it) } } catch (e: Exception) { return }
        val hasImage = File(dir, "image.png").exists()

        val ctx: Context = ContextThemeWrapper(svc, android.R.style.Theme_DeviceDefault)
        val dp = { v: Float -> Ui.dp(ctx, v) }

        // ---- the bar of icons: a wide dark pill, four even slots, the last one a white circle ----
        val pill = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xEB252C38.toInt(), 0xEB171C25.toInt())).apply {
                cornerRadius = dp(40f).toFloat()
            }
            setPadding(dp(24f), dp(4f), dp(24f), dp(4f))
            elevation = dp(6f).toFloat()
        }

        fun slot(content: View, label: String, onClick: () -> Unit) {
            val slot = FrameLayout(ctx).apply {
                contentDescription = label
                tooltipText = label
                setOnClickListener { onClick() }
            }
            slot.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            pill.addView(slot, LinearLayout.LayoutParams(0, dp(43f), 1f))
        }

        fun plainIcon(icon: Int) = FrameLayout(ctx).apply {
            addView(ImageView(ctx).apply { setImageResource(icon) }, FrameLayout.LayoutParams(dp(26f), dp(26f), Gravity.CENTER))
            layoutParams = FrameLayout.LayoutParams(dp(43f), dp(43f))
        }

        if (hasImage) slot(plainIcon(R.drawable.ic_edit), "Edit") { dismissNow(); Actions.edit(svc, dir) }
        slot(plainIcon(R.drawable.ic_cap_text), "Text of the whole page") { dismissNow(); onMore(Mode.TEXT) }
        slot(plainIcon(R.drawable.ic_share), "Share") { dismissNow(); Actions.share(svc, dir, hasImage) }
        // Scroll capture is the main action: a white circle with a blue icon, as on Samsung's.
        val scrollCircle = FrameLayout(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFFFFFF.toInt()) }
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_cap_scroll)
                setColorFilter(0xFF2F6FF0.toInt())
            }, FrameLayout.LayoutParams(dp(26f), dp(26f), Gravity.CENTER))
            layoutParams = FrameLayout.LayoutParams(dp(42f), dp(42f))
        }
        slot(scrollCircle, "Scroll capture") { dismissNow(); onMore(Mode.SCROLL) }

        // ---- the thumbnail ----
        val thumb = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(0xCC141518.toInt())
                cornerRadius = dp(12f).toFloat()
                setStroke(dp(1f), 0x33FFFFFF)
            }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            contentDescription = "Open full screen"
            elevation = dp(6f).toFloat()
            if (!hasImage) {
                setImageResource(R.drawable.ic_cap_text)
                scaleType = ImageView.ScaleType.CENTER
            }
            setOnClickListener { open() }
            installSwipeAway(this)
        }
        if (hasImage) loadThumb(dir, meta, thumb)

        holdWhileTouched(pill)
        holdWhileTouched(thumb)

        val screenW = ctx.resources.displayMetrics.widthPixels
        val thumbLp = overlayParams().apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = dp(24f)
            y = dp(128f)
        }
        val pillLp = overlayParams().apply {
            width = screenW - dp(58f)
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(58f)
        }
        // Each is its own small window, so the empty space between them is not blocked.
        wm.addView(thumb, thumbLp)
        wm.addView(pill, pillLp)
        views += thumb
        views += pill
        thumb.layoutParams = thumbLp
        pill.layoutParams = pillLp
        thumb.updateSize(dp(65f), dp(126f))

        // A note about the capture (stopped early, nothing to scroll, ...) sits beside the thumbnail,
        // where nothing covers it. It stays up a little longer than the bar.
        if (message.isNotBlank()) {
            val chip = android.widget.TextView(ctx).apply {
                text = message
                textSize = 14f
                setTextColor(C.INK)
                setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
                background = GradientDrawable().apply {
                    setColor(0xEB252C38.toInt())
                    cornerRadius = dp(18f).toFloat()
                }
                elevation = dp(6f).toFloat()
            }
            val left = dp(24f + 65f + 8f)
            val chipLp = overlayParams().apply {
                width = screenW - left - dp(24f)
                gravity = Gravity.BOTTOM or Gravity.START
                x = left
                y = dp(128f)
            }
            wm.addView(chip, chipLp)
            views += chip
            chip.layoutParams = chipLp
            holdWhileTouched(chip)
        }

        // Fade in and rise a little, like Samsung's.
        for (v in views) {
            v.alpha = 0f
            v.translationY = dp(16f).toFloat()
            v.animate().alpha(1f).translationY(0f).setDuration(220).start()
        }
        ui.postDelayed(fade, if (message.isNotBlank()) NOTE_MS else SHOW_MS)
    }

    private fun overlayParams() = WindowManager.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // Not focusable, not touch-modal: only these small windows take touches.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    )

    /** Give the thumbnail its fixed size (the window wraps whatever the view asks for). */
    private fun View.updateSize(w: Int, h: Int) {
        val lp = layoutParams as WindowManager.LayoutParams
        lp.width = w
        lp.height = h
        try { wm.updateViewLayout(this, lp) } catch (_: Exception) { }
    }

    /** While a finger is on the bar the timer waits; when it lifts the bar stays a little longer. */
    private fun holdWhileTouched(v: View) {
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> ui.removeCallbacks(fade)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!gone) { ui.removeCallbacks(fade); ui.postDelayed(fade, TOUCH_MS) }
                }
            }
            false
        }
    }

    /** Swipe the thumbnail sideways to send it away, as on Samsung's. */
    private fun installSwipeAway(v: View) {
        var downX = 0f
        val limit = Ui.dp(v.context, 56f)
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; ui.removeCallbacks(fade) }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    if (abs(dx) > 8) view.translationX = dx
                }
                MotionEvent.ACTION_UP -> {
                    val dx = e.rawX - downX
                    if (abs(dx) > limit) {
                        fadeOut(slide = if (dx > 0) 1 else -1)
                        return@setOnTouchListener true
                    }
                    view.translationX = 0f
                    if (!gone) { ui.removeCallbacks(fade); ui.postDelayed(fade, TOUCH_MS) }
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.translationX = 0f
                    if (!gone) { ui.removeCallbacks(fade); ui.postDelayed(fade, TOUCH_MS) }
                }
            }
            false
        }
    }

    private fun loadThumb(dir: File, meta: Properties, view: ImageView) {
        Executors.newSingleThreadExecutor().execute {
            try {
                val w = meta.getProperty("width").toInt()
                val rows = meta.getProperty("rows").toInt()
                val take = minOf(rows, (w * 2.2f).toInt())          // the top of a long capture is enough
                val px = RawRows.read(File(dir, "image.raw"), w, 0, take)
                val src = Bitmap.createBitmap(px, w, take, Bitmap.Config.ARGB_8888)
                val tw = 220
                val th = (take * tw.toFloat() / w).toInt().coerceAtLeast(1)
                val small = Bitmap.createScaledBitmap(src, tw, th, true)
                if (small !== src) src.recycle()
                ui.post { if (!gone) view.setImageBitmap(small) }
            } catch (e: Exception) {
                DebugLog.error("thumbnail", e)
            }
        }
    }

    private fun open() {
        dismissNow()
        svc.startActivity(
            Intent(svc, ResultActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(ResultActivity.EXTRA_ID, id)
        )
    }

    /** Fade away, then remove the windows. */
    private fun fadeOut(slide: Int = 0) {
        if (gone || views.isEmpty()) return
        gone = true
        ui.removeCallbacks(fade)
        val dist = Ui.dp(views[0].context, 80f).toFloat() * slide
        views.forEachIndexed { i, v ->
            val a = v.animate().alpha(0f).setDuration(320)
            if (slide != 0) a.translationX(dist) else a.translationY(Ui.dp(v.context, 10f).toFloat())
            if (i == views.lastIndex) a.withEndAction { removeAll() }
            a.start()
        }
    }

    /** Remove at once, no animation (a button was pressed and something else takes over). */
    fun dismissNow() {
        gone = true
        ui.removeCallbacks(fade)
        removeAll()
    }

    /** Called when another capture starts. */
    fun dismiss() = dismissNow()

    private fun removeAll() {
        for (v in views) {
            v.animate().cancel()
            try { wm.removeView(v) } catch (_: Exception) { }
        }
        views.clear()
    }

    private companion object {
        const val NOTE_MS = 9_000L      // a note needs time to be read
        const val SHOW_MS = 5_000L      // how long it stays before fading, like Samsung's
        const val TOUCH_MS = 3_000L     // extra time after a finger lifts
    }
}
