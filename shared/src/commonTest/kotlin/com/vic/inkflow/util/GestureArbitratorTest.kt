package com.vic.inkflow.util

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test
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
        assertTrue(sawPan, "scroll should lock PAN")
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
                    assertTrue(lastFactor in 0.9f..1.3f, "factor sane, got $lastFactor")
                }
                else -> Unit
            }
        }
        assertTrue(sawPinch, "pinch should lock PINCH")
        assertTrue(a.isPinching)
        assertTrue(lastFactor >= 1f, "net zoom in, last factor=$lastFactor")
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
        assertTrue(d is TwoFingerDecision.Pan, "tie should lock PAN, got $d")
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
            assertTrue(d is TwoFingerDecision.Pinch, "must stay PINCH, got $d")
        }
    }

    @Test
    fun closeFingersBelowMinSpanNeverPinch() {
        val small = TwoFingerArbitrator(touchSlopPx = 10f, spanSlopPx = 20f, minSpanPx = 154f)
        small.onFrame(500f, 800f, 100f)
        repeat(10) {
            val d = small.onFrame(500f, 800f, 100f + it * 3f) // dist 30 > slop but span < min
            assertTrue(d !is TwoFingerDecision.Pinch, "must not pinch below minSpan, got $d")
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

    @Test
    fun panUpgradesToPinchWhenSpanOpens() {
        val a = arb()
        a.onFrame(500f, 800f, 200f)
        var d = a.onFrame(500f, 860f, 200f) // lock PAN (moved 60 > slop 22)
        assertTrue(d is TwoFingerDecision.Pan, "should lock PAN first, got $d")
        // Span wobble within hysteresis must NOT upgrade.
        d = a.onFrame(500f, 870f, 230f)
        assertTrue(d is TwoFingerDecision.Pan, "small span change must stay PAN, got $d")
        // Span opens clearly past 2x span slop (66): upgrade, factor starts at ~1.
        d = a.onFrame(500f, 880f, 300f)
        assertTrue(d is TwoFingerDecision.Pinch, "should upgrade to PINCH, got $d")
        if (d is TwoFingerDecision.Pinch) {
            assertTrue(abs(d.zoomFactor - 1f) < 0.05f, "upgrade factor must not jump, got ${d.zoomFactor}")
        }
        assertTrue(a.isPinching)
        // Keeps pinching as span grows.
        d = a.onFrame(500f, 890f, 330f)
        assertTrue(d is TwoFingerDecision.Pinch, "must stay PINCH, got $d")
    }

    @Test
    fun panUpgradeRespectsMinSpan() {
        val small = TwoFingerArbitrator(touchSlopPx = 10f, spanSlopPx = 20f, minSpanPx = 154f)
        small.onFrame(500f, 800f, 100f)
        var d = small.onFrame(500f, 830f, 100f) // lock PAN
        assertTrue(d is TwoFingerDecision.Pan)
        // Span opens a lot but stays below minSpan: must NOT upgrade.
        repeat(5) {
            d = small.onFrame(500f, 830f + it * 5f, 100f + it * 8f)
            assertTrue(d is TwoFingerDecision.Pan, "must stay PAN below minSpan, got $d")
        }
    }

    @Test
    fun panUpgradeNeedsOnlyOneSlop() {
        // 自然捏合：span 開 40（> 1x spanSlop=33）就該升級，不要等到 2x。
        val a = arb()
        a.onFrame(500f, 800f, 200f)
        var d = a.onFrame(500f, 860f, 200f)
        assertTrue(d is TwoFingerDecision.Pan, "should lock PAN first, got $d")
        d = a.onFrame(500f, 865f, 240f)
        assertTrue(d is TwoFingerDecision.Pinch, "40px span open must upgrade, got $d")
        assertTrue(a.isPinching)
    }

    @Test
    fun fingerJoinKeepsPanRaceAlive() {
        // 加指不重開 PAN 賽局：起點保留，累計位移照算，照樣鎖得上。
        val a = arb()
        a.onFrame(500f, 800f, 200f)
        var d = a.onFrame(510f, 800f, 200f)
        assertTrue(d is TwoFingerDecision.Undecided, "10px must stay Undecided, got $d")
        a.rebaselineSpan(200f) // 第三指加入（span 書籤同步，不動賽局起點）
        d = a.onFrame(525f, 800f, 200f)
        assertTrue(d is TwoFingerDecision.Pan, "cumulative 25px must lock PAN, got $d")
        assertTrue(a.isPanning)
    }

    @Test
    fun joinSpanJumpProducesNoZoomSpike() {
        // 加指造成 span 跳變：平滑器重錨後首幀係數必須是 1（不跳）。
        val a = arb()
        a.onFrame(500f, 800f, 200f)
        var d = a.onFrame(500f, 800f, 260f)
        assertTrue(d is TwoFingerDecision.Pinch, "should lock PINCH, got $d")
        a.rebaselineSpan(400f)
        d = a.onFrame(500f, 800f, 400f)
        assertTrue(d is TwoFingerDecision.Pinch, "must stay PINCH, got $d")
        if (d is TwoFingerDecision.Pinch) {
            assertEquals(1f, d.zoomFactor, 0f)
        }
    }

    @Test
    fun frameDeltaIsCapped() {
        val a = arb()
        a.onFrame(500f, 800f, 200f)
        // Single-frame teleport (jank coalescing): applied delta must be capped.
        val d = a.onFrame(1000f, 800f, 200f)
        assertTrue(d is TwoFingerDecision.Pan, "tie jumps must lock PAN, got $d")
        if (d is TwoFingerDecision.Pan) {
            assertEquals(96f, d.dx, 0f)
            assertEquals(0f, d.dy, 0f)
        }
    }
}
