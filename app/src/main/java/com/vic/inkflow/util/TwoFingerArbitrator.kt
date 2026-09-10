package com.vic.inkflow.util

import kotlin.math.abs
import kotlin.math.hypot

/** Per-frame decision emitted by [TwoFingerArbitrator]. */
sealed interface TwoFingerDecision {
    data object Undecided : TwoFingerDecision
    /** Scroll intent: apply centroid delta (px) since last frame. */
    data class Pan(val dx: Float, val dy: Float) : TwoFingerDecision
    /** Pinch intent: multiply zoom by [zoomFactor]; [dx]/[dy] is centroid drift. */
    data class Pinch(val zoomFactor: Float, val dx: Float, val dy: Float) : TwoFingerDecision
}

/**
 * Decides whether a two-pointer gesture means scroll (PAN) or pinch-zoom (PINCH).
 *
 * Rules (mirroring AOSP ScaleGestureDetector spanSlop = 2x touchSlop, plus the
 * Compose `panZoomLock` idea of first-signal-wins):
 * - Both signals race from gesture start; the first past its threshold LOCKS the gesture.
 * - Pan threshold = touch slop; pinch needs span change > 2x slop AND span >= min span.
 * - Ties go to PAN (scroll wins; a pinch can simply be retried).
 * - After locking, the other signal is ignored until all pointers lift (tremor-proof).
 * - Span is EMA-smoothed with a deadband so hand tremor never zooms.
 * - Pointer-set changes (finger added/lifted, gesture continues) re-baseline
 *   without unlocking, so there are no jumps.
 *
 * Pure Kotlin, no Compose/Android dependencies — unit tested in GestureArbitratorTest.
 */
class TwoFingerArbitrator(
    private val touchSlopPx: Float,
    private val spanSlopPx: Float = touchSlopPx * 1.5f,
    private val minSpanPx: Float = 154f,
    private val smoothAlpha: Float = 0.75f,
    private val deadband: Float = 0.001f
) {
    private enum class Lock { NONE, PAN, PINCH }

    private var lock = Lock.NONE
    private var c0x = 0f
    private var c0y = 0f
    private var s0 = 0f
    private var lastCx = 0f
    private var lastCy = 0f
    private var smoothSpan = 0f
    private var prevSmooth = 0f
    private var hasBaseline = false

    fun reset() {
        lock = Lock.NONE
        hasBaseline = false
    }

    /** Pointer set changed but gesture continues: re-baseline, keep the lock. */
    fun rebaseline(cx: Float, cy: Float, span: Float) {
        c0x = cx
        c0y = cy
        s0 = span
        lastCx = cx
        lastCy = cy
        smoothSpan = span
        prevSmooth = span
        hasBaseline = true
    }

    /** Feed one frame (centroid + span in px). Deltas are absorbed until a lock. */
    fun onFrame(cx: Float, cy: Float, span: Float): TwoFingerDecision {
        if (!hasBaseline) {
            rebaseline(cx, cy, span)
            return TwoFingerDecision.Undecided
        }
        val dx = cx - lastCx
        val dy = cy - lastCy
        lastCx = cx
        lastCy = cy

        if (lock == Lock.NONE) {
            val panDist = hypot(cx - c0x, cy - c0y)
            val spanDist = abs(span - s0)
            val pinchReady = span >= minSpanPx && spanDist > spanSlopPx
            val panReady = panDist > touchSlopPx
            lock = when {
                pinchReady && !panReady -> Lock.PINCH
                panReady -> Lock.PAN // ties go to PAN
                else -> Lock.NONE
            }
            if (lock == Lock.NONE) return TwoFingerDecision.Undecided
            // Re-baseline at lock moment so the first locked deltas don't jump.
            prevSmooth = span
            smoothSpan = span
        }

        return when (lock) {
            Lock.PAN -> TwoFingerDecision.Pan(dx, dy)
            Lock.PINCH -> {
                smoothSpan += (span - smoothSpan) * smoothAlpha
                val rawFactor = if (prevSmooth > 0f && prevSmooth.isFinite()) smoothSpan / prevSmooth else 1f
                prevSmooth = smoothSpan
                val factor = if (!rawFactor.isFinite() || rawFactor.isNaN()) 1f else rawFactor
                val f = if (abs(factor - 1f) < deadband) 1f else factor
                TwoFingerDecision.Pinch(f, dx, dy)
            }
            Lock.NONE -> TwoFingerDecision.Undecided
        }
    }

    val isPinching: Boolean get() = lock == Lock.PINCH
    val isPanning: Boolean get() = lock == Lock.PAN
}
