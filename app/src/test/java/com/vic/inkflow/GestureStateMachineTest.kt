package com.vic.inkflow

import com.vic.inkflow.util.GestureStateMachine
import com.vic.inkflow.util.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression net for the finger-gesture rewrite. Every historical failure
 * mode gets a pinned test; M2 wiring must keep all of these green.
 */
class GestureStateMachineTest {
    private class D(val m: GestureStateMachine = GestureStateMachine(touchSlopPx = 22f)) {
        var t = 1000L
        fun adv(ms: Long = 8): Long {
            t += ms
            return t
        }

        fun down(id: Int, x: Float, y: Float, palm: Boolean = false, blank: Boolean = true) =
            m.pointerDown(id, x, y, palm, blank, t)

        fun move(vararg pts: Triple<Int, Float, Float>) =
            m.pointerMove(pts.associate { it.first to Pt(it.second, it.third) }, t)

        fun up(id: Int, canceled: Boolean = false) = m.pointerUp(id, canceled, t)
        fun drain() = m.drainStart()
    }

    private fun scrollDx(o: GestureStateMachine.Output?): Float {
        assertTrue("expected ScrollBy, got $o", o is GestureStateMachine.Output.ScrollBy)
        return (o as GestureStateMachine.Output.ScrollBy).dx
    }

    // ---- single finger (blank) ----

    @Test
    fun singleBlankPreSlopSilent() {
        val d = D()
        assertNull(d.down(1, 100f, 100f))
        assertNull(d.move(Triple(1, 105f, 103f)))
        assertTrue(d.m.state == GestureStateMachine.State.SINGLE_TRACKING)
    }

    @Test
    fun singleBlankLocksAndDeductsSlop() {
        val d = D()
        d.down(1, 100f, 100f)
        val o = d.move(Triple(1, 200f, 100f))
        // dist=100, slop=22 -> over-slop portion 78 (Chromium SubtractSlopRegion).
        assertEquals(78f, scrollDx(o), 1e-3f)
        assertEquals(
            GestureStateMachine.Output.GestureStart(GestureStateMachine.Kind.SINGLE_PAN),
            d.drain()
        )
    }

    @Test
    fun singleOnPaperNeverLocks() {
        val d = D()
        d.down(1, 100f, 100f, blank = false)
        assertNull(d.move(Triple(1, 300f, 100f)))
        assertNull(d.move(Triple(1, 300f, 400f)))
        assertTrue(d.m.state == GestureStateMachine.State.SINGLE_TRACKING)
        assertNull(d.drain())
    }

    @Test
    fun singleLiftEnds() {
        val d = D()
        d.down(1, 100f, 100f)
        d.move(Triple(1, 200f, 100f))
        d.drain()
        assertEquals(
            GestureStateMachine.Output.GestureEnd(GestureStateMachine.Kind.SINGLE_PAN),
            d.up(1)
        )
        assertTrue(d.m.state == GestureStateMachine.State.IDLE)
    }

    // ---- dual race ----

    @Test
    fun dualRaceSilentUntilThreshold() {
        val d = D()
        d.down(1, 400f, 800f)
        d.down(2, 600f, 800f)
        assertTrue(d.m.state == GestureStateMachine.State.DUAL_UNDECIDED)
        assertNull(d.move(Triple(1, 405f, 800f), Triple(2, 605f, 800f)))
    }

    @Test
    fun panLocksOnCentroid() {
        val d = D()
        d.down(1, 400f, 800f)
        d.down(2, 600f, 800f)
        val o = d.move(Triple(1, 430f, 800f), Triple(2, 630f, 800f))
        assertEquals(8f, scrollDx(o), 1e-3f) // 30 - 22 slop
        assertEquals(
            GestureStateMachine.Output.GestureStart(GestureStateMachine.Kind.DUAL_PAN),
            d.drain()
        )
    }

    @Test
    fun pinchLocksOnSpan() {
        val d = D()
        d.down(1, 400f, 800f)
        d.down(2, 600f, 800f) // span 200
        val o = d.move(Triple(1, 370f, 800f), Triple(2, 630f, 800f)) // span 260
        assertTrue("expected ZoomBy, got $o", o is GestureStateMachine.Output.ZoomBy)
        assertEquals(1f, (o as GestureStateMachine.Output.ZoomBy).factor, 1e-6f)
        assertEquals(
            GestureStateMachine.Output.GestureStart(GestureStateMachine.Kind.DUAL_PINCH),
            d.drain()
        )
    }

    /**
     * THE 13-PAN-STREAK REGRESSION: natural pinch moves its centroid, so the
     * race ties into PAN — but with the baseline FROZEN at 2nd-finger down,
     * the span opening must still upgrade to PINCH. (The old code re-anchored
     * the baseline every frame and never crossed its own drifting threshold.)
     */
    @Test
    fun naturalPinchUpgradesFromPan() {
        val d = D()
        d.down(1, 400f, 800f)
        d.down(2, 600f, 800f) // focus (500,800), initialSpan 200
        // Centroid +40, span +20: tie goes to PAN.
        val o1 = d.move(Triple(1, 440f, 800f), Triple(2, 660f, 800f))
        assertTrue("expected Pan ScrollBy, got $o1", o1 is GestureStateMachine.Output.ScrollBy)
        assertEquals(
            GestureStateMachine.Output.GestureStart(GestureStateMachine.Kind.DUAL_PAN),
            d.drain()
        )
        // Centroid drifts on, span opens to 260 (|260-200|=60 > 44): upgrade.
        val o2 = d.move(Triple(1, 430f, 810f), Triple(2, 690f, 810f))
        assertTrue("expected upgrade ZoomBy, got $o2", o2 is GestureStateMachine.Output.ZoomBy)
        assertEquals(
            GestureStateMachine.Output.GestureStart(GestureStateMachine.Kind.DUAL_PINCH),
            d.drain()
        )
        assertTrue(d.m.state == GestureStateMachine.State.DUAL_PINCH)
    }

    @Test
    fun minSpanBlocksPinch() {
        val d = D()
        d.down(1, 450f, 800f)
        d.down(2, 550f, 800f) // span 100 < 154
        // Open to 150 (dist 50 > 44) but still under minSpan: no lock.
        assertNull(d.move(Triple(1, 425f, 800f), Triple(2, 575f, 800f)))
        assertTrue(d.m.state == GestureStateMachine.State.DUAL_UNDECIDED)
    }

    // ---- pinch steady-state ----

    @Test
    fun pinchDeadbandSwallowsTremor() {
        val d = D()
        d.down(1, 400f, 800f)
        d.down(2, 600f, 800f)
        d.move(Triple(1, 370f, 800f), Triple(2, 630f, 800f))
        d.drain()
        // Span wiggle +-1px, focus static: silence.
        assertNull(d.move(Triple(1, 371f, 800f), Triple(2, 629f, 800f)))
        assertNull(d.move(Triple(1, 369f, 800f), Triple(2, 631f, 800f)))
    }

    @Test
    fun focusConvergesAfterJump() {
        val d = D()
        d.down(1, 400f, 800f)
        d.down(2, 600f, 800f)
        d.move(Triple(1, 370f, 800f), Triple(2, 630f, 800f))
        d.drain()
        // Jump focus +60 (span constant): anchor-only updates...
        var last: GestureStateMachine.Output? =
            d.move(Triple(1, 430f, 800f), Triple(2, 690f, 800f))
        assertTrue(last is GestureStateMachine.Output.ZoomBy)
        // ...then static frames must converge and go silent within 4 frames.
        var silentAt = -1
        repeat(6) { i ->
            val o = d.move(Triple(1, 430f, 800f), Triple(2, 690f, 800f))
            if (o == null && silentAt < 0) silentAt = i
            last = o ?: last
        }
        assertTrue("focus never converged, last=$last", silentAt in 0..3)
        val z = last as GestureStateMachine.Output.ZoomBy
        assertEquals(560f, z.focusX, 1e-3f) // snapped exactly, no residue
    }

    @Test
    fun zoomFactorAccumulatesToTwo() {
        val d = D()
        d.down(1, 400f, 800f)
        d.down(2, 600f, 800f) // span 200
        d.move(Triple(1, 375f, 800f), Triple(2, 625f, 800f)) // lock (span 250)
        d.drain()
        var acc = 1.0
        val spans = listOf(300f, 350f, 400f)
        for (s in spans) {
            val half = s / 2f
            val o = d.move(Triple(1, 500f - half, 800f), Triple(2, 500f + half, 800f))
            assertTrue("expected ZoomBy, got $o", o is GestureStateMachine.Output.ZoomBy)
            acc *= (o as GestureStateMachine.Output.ZoomBy).factor
        }
        // 250 -> 400 via lock-frame baseline: 400/250 = 1.6
        assertEquals(1.6, acc, 1e-3)
    }

    // ---- grace / lifecycle ----

    @Test
    fun graceResumesWithoutRelock() {
        val d = D()
        d.down(1, 400f, 800f)
        d.down(2, 600f, 800f)
        d.move(Triple(1, 430f, 800f), Triple(2, 630f, 800f))
        d.drain()
        d.up(2)
        assertTrue(d.m.state == GestureStateMachine.State.GRACE)
        d.adv(100)
        // Solo segment discarded: silence.
        assertNull(d.move(Triple(1, 450f, 820f)))
        // 2nd finger back inside 400ms: resume PAN, no re-lock fanfare.
        d.adv(100)
        assertNull(d.down(2, 650f, 820f))
        assertTrue(d.m.state == GestureStateMachine.State.DUAL_PAN)
        assertNull(d.drain())
        val o = d.move(Triple(1, 460f, 830f), Triple(2, 660f, 830f))
        assertTrue("expected ScrollBy, got $o", o is GestureStateMachine.Output.ScrollBy)
    }

    @Test
    fun graceExpiryEnds() {
        val d = D()
        d.down(1, 400f, 800f)
        d.down(2, 600f, 800f)
        d.move(Triple(1, 430f, 800f), Triple(2, 630f, 800f))
        d.drain()
        d.up(2)
        d.adv(500)
        assertEquals(
            GestureStateMachine.Output.GestureEnd(GestureStateMachine.Kind.DUAL_PAN),
            d.m.tick(d.t)
        )
        assertTrue(d.m.state == GestureStateMachine.State.IDLE)
    }

    @Test
    fun undecidedSoloLiftResets() {
        val d = D()
        d.down(1, 400f, 800f)
        d.down(2, 600f, 800f)
        assertNull(d.up(1))
        assertTrue(d.m.state == GestureStateMachine.State.IDLE)
    }

    @Test
    fun noOutputsAfterEnd() {
        val d = D()
        d.down(1, 400f, 800f)
        d.down(2, 600f, 800f)
        d.move(Triple(1, 370f, 800f), Triple(2, 630f, 800f))
        d.drain()
        d.up(1)
        d.up(2)
        // No-fling invariant: fingers off = stop. Pump frames, expect silence.
        repeat(20) { i ->
            d.adv(16)
            assertNull("frame $i emitted", d.move())
            assertNull("tick $i emitted", d.m.tick(d.t))
        }
    }

    // ---- integrity inputs ----

    @Test
    fun stylusYields() {
        val d = D()
        d.down(1, 400f, 800f)
        d.down(2, 600f, 800f)
        d.move(Triple(1, 370f, 800f), Triple(2, 630f, 800f))
        assertEquals(GestureStateMachine.Output.YieldToStylus, d.m.stylusDown())
        assertTrue(d.m.state == GestureStateMachine.State.IDLE)
        assertNull(d.move(Triple(1, 400f, 800f), Triple(2, 600f, 800f)))
    }

    @Test
    fun cancelAborts() {
        val d = D()
        d.down(1, 400f, 800f)
        d.down(2, 600f, 800f)
        d.move(Triple(1, 430f, 800f), Triple(2, 630f, 800f))
        d.drain()
        assertEquals(GestureStateMachine.Output.AbortGesture, d.m.cancelAll())
        assertTrue(d.m.state == GestureStateMachine.State.IDLE)
    }

    @Test
    fun canceledFlagUpAborts() {
        val d = D()
        d.down(1, 400f, 800f)
        d.down(2, 600f, 800f)
        d.move(Triple(1, 430f, 800f), Triple(2, 630f, 800f))
        d.drain()
        assertEquals(GestureStateMachine.Output.AbortGesture, d.up(2, canceled = true))
    }

    @Test
    fun palmExcludedFromFocus() {
        val d = D()
        d.down(9, 0f, 0f, palm = true)
        d.down(1, 100f, 100f)
        assertTrue(d.m.state == GestureStateMachine.State.SINGLE_TRACKING)
        val o = d.move(Triple(1, 200f, 100f), Triple(9, 900f, 900f))
        // Palm parked far away must not drag focus: dx reflects finger only.
        assertEquals(78f, scrollDx(o), 1e-3f)
    }

    @Test
    fun megaFrameCapped() {
        val d = D()
        d.down(1, 400f, 800f)
        d.down(2, 600f, 800f)
        d.move(Triple(1, 430f, 800f), Triple(2, 630f, 800f))
        d.drain()
        val o = d.move(Triple(1, 730f, 800f), Triple(2, 930f, 800f))
        assertEquals(96f, scrollDx(o), 1e-3f)
    }

    @Test
    fun thirdFingerRebaselinesWithoutJump() {
        val d = D()
        d.down(1, 400f, 800f)
        d.down(2, 600f, 800f)
        d.move(Triple(1, 430f, 800f), Triple(2, 630f, 800f))
        d.drain()
        // 3rd finger joins far away: no jump, stays PAN, no re-lock.
        assertNull(d.down(3, 900f, 200f))
        assertNull(d.drain())
        val o = d.move(Triple(1, 432f, 802f), Triple(2, 632f, 802f), Triple(3, 900f, 200f))
        val s = o as? GestureStateMachine.Output.ScrollBy
        assertTrue("expected tiny ScrollBy, got $o", s != null && kotlin.math.abs(s.dx) < 5f && kotlin.math.abs(s.dy) < 5f)
    }
}
