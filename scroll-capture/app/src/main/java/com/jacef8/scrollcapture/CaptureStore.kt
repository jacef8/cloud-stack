package com.jacef8.scrollcapture

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.Properties

/** Reads and removes earlier captures (the files kept for the viewer, and the Gallery copy). */
object CaptureStore {
    fun dir(ctx: Context, id: String) = File(ctx.cacheDir, "cap/$id")

    fun meta(ctx: Context, id: String): Properties = Properties().also { p ->
        try { File(dir(ctx, id), "meta.properties").inputStream().use { p.load(it) } } catch (_: Exception) { }
    }

    /** Removes the picture from Gallery (the saved copy), leaving the viewer's files alone. */
    fun deleteGalleryImage(ctx: Context, id: String) {
        meta(ctx, id).getProperty("imageUri")?.let {
            try { ctx.contentResolver.delete(Uri.parse(it), null, null) } catch (e: Exception) { DebugLog.error("delete gallery image", e) }
        }
    }

    fun deleteDir(ctx: Context, id: String) {
        dir(ctx, id).deleteRecursively()
    }
}
