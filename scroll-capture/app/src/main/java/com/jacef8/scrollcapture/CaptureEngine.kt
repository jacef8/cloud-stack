package com.jacef8.scrollcapture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.jacef8.scrollcapture.core.IncrementalStitcher
import com.jacef8.scrollcapture.core.Line
import com.jacef8.scrollcapture.core.Png
import com.jacef8.scrollcapture.core.Rows
import com.jacef8.scrollcapture.core.Step
import com.jacef8.scrollcapture.core.StripStore
import com.jacef8.scrollcapture.core.TextAccumulator
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Outcome(val id: String?, val error: String?)

/**
 * Takes the frames, scrolls between them, and turns them into one tall image
 * and/or one block of text. Runs on a background thread.
 */
class CaptureEngine(
    private val svc: AccessibilityService,
    private val mode: Mode,
    private val prefs: Prefs,
    private val shouldStop: () -> Boolean,
    private val onProgress: (Int) -> Unit,
) {
    private val exec = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    fun run(): Outcome = try {
        runInner()
    } catch (e: Throwable) {
        Log.e(TAG, "capture failed", e)
        Outcome(null, e.message ?: e.javaClass.simpleName)
    } finally {
        exec.shutdown()
    }

    private class FrameText(val header: List<Line>, val body: List<Line>, val footer: List<Line>)

    private fun runInner(): Outcome {
        val id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(svc.cacheDir, "cap/$id").apply { mkdirs() }
        val wantImage = mode != Mode.TEXT
        val wantText = mode == Mode.TEXT || mode == Mode.SCROLL_TEXT
        val scrolling = mode != Mode.SCREENSHOT
        val warnings = ArrayList<String>()
        val screenH = svc.resources.displayMetrics.heightPixels

        val root0 = svc.rootInActiveWindow
        val target = if (scrolling && root0 != null) ScrollTarget.find(root0, screenH) else null
        if (scrolling && target != null && prefs.startFromTop) scrollToTop(target)

        val bmp0 = grab() ?: return Outcome(
            null,
            "Couldn't take a screenshot. Check that Scroll Capture is still turned on in Accessibility."
        )
        val w = bmp0.width
        val h = bmp0.height
        val px0 = pixels(bmp0)
        bmp0.recycle()

        if (isBlank(px0)) {
            if (wantImage) {
                return Outcome(null, "This app doesn't allow screenshots, so the screen came out black. Try Text only.")
            }
            return textWithoutImages(id, dir, target)
        }

        val store = if (wantImage) StripStore(File(dir, "image.raw"), w) else null
        val acc = TextAccumulator()
        val tree0 = if (wantText) currentTree() else emptyList()

        // One screen only.
        if (!scrolling) {
            store?.append(px0, w, 0, h)
            if (wantText) acc.add(split(tree0, 0, h, px0, w).body, 0)
            return finish(id, dir, store, w, 1, acc, wantText, warnings)
        }

        // Scroll once to learn how this screen moves.
        val treeRegion = target?.let { regionOf(it, h) }
        scrollOnce(target, treeRegion, w, h)
        val px1 = grabPixels(w, h)
        if (px1 == null) {
            warnings += "Couldn't scroll this screen, so only what was visible was captured."
            store?.append(px0, w, 0, h)
            if (wantText) acc.add(split(tree0, 0, h, px0, w).body, 0)
            return finish(id, dir, store, w, 1, acc, wantText, warnings)
        }
        var tree = if (wantText) currentTree() else emptyList()

        val sig0 = Rows.signature(px0, w, h)
        val sig1 = Rows.signature(px1, w, h)
        val region = pickRegion(sig0, sig1, treeRegion)
        if (region == null) {
            warnings += if (sig0.hash.contentEquals(sig1.hash)) {
                "Nothing more to scroll here, so this is the whole screen."
            } else {
                "Couldn't line the scrolled screens up, so only the first screen was captured."
            }
            store?.append(px0, w, 0, h)
            if (wantText) acc.add(split(tree0, 0, h, px0, w).body, 0)
            return finish(id, dir, store, w, 1, acc, wantText, warnings)
        }
        val top = region[0]
        val bottom = region[1]

        val st = IncrementalStitcher(w, h, top, bottom, store)
        st.start(px0)
        var lastFooter: List<Line> = emptyList()
        if (wantText) {
            val ft0 = split(tree0, top, bottom, px0, w)
            acc.add(ft0.header, 0)
            acc.add(ft0.body, 0)
            lastFooter = ft0.footer
        }

        var pages = 1
        var px: IntArray = px1
        var warnedLost = false
        while (true) {
            var step = st.next(px, false)
            if (step is Step.Retry) {
                // The screen may still be settling; look once more.
                Thread.sleep(650)
                val again = grabPixels(w, h) ?: break
                px = again
                tree = if (wantText) currentTree() else emptyList()
                step = st.next(px, true)
            }
            when (step) {
                is Step.End -> break
                is Step.Added -> {
                    pages++
                    if (wantText) {
                        val ft = split(tree, top, bottom, px, w)
                        acc.add(ft.body, step.offset)
                        lastFooter = ft.footer
                    }
                }
                is Step.Lost -> {
                    pages++
                    if (!warnedLost) {
                        warnings += "Some parts could not be lined up exactly. Check the join marked in the image."
                        warnedLost = true
                    }
                    if (wantText) {
                        val ft = split(tree, top, bottom, px, w)
                        acc.add(ft.body, null)
                        lastFooter = ft.footer
                    }
                    if (st.consecutiveLost >= 3) {
                        warnings += "Stopped early: the screen kept changing too much to follow."
                        break
                    }
                }
                is Step.Retry -> break
            }
            onProgress(pages)
            if (shouldStop()) break
            if (pages >= MAX_PAGES) {
                warnings += "Stopped after $MAX_PAGES screens. Capture again from where it ended to continue."
                break
            }
            scrollOnce(target, region, w, h)
            px = grabPixels(w, h) ?: break
            tree = if (wantText) currentTree() else emptyList()
        }
        st.finish()
        if (wantText && lastFooter.isNotEmpty()) acc.add(lastFooter, st.offset)

        val rows = store?.rows ?: 0
        return finish(id, dir, store, w, rows, acc, wantText, warnings, pages)
    }

    /** Text only for an app that blacks out screenshots: the words are still readable. */
    private fun textWithoutImages(id: String, dir: File, target: AccessibilityNodeInfo?): Outcome {
        val acc = TextAccumulator()
        var prevKey = ""
        var pages = 0
        while (pages < MAX_PAGES && !shouldStop()) {
            val lines = currentTree()
            val key = lines.joinToString("|") { it.text }
            if (pages > 0 && key == prevKey) break
            acc.add(lines, null)
            prevKey = key
            pages++
            onProgress(pages)
            if (target == null || !ScrollTarget.scrollDown(target)) break
            Thread.sleep(SETTLE_MS)
        }
        val warnings = arrayListOf("This app blocks screenshots, so only its own text was read. Some parts may be missing.")
        return finish(id, dir, null, 0, 0, acc, true, warnings, pages)
    }

    private fun finish(
        id: String,
        dir: File,
        store: StripStore?,
        w: Int,
        rows: Int,
        acc: TextAccumulator,
        wantText: Boolean,
        warnings: MutableList<String>,
        pages: Int = 1,
    ): Outcome {
        store?.close()
        val props = Properties()
        props["pages"] = pages.toString()

        if (store != null) {
            val png = File(dir, "image.png")
            Png.write(File(dir, "image.raw"), w, rows, png)
            props["width"] = w.toString()
            props["rows"] = rows.toString()
            val uri = Saver.saveImage(svc, png, "Screenshot_${id}_ScrollCapture.png")
            if (uri != null) props["imageUri"] = uri.toString() else warnings += "Couldn't save the image to Gallery."
        }
        if (wantText) {
            val text = acc.render()
            if (text.isBlank()) {
                warnings += "No text was found on this screen."
            } else {
                File(dir, "text.txt").writeText(text)
                props["hasText"] = "1"
                val uri = Saver.saveText(svc, text, "Capture_$id.txt")
                if (uri != null) props["textUri"] = uri.toString()
            }
        }
        if (store == null && props["hasText"] == null) {
            return Outcome(null, warnings.firstOrNull() ?: "Nothing was captured.")
        }
        props["warnings"] = warnings.joinToString("\n")
        File(dir, "meta.properties").outputStream().use { props.store(it, null) }
        return Outcome(id, null)
    }

    // ---- text ----

    private fun currentTree(): List<Line> {
        val root = svc.rootInActiveWindow ?: return emptyList()
        return TreeText.collect(root)
    }

    private fun split(tree: List<Line>, top: Int, bottom: Int, px: IntArray?, w: Int): FrameText {
        fun cy(l: Line) = (l.top + l.bottom) / 2
        val header = tree.filter { cy(it) < top }
        val footer = tree.filter { cy(it) >= bottom }
        var body = tree.filter { cy(it) in top until bottom }
        // Some apps hide their text from the tree: read the picture instead.
        if (body.size < 3 && px != null) {
            val read = ocr(px, w, top, bottom)
            if (read.size > body.size) body = read
        }
        return FrameText(header, body, footer)
    }

    private fun ocr(px: IntArray, w: Int, top: Int, bottom: Int): List<Line> {
        return try {
            val hgt = bottom - top
            val crop = Bitmap.createBitmap(px, top * w, w, w, hgt, Bitmap.Config.ARGB_8888)
            val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val result = Tasks.await(rec.process(InputImage.fromBitmap(crop, 0)), 20, TimeUnit.SECONDS)
            rec.close()
            crop.recycle()
            val out = ArrayList<Line>()
            for (b in result.textBlocks) for (l in b.lines) {
                val box = l.boundingBox ?: continue
                out.add(Line(l.text, box.top + top, box.bottom + top, box.left))
            }
            out.sortedWith(compareBy({ it.top }, { it.left }))
        } catch (e: Exception) {
            Log.w(TAG, "ocr failed", e)
            emptyList()
        }
    }

    // ---- screen and scrolling ----

    private fun regionOf(node: AccessibilityNodeInfo, h: Int): IntArray? {
        node.refresh()
        val b = Rect()
        node.getBoundsInScreen(b)
        val t = maxOf(0, b.top)
        val bt = minOf(h, b.bottom)
        return if (bt - t >= h * 0.25) intArrayOf(t, bt) else null
    }

    private fun pickRegion(
        sig0: com.jacef8.scrollcapture.core.RowSig,
        sig1: com.jacef8.scrollcapture.core.RowSig,
        treeRegion: IntArray?,
    ): IntArray? {
        val candidates = ArrayList<IntArray>()
        if (treeRegion != null) candidates += treeRegion
        Rows.movingRegion(sig0, sig1)?.let { m ->
            if (m[1] - m[0] >= sig0.height * 0.25) candidates += m
        }
        for (c in candidates) {
            val sh = Rows.findShift(sig0, sig1, c[0], c[1], (c[1] - c[0]) * 3 / 4)
            if (sh != null && sh.s > 0) return c
        }
        return null
    }

    private fun scrollToTop(target: AccessibilityNodeInfo) {
        var last = ""
        var same = 0
        for (i in 0 until 80) {
            if (shouldStop()) return
            if (!ScrollTarget.scrollUp(target)) return
            Thread.sleep(320)
            val key = currentTree().take(40).joinToString("|") { it.text }
            if (key == last) {
                if (++same >= 2) return
            } else {
                same = 0
            }
            last = key
        }
    }

    private fun scrollOnce(target: AccessibilityNodeInfo?, region: IntArray?, w: Int, h: Int) {
        if (target != null && ScrollTarget.scrollDown(target)) {
            Thread.sleep(SETTLE_MS)
            return
        }
        val t = region?.get(0) ?: 0
        val b = region?.get(1) ?: h
        val span = b - t
        swipe(w / 2, t + (span * 0.8f).toInt(), t + (span * 0.28f).toInt())
        Thread.sleep(SETTLE_MS + 350)
    }

    private fun swipe(x: Int, y1: Int, y2: Int) {
        val path = Path().apply {
            moveTo(x.toFloat(), y1.toFloat())
            lineTo(x.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 380))
            .build()
        val latch = CountDownLatch(1)
        svc.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) = latch.countDown()
            override fun onCancelled(g: GestureDescription?) = latch.countDown()
        }, ui)
        latch.await(3, TimeUnit.SECONDS)
    }

    private fun grabPixels(w: Int, h: Int): IntArray? {
        val b = grab() ?: return null
        if (b.width != w || b.height != h) {
            b.recycle()
            return null
        }
        val px = pixels(b)
        b.recycle()
        return px
    }

    private fun pixels(b: Bitmap): IntArray {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return px
    }

    private fun isBlank(px: IntArray): Boolean {
        var i = 0
        while (i < px.size) {
            if ((px[i] and 0xFFFFFF) != 0) return false
            i += 97
        }
        return true
    }

    /** One screenshot of the whole display, as a normal bitmap. Android allows about one per second. */
    private fun grab(): Bitmap? {
        repeat(8) {
            val latch = CountDownLatch(1)
            val holder = arrayOfNulls<Bitmap>(1)
            val err = intArrayOf(0)
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, exec, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    try {
                        val hb = result.hardwareBuffer
                        val wrapped = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                        holder[0] = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                        wrapped?.recycle()
                        hb.close()
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    err[0] = errorCode
                    latch.countDown()
                }
            })
            latch.await(5, TimeUnit.SECONDS)
            holder[0]?.let { return it }
            Thread.sleep(if (err[0] == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) 500 else 250)
        }
        return null
    }

    private companion object {
        const val TAG = "ScrollCapture"
        const val MAX_PAGES = 150
        const val SETTLE_MS = 750L
    }
}
