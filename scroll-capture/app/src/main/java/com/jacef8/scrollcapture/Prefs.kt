package com.jacef8.scrollcapture

import android.content.Context

enum class Mode { SCREENSHOT, SCROLL, TEXT, SCROLL_TEXT }

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /** Scroll back to the top before a long capture, so nothing above is missed. */
    var startFromTop: Boolean
        get() = sp.getBoolean("top", true)
        set(v) = sp.edit().putBoolean("top", v).apply()

    var autoCopyText: Boolean
        get() = sp.getBoolean("autocopy", true)
        set(v) = sp.edit().putBoolean("autocopy", v).apply()

    /** Opening the app (side button double press) goes straight to the capture choices. */
    var startOnOpen: Boolean
        get() = sp.getBoolean("onopen", false)
        set(v) = sp.edit().putBoolean("onopen", v).apply()

    var volumeTrigger: Boolean
        get() = sp.getBoolean("volume", true)
        set(v) = sp.edit().putBoolean("volume", v).apply()
}
