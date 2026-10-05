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
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
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
        createChannel()
        ContextCompat.registerReceiver(
            this, stopReceiver, IntentFilter(ACTION_STOP), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        purgeOldCaptures()
    }

    override fun onDestroy() {
        instance = null
        try { unregisterReceiver(stopReceiver) } catch (_: Exception) { }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
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
    fun requestCapture(mode: Mode, delayMs: Long) {
        main.postDelayed({ if (!running) startCapture(mode) }, delayMs)
    }

    private fun startCapture(mode: Mode) {
        if (running) return
        bar?.dismiss()
        bar = null
        running = true
        stopRequested = false
        DebugLog.log("capture requested: $mode")
        buzz(60)
        progressNotice(1)
        Thread {
            val engine = CaptureEngine(this, mode, prefs, { stopRequested }) { progressNotice(it) }
            val outcome = engine.run()
            running = false
            NotificationManagerCompat.from(this).cancel(NOTE_PROGRESS)
            buzz(110)
            main.post {
                if (outcome.id != null) {
                    // A floating toolbar over the live app, not a new screen, so the app stays in front.
                    bar = ResultBar(this, outcome.id) { requestCapture(it, 350) }.also { it.show() }
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

    private fun buzz(ms: Long) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
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
