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

/** What the engine tells the service while it works, so the service can show feedback. */
interface CaptureListener {
    /** About to take the first picture: hide anything of ours that must not be in it. */
    fun beforeFirstFrame()
    /** The first picture is taken (the shutter click for a plain screenshot). */
    fun firstFrameTaken()
    /** A long capture has [pages] screens so far. */
    fun progress(pages: Int)
}

/**
 * Takes the frames, scrolls between them, and turns them into one tall image
 * and/or one block of text. Runs on a background thread.
 */
class CaptureEngine(
    private val svc: AccessibilityService,
    private val mode: Mode,
    private val prefs: Prefs,
    private val shouldStop: () -> Boolean,
    private val listener: CaptureListener,
) {
    private val exec = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var lastApp = ""

    fun run(): Outcome = try {
        runInner()
    } catch (e: Throwable) {
        Log.e(TAG, "capture failed", e)
        DebugLog.error("capture failed", e)
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

        // If our own viewer is still closing, wait for the app underneath to come back to the front.
        var root0 = svc.rootInActiveWindow
        var waited = 0
        while (root0?.packageName == svc.packageName && mode != Mode.SCREENSHOT && waited < 8) {
            Thread.sleep(250)
            root0 = svc.rootInActiveWindow
            waited++
        }
        val pkg = root0?.packageName?.toString().orEmpty()
        lastApp = pkg
        DebugLog.log("capture start mode=$mode app=$pkg waited=${waited * 250}ms")
        if (pkg == svc.packageName && mode != Mode.SCREENSHOT) {
            return Outcome(null, "Couldn't find the app to capture. Go to the screen you want, then press Volume Up + Down.")
        }
        val target = if (scrolling && root0 != null) ScrollTarget.find(root0, screenH) else null
        DebugLog.log("scroll area: ${if (target != null) "found" else "none"}")
        if (scrolling && target != null && prefs.startFromTop) scrollToTop(target)

        if (scrolling) {
            listener.beforeFirstFrame()   // hide the "Getting ready" pill so it is not in the picture
            Thread.sleep(170)
        }
        val bmp0 = grab() ?: return Outcome(
            null,
            "Couldn't take a screenshot. Check that Scroll Capture is still turned on in Accessibility."
        )
        listener.firstFrameTaken()   // the picture is taken: feedback fires now, before anything is saved
        val w = bmp0.width
        val h = bmp0.height
        val px0 = pixels(bmp0)
        bmp0.recycle()

        DebugLog.log("first frame ${w}x$h")
        if (isBlank(px0)) {
            DebugLog.log("first frame is all black: $pkg blocks screenshots")
            if (wantImage) {
                val who = if (pkg.contains("settings")) "Settings pages" else "This screen"
                return Outcome(null, "$who can't be captured: Android blocks screenshots there, so it came out black. Try a normal app screen.")
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
            return finish(id, dir, store, w, acc, wantText, warnings)
        }

        // Scroll once to learn how this screen moves. Scrolling is done like a finger swipe of about half
        // the area: the built-in "scroll down" command moves a whole screenful, which leaves no overlap
        // between pictures to line them up on.
        val treeRegion = target?.let { regionOf(it, h) }
        val sig0 = Rows.signature(px0, w, h)
        var useAction = false
        scrollOnce(null, treeRegion, w, h)
        var px1 = grabPixels(w, h)
        if (px1 != null && target != null && Rows.signature(px1, w, h).hash.contentEquals(sig0.hash)) {
            // The swipe moved nothing (gestures blocked?): try the scroll command instead.
            DebugLog.log("the swipe moved nothing; trying the scroll command instead")
            scrollOnce(target, treeRegion, w, h)
            val second = grabPixels(w, h)
            if (second != null) {
                px1 = second
                useAction = true
            }
        }
        if (px1 == null) {
            warnings += "Couldn't scroll this screen, so only what was visible was captured."
            store?.append(px0, w, 0, h)
            if (wantText) acc.add(split(tree0, 0, h, px0, w).body, 0)
            return finish(id, dir, store, w, acc, wantText, warnings)
        }
        var tree = if (wantText) currentTree() else emptyList()

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
            return finish(id, dir, store, w, acc, wantText, warnings)
        }
        val top = region[0]
        val bottom = region[1]
        DebugLog.log("scroll region $top-$bottom")
        listener.progress(1)   // from here on the "Capturing…" pill is shown, above the scroll area so it never lands in the image

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
                is Step.End -> { DebugLog.log("reached the end after $pages screens"); break }
                is Step.Added -> {
                    pages++
                    DebugLog.log("page $pages: moved ${step.shift}")
                    if (wantText) {
                        val ft = split(tree, top, bottom, px, w)
                        acc.add(ft.body, step.offset)
                        lastFooter = ft.footer
                    }
                }
                is Step.Lost -> {
                    DebugLog.log("page ${pages + 1}: the screen changed and could not be followed")
                    warnings += "Stopped: the screen changed in a way that couldn't be followed (did something move or open?). Everything captured up to that point is kept."
                    break
                }
                is Step.Retry -> break
            }
            listener.progress(pages)
            if (shouldStop()) break
            if (pages >= MAX_PAGES) {
                warnings += "Stopped after $MAX_PAGES screens. Capture again from where it ended to continue."
                break
            }
            if (leftApp(pkg)) {
                DebugLog.log("left the app: now in ${svc.rootInActiveWindow?.packageName}")
                warnings += "Stopped because you left the app. Everything captured up to that point is kept."
                break
            }
            scrollOnce(if (useAction) target else null, region, w, h)
            px = grabPixels(w, h) ?: break
            tree = if (wantText) currentTree() else emptyList()
        }
        st.finish()
        if (wantText && lastFooter.isNotEmpty()) acc.add(lastFooter, st.offset)

        return finish(id, dir, store, w, acc, wantText, warnings, pages)
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
            listener.progress(pages)
            if (target == null || !ScrollTarget.scrollDown(target)) break
            Thread.sleep(SETTLE_MS)
        }
        val warnings = arrayListOf("This app blocks screenshots, so only its own text was read. Some parts may be missing.")
        return finish(id, dir, null, 0, acc, true, warnings, pages)
    }

    private fun finish(
        id: String,
        dir: File,
        store: StripStore?,
        w: Int,
        acc: TextAccumulator,
        wantText: Boolean,
        warnings: MutableList<String>,
        pages: Int = 1,
    ): Outcome {
        store?.close()
        val rows = store?.rows ?: 0
        val props = Properties()
        props["pages"] = pages.toString()

        if (store != null) {
            val png = File(dir, "image.png")
            Png.write(File(dir, "image.raw"), w, rows, png)
            props["width"] = w.toString()
            props["rows"] = rows.toString()
            val uri = Saver.saveImage(svc, png, imageName(id), w, rows)
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

    /** Samsung-style: Screenshot_20261005_101800_Chrome.png */
    private fun imageName(id: String): String {
        val stamp = id.replace('-', '_')
        val pkg = lastApp
        val label = try {
            val info = svc.packageManager.getApplicationInfo(pkg, 0)
            svc.packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            pkg.substringAfterLast('.')
        }
        val clean = label.filter { it.isLetterOrDigit() }.take(24).ifEmpty { "ScrollCapture" }
        return "Screenshot_${stamp}_$clean.png"
    }

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

    /** True when a different app has come to the front since the capture began (not the keyboard or system bars). */
    private fun leftApp(start: String): Boolean {
        val now = svc.rootInActiveWindow?.packageName?.toString() ?: return false
        if (start.isEmpty() || now == start || now == svc.packageName) return false
        val ignorable = now == "com.android.systemui" || now.contains("inputmethod") ||
            now.contains("honeyboard") || now.contains("keyboard")
        return !ignorable
    }

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
        swipe(w / 2, t + (span * 0.78f).toInt(), t + (span * 0.28f).toInt())
        Thread.sleep(SETTLE_MS + 350)
    }

    private fun swipe(x: Int, y1: Int, y2: Int) {
        val path = Path().apply {
            moveTo(x.toFloat(), y1.toFloat())
            lineTo(x.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 450))
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
        const val MAX_PAGES = 250
        const val SETTLE_MS = 750L
    }
}
