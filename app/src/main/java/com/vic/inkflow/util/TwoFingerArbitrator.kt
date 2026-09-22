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
 * Single-frame applied-delta ceiling (px, per axis).
 * Touch batches coalesce after UI jank into one mega-frame (observed: 163px);
 * applying it raw = visible page flash. Capped remainder is dropped (a transient
 * sub-100px lag the ongoing motion covers), the flash is not.
 * 96px/frame still allows ~23m/s at 240Hz touch rate — never hit legitimately.
 */
const val MAX_FRAME_DELTA_PX = 96f

/**
 * Decides whether a two-pointer gesture means scroll (PAN) or pinch-zoom (PINCH).
 *
 * Rules (mirroring AOSP ScaleGestureDetector spanSlop = 2x touchSlop, plus the
 * Compose `panZoomLock` idea of first-signal-wins):
 * - Both signals race from gesture start; the first past its threshold LOCKS the gesture.
 * - Pan threshold = touch slop; pinch needs span change > 2x slop AND span >= min span.
 * - Ties go to PAN (scroll wins; a pinch can simply be retried).
 * - PINCH never downgrades. PAN can UPGRADE to PINCH mid-gesture when the span
 *   clearly opens (span change from pan-lock moment > 2x span slop AND span >=
 *   min span): scrolling morphs into zoom like Maps/Chrome. The zoom smoother
 *   re-anchors at upgrade so the first pinched frames don't jump.
 * - Span is EMA-smoothed with a deadband so hand tremor never zooms.
 * - Pointer-set changes (finger added/lifted, gesture continues) re-baseline
 *   without unlocking, so there are no jumps.
 * - Applied deltas are capped per frame ([MAX_FRAME_DELTA_PX]) so coalesced
 *   post-jank batches can't teleport the page.
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
    /** Span at the moment PAN locked; upgrade compares against this (hysteresis). */
    private var panLockSpan = 0f

    fun reset() {
        lock = Lock.NONE
        hasBaseline = false
        panLockSpan = 0f
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
        // 新手指加入會改變 span 基準，升級比較也同步重錨，避免誤升級。
        if (lock == Lock.PAN) panLockSpan = span
    }

    /** Feed one frame (centroid + span in px). Deltas are absorbed until a lock. */
    fun onFrame(cx: Float, cy: Float, span: Float): TwoFingerDecision {
        if (!hasBaseline) {
            rebaseline(cx, cy, span)
            return TwoFingerDecision.Undecided
        }
        // 單幀封頂：卡頓合併幀不傳送整頁（實測抓到過 163px）。
        val dx = (cx - lastCx).coerceIn(-MAX_FRAME_DELTA_PX, MAX_FRAME_DELTA_PX)
        val dy = (cy - lastCy).coerceIn(-MAX_FRAME_DELTA_PX, MAX_FRAME_DELTA_PX)
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
            if (lock == Lock.PAN) panLockSpan = span
        }

        return when (lock) {
            Lock.PAN -> {
                // 滾動中張開：升級成捏合（Maps/Chrome 行為），zoom 平滑器重錨無跳變。
                if (span >= minSpanPx && abs(span - panLockSpan) > spanSlopPx * 2f) {
                    lock = Lock.PINCH
                    prevSmooth = span
                    smoothSpan = span
                    return TwoFingerDecision.Pinch(1f, dx, dy)
                }
                TwoFingerDecision.Pan(dx, dy)
            }
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
