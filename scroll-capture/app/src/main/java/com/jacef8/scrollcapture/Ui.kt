package com.jacef8.scrollcapture

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** The look: dark canvas, lighter = closer, sharp text, one cyan-to-lime accent. */
object C {
    const val CANVAS = 0xFF0B0D13.toInt()
    const val E1 = 0xF2161922.toInt()
    const val E1_HI = 0xF21E222E.toInt()
    const val E2 = 0xFF202533.toInt()
    const val E2_HI = 0xFF2A3042.toInt()
    const val RECESS = 0xFF07090D.toInt()
    const val INK = 0xFFFFFFFF.toInt()
    const val INK2 = 0xD1FFFFFF.toInt()
    const val INK3 = 0xA8FFFFFF.toInt()
    const val ACCENT = 0xFF00D9FF.toInt()
    const val ACCENT2 = 0xFFB6FF3D.toInt()
    const val ON_ACCENT = 0xFF04141A.toInt()
    const val BAD = 0xFFFF4257.toInt()
    const val WARN = 0xFFFF8A1E.toInt()
    const val EDGE = 0x14FFFFFF
    const val EDGE_HI = 0x2EFFFFFF
}

object Ui {
    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
    fun dp(ctx: Context, v: Int): Int = dp(ctx, v.toFloat())

    private fun grad(top: Int, bottom: Int, radius: Float, strokeColor: Int, strokePx: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
            cornerRadius = radius
            setStroke(strokePx, strokeColor)
        }

    /** A translucent housing that holds other objects. */
    fun card(ctx: Context) = grad(C.E1_HI, C.E1, dp(ctx, 20f).toFloat(), C.EDGE, dp(ctx, 1))

    /** A raised control inside a card. */
    fun widget(ctx: Context, radiusDp: Float = 14f) =
        grad(C.E2_HI, C.E2, dp(ctx, radiusDp).toFloat(), C.EDGE_HI, dp(ctx, 1))

    /** A groove cut into the surface. */
    fun well(ctx: Context, radiusDp: Float = 999f) =
        grad(C.RECESS, 0xFF0D1018.toInt(), dp(ctx, radiusDp).toFloat(), C.EDGE, dp(ctx, 1))

    fun accentFill(ctx: Context, radiusDp: Float = 14f) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(C.ACCENT, C.ACCENT2)).apply {
            cornerRadius = dp(ctx, radiusDp).toFloat()
        }

    fun circle(ctx: Context, accent: Boolean = false) =
        if (accent) GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(C.ACCENT, C.ACCENT2)).apply {
            shape = GradientDrawable.OVAL
        } else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(C.E2_HI, C.E2)).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(ctx, 1), C.EDGE_HI)
        }

    fun text(ctx: Context, s: CharSequence, sp: Float, color: Int = C.INK, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            textSize = sp
            setTextColor(color)
            typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        }

    /** Small uppercase label: 12sp, spaced, never dimmer than 64% white. */
    fun label(ctx: Context, s: String): TextView = text(ctx, s.uppercase(), 12f, C.INK3, true).apply {
        letterSpacing = 0.12f
    }

    fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    fun toggleRow(ctx: Context, title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 10), 0, dp(ctx, 10))
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(ctx, title, 16f, C.INK, true))
        col.addView(text(ctx, sub, 13f, C.INK3))
        row.addView(col, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(ctx).apply {
            isChecked = checked
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(C.ACCENT2, 0xFFB8BECC.toInt())
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0x8000D9FF.toInt(), 0xFF07090D.toInt())
            )
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        return row
    }
}

/** The one large circular anchor: a thick gradient arc over a dark track. */
class Ring(ctx: Context) : View(ctx) {
    var fraction = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xA6000000.toInt()
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ticks = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x29FFFFFF
        strokeWidth = Ui.dp(ctx, 1).toFloat()
    }
    private val rect = RectF()

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat()
        val stroke = s * 0.085f
        val cx = width / 2f
        val cy = height / 2f
        val r = s / 2f - stroke * 1.6f
        track.strokeWidth = stroke * 1.1f
        arc.strokeWidth = stroke
        arc.shader = SweepGradient(cx, cy, intArrayOf(C.ACCENT, C.ACCENT2, C.ACCENT2), floatArrayOf(0f, 0.75f, 1f)).also {
            it.setLocalMatrix(Matrix().apply { postRotate(135f, cx, cy) })
        }
        arc.setShadowLayer(Ui.dp(context, 4f).toFloat(), 0f, 0f, C.ACCENT)
        ticks.pathEffect = DashPathEffect(floatArrayOf(1.5f, Ui.dp(context, 5f).toFloat()), 0f)
        c.drawCircle(cx, cy, s / 2f - stroke * 0.3f, ticks)
        rect.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(rect, 135f, 270f, false, track)
        if (fraction > 0f) c.drawArc(rect, 135f, 270f * fraction, false, arc)
    }
}

/** Fills a button-like view with the accent gradient. */
fun View.accentBackground(radiusDp: Float = 14f) {
    background = Ui.accentFill(context, radiusDp)
}

@Suppress("unused")
private fun unusedShader(): Shader = LinearGradient(0f, 0f, 1f, 1f, Color.BLACK, Color.WHITE, Shader.TileMode.CLAMP)
