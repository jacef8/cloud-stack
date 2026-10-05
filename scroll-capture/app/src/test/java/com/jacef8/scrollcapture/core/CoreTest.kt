package com.jacef8.scrollcapture.core

import java.io.File
import java.util.Random
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Inflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    private val w = 120
    private val h = 1000
    private val headerRows = 100
    private val footerRows = 80
    private val top = headerRows
    private val bottom = h - footerRows

    /** A page whose rows each look different, like lines of text. */
    private fun makeDoc(rows: Int, seed: Long): IntArray {
        val rnd = Random(seed)
        val doc = IntArray(rows * w)
        for (y in 0 until rows) {
            val bg = 0xFF000000.toInt() or (0xF0 shl 16) or (0xF0 shl 8) or 0xF0
            for (x in 0 until w) doc[y * w + x] = bg
            if (y % 3 != 0) {
                val ink = 0xFF000000.toInt() or rnd.nextInt(0xFFFFFF)
                val start = rnd.nextInt(30)
                val len = 20 + rnd.nextInt(60)
                for (x in start until minOf(w, start + len)) doc[y * w + x] = ink
            }
        }
        return doc
    }

    private fun frameAt(doc: IntArray, docRows: Int, offset: Int): IntArray {
        val f = IntArray(w * h)
        for (y in 0 until headerRows) for (x in 0 until w) f[y * w + x] = 0xFF112233.toInt() + (y * 7 + x) % 5
        for (y in top until bottom) {
            System.arraycopy(doc, (offset + y - top) * w, f, y * w, w)
        }
        for (y in bottom until h) for (x in 0 until w) f[y * w + x] = 0xFF332211.toInt() + (y * 3 + x) % 7
        return f
    }

    private fun stitch(docRows: Int, step: Int, seed: Long): Pair<IntArray, IntArray> {
        val doc = makeDoc(docRows, seed)
        val regionH = bottom - top
        val file = File.createTempFile("raw", ".bin")
        val store = StripStore(file, w)
        val st = IncrementalStitcher(w, h, top, bottom, store)
        var offset = 0
        st.start(frameAt(doc, docRows, 0))
        var guard = 0
        while (guard++ < 100) {
            offset = minOf(offset + step, docRows - regionH)
            val step1 = st.next(frameAt(doc, docRows, offset), true)
            if (step1 is Step.End) break
        }
        st.finish()
        store.close()
        val got = RawRows.read(file, w, 0, store.rows)
        file.delete()
        val expected = IntArray((headerRows + docRows + footerRows) * w)
        val f0 = frameAt(doc, docRows, 0)
        System.arraycopy(f0, 0, expected, 0, headerRows * w)
        System.arraycopy(doc, 0, expected, headerRows * w, docRows * w)
        val fl = frameAt(doc, docRows, docRows - regionH)
        System.arraycopy(fl, bottom * w, expected, (headerRows + docRows) * w, footerRows * w)
        return Pair(got, expected)
    }

    private fun rgb(a: IntArray) = IntArray(a.size) { a[it] and 0xFFFFFF }

    @Test fun stitchesWholePageWithFixedHeaderAndFooter() {
        val (got, expected) = stitch(3000, 600, 1)
        assertEquals(expected.size, got.size)
        assertTrue("stitched image differs from the original", rgb(expected).contentEquals(rgb(got)))
    }

    @Test fun stitchesWhenTheLastScrollIsShort() {
        val (got, expected) = stitch(2750, 640, 2)
        assertEquals(expected.size, got.size)
        assertTrue(rgb(expected).contentEquals(rgb(got)))
    }

    /** A swipe of about half the scroll area, as the app now scrolls. */
    @Test fun stitchesHalfScreenSwipes() {
        val (got, expected) = stitch(3500, 410, 7)
        assertEquals(expected.size, got.size)
        assertTrue(rgb(expected).contentEquals(rgb(got)))
    }

    /** Why the app no longer uses the built-in "scroll down": it jumps a whole screen, leaving nothing to line up on. */
    @Test fun aWholeScreenJumpHasNothingToLineUpOn() {
        val doc = makeDoc(3000, 8)
        val st = IncrementalStitcher(w, h, top, bottom, null)
        st.start(frameAt(doc, 3000, 0))
        assertTrue(st.next(frameAt(doc, 3000, bottom - top), true) is Step.Lost)
    }

    /** A feed with autoplaying video: a block of rows is different in every frame, yet the shift is still found. */
    @Test fun findsTheShiftWhileAVideoBlockKeepsChanging() {
        val doc = makeDoc(3000, 11)
        fun noisy(offset: Int, seed: Long): IntArray {
            val f = frameAt(doc, 3000, offset)
            val rnd = Random(seed)
            for (y in top + 150 until top + 480) for (x in 0 until w) f[y * w + x] = 0xFF000000.toInt() or rnd.nextInt(0xFFFFFF)
            return f
        }
        val a = Rows.signature(noisy(0, 1), w, h)
        val b = Rows.signature(noisy(380, 2), w, h)
        val sh = Rows.findShift(a, b, top, bottom, 300)
        assertNotNull("no shift found: ${Rows.lastNote}", sh)
        assertEquals(380, sh!!.s)
    }

    /** Every pixel is a shade off (as with sub-pixel drawing): exact matching finds nothing, the looser pass does. */
    @Test fun aPageThatRedrawsSlightlyDifferentlyStillLinesUp() {
        val doc = makeDoc(3000, 13)
        val a = frameAt(doc, 3000, 0)
        val b = frameAt(doc, 3000, 400)
        val rnd = Random(5)
        for (i in b.indices) {
            val p = b[i]
            val d = rnd.nextInt(5) - 2
            fun c(v: Int) = (v + d).coerceIn(0, 255)
            b[i] = 0xFF000000.toInt() or (c((p shr 16) and 255) shl 16) or (c((p shr 8) and 255) shl 8) or c(p and 255)
        }
        assertTrue("exact matching should fail here", Rows.findShift(Rows.signature(a, w, h), Rows.signature(b, w, h), top, bottom, 300) == null)
        val loose = Rows.findShiftFuzzy(Rows.profile(a, w, h), Rows.profile(b, w, h), top, bottom, 300)
        assertNotNull(loose)
        assertEquals(400, loose!!.s)
    }

    @Test fun stitchesSmallScrollSteps() {
        val (got, expected) = stitch(1500, 120, 3)
        assertEquals(expected.size, got.size)
        assertTrue(rgb(expected).contentEquals(rgb(got)))
    }

    @Test fun reportsEndWhenNothingMoved() {
        val doc = makeDoc(900, 4)
        val st = IncrementalStitcher(w, h, top, bottom, null)
        val f = frameAt(doc, 900, 0)
        st.start(f)
        assertTrue(st.next(f.clone(), true) is Step.End)
    }

    @Test fun aScreenThatChangesCompletelyIsNotGluedIn() {
        val file = File.createTempFile("raw", ".bin")
        val store = StripStore(file, w)
        val st = IncrementalStitcher(w, h, top, bottom, store)
        st.start(frameAt(makeDoc(2000, 10), 2000, 0))
        val before = store.rows
        // Something else entirely (another app opened): must stop, adding nothing.
        val step = st.next(frameAt(makeDoc(2000, 99), 2000, 300), true)
        assertTrue(step is Step.Lost)
        assertEquals(before, store.rows)
        store.close(); file.delete()
    }

    @Test fun findsTheMovingBandWithoutKnowingTheScrollArea() {
        val doc = makeDoc(3000, 5)
        val a = Rows.signature(frameAt(doc, 3000, 0), w, h)
        val b = Rows.signature(frameAt(doc, 3000, 500), w, h)
        val r = Rows.movingRegion(a, b)
        assertNotNull(r)
        assertTrue(r!![0] in top..(top + 3))
        assertTrue(r[1] in (bottom - 3)..bottom)
    }

    @Test fun pngRoundTrips() {
        val width = 37
        val rows = 90
        val raw = File.createTempFile("raw", ".bin")
        val store = StripStore(raw, width)
        val px = IntArray(width * rows) { 0xFF000000.toInt() or ((it * 2654435761L).toInt() and 0xFFFFFF) }
        store.append(px, width, 0, rows)
        store.close()
        val png = File.createTempFile("img", ".png")
        val recon = IntArray(width * rows * 3)
        Png.write(raw, width, rows, png)
        // Decode it independently: check signature, chunk CRCs, then inflate and undo the "Up" filter.
        val bytes = png.readBytes()
        assertTrue(bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        val bb = ByteBuffer.wrap(bytes).position(8) as ByteBuffer
        val idat = ByteArrayOutputStream()
        var gotW = 0
        var gotH = 0
        var sawEnd = false
        while (bb.hasRemaining()) {
            val len = bb.int
            val type = ByteArray(4).also { bb.get(it) }
            val data = ByteArray(len).also { bb.get(it) }
            val crc = bb.int
            val calc = CRC32().apply { update(type); update(data) }.value.toInt()
            assertEquals("bad chunk CRC", calc, crc)
            when (String(type, Charsets.US_ASCII)) {
                "IHDR" -> { val h = ByteBuffer.wrap(data); gotW = h.int; gotH = h.int }
                "IDAT" -> idat.write(data)
                "IEND" -> sawEnd = true
            }
        }
        assertTrue(sawEnd)
        assertEquals(width, gotW)
        assertEquals(rows, gotH)
        val inf = Inflater()
        inf.setInput(idat.toByteArray())
        val rowLen = width * 3 + 1
        val out = ByteArray(rowLen * rows)
        var n = 0
        while (n < out.size && !inf.finished()) n += inf.inflate(out, n, out.size - n)
        assertEquals(out.size, n)
        for (y in 0 until rows) {
            assertEquals(2, out[y * rowLen].toInt())
            for (x in 0 until width) for (c in 0 until 3) {
                val up = if (y == 0) 0 else recon[(y - 1) * width * 3 + x * 3 + c]
                val v = ((out[y * rowLen + 1 + x * 3 + c].toInt() and 0xFF) + up) and 0xFF
                recon[y * width * 3 + x * 3 + c] = v
            }
        }
        for (y in 0 until rows) for (x in 0 until width) {
            val p = px[y * width + x]
            assertEquals((p shr 16) and 0xFF, recon[y * width * 3 + x * 3])
            assertEquals((p shr 8) and 0xFF, recon[y * width * 3 + x * 3 + 1])
            assertEquals(p and 0xFF, recon[y * width * 3 + x * 3 + 2])
        }
        raw.delete(); png.delete()
    }

    @Test fun textIsNotRepeatedWhereFramesOverlap() {
        val acc = TextAccumulator()
        fun l(t: String, y: Int) = Line(t, y, y + 20, 10)
        acc.add(listOf(l("one", 0), l("two", 30), l("three", 60)), 0)
        acc.add(listOf(l("two", 0), l("three", 30), l("four", 60)), 30)
        acc.add(listOf(l("three", 0), l("four", 30), l("five", 60)), 60)
        assertEquals("one\ntwo\nthree\nfour\nfive", acc.render())
    }

    @Test fun repeatedWordsAtDifferentPlacesAreKept() {
        val acc = TextAccumulator()
        fun l(t: String, y: Int) = Line(t, y, y + 20, 10)
        acc.add(listOf(l("Reply", 0), l("hello", 40), l("Reply", 80)), 0)
        assertEquals("Reply\nhello\nReply", acc.render())
    }

    @Test fun timestampsJoinTheirText() {
        val acc = TextAccumulator()
        acc.add(listOf(Line("0:01", 0, 20, 10), Line("hello there", 0, 20, 90), Line("0:05", 40, 60, 10), Line("next line", 40, 60, 90)), 0)
        assertEquals("0:01 hello there\n0:05 next line", acc.render())
    }

    @Test fun sequentialMergeWhenDistanceIsUnknown() {
        val acc = TextAccumulator()
        fun l(t: String, y: Int) = Line(t, y, y + 20, 10)
        acc.add(listOf(l("a", 0), l("b", 30), l("c", 60)), null)
        acc.add(listOf(l("b", 0), l("c", 30), l("d", 60)), null)
        assertEquals("a\nb\nc\nd", acc.render())
    }

    // ---- the person-driven session ----

    @Test fun releasingTheButtonDoesNotEndTheCapture() {
        var now = 0L
        val c = SessionControl { now }
        c.press()                    // the press that starts the session
        c.consumeInitial()           // ... which the first step answered
        c.release()                  // let go: pause, not finish
        assertTrue(!c.done)
        now += 1_000
        c.press()                    // press again: one more step
        assertTrue(c.waitForStep(10_000, 1))
        c.release()
        now += 20_000                // left idle: saved rather than lost
        assertTrue(!c.waitForStep(10_000, 1))
    }

    @Test fun holdingKeepsStepsComingAndDoneStopsThem() {
        val c = SessionControl()
        c.press()
        c.consumeInitial()
        repeat(5) { assertTrue(c.waitForStep(10_000, 1)) }
        c.finish()
        assertTrue(!c.waitForStep(10_000, 1))
    }

    @Test fun aHandScrollIsPickedUpOnceThePageStopsMoving() {
        var now = 0L
        val c = SessionControl { now }
        c.press(); c.consumeInitial(); c.release()
        c.noteScroll()                         // the person swipes
        now += 200
        c.noteScroll()                         // still moving
        now += 600                             // ... and has now been still long enough
        assertEquals(Trigger.SETTLED, c.awaitTrigger(500, 60_000, 1))
    }

    @Test fun nothingEndsTheSessionExceptDoneOrBeingLeftIdle() {
        var now = 0L
        val c = SessionControl { now }
        c.press(); c.consumeInitial(); c.release()
        now += 10_000
        c.press()
        assertEquals(Trigger.STEP, c.awaitTrigger(500, 60_000, 1))
        c.release()
        now += 61_000
        assertEquals(Trigger.IDLE, c.awaitTrigger(500, 60_000, 1))
        c.finish()
        assertEquals(Trigger.DONE, c.awaitTrigger(500, 60_000, 1))
    }

    // ---- fallback: how far did it scroll, from where the words sit ----

    private fun l(t: String, y: Int, x: Int = 20) = Line(t, y, y + 40, x)

    @Test fun wordsGiveTheScrolledDistance() {
        val prev = listOf(l("Home", 10), l("first post here", 400), l("second post here", 700), l("third post here", 1000), l("fourth post", 1300))
        val cur = listOf(l("Home", 10), l("second post here", 300), l("third post here", 600), l("fourth post", 900), l("fifth post", 1200))
        assertEquals(400, TreeAlign.shift(prev, cur, 100, 1900, 20, 1500))
    }

    @Test fun wordsThatDisagreeGiveNothing() {
        val prev = listOf(l("same words", 200), l("same words", 500), l("same words", 800))
        val cur = listOf(l("same words", 100), l("same words", 400), l("same words", 700))
        // Every pairing is equally likely (100, 400 or 700 apart): no single answer.
        assertTrue(TreeAlign.shift(prev, cur, 0, 1900, 20, 1500) == null)
    }

    @Test fun tallPostsThatRunOffTheScreenStillGiveTheDistance() {
        // Two long posts: only their top edges are on screen in the first picture, only their bottoms are in the second.
        val prev = listOf(Line("long post one", 1500, 3200, 20), Line("long post two", 2100, 4200, 20))
        val cur = listOf(Line("long post one", 1100, 2800, 20), Line("long post two", 1700, 3800, 20))
        assertEquals(400, TreeAlign.shift(prev, cur, 0, 2300, 20, 2000))
    }

    @Test fun aSteadyTabBarIsNotMistakenForScrolling() {
        val prev = listOf(l("For you", 90), l("Following", 90, 300), l("post one", 500), l("post two", 800), l("post three", 1100))
        val cur = listOf(l("For you", 90), l("Following", 90, 300), l("post two", 380), l("post three", 680), l("post four", 980))
        assertEquals(420, TreeAlign.shift(prev, cur, 0, 1900, 20, 1500))
    }

    @Test fun aStillPageWithASmallPillStillCountsAsUnchanged() {
        val doc = makeDoc(3000, 12)
        val a = frameAt(doc, 3000, 400)
        val b = frameAt(doc, 3000, 400)
        for (y in top + 20 until top + 90) for (x in 10 until 110) b[y * w + x] = 0xFFFF00FF.toInt()   // a pill appears
        val share = Rows.unchangedShare(Rows.signature(a, w, h), Rows.signature(b, w, h), top, bottom)
        assertTrue("share=$share", share > 0.9f)
    }
}
