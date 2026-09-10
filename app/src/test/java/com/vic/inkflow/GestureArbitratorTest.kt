package com.vic.inkflow

import com.vic.inkflow.util.TwoFingerArbitrator
import com.vic.inkflow.util.TwoFingerDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GestureArbitratorTest {

    private fun arb() = TwoFingerArbitrator(touchSlopPx = 22f)

    @Test
    fun verticalScrollNeverPinches() {
        val a = arb()
        var sawPan = false
        // Two fingers sliding straight down, span wobbling within tremor range.
        var y = 0f
        repeat(30) { i ->
            y += 12f
            val span = 200f + if (i % 2 == 0) 6f else -6f
            when (val d = a.onFrame(500f, y, span)) {
                is TwoFingerDecision.Pinch -> throw AssertionError("pinched during scroll: $d")
                is TwoFingerDecision.Pan -> sawPan = true
                TwoFingerDecision.Undecided -> Unit
            }
        }
        assertTrue("scroll should lock PAN", sawPan)
        assertTrue(a.isPanning)
    }

    @Test
    fun pinchOutLocksPinchWithSaneFactor() {
        val a = arb()
        var lastFactor = 1f
        var sawPinch = false
        var span = 200f
        repeat(20) {
            span += 12f
            when (val d = a.onFrame(500f, 800f, span)) {
                is TwoFingerDecision.Pinch -> {
                    sawPinch = true
                    lastFactor = d.zoomFactor
                    assertTrue("factor sane, got $lastFactor", lastFactor in 0.9f..1.3f)
                }
                else -> Unit
            }
        }
        assertTrue("pinch should lock PINCH", sawPinch)
        assertTrue(a.isPinching)
        assertTrue("net zoom in, last factor=$lastFactor", lastFactor >= 1f)
    }

    @Test
    fun tremorAloneNeverLocks() {
        val a = arb()
        repeat(40) { i ->
            val span = 200f + (if (i % 2 == 0) 5f else -5f)
            val d = a.onFrame(500f + (if (i % 3 == 0) 4f else 0f), 800f, span)
            assertEquals(TwoFingerDecision.Undecided, d)
        }
    }

    @Test
    fun tieGoesToPan() {
        val a = arb()
        // One frame: centroid jumps 60px AND span jumps 60px at once.
        a.onFrame(500f, 800f, 200f)
        val d = a.onFrame(560f, 800f, 260f)
        assertTrue("tie should lock PAN, got $d", d is TwoFingerDecision.Pan)
    }

    @Test
    fun lockPersistsAgainstOtherSignal() {
        val a = arb()
        a.onFrame(500f, 800f, 200f)
        // Lock PINCH first with a clean span jump.
        var d = a.onFrame(500f, 800f, 260f)
        assertTrue(d is TwoFingerDecision.Pinch)
        // Now fling the centroid far: must STAY pinch, never flip to pan.
        repeat(10) { i ->
            d = a.onFrame(500f + i * 30f, 800f, 260f)
            assertTrue("must stay PINCH, got $d", d is TwoFingerDecision.Pinch)
        }
    }

    @Test
    fun closeFingersBelowMinSpanNeverPinch() {
        val small = TwoFingerArbitrator(touchSlopPx = 10f, spanSlopPx = 20f, minSpanPx = 154f)
        small.onFrame(500f, 800f, 100f)
        repeat(10) {
            val d = small.onFrame(500f, 800f, 100f + it * 3f) // dist 30 > slop but span < min
            assertTrue("must not pinch below minSpan, got $d", d !is TwoFingerDecision.Pinch)
        }
    }

    @Test
    fun rebaselineKeepsLockWithoutJump() {
        val a = arb()
        a.onFrame(500f, 800f, 200f)
        a.onFrame(500f, 800f, 260f) // lock PINCH
        a.rebaseline(520f, 810f, 260f) // third finger joins
        val d = a.onFrame(522f, 812f, 262f)
        assertTrue(d is TwoFingerDecision.Pinch)
        if (d is TwoFingerDecision.Pinch) {
            assertTrue(abs(d.zoomFactor - 1f) < 0.05f)
        }
    }

    @Test
    fun resetRestoresUndecided() {
        val a = arb()
        a.onFrame(500f, 800f, 200f)
        a.onFrame(500f, 860f, 200f) // lock PAN
        assertTrue(a.isPanning)
        a.reset()
        assertEquals(TwoFingerDecision.Undecided, a.onFrame(500f, 800f, 200f))
    }
}
