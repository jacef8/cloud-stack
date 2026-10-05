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
import com.jacef8.scrollcapture.core.SessionControl
import com.jacef8.scrollcapture.core.TreeAlign
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
    /** About to take a picture: make anything of ours that is on screen invisible so it is not in it. */
    fun beforeGrab()
    /** The picture is taken: show it again. */
    fun afterGrab()
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
    private val control: SessionControl? = null,
) {
    private val exec = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var lastApp = ""
    private var debugNote = ""

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
        // A person-driven session extends down from where they are; only a hands-off capture goes back to the top first.
        if (scrolling && target != null && prefs.startFromTop && control == null) scrollToTop(target)

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

        // ---- a scrolling capture ----
        val treeRegion = target?.let { regionOf(it, h) }
        val sig0 = Rows.signature(px0, w, h)
        val words0 = currentTree()          // the words on screen now: for text, and to line pictures up when pixels cannot
        var useAction = false
        var movedBy = "drag"

        // Scroll once to learn how this screen moves. Scrolling is a finger drag of under half the area that
        // pauses before lifting (so the list does not coast on): the built-in "scroll down" command moves a
        // whole screenful, which leaves no overlap between pictures to line them up on.
        scrollOnce(null, treeRegion, w, h)
        control?.consumeInitial()
        var px1 = grabPixels(w, h)
        var words1 = currentTree()

        val stillTop = treeRegion?.get(0) ?: 0
        val stillBottom = treeRegion?.get(1) ?: h
        fun stillPage(p: IntArray?): Boolean =
            p != null && Rows.unchangedShare(sig0, Rows.signature(p, w, h), stillTop, stillBottom) > 0.92f

        if (stillPage(px1)) {
            // Nothing moved. Some pages ignore a slow drag: try a quick flick, then the scroll command.
            DebugLog.log("the drag moved nothing; trying a quick flick")
            movedBy = "flick"
            flickOnce(treeRegion, w, h)
            px1 = grabPixels(w, h)
            words1 = currentTree()
            if (stillPage(px1) && target != null) {
                DebugLog.log("the flick moved nothing; trying the scroll command")
                movedBy = "command"
                scrollOnce(target, treeRegion, w, h)
                px1 = grabPixels(w, h)
                words1 = currentTree()
                useAction = !stillPage(px1)
            }
        }
        if (px1 == null) {
            warnings += "Couldn't scroll this screen, so only what was visible was captured."
            store?.append(px0, w, 0, h)
            if (wantText) acc.add(split(tree0, 0, h, px0, w).body, 0)
            return finish(id, dir, store, w, acc, wantText, warnings)
        }
        if (stillPage(px1)) {
            DebugLog.log("nothing moved after three tries")
            warnings += "This screen didn't scroll (it may already be at the end), so only what was visible was captured."
            store?.append(px0, w, 0, h)
            if (wantText) acc.add(split(tree0, 0, h, px0, w).body, 0)
            return finish(id, dir, store, w, acc, wantText, warnings)
        }
        var tree = words1

        val sig1 = Rows.signature(px1, w, h)
        var region = pickRegion(sig0, sig1, treeRegion)
        var firstShift: Int? = null
        if (region == null) {
            DebugLog.log("pictures did not line up: ${Rows.lastNote}")
            // Fall back to where the words sit: the same words, lower or higher, give the distance.
            val cand = treeRegion ?: Rows.movingRegion(sig0, sig1) ?: intArrayOf(0, h)
            val s = TreeAlign.shift(words0, words1, cand[0], cand[1], 12, (cand[1] - cand[0]) - 40)
            if (s != null) {
                DebugLog.log("lined up from the words on screen: moved $s")
                region = cand
                firstShift = s
            }
        }
        if (region == null && movedBy == "command") {
            // The scroll command moves exactly one screenful, so there is no overlap to match on; the pictures
            // simply follow one another. Join them directly.
            val cand = treeRegion ?: intArrayOf(0, h)
            DebugLog.log("a whole-screen jump: joining the pictures end to end (${cand[1] - cand[0]})")
            region = cand
            firstShift = cand[1] - cand[0]
        }
        if (region == null) {
            debugNote = "moved by $movedBy; unchanged ${"%.2f".format(Rows.unchangedShare(sig0, sig1, stillTop, stillBottom))}; ${Rows.lastNote.ifEmpty { "no candidate shift" }}; words ${words0.size}/${words1.size}"
            warnings += "Couldn't line the scrolled screens up, so only the first screen was captured."
            store?.append(px0, w, 0, h)
            if (wantText) acc.add(split(tree0, 0, h, px0, w).body, 0)
            return finish(id, dir, store, w, acc, wantText, warnings)
        }
        val top = region[0]
        val bottom = region[1]
        DebugLog.log("scroll region $top-$bottom")
        listener.progress(1)   // from here on the status pill is shown, above the scroll area so it never lands in the image

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
        var prevWords = words0
        var forced: Int? = firstShift
        while (true) {
            val known = forced
            forced = null
            var step: Step = if (known != null) st.nextWith(px, known) else st.next(px, false)
            if (step is Step.Retry) {
                // The screen may still be settling; look once more.
                Thread.sleep(650)
                val again = grabPixels(w, h) ?: break
                px = again
                tree = currentTree()
                step = st.next(px, true)
                if (step is Step.Lost) {
                    val s = TreeAlign.shift(prevWords, tree, top, bottom, 12, (bottom - top) - 40)
                    if (s != null) {
                        DebugLog.log("lined up from the words on screen: moved $s")
                        step = st.nextWith(px, s)
                    } else if (useAction) {
                        // Whole-screenful jumps do not overlap: the next picture simply follows on.
                        DebugLog.log("whole-screen jump: following on directly")
                        step = st.nextWith(px, bottom - top)
                    }
                }
            }
            when (step) {
                is Step.End -> { DebugLog.log("reached the end after $pages screens"); break }
                is Step.Added -> {
                    pages++
                    DebugLog.log("page $pages: moved ${step.shift} (${Rows.lastNote})")
                    if (wantText) {
                        val ft = split(tree, top, bottom, px, w)
                        acc.add(ft.body, step.offset)
                        lastFooter = ft.footer
                    }
                    prevWords = tree
                }
                is Step.Lost -> {
                    DebugLog.log("page ${pages + 1}: could not line up (${Rows.lastNote})")
                    debugNote = "page ${pages + 1}; ${Rows.lastNote.ifEmpty { "no candidate shift" }}; words ${prevWords.size}/${tree.size}"
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
            // Driven by the person: hold to keep scrolling, let go to pause, Done (or leaving it idle) to finish.
            if (control != null && !control.waitForStep(IDLE_MS)) {
                DebugLog.log("finished after $pages screens (done, or left idle)")
                break
            }
            if (leftApp(pkg)) {
                DebugLog.log("left the app: now in ${svc.rootInActiveWindow?.packageName}")
                warnings += "Stopped because you left the app. Everything captured up to that point is kept."
                break
            }
            scrollOnce(if (useAction) target else null, region, w, h)
            px = grabPixels(w, h) ?: break
            tree = currentTree()
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
        if (warnings.isNotEmpty() && debugNote.isNotEmpty()) props["debug"] = debugNote
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
        swipe(w / 2, t + (span * 0.75f).toInt(), t + (span * 0.30f).toInt())
        Thread.sleep(SETTLE_MS - 100)
    }

    private fun flickOnce(region: IntArray?, w: Int, h: Int) {
        val t = region?.get(0) ?: 0
        val b = region?.get(1) ?: h
        val span = b - t
        val path = Path().apply {
            moveTo(w / 2f, t + span * 0.70f)
            lineTo(w / 2f, t + span * 0.40f)
        }
        dispatch(GestureDescription.StrokeDescription(path, 0, 200))
        Thread.sleep(SETTLE_MS + 500)
    }

    /**
     * Drag up, then hold still for a moment before lifting. A finger that lifts at speed makes a
     * list coast on by a screenful or more, leaving nothing to line the pictures up on.
     */
    private fun swipe(x: Int, y1: Int, y2: Int) {
        val drag = Path().apply {
            moveTo(x.toFloat(), y1.toFloat())
            lineTo(x.toFloat(), y2.toFloat())
        }
        val first = GestureDescription.StrokeDescription(drag, 0, 420, true)
        if (!dispatch(first)) return
        val stay = Path().apply { moveTo(x.toFloat(), y2.toFloat()) }
        dispatch(first.continueStroke(stay, 0, 280, false))
    }

    private fun dispatch(stroke: GestureDescription.StrokeDescription): Boolean {
        val latch = CountDownLatch(1)
        val ok = BooleanArray(1)
        val accepted = svc.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { ok[0] = true; latch.countDown() }
                override fun onCancelled(g: GestureDescription?) { latch.countDown() }
            }, ui)
        if (!accepted) return false
        latch.await(4, TimeUnit.SECONDS)
        return ok[0]
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
        // Anything of ours on screen (the buttons, the status pill) goes invisible for the picture, so it is never in it.
        val hide = mode != Mode.SCREENSHOT
        if (hide) {
            listener.beforeGrab()
            Thread.sleep(UI_HIDE_MS)
        }
        try {
            return grabRaw()
        } finally {
            if (hide) listener.afterGrab()
        }
    }

    private fun grabRaw(): Bitmap? {
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
        const val UI_HIDE_MS = 150L
        const val IDLE_MS = 25_000L
    }
}
