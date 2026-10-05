package com.jacef8.scrollcapture

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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

/** Shows a finished capture and does what Samsung's screenshot toolbar does: share, edit, copy, delete. */
class ResultActivity : Activity() {
    private lateinit var dir: File
    private lateinit var meta: Properties
    private lateinit var prefs: Prefs

    private var hasImage = false
    private var hasText = false
    private var tab = TAB_IMAGE

    private lateinit var imageList: RecyclerView
    private lateinit var textScroll: ScrollView
    private lateinit var tabImage: TextView
    private lateinit var tabText: TextView
    private lateinit var editBtn: View

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

        (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(CaptureService.NOTE_READY)
        setContentView(buildUi())
        showTab(tab)

        if (savedInstanceState == null && hasText && prefs.autoCopyText) {
            copyText()
            Toast.makeText(this, "Text copied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildUi(): View {
        val dp = { v: Float -> Ui.dp(this, v) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.CANVAS)
        }

        // Header
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(14f), dp(18f), dp(8f))
        }
        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(Ui.text(this, "Capture ready", 22f, C.INK, true), Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(Ui.text(this, "Done", 16f, C.ACCENT, true).apply {
            setPadding(dp(12f), dp(8f), 0, dp(8f))
            setOnClickListener { finish() }
        })
        head.addView(titleRow)

        val bits = ArrayList<String>()
        if (hasImage) bits += "${meta.getProperty("width")} × ${"%,d".format(meta.getProperty("rows", "0").toInt())} px"
        val pages = meta.getProperty("pages", "1")
        if (pages != "1") bits += "$pages screens"
        if (meta.getProperty("imageUri") != null) bits += "saved to Gallery"
        if (meta.getProperty("textUri") != null) bits += "text saved"
        head.addView(Ui.text(this, bits.joinToString(" · "), 14f, C.INK3))
        val warn = meta.getProperty("warnings", "")
        if (warn.isNotBlank()) {
            head.addView(Ui.text(this, warn, 14f, C.WARN).apply { setPadding(0, dp(6f), 0, 0) })
        }
        root.addView(head)

        // Image | Text switch, sunk into a well, when both exist
        if (hasImage && hasText) {
            val seg = LinearLayout(this).apply {
                background = Ui.well(context)
                setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
            }
            tabImage = tabPill("Image") { showTab(TAB_IMAGE) }
            tabText = tabPill("Text") { showTab(TAB_TEXT) }
            seg.addView(tabImage, Ui.lp(0, dp(40f), 1f))
            seg.addView(tabText, Ui.lp(0, dp(40f), 1f))
            root.addView(seg, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(18f); rightMargin = dp(18f); bottomMargin = dp(8f)
            })
        }

        // Content
        val content = FrameLayout(this)
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
        content.addView(imageList, FrameLayout.LayoutParams(-1, -1))
        content.addView(textScroll, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            leftMargin = dp(10f); rightMargin = dp(10f)
        })

        if (hasImage) {
            imageList.post {
                val raw = File(dir, "image.raw")
                if (raw.exists()) {
                    imageList.adapter = TileAdapter(raw, meta.getProperty("width").toInt(), meta.getProperty("rows").toInt(), imageList.width)
                }
            }
        }

        // Dock
        val dock = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8f), dp(12f), dp(8f), dp(16f))
        }
        dock.addView(dockButton(R.drawable.ic_share, "Share") { share() })
        editBtn = dockButton(R.drawable.ic_edit, "Edit") { edit() }
        if (hasImage) dock.addView(editBtn)
        dock.addView(dockButton(R.drawable.ic_copy, "Copy") { if (tab == TAB_TEXT) { copyText(); toast("Text copied") } else { copyImage(); toast("Image copied") } })
        dock.addView(dockButton(R.drawable.ic_delete, "Delete") { delete() })
        root.addView(dock)
        return root
    }

    private fun tabPill(label: String, onClick: () -> Unit): TextView =
        Ui.text(this, label, 15f, C.INK3, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }

    private fun dockButton(icon: Int, label: String, onClick: () -> Unit): View {
        val dp = { v: Float -> Ui.dp(this, v) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }
        val circle = FrameLayout(this).apply {
            background = Ui.circle(context)
            elevation = dp(3f).toFloat()
        }
        circle.addView(ImageView(this).apply { setImageResource(icon) }, FrameLayout.LayoutParams(dp(26f), dp(26f), Gravity.CENTER))
        col.addView(circle, Ui.lp(dp(58f), dp(58f)))
        col.addView(Ui.text(this, label, 13f, C.INK2).apply { setPadding(0, dp(6f), 0, 0); gravity = Gravity.CENTER })
        col.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        return col
    }

    private fun showTab(t: Int) {
        tab = t
        imageList.visibility = if (t == TAB_IMAGE && hasImage) View.VISIBLE else View.GONE
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

    // ---- actions ----

    private fun imageUri(): Uri = FileProvider.getUriForFile(this, "$packageName.files", File(dir, "image.png"))
    private fun textFileUri(): Uri = FileProvider.getUriForFile(this, "$packageName.files", File(dir, "text.txt"))

    private fun share() {
        val send = Intent(Intent.ACTION_SEND)
        if (tab == TAB_IMAGE && hasImage) {
            send.type = "image/png"
            send.putExtra(Intent.EXTRA_STREAM, imageUri())
        } else {
            val text = File(dir, "text.txt").readText()
            if (text.length < 300_000) {
                send.type = "text/plain"
                send.putExtra(Intent.EXTRA_TEXT, text)
            } else {
                send.type = "text/plain"
                send.putExtra(Intent.EXTRA_STREAM, textFileUri())
            }
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share"))
    }

    private fun edit() {
        if (!hasImage || tab != TAB_IMAGE) { toast("Editing is for images"); return }
        val i = Intent(Intent.ACTION_EDIT).setDataAndType(imageUri(), "image/png")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(i, "Edit with"))
        } catch (e: Exception) {
            toast("No editor found")
        }
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
    }
}

/** Shows the long image in slices, read from the raw rows file, so any length stays smooth. */
private class TileAdapter(
    private val raw: File,
    private val width: Int,
    private val rows: Int,
    viewWidth: Int,
) : RecyclerView.Adapter<TileAdapter.Holder>() {
    private val tileRows = 800
    private val scale = viewWidth.toFloat() / width
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
