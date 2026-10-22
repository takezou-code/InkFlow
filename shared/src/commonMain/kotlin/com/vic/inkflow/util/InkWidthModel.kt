package com.vic.inkflow.util

import kotlin.math.hypot

/**
 * Per-sample stroke width — **one rule for both apps**.
 *
 * The tablet is the source of truth for the ink format. It stores `strokeWidth`
 * as the **base** width the user picked — its own comment is explicit that this is
 * "so PDF export uses the correct line thickness rather than the
 * velocity-derived per-point width" — and stores the *actual* drawn width on
 * every sample in `points.width`.
 *
 * A mouse reports no pressure, so the velocity path is the only one available
 * on the desktop; that is also the tablet's fallback for a pressureless stylus, so
 * a desktop stroke follows exactly the curve the tablet would produce for the same
 * motion.
 *
 * ## Why this lives in `:shared` now
 *
 * The constants and the smoothing used to be **copied** into the desktop, with a
 * comment saying so: "if either side is tuned, the other has to be tuned to match
 * or the same gesture produces different ink on each device". That is a drift
 * waiting to happen, and the ink curve is exactly the kind of thing that gets
 * tuned ("the strokes feel too thin at speed") without anyone remembering the
 * other app.
 *
 * The tablet's *pressure* path stays on the tablet — a mouse has no pressure, and
 * the pressure branch needs the tablet's own pressure bookkeeping. But the
 * **smoothing both paths share** lives here ([smoothWidth]), because a rule that
 * one path applies and the other does not is a rule that will drift: it already
 * did once (the pressure path used the raw target while the velocity path
 * smoothed, and the "bamboo" segments came back).
 */
object InkWidthModel {

    /** Thickest/Thinnest as multiples of the base width (tablet: 1.7f / 0.25f). */
    private const val MAX_MULT = 1.7f
    private const val MIN_MULT = 0.25f

    /**
     * Sensitivity divisor (tablet: `0.7f / sensitivity`). Tablet default is 0.80.
     * Higher = thins out sooner as the pen accelerates.
     */
    private const val VELOCITY_BASE = 0.7f

    /** Matches the tablet's global default for 「粗細跟手速度」. */
    const val DEFAULT_SENSITIVITY = 0.80f

    /** Matches the tablet's global default for 「提筆變細強度」 (0 = blunt, 1 = lively). */
    const val DEFAULT_RESPONSIVENESS = 0.33f

    /**
     * Eases the width from the previous sample towards the target.
     *
     * **Both** width paths go through this — the velocity one *and* the pressure
     * one. The tablet's stylus reports a pressure that jumps between samples
     * (the digitiser's resolution is coarse, and a fast stroke changes pressure
     * a lot between two points), so a target that is used directly makes the
     * line's thickness step at every sample — the "bamboo" segments the comment
     * below keeps mentioning. The previous sample is weighted heavily so the
     * thickness *eases* into the target instead of stepping.
     *
     * It is public because the pressure path lives on the tablet (only a
     * pressure-capable stylus has one), and the tablet's path must smooth the
     * same way the velocity path does. When the smoothing lived only inside
     * [widthFor], the tablet's pressure branch returned the raw target and the
     * bamboo came back — the two paths must share this one function.
     */
    fun smoothWidth(previousWidth: Float, targetWidth: Float, responsiveness: Float = DEFAULT_RESPONSIVENESS): Float {
        val smoothOld = 0.95f - 0.45f * responsiveness.coerceIn(0f, 1f)
        return previousWidth * smoothOld + targetWidth * (1f - smoothOld)
    }

    /**
     * Width for the segment ending at [pos], given the previous sample.
     *
     * @param distPx distance travelled since the previous sample, in the same
     *   units as [pos] (screen pixels on the desktop).
     * @param dtMs milliseconds since the previous sample. Floored at 1 so a
     *   coalesced event cannot produce a division by zero and an infinite width.
     */
    fun widthFor(
        distPx: Float,
        dtMs: Long,
        previousWidth: Float,
        baseWidth: Float,
        isHighlighter: Boolean,
        sensitivity: Float = DEFAULT_SENSITIVITY,
        responsiveness: Float = DEFAULT_RESPONSIVENESS
    ): Float {
        // The tablet deliberately does not vary highlighter thickness: a
        // translucent highlighter that thins on fast strokes stops reading as a
        // marker and starts reading as a gap.
        if (isHighlighter) return baseWidth

        val dt = dtMs.coerceAtLeast(1L)
        val velocity = distPx / dt.toFloat()
        val sens = sensitivity.coerceAtLeast(0.1f)

        val maxW = baseWidth * MAX_MULT
        val minW = baseWidth * MIN_MULT

        val velocityThreshold = VELOCITY_BASE / sens
        val vMapped = (velocity / velocityThreshold).coerceIn(0f, 1f)
        val velocityTarget = minW + (maxW - minW) * (1f - vMapped)

        return smoothWidth(previousWidth, velocityTarget, responsiveness)
    }

    /** Convenience overload taking the previous sample's position directly. */
    fun widthFor(
        previous: StrokePoint,
        posX: Float,
        posY: Float,
        dtMs: Long,
        baseWidth: Float,
        isHighlighter: Boolean,
        sensitivity: Float = DEFAULT_SENSITIVITY,
        responsiveness: Float = DEFAULT_RESPONSIVENESS
    ): Float = widthFor(
        distPx = hypot(posX - previous.x, posY - previous.y),
        dtMs = dtMs,
        previousWidth = previous.width,
        baseWidth = baseWidth,
        isHighlighter = isHighlighter,
        sensitivity = sensitivity,
        responsiveness = responsiveness
    )
}