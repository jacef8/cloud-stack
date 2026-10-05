package com.jacef8.scrollcapture.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Rows are written to disk as they arrive (3 bytes per pixel, no alpha), so a
 * very long capture never has to fit in memory as one bitmap.
 */
class StripStore(private val file: File, val width: Int) : RowSink {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 17)
    private val buf = ByteArray(width * 3)

    var rows = 0
        private set

    override fun append(px: IntArray, width: Int, fromRow: Int, toRow: Int) {
        require(width == this.width) { "width changed mid-capture" }
        if (toRow <= fromRow) return
        for (y in fromRow until toRow) {
            var o = 0
            val base = y * width
            for (x in 0 until width) {
                val p = px[base + x]
                buf[o++] = (p shr 16).toByte()
                buf[o++] = (p shr 8).toByte()
                buf[o++] = p.toByte()
            }
            out.write(buf)
        }
        rows += toRow - fromRow
    }

    fun close() = out.close()
}

object RawRows {
    /** Reads [count] rows starting at [from] back as ARGB ints. */
    fun read(file: File, width: Int, from: Int, count: Int): IntArray {
        val rowLen = width * 3
        val bytes = ByteArray(rowLen * count)
        RandomAccessFile(file, "r").use { f ->
            f.seek(from.toLong() * rowLen)
            f.readFully(bytes)
        }
        val px = IntArray(width * count)
        var i = 0
        for (k in px.indices) {
            val r = bytes[i++].toInt() and 0xFF
            val g = bytes[i++].toInt() and 0xFF
            val b = bytes[i++].toInt() and 0xFF
            px[k] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return px
    }
}

/** Writes a PNG straight from the raw rows file, one row at a time. */
object Png {
    fun write(raw: File, width: Int, height: Int, dest: File) {
        DataOutputStream(BufferedOutputStream(FileOutputStream(dest), 1 shl 16)).use { out ->
            out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            val ihdr = ByteBuffer.allocate(13)
                .putInt(width).putInt(height)
                .put(8).put(2).put(0).put(0).put(0)
                .array()
            chunk(out, "IHDR", ihdr, ihdr.size)

            val idat = IdatStream(out)
            val deflater = Deflater(3)
            val dout = DeflaterOutputStream(idat, deflater, 1 shl 16)

            val rowLen = width * 3
            var prev = ByteArray(rowLen)
            var cur = ByteArray(rowLen)
            val filtered = ByteArray(rowLen + 1)
            filtered[0] = 2 // filter type "Up"
            DataInputStream(BufferedInputStream(FileInputStream(raw), 1 shl 17)).use { input ->
                for (y in 0 until height) {
                    input.readFully(cur)
                    for (i in 0 until rowLen) filtered[i + 1] = (cur[i] - prev[i]).toByte()
                    dout.write(filtered)
                    val t = prev; prev = cur; cur = t
                }
            }
            dout.finish()
            idat.flushChunk()
            deflater.end()
            chunk(out, "IEND", ByteArray(0), 0)
        }
    }

    private class IdatStream(private val out: DataOutputStream) : OutputStream() {
        private val buf = ByteArrayOutputStream(1 shl 18)

        override fun write(b: Int) {
            buf.write(b)
            if (buf.size() >= (1 shl 18)) flushChunk()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            buf.write(b, off, len)
            if (buf.size() >= (1 shl 18)) flushChunk()
        }

        fun flushChunk() {
            if (buf.size() == 0) return
            val data = buf.toByteArray()
            chunk(out, "IDAT", data, data.size)
            buf.reset()
        }
    }

    private fun chunk(out: DataOutputStream, type: String, data: ByteArray, len: Int) {
        val t = type.toByteArray(Charsets.US_ASCII)
        out.writeInt(len)
        out.write(t)
        out.write(data, 0, len)
        val crc = CRC32()
        crc.update(t)
        crc.update(data, 0, len)
        out.writeInt(crc.value.toInt())
    }
}
