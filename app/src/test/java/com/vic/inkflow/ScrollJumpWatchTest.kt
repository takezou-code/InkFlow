package com.vic.inkflow

import com.vic.inkflow.util.ScrollJumpWatch
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollJumpWatchTest {

    private val page = 4400f

    @Test
    fun jumpWithinOnePageIsReported() {
        val w = ScrollJumpWatch(jumpFraction = 0.5f)
        assertNull(w.sample(1_000L, 3, 100, page))
        val line = w.sample(1_016L, 4, 900, page)
        assertTrue(line!!.startsWith("JUMP "))
        assertTrue(line.contains("dz=5200"))
        assertTrue(line.contains("dt=16"))
    }

    @Test
    fun smallScrollIsQuiet() {
        val w = ScrollJumpWatch(jumpFraction = 0.5f)
        assertNull(w.sample(1_000L, 3, 100, page))
        assertNull(w.sample(1_016L, 3, 240, page))
        assertNull(w.sample(1_032L, 3, 380, page))
    }

    @Test
    fun exactlyHalfPageIsReported() {
        val w = ScrollJumpWatch(jumpFraction = 0.5f)
        assertNull(w.sample(1_000L, 0, 0, page))
        assertTrue(w.sample(1_016L, 0, 2200, page)!!.startsWith("JUMP "))
    }

    @Test
    fun longGapOnlyRebases() {
        val w = ScrollJumpWatch(jumpFraction = 0.5f, windowMs = 400L)
        assertNull(w.sample(1_000L, 0, 0, page))
        // 隔了 2 秒才來（中途可能換頁/渲染），不判，但新基準要生效
        assertNull(w.sample(3_000L, 2, 0, page))
        assertNull(w.sample(3_016L, 2, 120, page))
    }

    @Test
    fun pageHeightChangeIsLayoutNotJump() {
        val w = ScrollJumpWatch(jumpFraction = 0.5f)
        assertNull(w.sample(1_000L, 3, 100, page))
        val line = w.sample(1_016L, 3, 100, page * 1.1f)
        assertTrue(line!!.startsWith("LAYOUT "))
        assertTrue(line.contains("full=4400->4840"))
    }

    @Test
    fun unknownHeightIsIgnoredUntilMeasured() {
        val w = ScrollJumpWatch(jumpFraction = 0.5f)
        assertNull(w.sample(1_000L, 3, 100, 0f))
        assertNull(w.sample(1_016L, 3, 120, 0f))
        assertNull(w.sample(1_032L, 3, 140, page))
    }

    @Test
    fun resetDropsBaseline() {
        val w = ScrollJumpWatch(jumpFraction = 0.5f)
        assertNull(w.sample(1_000L, 0, 0, page))
        w.reset()
        assertNull(w.sample(1_016L, 5, 0, page))
    }
}
