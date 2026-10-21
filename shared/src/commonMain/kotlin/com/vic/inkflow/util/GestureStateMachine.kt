package com.vic.inkflow.util

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A 2D point in screen px. */
data class Pt(val x: Float, val y: Float)

/**
 * Finger-gesture state machine for the document workspace (finger mode only).
 *
 * Single owner of PAN-vs-PINCH arbitration, replacing the layered patch stack
 * (selective-consume + grace + rebaselineSpan + upgrade threshold + carry ...).
 * Pure Kotlin, no Compose/Android dependencies — fully unit tested.
 *
 * Design notes (each grounded in prior on-device evidence or reference impls):
 * - Span is PURE GEOMETRY (finger distance only). Area/size must never enter
 *   span or focus math — Chromium removed touch radius from span for exactly
 *   this reason ("Eliminate pinch drift").
 * - Upgrade follows AOSP ScaleGestureDetector semantics: initialSpan is FROZEN
 *   when the 2nd finger lands; PINCH wins once |span - initial| > spanSlop.
 *   No re-anchoring of the baseline on finger add (that drift is what made
 *   natural pinches lock 13x PAN in a row). Pointer-set change re-baselines
 *   the APPLIED deltas (no jump) but ALSO restarts initialSpan (like AOSP
 *   ending + restarting the scale stream) — one single baseline source.
 * - First locked frame deducts slop (Chromium SubtractSlopRegion) so lock
 *   engagement never jumps.
 * - Pinch focus is exponentially approached + snapped (fingers stop =>
 *   converges in a few frames, no spring chasing a moving target).
 * - Span updates below spanEpsPx are dropped (Chromium min_pinch_update_span_delta).
 * - Applied deltas are capped per frame (MAX_FRAME_DELTA_PX, anti-flash for
 *   coalesced post-jank batches, evidence: 163px mega-frame).
 * - Release emits GestureEnd and then NOTHING (no-fling invariant: fingers
 *   off = stop). Tests assert zero outputs after end.
 * - Identity is locked at down (palm stays palm until lift); a canceled-flag
 *   lift mid-gesture aborts immediately.
 * - Stylus is a frozen contract, not a redesign: any stylus down yields.
 */
class GestureStateMachine(
    private val touchSlopPx: Float,
    /** AOSP: spanSlop = 2x touchSlop. Single threshold, measured from FROZEN initial. */
    private val spanSlopPx: Float = touchSlopPx * 2f,
    /** AOSP: getScaledMinimumScalingSpan. Below this span PINCH can never lock. */
    private val minSpanPx: Float = 154f,
    /** Chromium min_pinch_update_span_delta: smaller span wiggles emit no zoom. */
    private val spanEpsPx: Float = 3f,
    private val focusAlpha: Float = 0.7f,
    private val focusSnapPx: Float = 1.5f,
    private val graceMs: Long = 400L,
) {
    enum class State { IDLE, SINGLE_TRACKING, SINGLE_PAN, DUAL_UNDECIDED, DUAL_PAN, DUAL_PINCH, GRACE }
    enum class Kind { SINGLE_PAN, DUAL_PAN, DUAL_PINCH }

    sealed interface Output {
        /** A gesture locked. While already active, Start of another kind = mode switch. */
        data class GestureStart(val kind: Kind) : Output
        data class GestureEnd(val kind: Kind) : Output
        data class ScrollBy(val dx: Float, val dy: Float) : Output
        data class ZoomBy(val factor: Float, val focusX: Float, val focusY: Float) : Output
        /** Frozen contract: stylus appeared, hand over to ink immediately. */
        data object YieldToStylus : Output
        /** Unintentional touch (cancel / canceled-flag lift): freeze now. */
        data object AbortGesture : Output
    }

    val state: State get() = _state
    val isActive: Boolean get() = _state != State.IDLE

    private var _state = State.IDLE
    private data class Track(var x: Float, var y: Float, val isPalm: Boolean)
    private val tracked = LinkedHashMap<Int, Track>()

    // Single-finger lock baseline.
    private var s0x = 0f
    private var s0y = 0f
    private var singleAllowed = false
    // Dual baselines. initialSpan is FROZEN at 2nd-finger down (or set change).
    private var focus0x = 0f
    private var focus0y = 0f
    private var initialSpan = 0f
    // Last applied (jump-free continuation).
    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    // Smoothed pinch focus (what ZoomBy reports).
    private var smoothX = 0f
    private var smoothY = 0f
    // Grace bookkeeping.
    private var graceKind: Kind? = null
    private var graceDeadline = 0L

    private fun counted(): List<Map.Entry<Int, Track>> = tracked.entries.filter { !it.value.isPalm }

    private fun focusOf(pts: List<Track>): Pt {
        var sx = 0f
        var sy = 0f
        for (p in pts) {
            sx += p.x
            sy += p.y
        }
        return Pt(sx / pts.size, sy / pts.size)
    }

    /** AOSP span: 2x mean deviation, hypot — exact finger distance for 2 pointers. */
    private fun spanOf(pts: List<Track>, f: Pt): Float {
        var dx = 0f
        var dy = 0f
        for (p in pts) {
            dx += abs(p.x - f.x)
            dy += abs(p.y - f.y)
        }
        dx = dx / pts.size * 2f
        dy = dy / pts.size * 2f
        return hypot(dx, dy).toFloat()
    }

    private fun smoothFocus(tx: Float, ty: Float): Pt {
        smoothX += (tx - smoothX) * focusAlpha
        smoothY += (ty - smoothY) * focusAlpha
        if (abs(tx - smoothX) < focusSnapPx) smoothX = tx
        if (abs(ty - smoothY) < focusSnapPx) smoothY = ty
        return Pt(smoothX, smoothY)
    }

    private fun cap(v: Float) = v.coerceIn(-MAX_FRAME_DELTA_PX, MAX_FRAME_DELTA_PX)

    /** Full re-baseline: applied deltas AND frozen upgrade baseline (AOSP restart). */
    private fun rebaselineAll(fx: Float, fy: Float, span: Float) {
        focus0x = fx
        focus0y = fy
        initialSpan = span
        lastX = fx
        lastY = fy
        lastSpan = span
        smoothX = fx
        smoothY = fy
    }

    private fun currentFrame(): Triple<Float, Float, Float> {
        val c = counted().map { it.value }
        val f = focusOf(c)
        return Triple(f.x, f.y, spanOf(c, f))
    }

    private fun endActive(): Output? {
        val kind = when (_state) {
            State.SINGLE_PAN -> Kind.SINGLE_PAN
            State.DUAL_PAN -> Kind.DUAL_PAN
            State.DUAL_PINCH -> Kind.DUAL_PINCH
            State.GRACE -> graceKind
            else -> null
        }
        tracked.clear()
        _state = State.IDLE
        graceKind = null
        return if (kind != null) Output.GestureEnd(kind) else null
    }

    private fun abortActive(): Output {
        tracked.clear()
        _state = State.IDLE
        graceKind = null
        return Output.AbortGesture
    }

    // ---- inputs ----

    /**
     * @param allowSinglePan adapter's blank check (isBlankX at down). A lone
     * finger on paper must never lock here — ink owns it.
     */
    fun pointerDown(id: Int, x: Float, y: Float, isPalm: Boolean, allowSinglePan: Boolean, nowMs: Long): Output? {
        if (_state == State.GRACE && nowMs >= graceDeadline) {
            val end = endActive()
            // Re-process as a fresh gesture (fresh IDLE down emits null
            // by construction; the tracking side effect is what matters).
            pointerDown(id, x, y, isPalm, allowSinglePan, nowMs)
            return end
        }
        if (tracked.containsKey(id)) return null
        tracked[id] = Track(x, y, isPalm)
        val c = counted()
        return when (_state) {
            State.IDLE -> {
                if (c.size == 1 && !isPalm) {
                    val p = c[0].value
                    s0x = p.x
                    s0y = p.y
                    singleAllowed = allowSinglePan
                    _state = State.SINGLE_TRACKING
                }
                null
            }
            State.SINGLE_TRACKING, State.SINGLE_PAN -> {
                if (c.size >= 2) {
                    // 2nd finger: blank single voids, fresh dual race (frozen initial).
                    val wasPan = _state == State.SINGLE_PAN
                    val (fx, fy, span) = currentFrame()
                    rebaselineAll(fx, fy, span)
                    _state = State.DUAL_UNDECIDED
                    if (wasPan) Output.GestureEnd(Kind.SINGLE_PAN) else null
                } else null
            }
            State.DUAL_UNDECIDED, State.DUAL_PAN, State.DUAL_PINCH -> {
                // Set change mid-gesture: re-baseline everything, keep/extend race.
                // (AOSP restart semantics; single baseline source, no drift.)
                if (c.size >= 2) {
                    val (fx, fy, span) = currentFrame()
                    rebaselineAll(fx, fy, span)
                    if (_state == State.DUAL_PAN || _state == State.DUAL_PINCH) {
                        // Stay locked; upgrade judged from the new frozen initial.
                    } else {
                        _state = State.DUAL_UNDECIDED
                    }
                }
                null
            }
            State.GRACE -> {
                if (c.size >= 2) {
                    val (fx, fy, span) = currentFrame()
                    rebaselineAll(fx, fy, span)
                    _state = when (graceKind) {
                        Kind.DUAL_PINCH -> State.DUAL_PINCH
                        else -> State.DUAL_PAN
                    }
                    graceKind = null
                }
                null
            }
        }
    }

    fun pointerMove(positions: Map<Int, Pt>, nowMs: Long): Output? {
        for ((id, p) in positions) tracked[id]?.let { it.x = p.x; it.y = p.y }
        if (_state == State.GRACE) {
            if (nowMs >= graceDeadline) return endActive()
            // Solo segment: silently track (discarded), so return doesn't jump.
            val (fx, fy, span) = currentFrame()
            lastX = fx
            lastY = fy
            lastSpan = span
            smoothX = fx
            smoothY = fy
            return null
        }
        val c = counted()
        if (c.isEmpty()) return null
        val (fx, fy, span) = currentFrame()
        return when (_state) {
            State.SINGLE_TRACKING -> {
                if (!singleAllowed) return null
                val dx = fx - s0x
                val dy = fy - s0y
                val dist = hypot(dx, dy)
                if (dist > touchSlopPx) {
                    _state = State.SINGLE_PAN
                    lastX = fx
                    lastY = fy
                    val r = (dist - touchSlopPx) / dist
                    // Lock frame carries two signals (Start + first delta):
                    // Start is parked for the adapter to drain first.
                    pendingStart = Output.GestureStart(Kind.SINGLE_PAN)
                    Output.ScrollBy(cap(dx * r), cap(dy * r))
                } else null
            }
            State.SINGLE_PAN -> {
                if (c.size >= 2) return null // wait for down-handler race; moves carry no delta here
                Output.ScrollBy(cap(fx - lastX), cap(fy - lastY)).also { lastX = fx; lastY = fy }
            }
            State.DUAL_UNDECIDED -> {
                if (c.size < 2) return null
                val panDist = hypot(fx - focus0x, fy - focus0y)
                val spanDist = abs(span - initialSpan)
                val pinchReady = span >= minSpanPx && spanDist > spanSlopPx
                val panReady = panDist > touchSlopPx
                when {
                    pinchReady && !panReady -> {
                        _state = State.DUAL_PINCH
                        lastX = fx
                        lastY = fy
                        lastSpan = span
                        smoothX = fx
                        smoothY = fy
                        pendingStart = Output.GestureStart(Kind.DUAL_PINCH)
                        Output.ZoomBy(1f, fx, fy)
                    }
                    panReady -> {
                        _state = State.DUAL_PAN
                        val dx = fx - focus0x
                        val dy = fy - focus0y
                        val dist = max(panDist, 1e-6f)
                        val r = (dist - touchSlopPx) / dist
                        lastX = fx
                        lastY = fy
                        pendingStart = Output.GestureStart(Kind.DUAL_PAN)
                        Output.ScrollBy(cap(dx * r), cap(dy * r))
                    }
                    else -> null
                }
            }
            State.DUAL_PAN -> {
                if (c.size < 2) return null
                if (span >= minSpanPx && abs(span - initialSpan) > spanSlopPx) {
                    _state = State.DUAL_PINCH
                    lastX = fx
                    lastY = fy
                    lastSpan = span
                    smoothX = fx
                    smoothY = fy
                    pendingStart = Output.GestureStart(Kind.DUAL_PINCH)
                    return Output.ZoomBy(1f, fx, fy)
                }
                Output.ScrollBy(cap(fx - lastX), cap(fy - lastY)).also { lastX = fx; lastY = fy }
            }
            State.DUAL_PINCH -> {
                if (c.size < 2) return null
                val sf = smoothFocus(fx, fy)
                return if (abs(span - lastSpan) < spanEpsPx) {
                    if (abs(sf.x - lastX) < 0.5f && abs(sf.y - lastY) < 0.5f) {
                        lastX = sf.x
                        lastY = sf.y
                        null
                    } else {
                        lastX = sf.x
                        lastY = sf.y
                        Output.ZoomBy(1f, sf.x, sf.y)
                    }
                } else {
                    val raw = if (lastSpan > 0f) span / lastSpan else 1f
                    lastSpan = span
                    lastX = sf.x
                    lastY = sf.y
                    val f = if (!raw.isFinite()) 1f else raw.coerceIn(0.5f, 2f)
                    Output.ZoomBy(f, sf.x, sf.y)
                }
            }
            else -> null
        }
    }

    /**
     * Single-output contract: lock frames need TWO signals (Start + first
     * delta). The Start is parked here; the adapter must drain it before
     * applying the returned delta. Null when nothing parked.
     */
    var pendingStart: Output.GestureStart? = null
        private set

    fun drainStart(): Output.GestureStart? {
        val s = pendingStart
        pendingStart = null
        return s
    }

    fun pointerUp(id: Int, wasCanceled: Boolean, nowMs: Long): Output? {
        val t = tracked.remove(id)
        if (t == null) return null
        if (wasCanceled && !t.isPalm && isActive) return abortActive()
        val c = counted()
        return when (_state) {
            State.IDLE -> null
            State.SINGLE_TRACKING, State.SINGLE_PAN -> endActive()
            State.DUAL_UNDECIDED -> {
                // Nothing locked: no grace worth keeping.
                if (c.isEmpty()) endActive() else {
                    _state = State.IDLE
                    tracked.clear()
                    null
                }
            }
            State.DUAL_PAN, State.DUAL_PINCH -> {
                if (c.isEmpty()) {
                    endActive()
                } else if (c.size == 1) {
                    graceKind = if (_state == State.DUAL_PINCH) Kind.DUAL_PINCH else Kind.DUAL_PAN
                    graceDeadline = nowMs + graceMs
                    _state = State.GRACE
                    val (fx, fy, span) = currentFrame()
                    lastX = fx
                    lastY = fy
                    lastSpan = span
                    null
                } else {
                    val (fx, fy, span) = currentFrame()
                    rebaselineAll(fx, fy, span)
                    null
                }
            }
            State.GRACE -> {
                if (nowMs >= graceDeadline) endActive()
                else if (c.isEmpty()) endActive()
                else null
            }
        }
    }

    fun cancelAll(): Output? {
        if (!isActive && tracked.isEmpty()) return null
        return abortActive()
    }

    /** Frozen contract: stylus down yields to ink immediately. */
    fun stylusDown(): Output? {
        if (!isActive && tracked.isEmpty()) return null
        tracked.clear()
        _state = State.IDLE
        graceKind = null
        return Output.YieldToStylus
    }

    /** Grace timeout + periodic pump. */
    fun tick(nowMs: Long): Output? {
        if (_state == State.GRACE && nowMs >= graceDeadline) return endActive()
        return null
    }
}
