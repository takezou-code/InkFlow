package com.vic.inkflow.util

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.min

/** One sample of a stroke: position plus the width *at that sample*. */
data class StrokePoint(val x: Float, val y: Float, val width: Float)

/**
 * Builds the filled outline ("envelope") of a variable-width stroke.
 *
 * ## Why one implementation
 *
 * This file used to exist twice — once in `:app` and once in `:desktopApp` — with
 * the desktop's copy annotated "ported **verbatim** from the tablet". It was not
 * verbatim: the two had drifted apart structurally, and keeping them in sync needed
 * a third copy of the algorithm in the desktop's *test* source set purely to diff
 * against. Now both apps, the PDF export and the iOS build call this one function,
 * so a stroke cannot render differently per platform.
 *
 * ## Why an envelope at all
 *
 * The tablet records `points.width` **per sample** — velocity-derived, not a single
 * value per stroke. `drawPath(style = Stroke(width = ...))` has one width for the
 * whole path, so it cannot express that; using the stroke's nominal `strokeWidth`
 * renders a tapering brush stroke as a flat hairline. Hence a filled polygon.
 *
 * ## The shape is the nib sweep, built as a union of independent primitives
 *
 * The outline is `⋃ per-sample disc` ∪ `⋃ per-segment quad`. Each primitive is
 * emitted on its own (no shared contour), so:
 *
 *  - **No corner can be cut.** The old version chained one contour through offset
 *    points with `quadraticTo(control = p1, end = mid(p1, p2))`, which only ever
 *    *passes through the midpoints*: exact on a straight run, and on a turn it
 *    shaved 25–37 % of the pen radius off the corner at 12–60 px sample spacing.
 *    A chamfer band across the throat of the V was the same bug seen from outside.
 *    Quads per segment cannot express that error: each segment is filled with its
 *    own normal, and the discs round the joins.
 *  - **Bounds stay exact.** Every point this emits lies inside the *capsule* of the
 *    raw segment it came from (see [clampToCapsule]), so the outline can never
 *    exceed the union of the recorded nib discs. Ink only ever appears where the
 *    nib demonstrably passed.
 *  - **Faster.** The old code allocated two [androidx.compose.ui.geometry.Offset]
 *    per sample plus a Bezier contour plus 2 × [CAP_STEPS] cap points. This one
 *    allocates nothing per sample beyond the primitives themselves.
 *
 * ## The centreline between samples
 *
 * Two samples 60 px apart (fast writing) leave the nib's true path unknown, and
 * joining them with a straight quad looks faceted. [presmooth] therefore subdivides
 * long segments with an **interpolating** Catmull-Rom (it passes *through* every
 * sample, so it cannot shrink an arc the way a corner-cutting scheme does — Chaikin
 * pulled a 120 px arc 2.5 px inward at 24 px spacing) and **anchors every real
 * corner**, so a genuine V stays sharp instead of being rounded away into a smear.
 *
 * A corner that falls entirely *between* two samples cannot be recovered: the
 * samples say the pen went straight, and any renderer that guessed otherwise would
 * be inventing ink. That is why sample rate, not pen width, decides whether a hard
 * turn is faithful.
 */
object EnvelopeUtils {

    /**
     * Samples closer together than this are treated as one point when deriving a
     * tangent: below it the direction is dominated by sensor jitter and the local
     * normal inverts.
     */
    private const val NEIGHBOUR_MIN_PX = 1f

    /**
     * A turn sharper than this is real ink, not a sampling artefact, and is kept
     * sharp. 60° is well above the curvature of handwriting (a 10 px-spaced arc of
     * radius 20 px turns ~29°) and well below a deliberate corner or tick.
     */
    private const val CORNER_DEGREES = 60.0

    /**
     * Longest piece the centreline may be split into. Only matters when samples are
     * far apart (fast strokes); dense sampling is left untouched.
     */
    private const val MAX_SEGMENT_PX = 10f

    /** Hard ceiling on subdivisions per segment, so one absurd jump cannot explode. */
    private const val MAX_SUBDIVISIONS = 64

    private const val EPS = 1e-4f

    /**
     * Generates a filled path for a variable-width stroke: round nib discs at every
     * recorded sample, joined by the swept quad of each segment.
     */
    fun generateEnvelopePath(points: List<StrokePoint>): Path {
        val path = Path()
        if (points.isEmpty()) return path

        if (points.size == 1) {
            addNibDisc(path, points[0].x, points[0].y, points[0].width / 2f)
            return path
        }

        // 1. Every recorded sample is ink. This is also what guarantees the outline
        //    cannot exceed the disc union: see presmooth().
        for (p in points) addNibDisc(path, p.x, p.y, p.width / 2f)

        // 2. Join consecutive samples with the area the nib swept between them.
        val centre = presmooth(points)
        for (i in 1 until centre.size) addSweepQuad(path, centre[i - 1], centre[i])
        return path
    }

    /** A nib disc, wound the same way as [addSweepQuad] so overlaps cannot cancel. */
    private fun addNibDisc(path: Path, x: Float, y: Float, r: Float) {
        path.addOval(Rect(x - r, y - r, x + r, y + r), Path.Direction.Clockwise)
    }

    /**
     * The area swept by a round nib of radius `r` moving from `a` to `b` with the
     * radius changing linearly — a trapezoid. Vertices are emitted clockwise in
     * screen coordinates (y grows downward), matching [addNibDisc]'s
     * `Direction.Clockwise`: with the non-zero winding rule a region covered twice
     * must not come out with winding 0, or the fill punches a transparent hole
     * exactly where two pieces of ink overlap. `EnvelopeRenderTest` fills the real
     * path and would go red if these two ever disagreed.
     */
    private fun addSweepQuad(path: Path, a: StrokePoint, b: StrokePoint) {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy)
        if (len < EPS) return
        val nx = -dy / len
        val ny = dx / len
        val ra = a.width / 2f
        val rb = b.width / 2f
        path.moveTo(a.x - nx * ra, a.y - ny * ra)
        path.lineTo(b.x - nx * rb, b.y - ny * rb)
        path.lineTo(b.x + nx * rb, b.y + ny * rb)
        path.lineTo(a.x + nx * ra, a.y + ny * ra)
        path.close()
    }

    /**
     * Subdivides long segments with a corner-preserving interpolating spline.
     *
     * Returns the raw list untouched when samples are dense (`size < 3` or every
     * segment short), which is the common case: a normal-speed stroke hands us new
     * samples every few pixels, and smoothing those would only blur what was
     * actually recorded.
     *
     * Two guarantees, both asserted in `EnvelopeMathTest`:
     *  - it passes exactly through every input point, so no recorded ink is dropped
     *    and an arc is never pulled inward;
     *  - every point it invents lies inside the capsule of the raw segment it belongs
     *    to, so no ink appears outside the nib sweep of the recorded samples.
     */
    internal fun presmooth(points: List<StrokePoint>): List<StrokePoint> {
        val n = points.size
        if (n < 3) return points

        // A vertex whose turn exceeds CORNER_DEGREES is an anchor: the spline is cut
        // there and the two sides use one-sided tangents, so a real corner stays a
        // corner. Endpoints are always anchors (a stroke must not shorten).
        val anchor = BooleanArray(n)
        anchor[0] = true
        anchor[n - 1] = true
        for (i in 1 until n - 1) {
            val a = points[i - 1]
            val b = points[i]
            val c = points[i + 1]
            val d1x = b.x - a.x
            val d1y = b.y - a.y
            val d2x = c.x - b.x
            val d2y = c.y - b.y
            val l1 = hypot(d1x, d1y)
            val l2 = hypot(d2x, d2y)
            if (l1 < NEIGHBOUR_MIN_PX || l2 < NEIGHBOUR_MIN_PX) continue
            val cosine = ((d1x * d2x + d1y * d2y) / (l1 * l2)).coerceIn(-1f, 1f)
            if (acos(cosine) * 180.0 / PI > CORNER_DEGREES) anchor[i] = true
        }

        val out = ArrayList<StrokePoint>(n + 8)
        out.add(points[0])
        for (i in 0 until n - 1) {
            val p0 = points[i]
            val p1 = points[i + 1]
            val dx = p1.x - p0.x
            val dy = p1.y - p0.y
            val len = hypot(dx, dy)
            if (len < EPS) {
                // Coincident samples carry no direction; keep the second disc's
                // position in the sequence so widths still advance.
                out.add(p1)
                continue
            }
            if (len <= MAX_SEGMENT_PX) {
                out.add(p1)
                continue
            }

            // Catmull-Rom tangents -> cubic Bezier handles, one-sided at an anchor so
            // the corner is approached along its own segment instead of being rounded.
            var t1x = dx
            var t1y = dy
            if (!anchor[i] && i > 0) {
                t1x = (p1.x - points[i - 1].x) * 0.5f
                t1y = (p1.y - points[i - 1].y) * 0.5f
            }
            var t2x = dx
            var t2y = dy
            if (!anchor[i + 1] && i + 2 < n) {
                t2x = (points[i + 2].x - p0.x) * 0.5f
                t2y = (points[i + 2].y - p0.y) * 0.5f
            }
            // Uneven sample spacing can make a handle longer than the segment, which
            // overshoots and loops. Clamping to a third of the segment is the standard
            // fix and cannot move an interpolating spline off the samples.
            val maxHandle = len / 3f
            val m1 = hypot(t1x, t1y)
            if (m1 > maxHandle && m1 > EPS) {
                t1x = t1x * maxHandle / m1
                t1y = t1y * maxHandle / m1
            }
            val m2 = hypot(t2x, t2y)
            if (m2 > maxHandle && m2 > EPS) {
                t2x = t2x * maxHandle / m2
                t2y = t2y * maxHandle / m2
            }
            val c1x = p0.x + t1x / 3f
            val c1y = p0.y + t1y / 3f
            val c2x = p1.x - t2x / 3f
            val c2y = p1.y - t2y / 3f

            val steps = min(MAX_SUBDIVISIONS, ceil(len / MAX_SEGMENT_PX).toInt())
            for (k in 1..steps) {
                val u = k.toFloat() / steps
                val v = 1f - u
                val px = v * v * v * p0.x + 3f * v * v * u * c1x + 3f * v * u * u * c2x + u * u * u * p1.x
                val py = v * v * v * p0.y + 3f * v * v * u * c1y + 3f * v * u * u * c2y + u * u * u * p1.y
                val width = p0.width + (p1.width - p0.width) * u
                out.add(clampToCapsule(px, py, p0, p1, dx / len, dy / len, len, width / 2f))
            }
        }
        return out
    }

    /**
     * Projects a point into the capsule (slab ∩ radius) of segment `p0`→`p1`.
     *
     * This is what keeps the smoothing honest: a Catmull-Rom handle points along the
     * neighbour chord, which near a turn can leave the segment laterally. Unclamped,
     * that would paint ink beside the stroke and grow the outline's bounds past the
     * recorded nib. Clamped, the spline may only bend *inside* what the nib swept.
     */
    private fun clampToCapsule(
        px: Float,
        py: Float,
        p0: StrokePoint,
        p1: StrokePoint,
        dirX: Float,
        dirY: Float,
        len: Float,
        r: Float
    ): StrokePoint {
        val ax = px - p0.x
        val ay = py - p0.y
        val along = (ax * dirX + ay * dirY).coerceIn(0f, len)
        var perpX = ax - dirX * along
        var perpY = ay - dirY * along
        val perpLen = hypot(perpX, perpY)
        if (perpLen > r && perpLen > EPS) {
            val s = r / perpLen
            perpX *= s
            perpY *= s
        }
        return StrokePoint(p0.x + dirX * along + perpX, p0.y + dirY * along + perpY, r * 2f)
    }
}
