package com.jacef8.scrollcapture

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jacef8.scrollcapture.core.RawRows
import java.io.File
import java.util.Properties
import java.util.concurrent.Executors

/**
 * Shows a finished capture. A normal screenshot is fitted whole on the screen with no scrolling;
 * tap it to hide the bars and see it full screen. Very long captures scroll. The toolbar has
 * Samsung's usual actions (share, edit, copy, delete) and lets you go on to a scroll capture
 * or text of the screen you just shot.
 */
class ResultActivity : Activity() {
    private lateinit var dir: File
    private lateinit var meta: Properties
    private lateinit var prefs: Prefs

    private var hasImage = false
    private var hasText = false
    private var tab = TAB_IMAGE
    private var barsHidden = false

    private lateinit var fitImage: ImageView
    private lateinit var imageList: RecyclerView
    private lateinit var textScroll: ScrollView
    private lateinit var tabImage: TextView
    private lateinit var tabText: TextView
    private lateinit var editBtn: View
    private lateinit var top: View
    private lateinit var seg: View
    private lateinit var dock: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val id = intent.getStringExtra(EXTRA_ID)
        dir = File(cacheDir, "cap/$id")
        val metaFile = File(dir, "meta.properties")
        if (id == null || !metaFile.exists()) {
            Toast.makeText(this, "That capture is no longer available", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        meta = Properties().also { p -> metaFile.inputStream().use { p.load(it) } }
        hasImage = File(dir, "image.png").exists()
        hasText = File(dir, "text.txt").exists()
        tab = if (hasImage) TAB_IMAGE else TAB_TEXT

        setContentView(buildUi())
        showTab(tab)

        if (savedInstanceState == null && hasText && prefs.autoCopyText) {
            copyText()
            Toast.makeText(this, "Text copied", Toast.LENGTH_SHORT).show()
        }
    }

    /** True for a normal screen-sized capture, which is fitted whole instead of scrolled. */
    private fun fitsOnScreen(): Boolean {
        val w = meta.getProperty("width")?.toIntOrNull() ?: return false
        val rows = meta.getProperty("rows")?.toIntOrNull() ?: return false
        return rows.toFloat() / w <= FIT_MAX_ASPECT
    }

    private fun buildUi(): View {
        val dp = { v: Float -> Ui.dp(this, v) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.CANVAS)
        }

        // Slim top bar: what it is, then Done.
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(6f), dp(8f), dp(4f))
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val bits = ArrayList<String>()
        if (hasImage) bits += "${meta.getProperty("width")} × ${"%,d".format(meta.getProperty("rows", "0").toInt())}"
        val pages = meta.getProperty("pages", "1")
        if (pages != "1") bits += "$pages screens"
        if (meta.getProperty("imageUri") != null) bits += "saved to Gallery"
        if (meta.getProperty("textUri") != null) bits += "text saved"
        row.addView(Ui.text(this, bits.joinToString(" · "), 14f, C.INK3), Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.text(this, "Done", 16f, C.ACCENT, true).apply {
            setPadding(dp(14f), dp(10f), dp(10f), dp(10f))
            setOnClickListener { finish() }
        })
        head.addView(row)
        val warn = meta.getProperty("warnings", "")
        if (warn.isNotBlank()) {
            head.addView(Ui.text(this, warn, 14f, C.WARN).apply { setPadding(0, 0, dp(8f), dp(4f)) })
        }
        top = head
        root.addView(head)

        // Image | Text switch, sunk into a well, when both exist
        val segRow = LinearLayout(this).apply {
            background = Ui.well(context)
            setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
            visibility = if (hasImage && hasText) View.VISIBLE else View.GONE
        }
        tabImage = tabPill("Image") { showTab(TAB_IMAGE) }
        tabText = tabPill("Text") { showTab(TAB_TEXT) }
        segRow.addView(tabImage, Ui.lp(0, dp(38f), 1f))
        segRow.addView(tabText, Ui.lp(0, dp(38f), 1f))
        seg = segRow
        root.addView(segRow, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(16f); rightMargin = dp(16f); bottomMargin = dp(6f)
        })

        // Content
        val content = FrameLayout(this)
        fitImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            setOnClickListener { toggleBars() }
        }
        imageList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(context)
            setBackgroundColor(C.RECESS)
            visibility = View.GONE
        }
        textScroll = ScrollView(this).apply { visibility = View.GONE; setBackgroundColor(C.RECESS) }
        val textView = Ui.text(this, if (hasText) File(dir, "text.txt").readText() else "", 16f, C.INK).apply {
            setTextIsSelectable(true)
            setPadding(dp(18f), dp(14f), dp(18f), dp(14f))
            setLineSpacing(0f, 1.15f)
        }
        textScroll.addView(textView)
        content.addView(fitImage, FrameLayout.LayoutParams(-1, -1))
        content.addView(imageList, FrameLayout.LayoutParams(-1, -1))
        content.addView(textScroll, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        if (hasImage) loadImage()

        dock = buildDock()
        root.addView(dock)
        return root
    }

    private fun loadImage() {
        val raw = File(dir, "image.raw")
        if (!raw.exists()) return
        val width = meta.getProperty("width").toInt()
        val rows = meta.getProperty("rows").toInt()
        if (fitsOnScreen()) {
            // A normal screenshot: decode it whole and fit it to the space available.
            val ui = Handler(Looper.getMainLooper())
            Executors.newSingleThreadExecutor().execute {
                val px = RawRows.read(raw, width, 0, rows)
                val bmp = Bitmap.createBitmap(px, width, rows, Bitmap.Config.ARGB_8888)
                ui.post { if (!isDestroyed) fitImage.setImageBitmap(bmp) }
            }
        } else {
            // A long capture: show it in slices that scroll.
            imageList.adapter = TileAdapter(raw, width, rows) { imageList.width }
        }
    }

    /** The toolbar, in the style of Samsung's: a row of icons with short labels, on a transparent background. */
    private fun buildDock(): View {
        val dp = { v: Float -> Ui.dp(this, v) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // No background: just the icons and labels. (A press still lights up its own button.)
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
        }

        fun item(icon: Int, label: String, onClick: () -> Unit): View {
            val col = LinearLayout(this).apply {
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
                setOnClickListener { onClick() }
            }
            col.addView(ImageView(this).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(26f), dp(26f)))
            col.addView(Ui.text(this, label, 12f, C.INK2).apply { gravity = Gravity.CENTER; maxLines = 1 })
            return col
        }

        fun add(v: View) = bar.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        add(item(R.drawable.ic_share, "Share") { share() })
        editBtn = item(R.drawable.ic_edit, "Edit") { edit() }
        if (hasImage) add(editBtn)
        add(item(R.drawable.ic_copy, "Copy") {
            if (tab == TAB_TEXT) { copyText(); toast("Text copied") } else { copyImage(); toast("Image copied") }
        })
        add(item(R.drawable.ic_delete, "Delete") { delete() })

        bar.addView(View(this).apply { setBackgroundColor(0x33FFFFFF) },
            LinearLayout.LayoutParams(dp(1f), dp(30f)).apply { leftMargin = dp(2f); rightMargin = dp(2f) })

        // Go on from the screen just shot: these return to that app and capture it.
        add(item(R.drawable.ic_cap_scroll, "Scroll") { captureMore(Mode.SCROLL) })
        add(item(R.drawable.ic_cap_text, "Text") { captureMore(Mode.TEXT) })
        add(item(R.drawable.ic_cap_both, "Both") { captureMore(Mode.SCROLL_TEXT) })

        return LinearLayout(this).apply {
            setPadding(dp(10f), dp(6f), dp(10f), dp(12f))
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun tabPill(label: String, onClick: () -> Unit): TextView =
        Ui.text(this, label, 15f, C.INK3, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }

    private fun showTab(t: Int) {
        tab = t
        val showImage = t == TAB_IMAGE && hasImage
        val fit = fitsOnScreen()
        fitImage.visibility = if (showImage && fit) View.VISIBLE else View.GONE
        imageList.visibility = if (showImage && !fit) View.VISIBLE else View.GONE
        textScroll.visibility = if (t == TAB_TEXT && hasText) View.VISIBLE else View.GONE
        if (hasImage && hasText) {
            styleTab(tabImage, t == TAB_IMAGE)
            styleTab(tabText, t == TAB_TEXT)
        }
        if (hasImage) editBtn.alpha = if (t == TAB_IMAGE) 1f else 0.35f
    }

    private fun styleTab(v: TextView, on: Boolean) {
        v.setTextColor(if (on) C.ON_ACCENT else C.INK3)
        v.background = if (on) Ui.accentFill(this, 999f) else null
    }

    /** Tap the picture to hide every bar (and the system bars) for a true full-screen view. */
    private fun toggleBars() {
        barsHidden = !barsHidden
        val v = if (barsHidden) View.GONE else View.VISIBLE
        top.visibility = v
        dock.visibility = v
        seg.visibility = if (barsHidden || !(hasImage && hasText)) View.GONE else View.VISIBLE
        window.insetsController?.let {
            if (barsHidden) it.hide(WindowInsets.Type.systemBars()) else it.show(WindowInsets.Type.systemBars())
        }
    }

    // ---- actions ----

    private fun imageUri(): Uri = Actions.fileUri(this, File(dir, "image.png"))

    private fun share() = Actions.share(this, dir, tab == TAB_IMAGE && hasImage)

    private fun edit() {
        if (!hasImage || tab != TAB_IMAGE) { toast("Editing is for images"); return }
        Actions.edit(this, dir)
    }

    /** Close this screen and capture the app underneath again, this time scrolling and/or reading its text. */
    private fun captureMore(mode: Mode) {
        val svc = CaptureService.instance
        if (svc == null) { toast("Scroll Capture is off in Accessibility"); return }
        svc.requestCapture(mode, 900, dir.name)
        finish()
    }

    private fun clipboard() = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun copyText() {
        clipboard().setPrimaryClip(ClipData.newPlainText("Captured text", File(dir, "text.txt").readText()))
    }

    private fun copyImage() {
        clipboard().setPrimaryClip(ClipData.newUri(contentResolver, "Screenshot", imageUri()))
    }

    private fun delete() {
        for (key in listOf("imageUri", "textUri")) {
            meta.getProperty(key)?.let {
                try { contentResolver.delete(Uri.parse(it), null, null) } catch (_: Exception) { }
            }
        }
        dir.deleteRecursively()
        toast("Deleted")
        finish()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_ID = "id"
        private const val TAB_IMAGE = 0
        private const val TAB_TEXT = 1

        /** Height over width up to which a capture is fitted whole (a tall phone screen is about 2.2). */
        private const val FIT_MAX_ASPECT = 3.0f
    }
}

/** Shows a long image in slices, read from the raw rows file, so any length stays smooth. */
private class TileAdapter(
    private val raw: File,
    private val width: Int,
    private val rows: Int,
    private val viewWidth: () -> Int,
) : RecyclerView.Adapter<TileAdapter.Holder>() {
    private val tileRows = 800
    private val exec = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    class Holder(val view: ImageView) : RecyclerView.ViewHolder(view)

    override fun getItemCount() = (rows + tileRows - 1) / tileRows

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = ImageView(parent.context).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(C.RECESS)
        }
        return Holder(v)
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val from = position * tileRows
        val count = minOf(tileRows, rows - from)
        val vw = viewWidth().takeIf { it > 0 } ?: h.view.resources.displayMetrics.widthPixels
        val scale = vw.toFloat() / width
        h.view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (count * scale).toInt().coerceAtLeast(1))
        h.view.setImageDrawable(null)
        h.view.tag = position
        exec.execute {
            val px = RawRows.read(raw, width, from, count)
            val bmp = Bitmap.createBitmap(px, width, count, Bitmap.Config.ARGB_8888)
            ui.post { if (h.view.tag == position) h.view.setImageBitmap(bmp) }
        }
    }

    override fun onViewRecycled(h: Holder) {
        h.view.setImageDrawable(null)
        h.view.tag = null
    }
}
