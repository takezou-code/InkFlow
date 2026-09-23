package com.vic.inkflow

import com.vic.inkflow.util.Pt
import com.vic.inkflow.util.TraceRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceRecorderTest {
    @Test
    fun frameLineHasAllFields() {
        val r = TraceRecorder()
        r.frame(1000L, listOf(Pt(100f, 200f), Pt(300f, 200f)), "DUAL_PAN", "S(0,-12)",
            4, 1180, 5f, 1.0f)
        val lines = r.flush("END-DUAL_PAN")
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("S kind=END-DUAL_PAN nframes=1 dropped=0"))
        val f = lines[1]
        assertTrue(f.contains("t=1000") && f.contains("n=2") && f.contains("cx=200") &&
            f.contains("span=200") && f.contains("st=DUAL_PAN") && f.contains("idx=4") &&
            f.contains("off=1180"))
        assertEquals(0, r.pending())
    }

    @Test
    fun capacityDropsOldest() {
        val r = TraceRecorder(capacity = 5)
        repeat(8) { i ->
            r.frame(1000L + i, listOf(Pt(i.toFloat(), 0f)), "S", "-", 0, 0, 0f, 1f)
        }
        val lines = r.flush("X")
        assertTrue(lines[0].contains("nframes=5 dropped=3"))
        assertTrue(lines[1].contains("t=1003"))
        assertTrue(lines[5].contains("t=1007"))
    }

    @Test
    fun emptyFlushStillHeaders() {
        val r = TraceRecorder()
        val lines = r.flush("IDLE")
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("nframes=0"))
    }
}
