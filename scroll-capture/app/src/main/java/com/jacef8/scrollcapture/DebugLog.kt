package com.jacef8.scrollcapture

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A small on-phone log of what the app did, so a failure can be pasted and diagnosed. Never leaves the phone by itself. */
object DebugLog {
    private var file: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(ctx: Context) {
        if (file == null) file = File(ctx.applicationContext.filesDir, "log.txt")
    }

    @Synchronized
    fun log(msg: String) {
        Log.d("ScrollCapture", msg)
        val f = file ?: return
        try {
            if (f.length() > 120_000) {
                // Keep the newest half.
                val text = f.readText()
                f.writeText(text.substring(text.length / 2))
            }
            f.appendText("${fmt.format(Date())} $msg\n")
        } catch (_: Exception) { }
    }

    fun error(msg: String, t: Throwable) {
        log("$msg: $t")
        t.stackTrace.take(8).forEach { log("    at $it") }
    }

    @Synchronized
    fun read(): String = try { file?.readText().orEmpty() } catch (_: Exception) { "" }
}
