package com.jacef8.scrollcapture

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.MediaActionSound
import android.media.SoundPool
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibrationAttributes
import android.os.Vibrator
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jacef8.scrollcapture.core.SessionControl
import java.io.File

/**
 * The always-on part. It listens for Volume Up + Volume Down, shows the choices,
 * and runs the capture. It is an accessibility service because that is the only
 * way Android lets an app screenshot and scroll other apps.
 */
class CaptureService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs

    private var bar: ResultBar? = null
    @Volatile private var replaceId: String? = null
    @Volatile private var control: SessionControl? = null
    private var shutterSound: MediaActionSound? = null
    private var clickPool: SoundPool? = null
    private var clickId = 0
    @Volatile private var clickReady = false
    private val indicator by lazy { CaptureIndicator(this) { control?.finish(); stopRequested = true } }

    @Volatile private var running = false
    @Volatile private var stopRequested = false

    private var upDown = false
    private var downDown = false
    private var combo = false
    private var pendingKey: Runnable? = null

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { stopRequested = true }
    }

    override fun onServiceConnected() {
        instance = this
        prefs = Prefs(this)
        DebugLog.init(this)
        DebugLog.log("service connected")
        // Load the camera shutter now so it plays instantly later.
        shutterSound = MediaActionSound().also { it.load(MediaActionSound.SHUTTER_CLICK) }
        // The phone's own click, played through a sound pool so its volume can be set (MediaActionSound is always loud).
        try {
            val file = File("/system/media/audio/ui/camera_click.ogg")
            if (file.exists()) {
                val pool = SoundPool.Builder()
                    .setMaxStreams(1)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    ).build()
                pool.setOnLoadCompleteListener { _, _, status -> clickReady = status == 0 }
                clickId = pool.load(file.path, 1)
                clickPool = pool
            }
        } catch (e: Exception) {
            DebugLog.error("click sound", e)
        }
        createChannel()
        ContextCompat.registerReceiver(
            this, stopReceiver, IntentFilter(ACTION_STOP), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        purgeOldCaptures()
    }

    override fun onDestroy() {
        instance = null
        try { unregisterReceiver(stopReceiver) } catch (_: Exception) { }
        shutterSound?.release()
        shutterSound = null
        clickPool?.release()
        clickPool = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // While a person-driven scroll capture is open, note whenever the page scrolls (by hand or by a step),
        // so a picture is taken once it has stopped moving.
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && running &&
            event.packageName?.toString() != packageName
        ) {
            control?.noteScroll()
        }
    }
    override fun onInterrupt() = Unit

    // ---- Volume Up + Volume Down ----

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (!::prefs.isInitialized || !prefs.volumeTrigger) return false
        val code = e.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        val isUp = code == KeyEvent.KEYCODE_VOLUME_UP
        val direction = if (isUp) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER

        if (e.action == KeyEvent.ACTION_DOWN) {
            if (e.repeatCount > 0) {
                // Key held down: behave like a normal volume key unless it is part of the shortcut.
                if (!combo) adjustVolume(direction)
                return true
            }
            if (isUp) upDown = true else downDown = true
            if (upDown && downDown) {
                combo = true
                pendingKey?.let { main.removeCallbacks(it) }
                pendingKey = null
                onShortcut()
                return true
            }
            // Wait a moment to see whether the other key follows. If not, it is a normal volume press.
            val r = Runnable { pendingKey = null; adjustVolume(direction) }
            pendingKey = r
            main.postDelayed(r, COMBO_WINDOW_MS)
            return true
        }

        if (e.action == KeyEvent.ACTION_UP) {
            if (isUp) upDown = false else downDown = false
            if (!upDown && !downDown) combo = false
            pendingKey?.let { main.removeCallbacks(it); it.run() }
            return true
        }
        return false
    }

    private fun adjustVolume(direction: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustSuggestedStreamVolume(direction, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
    }

    private fun onShortcut() {
        // While a long capture runs the shortcut stops it; otherwise it takes a screenshot at once.
        if (running) stopRequested = true else requestCapture(Mode.SCREENSHOT, 0)
    }

    // ---- capture ----

    /** Starts a capture after [delayMs]. A screenshot from the shortcut starts at once. */
    fun requestCapture(mode: Mode, delayMs: Long, replacesId: String? = null) {
        main.postDelayed({
            if (!running) {
                replaceId = replacesId
                startCapture(mode)
            }
        }, delayMs)
    }

    /** The white scroll circle was pressed: begin a person-driven scroll capture, or take another step of it. */
    private fun scrollPress(id: String) {
        // You scroll the page by hand; the tool captures as it moves. Tapping the circle gives one automatic step.
        if (running) return
        val fresh = SessionControl().also { it.press() }
        control = fresh
        replaceId = id
        bar?.dismiss()
        bar = null
        startCapture(Mode.SCROLL, fresh)
    }

    private fun startCapture(mode: Mode, session: SessionControl? = null) {
        if (running) return
        if (session == null) {
            bar?.dismiss()
            bar = null
        }
        running = true
        stopRequested = false
        DebugLog.log("capture requested: $mode")
        // A plain screenshot gives its feedback the instant the picture is taken (below); a long
        // capture buzzes now to say it has started, and clicks when it is done.
        if (mode != Mode.SCREENSHOT) sharpBuzz()
        progressNotice(1)
        // A long capture shows its pill straight away, so it is clear it is working and not waiting on you.
        if (mode != Mode.SCREENSHOT) {
            main.post { indicator.show(if (session != null) "Scroll the page" else "Scrolling…") }
        }
        Thread {
            val listener = object : CaptureListener {
                override fun beforeGrab() { main.post { indicator.setVisible(false); bar?.setGrabHidden(true) } }
                override fun afterGrab() { main.post { indicator.setVisible(true); bar?.setGrabHidden(false) } }
                override fun firstFrameTaken() { if (mode == Mode.SCREENSHOT) captureFeedback() }
                override fun warn() { doubleBuzz() }
                override fun note(text: String) { DebugLog.log("note: $text") }
                override fun progress(pages: Int) {
                    progressNotice(pages)
                    main.post {
                        if (running) {
                            indicator.show(if (session != null) "Scroll the page · $pages" else "Scrolling… $pages")
                        }
                    }
                }
            }
            val engine = CaptureEngine(this, mode, prefs, { stopRequested || session?.done == true }, listener, session)
            val outcome = engine.run()
            running = false
            control = null
            // Anything the capture wants to tell you (stopped early, nothing to scroll, ...).
            var shownId = outcome.id
            val replaced = replaceId
            replaceId = null
            val detail = outcome.id?.let { id -> CaptureStore.meta(this, id).getProperty("debug", "") }.orEmpty()
            var warning = outcome.id?.let { id ->
                try {
                    java.util.Properties().also { p ->
                        File(cacheDir, "cap/$id/meta.properties").inputStream().use { p.load(it) }
                    }.getProperty("warnings", "").lines().firstOrNull().orEmpty()
                } catch (_: Exception) { "" }
            }.orEmpty()
            // Like Samsung's, ONE continuous image: a scroll that worked replaces the first screenshot of
            // that screen; one that did not keeps the original and adds no duplicate.
            if (outcome.id != null && replaced != null && mode != Mode.SCREENSHOT && mode != Mode.TEXT) {
                val meta = CaptureStore.meta(this, outcome.id)
                val pages = meta.getProperty("pages", "1").toIntOrNull() ?: 1
                val hasText = File(CaptureStore.dir(this, outcome.id), "text.txt").exists()
                if (meta.getProperty("imageUri") != null) {
                    if (pages > 1) {
                        CaptureStore.deleteGalleryImage(this, replaced)
                        CaptureStore.deleteDir(this, replaced)
                    } else if (!hasText) {
                        CaptureStore.deleteGalleryImage(this, outcome.id)
                        CaptureStore.deleteDir(this, outcome.id)
                        shownId = replaced
                    }
                }
            }
            NotificationManagerCompat.from(this).cancel(NOTE_PROGRESS)
            // The screenshot already clicked; a long capture finishing is only a buzz, so there is one camera sound, not two.
            if (outcome.id != null) { if (mode != Mode.SCREENSHOT) sharpBuzz() } else sharpBuzz(120)
            main.post {
                indicator.hide()
                bar?.dismiss()
                bar = null
                if (shownId != null) {
                    // A floating toolbar over the live app, not a new screen, so the app stays in front.
                    val shown: String = shownId
                    bar = ResultBar(
                        this, shown, warning, "",
                        onMore = { requestCapture(it, 350, shown) },
                        onScrollPress = { scrollPress(shown) },
                        onScrollRelease = { },
                        onDone = { },
                    ).also { it.show() }
                } else {
                    DebugLog.log("capture failed: ${outcome.error}")
                    Toast.makeText(this, outcome.error ?: "Capture failed", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ---- notification and feedback ----

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Capture progress", NotificationManager.IMPORTANCE_LOW)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(ch)

    }

    private fun progressNotice(pages: Int) {
        val stop = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_STOP).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Capturing… screen $pages")
            .setContentText("Press Volume Up + Down or tap Stop to finish early")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stop)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(NOTE_PROGRESS, n)
        } catch (_: SecurityException) { }
    }

    /** The camera shutter click and a quick, sharp buzz, together. */
    private fun captureFeedback() {
        sharpBuzz()
        when (prefs.shutterLevel) {
            0 -> Unit
            else -> {
                // Quiet is a quarter of the phone's system volume for touch sounds; Normal is about two thirds.
                val volume = if (prefs.shutterLevel == 1) 0.22f else 0.65f
                val pool = clickPool
                if (pool != null && clickReady) {
                    pool.play(clickId, volume, volume, 1, 0, 1f)
                } else if (prefs.shutterLevel == 2) {
                    try { shutterSound?.play(MediaActionSound.SHUTTER_CLICK) } catch (_: Exception) { }
                }
            }
        }
    }

    /** A short buzz at full strength, so it feels like a click. Marked as accessibility feedback so it is not muted with touch feedback. */
    private fun sharpBuzz(ms: Long = 30) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrate(VibrationEffect.createOneShot(ms, if (v.hasAmplitudeControl()) 255 else VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /** Two quick buzzes: "slow down", felt in the middle of a swipe. */
    private fun doubleBuzz() {
        vibrate(VibrationEffect.createWaveform(longArrayOf(0, 35, 70, 35), -1))
    }

    private fun vibrate(effect: VibrationEffect) {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ACCESSIBILITY))
            } else {
                v.vibrate(effect)
            }
        } catch (e: Exception) {
            DebugLog.error("buzz", e)
        }
    }

    /** Captures kept only while they might still be open on screen. */
    private fun purgeOldCaptures() {
        val dir = File(cacheDir, "cap")
        val cutoff = System.currentTimeMillis() - 6 * 60 * 60 * 1000
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.deleteRecursively() }
    }

    companion object {
        @Volatile var instance: CaptureService? = null
        private const val CHANNEL = "capture"
        private const val NOTE_PROGRESS = 1
        private const val ACTION_STOP = "com.jacef8.scrollcapture.STOP"
        private const val COMBO_WINDOW_MS = 160L
    }
}
