package com.jacef8.scrollcapture

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

/** Setup and settings. When "open starts capture" is on, it hands straight over to the capture choices. */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var ring: Ring
    private lateinit var ringTitle: TextView
    private lateinit var ringSub: TextView
    private lateinit var step1Status: TextView
    private lateinit var step1Btn: TextView
    private lateinit var step2Status: TextView
    private lateinit var step2Btn: TextView
    private lateinit var captureBtn: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val svc = CaptureService.instance
        val wantsCapture = intent.getBooleanExtra(EXTRA_CAPTURE_NOW, false) ||
            (prefs.startOnOpen && !intent.getBooleanExtra(EXTRA_NO_AUTOSTART, false))
        if (savedInstanceState == null && wantsCapture && svc != null) {
            svc.showPicker(450)
            finish()
            overridePendingTransition(0, 0)
            return
        }
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        if (::ring.isInitialized) refresh()
    }

    private fun enabled(): Boolean {
        if (CaptureService.instance != null) return true
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, CaptureService::class.java)
        return list.split(':').any {
            it.equals(me.flattenToString(), true) || it.equals(me.flattenToShortString(), true)
        }
    }

    private fun notificationsOk(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        val on = enabled()
        val notes = notificationsOk()
        ring.fraction = when { on && notes -> 1f; on -> 0.8f; else -> 0.15f }
        ringTitle.text = if (on) "READY" else "SET UP"
        ringSub.text = if (on) "Press Volume\nUp + Down" else "1 step\nto go"
        step1Status.text = if (on) "On" else "Off"
        step1Status.setTextColor(if (on) C.ACCENT2 else C.WARN)
        step1Btn.text = if (on) "Open Accessibility settings" else "Turn on"
        step2Status.text = if (notes) "On" else "Off (optional)"
        step2Status.setTextColor(if (notes) C.ACCENT2 else C.INK3)
        step2Btn.visibility = if (notes) View.GONE else View.VISIBLE
        captureBtn.alpha = if (on) 1f else 0.4f
    }

    private fun buildUi(): View {
        val dp = { v: Float -> Ui.dp(this, v) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(22f), dp(18f), dp(28f))
        }

        col.addView(Ui.text(this, "Scroll Capture", 30f, C.INK, true))
        col.addView(Ui.text(this, "Long screenshots and every word, on your phone only.", 15f, C.INK3).apply {
            setPadding(0, dp(4f), 0, dp(14f))
        })

        // Anchor. The words inside are sized to the ring's inner circle, with a buffer, so they can never touch it.
        val ringDp = 260f
        val (boxW, boxH) = Ring.safeBoxDp(ringDp)
        val anchor = FrameLayout(this)
        ring = Ring(this)
        anchor.addView(ring, FrameLayout.LayoutParams(dp(ringDp), dp(ringDp), Gravity.CENTER))
        val center = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        ringTitle = Ui.text(this, "", 30f, C.INK, true).apply {
            maxLines = 1
            gravity = Gravity.CENTER
            letterSpacing = -0.02f
            setAutoSizeTextTypeUniformWithConfiguration(14, 30, 1, TypedValue.COMPLEX_UNIT_SP)
        }
        ringSub = Ui.text(this, "", 13f, C.INK3).apply {
            maxLines = 2
            gravity = Gravity.CENTER
            setAutoSizeTextTypeUniformWithConfiguration(12, 13, 1, TypedValue.COMPLEX_UNIT_SP)
        }
        center.addView(ringTitle, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        center.addView(ringSub, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        anchor.addView(center, FrameLayout.LayoutParams(dp(boxW), dp(boxH), Gravity.CENTER))
        col.addView(anchor, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(ringDp + 10f)))

        captureBtn = Ui.text(this, "Capture now", 18f, C.ON_ACCENT, true).apply {
            gravity = Gravity.CENTER
            accentBackground(16f)
            elevation = dp(4f).toFloat()
            setOnClickListener {
                val s = CaptureService.instance
                if (s == null) {
                    Toast.makeText(context, "Turn on Scroll Capture in Accessibility first", Toast.LENGTH_LONG).show()
                } else {
                    moveTaskToBack(true)
                    s.showPicker(700)
                }
            }
        }
        col.addView(captureBtn, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(56f)).apply {
            topMargin = dp(8f); bottomMargin = dp(16f)
        })

        // Setup card
        val setup = card()
        setup.addView(Ui.label(this, "Setup"))

        val s1 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.widget(context)
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            elevation = dp(2f).toFloat()
        }
        val s1Head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        s1Head.addView(Ui.text(this, "1. Accessibility", 16f, C.INK, true), Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        step1Status = Ui.text(this, "", 14f, C.WARN, true)
        s1Head.addView(step1Status)
        s1.addView(s1Head)
        s1.addView(Ui.text(this, "Android only lets an accessibility service take screenshots of other apps and scroll them.", 13f, C.INK3).apply {
            setPadding(0, dp(4f), 0, dp(8f))
        })
        step1Btn = button("Turn on") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Find Scroll Capture under Installed apps", Toast.LENGTH_LONG).show()
        }
        s1.addView(step1Btn)
        s1.addView(Ui.text(this, "If the switch is greyed out: open App info, tap the three dots at the top right, choose Allow restricted settings, then try again.", 13f, C.INK3).apply {
            setPadding(0, dp(10f), 0, dp(6f))
        })
        s1.addView(button("Open App info") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }.apply { secondary() })
        setup.addView(s1, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })

        val s2 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.widget(context)
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            elevation = dp(2f).toFloat()
        }
        val s2Head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        s2Head.addView(Ui.text(this, "2. Notifications", 16f, C.INK, true), Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        step2Status = Ui.text(this, "", 14f, C.INK3, true)
        s2Head.addView(step2Status)
        s2.addView(s2Head)
        s2.addView(Ui.text(this, "Shows progress on long captures, with a Stop button.", 13f, C.INK3).apply {
            setPadding(0, dp(4f), 0, dp(8f))
        })
        step2Btn = button("Allow") {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        s2.addView(step2Btn)
        setup.addView(s2, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })
        col.addView(setup, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14f) })

        // Settings card
        val opts = card()
        opts.addView(Ui.label(this, "How it starts"))
        opts.addView(Ui.toggleRow(this, "Volume Up + Down", "Press both together to open the choices", prefs.volumeTrigger) { prefs.volumeTrigger = it })
        opts.addView(Ui.toggleRow(this, "Open app starts a capture", "For the side button: Settings, Advanced features, Side button, Double press, Open app", prefs.startOnOpen) { prefs.startOnOpen = it })
        opts.addView(Ui.label(this, "Results").apply { setPadding(0, dp(10f), 0, 0) })
        opts.addView(Ui.toggleRow(this, "Copy text automatically", "Text captures are ready to paste right away", prefs.autoCopyText) { prefs.autoCopyText = it })
        opts.addView(Ui.toggleRow(this, "Start long captures at the top", "Scrolls back up first so nothing is missed", prefs.startFromTop) { prefs.startFromTop = it })
        col.addView(opts, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        col.addView(Ui.text(this, "Images save to Pictures/Screenshots next to Samsung's. Text saves to Documents/ScrollCapture. Nothing is uploaded.", 13f, C.INK3).apply {
            setPadding(0, dp(14f), 0, 0)
        })

        refresh()
        return ScrollView(this).apply {
            setBackgroundColor(C.CANVAS)
            isFillViewport = true
            addView(col)
        }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.card(context)
        setPadding(Ui.dp(context, 16f), Ui.dp(context, 14f), Ui.dp(context, 16f), Ui.dp(context, 14f))
    }

    private fun button(label: String, onClick: () -> Unit): TextView =
        Ui.text(this, label, 15f, C.ON_ACCENT, true).apply {
            gravity = Gravity.CENTER
            accentBackground(12f)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 46f))
        }

    private fun TextView.secondary() {
        setTextColor(C.INK)
        background = Ui.widget(context, 12f)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    companion object {
        const val EXTRA_NO_AUTOSTART = "no_autostart"
        const val EXTRA_CAPTURE_NOW = "capture_now"
    }
}
