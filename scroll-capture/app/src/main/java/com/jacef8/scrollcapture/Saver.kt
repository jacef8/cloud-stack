package com.jacef8.scrollcapture

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Puts finished captures where Samsung's own screenshots go, so they show up in Gallery and Photos. */
object Saver {
    /**
     * Samsung phones keep their screenshots in DCIM/Screenshots, so that is where these go first,
     * and Gallery and Google Photos list them with the rest. Pictures/Screenshots is the fallback.
     */
    fun saveImage(ctx: Context, png: File, name: String, width: Int, height: Int): Uri? {
        val folders = listOf(
            Environment.DIRECTORY_DCIM + "/Screenshots",
            Environment.DIRECTORY_PICTURES + "/Screenshots",
        )
        for (folder in folders) {
            val uri = insertImage(ctx, png, name, width, height, folder)
            if (uri != null) return uri
        }
        return null
    }

    private fun insertImage(ctx: Context, png: File, name: String, width: Int, height: Int, folder: String): Uri? {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DATE_TAKEN, now)
            put(MediaStore.Images.Media.DATE_ADDED, now / 1000)
            put(MediaStore.Images.Media.DATE_MODIFIED, now / 1000)
            put(MediaStore.Images.Media.WIDTH, width)
            put(MediaStore.Images.Media.HEIGHT, height)
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, folder)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = try {
            resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        } catch (e: Exception) {
            DebugLog.error("insert into $folder", e)
            null
        } ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out -> png.inputStream().use { it.copyTo(out) } }
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            verify(ctx, uri, name, folder)
            uri
        } catch (e: Exception) {
            DebugLog.error("saving image to $folder", e)
            try { resolver.delete(uri, null, null) } catch (_: Exception) { }
            null
        }
    }

    /** Looks the saved row back up, logs where it really is, and asks Android to index the file. */
    private fun verify(ctx: Context, uri: Uri, name: String, folder: String) {
        val cols = arrayOf(
            MediaStore.Images.Media.DATA, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.IS_PENDING,
        )
        ctx.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val path = c.getString(0)
                DebugLog.log("saved $name in $folder: path=$path size=${c.getLong(1)} ${c.getInt(2)}x${c.getInt(3)} pending=${c.getInt(4)}")
                if (!path.isNullOrEmpty()) {
                    MediaScannerConnection.scanFile(ctx, arrayOf(path), arrayOf("image/png"), null)
                }
            } else {
                DebugLog.log("saved $name but the row could not be read back: $uri")
            }
        }
    }

    fun saveText(ctx: Context, text: String, name: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Files.FileColumns.DISPLAY_NAME, name)
            put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain")
            put(MediaStore.Files.FileColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/ScrollCapture")
            put(MediaStore.Files.FileColumns.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            val done = ContentValues().apply { put(MediaStore.Files.FileColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }
}
