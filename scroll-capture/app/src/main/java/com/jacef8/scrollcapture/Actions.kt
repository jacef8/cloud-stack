package com.jacef8.scrollcapture

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** Share and edit, used by both the floating toolbar and the full-screen viewer. */
object Actions {
    fun fileUri(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)

    fun share(ctx: Context, dir: File, image: Boolean) {
        val send = Intent(Intent.ACTION_SEND)
        if (image && File(dir, "image.png").exists()) {
            send.type = "image/png"
            send.putExtra(Intent.EXTRA_STREAM, fileUri(ctx, File(dir, "image.png")))
        } else {
            val f = File(dir, "text.txt")
            val text = f.readText()
            send.type = "text/plain"
            if (text.length < 300_000) send.putExtra(Intent.EXTRA_TEXT, text)
            else send.putExtra(Intent.EXTRA_STREAM, fileUri(ctx, f))
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun edit(ctx: Context, dir: File) {
        val i = Intent(Intent.ACTION_EDIT).setDataAndType(fileUri(ctx, File(dir, "image.png")), "image/png")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            ctx.startActivity(Intent.createChooser(i, "Edit with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(ctx, "No editor found", Toast.LENGTH_SHORT).show()
        }
    }
}
