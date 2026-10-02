package com.vic.inkflow.util

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** One sample of a stroke: position plus the width *at that sample*. */
data class StrokePoint(val x: Float, val y: Float, val width: Float)

/**
 * Builds the filled outline ("envelope") of a variable-width stroke.
 *
 * Ported **verbatim** from the tablet's `app/.../util/EnvelopeUtils.kt`. Do not
 * "improve" it independently: the tablet is the source of truth for the ink
 * format, and this routine is what makes a stroke drawn there look the same here.
 * Any change must land on both sides at once, because the same stroke is rendered
 * by both from the same rows in the database.
 *
 * ## Why an envelope at all
 *
 * The tablet records `points.width` **per sample** — velocity-derived, not a
 * single value per stroke. In the synced data that comes out as ~2,100 distinct
 * widths across ~16,000 points, and a single 1,174-point stroke carries 457 of
 * them. That is the pressure response of a real pen.
 *
 * `DrawScope.drawPath(style = Stroke(width = ...))` has one width for the whole
 * path, so it cannot express this at all; using the stroke's nominal
 * `strokeWidth` (which the tablet deliberately stores as the *base* width, "not
 * the velocity-derived per-point width") renders a tapering brush stroke as a
 * flat hairline. Hence a filled polygon.
 *
 * ## The three non-obvious parts
 *
 * 1. **A circle is added at every sample.** Two reasons, both load-bearing: it
 *    fills the holes that the non-zero winding rule leaves where the envelope
 *    self-intersects on a tight curve, and it rounds every corner no matter how
 *    ragged the Bezier contour got. Removing it does not degrade gracefully — it
 *    punches transparent holes through the middle of fast strokes.
 * 2. **Tangent from far-apart neighbours**, skipping samples closer than 1px, so
 *    one jittery sample cannot swing the normal and locally invert the outline.
 * 3. **The caps subtract the angle**, which sweeps the arc *outward*. Adding it
 *    carves a concave dish into the end of every stroke, and because the ink is
 *    filled, that dish shows as background.
 */
object EnvelopeUtils {

    /** Steps in the semicircular end caps. 12 keeps a tip visibly round at 300dpi. */
    private const val CAP_STEPS = 12

    /** Neighbour sampling distance; closer than this a neighbour is ignored. */
    private const val NEIGHBOUR_MIN_PX = 1f

    fun generateEnvelopePath(points: List<StrokePoint>): Path {
        val path = Path()
        if (points.isEmpty()) return path

        if (points.size == 1) {
            val p = points.first()
            val r = p.width / 2f
            path.addOval(Rect(p.x - r, p.y - r, p.x + r, p.y + r))
            return path
        }

        val leftPoints = mutableListOf<Offset>()
        val rightPoints = mutableListOf<Offset>()

        for (i in points.indices) {
            val curr = points[i]

            var prevIndex = i - 1
            while (prevIndex >= 0 &&
                hypot(curr.x - points[prevIndex].x, curr.y - points[prevIndex].y) < NEIGHBOUR_MIN_PX
            ) prevIndex--
            val prev = if (prevIndex >= 0) points[prevIndex] else curr

            var nextIndex = i + 1
            while (nextIndex < points.size &&
                hypot(points[nextIndex].x - curr.x, points[nextIndex].y - curr.y) < NEIGHBOUR_MIN_PX
            ) nextIndex++
            val next = if (nextIndex < points.size) points[nextIndex] else curr

            var dx = next.x - prev.x
            var dy = next.y - prev.y

            // Endpoints have no neighbour on one side; fall back to the one that exists.
            if (prev === curr && next !== curr) {
                dx = next.x - curr.x
                dy = next.y - curr.y
            } else if (next === curr && prev !== curr) {
                dx = curr.x - prev.x
                dy = curr.y - prev.y
            }

            val len = hypot(dx, dy)
            if (len > 0.01f) {
                dx /= len
                dy /= len
            } else {
                dx = 1f
                dy = 0f
            }

            val nx = -dy
            val ny = dx

            val r = curr.width / 2f
            leftPoints.add(Offset(curr.x + nx * r, curr.y + ny * r))
            rightPoints.add(Offset(curr.x - nx * r, curr.y - ny * r))

            // The per-sample disc: fills self-intersection holes and rounds corners.
            path.addOval(Rect(curr.x - r, curr.y - r, curr.x + r, curr.y + r))
        }

        // Left edge forwards, smoothed.
        path.moveTo(leftPoints.first().x, leftPoints.first().y)
        for (i in 1 until leftPoints.size) {
            val p1 = leftPoints[i - 1]
            val p2 = leftPoints[i]
            path.quadraticTo(p1.x, p1.y, (p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
        }
        path.lineTo(leftPoints.last().x, leftPoints.last().y)

        // End cap: subtract the angle so the arc sweeps outward.
        val endPt = points.last()
        val endR = endPt.width / 2f
        val endAngle = atan2(leftPoints.last().y - endPt.y, leftPoints.last().x - endPt.x)
        for (i in 0..CAP_STEPS) {
            val a = endAngle - (Math.PI.toFloat() * i / CAP_STEPS)
            path.lineTo(endPt.x + cos(a) * endR, endPt.y + sin(a) * endR)
        }

        // Right edge backwards.
        path.lineTo(rightPoints.last().x, rightPoints.last().y)
        for (i in rightPoints.indices.reversed().drop(1)) {
            val p1 = rightPoints[i + 1]
            val p2 = rightPoints[i]
            path.quadraticTo(p1.x, p1.y, (p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
        }
        path.lineTo(rightPoints.first().x, rightPoints.first().y)

        // Start cap.
        val startPt = points.first()
        val startR = startPt.width / 2f
        val startAngle = atan2(rightPoints.first().y - startPt.y, rightPoints.first().x - startPt.x)
        for (i in 0..CAP_STEPS) {
            val a = startAngle - (Math.PI.toFloat() * i / CAP_STEPS)
            path.lineTo(startPt.x + cos(a) * startR, startPt.y + sin(a) * startR)
        }

        path.close()
        return path
    }
}