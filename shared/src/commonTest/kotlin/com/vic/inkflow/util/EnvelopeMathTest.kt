package com.vic.inkflow.util

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The geometry the pen's outline is made of, pinned independently of any renderer.
 *
 * ## Why this exists
 *
 * The corner defect that prompted this file was invisible to every test we had:
 * `EnvelopeGeometryTest` asserted the outline's *bounds*, and the bounds were always
 * right. A corner that lost 35 % of the pen radius lives entirely inside the correct
 * bounds, so a bounds check cannot see it — which is exactly how it survived.
 *
 * Everything here is arithmetic on [EnvelopeUtils.presmooth] plus the two rules the
 * renderer obeys (a disc of radius `width/2` at every sample, the swept quad between
 * consecutive centreline points). "Is the ink here?" is therefore `distance to the
 * centreline <= r` — no rasterisation, no Path, so the same assertions run on
 * desktop, Android and iOS.
 *
 * ## What the numbers mean
 *
 * For a V whose arms meet at 90°, the nib at the vertex is a disc, so:
 *  - straight out past the vertex (the **tip**) the ink reaches exactly `r`;
 *  - into the throat the arms themselves fill `r / sin(45°) = 1.41 r`. That is not a
 *    defect, it is what a round nib physically leaves. The old renderer produced
 *    **6 r** — a straight band sealing the mouth — because it drew one chamfer
 *    between the last sample on each arm.
 */
class EnvelopeMathTest {

    private val r = 5f          // pen half-width: width 10
    private val width = 2f * r

    // ------------------------------------------------------------------ helpers

    private fun p(x: Float, y: Float) = StrokePoint(x, y, width)

    private fun lerp(a: StrokePoint, b: StrokePoint, u: Float) =
        p(a.x + (b.x - a.x) * u, a.y + (b.y - a.y) * u)

    /** Samples along one arm, optionally starting late / stopping short of [to]. */
    private fun arm(
        from: StrokePoint,
        to: StrokePoint,
        step: Float,
        startSkip: Float = 0f,
        includeEnd: Boolean = true
    ): List<StrokePoint> {
        val len = hypot(to.x - from.x, to.y - from.y)
        val out = ArrayList<StrokePoint>()
        var t = startSkip
        while (t < len - 1e-3f) {
            out.add(lerp(from, to, t / len))
            t += step
        }
        if (includeEnd) out.add(to)
        return out
    }

    /** A sampled polyline that keeps every vertex exactly (a real pen would not,
     *  which is what `vAt`'s `vertexSampled = false` case models). */
    private fun polyline(vertices: List<StrokePoint>, step: Float): List<StrokePoint> {
        val out = ArrayList<StrokePoint>()
        for (i in 0 until vertices.size - 1) {
            val seg = arm(vertices[i], vertices[i + 1], step)
            if (i > 0) out.addAll(seg.drop(1)) else out.addAll(seg)
        }
        return out
    }

    private fun distToSegment(q: StrokePoint, a: StrokePoint, b: StrokePoint): Float {
        val vx = b.x - a.x
        val vy = b.y - a.y
        val vv = vx * vx + vy * vy
        if (vv < 1e-6f) return hypot(q.x - a.x, q.y - a.y)
        val t = (((q.x - a.x) * vx + (q.y - a.y) * vy) / vv).coerceIn(0f, 1f)
        return hypot(q.x - (a.x + vx * t), q.y - (a.y + vy * t))
    }

    private fun distToPolyline(q: StrokePoint, poly: List<StrokePoint>): Float {
        var best = Float.MAX_VALUE
        for (i in 0 until poly.size - 1) {
            val d = distToSegment(q, poly[i], poly[i + 1])
            if (d < best) best = d
        }
        return best
    }

    /** How far the ink reaches from [from] along a unit direction, in px. -1 = none. */
    private fun inkExtent(
        from: StrokePoint,
        dirX: Float,
        dirY: Float,
        poly: List<StrokePoint>,
        maxR: Float = 80f
    ): Float {
        var lastInside = -1f
        var t = 0f
        while (t <= maxR) {
            val q = p(from.x + dirX * t, from.y + dirY * t)
            if (distToPolyline(q, poly) <= r) lastInside = t
            t += 0.05f
        }
        return lastInside
    }

    /** The centreline actually rendered: pre-smoothed, as the outline uses it. */
    private fun centreline(raw: List<StrokePoint>) = EnvelopeUtils.presmooth(raw)

    /**
     * A V with its vertex at (100, 200) and arms rising left and right, so +y is the
     * tip and -y is the throat.
     */
    private fun vAt(
        spacing: Float,
        vertexSampled: Boolean = true,
        secondArmSkip: Float = 0f
    ): List<StrokePoint> {
        val vertex = p(100f, 200f)
        val left = p(0f, 100f)
        val right = p(200f, 100f)
        val first = arm(left, vertex, spacing, includeEnd = vertexSampled)
        val second = arm(vertex, right, spacing, startSkip = secondArmSkip)
        return first + second.drop(1)
    }

    // -------------------------------------------------------------------- tests

    @Test
    fun `a straight stroke is exactly two radii wide`() {
        val raw = (0..20).map { p(it * 6f, 0f) }
        val line = centreline(raw)
        val mid = p(60f, 0f)
        val up = inkExtent(mid, 0f, -1f, line)
        val down = inkExtent(mid, 0f, 1f, line)
        assertTrue(abs(up - r) < 0.1f, "half width up = $up, expected $r")
        assertTrue(abs(down - r) < 0.1f, "half width down = $down, expected $r")
        // Dense samples are left exactly as recorded: smoothing dense ink would only
        // blur what the pen actually did.
        assertTrue(line.size == raw.size, "dense samples must not be resampled")
    }

    @Test
    fun `a corner that was sampled keeps its tip and its throat`() {
        val line = centreline(vAt(spacing = 60f))
        val vertex = p(100f, 200f)

        // Out past the vertex: exactly one nib radius, which is where the nib ends.
        val tip = inkExtent(vertex, 0f, 1f, line)
        assertTrue(abs(tip - r) <= 0.1f * r, "tip = $tip, expected $r +-10%")

        // Into the throat: the arms' own half-widths meet at r / sin(45deg). The old
        // algorithm produced ~6 r here, a band sealing the mouth.
        val expected = r / sin((PI / 4).toFloat())
        val throat = inkExtent(vertex, 0f, -1f, line)
        assertTrue(
            abs(throat - expected) <= 0.1f * expected,
            "throat = $throat, expected $expected +-10% (a sealed mouth reads ~${6 * r})"
        )
    }

    @Test
    fun `a corner that was sampled is not rounded away`() {
        val line = centreline(vAt(spacing = 60f))
        val closest = line.minOf { hypot(it.x - 100f, it.y - 200f) }
        assertTrue(closest < 0.01f, "the presmoothed centreline misses the vertex by $closest px")
    }

    @Test
    fun `presmoothing passes through every sample`() {
        // Interpolating, not approximating: if the curve stopped touching its samples,
        // every recorded width would drift away from its recorded position.
        val raw = (0..12).map { i -> p(i * 17f, sin(i * 0.7f) * 40f + 100f) }
        val line = centreline(raw)
        raw.forEach { s ->
            val d = line.minOf { hypot(it.x - s.x, it.y - s.y) }
            assertTrue(d < 0.01f, "sample at (${s.x}, ${s.y}) is $d px off the centreline")
        }
    }

    @Test
    fun `presmoothing never invents ink outside the nib sweep`() {
        // Every emitted point must stay inside the capsule of the raw segment it came
        // from. That is what keeps the exact disc-union bounds in EnvelopeGeometryTest
        // true, and stops smoothing from spraying ink beside a fast turn.
        val shapes = listOf(
            (0..30).map { p(it * 23f, sin(it * 0.4f) * 60f + 300f) },
            polyline(listOf(p(0f, 0f), p(300f, 0f), p(300f, 300f)), 40f),
            (0..25).map { i ->
                val t = i * 0.5f
                p(60f + cos(t) * (20f + t * 4f), 60f + sin(t) * (20f + t * 4f))
            }
        )
        shapes.forEachIndexed { index, raw ->
            centreline(raw).forEach { q ->
                val d = distToPolyline(q, raw)
                assertTrue(d <= r + 0.05f, "shape $index: ink $d px from the stroke (r=$r)")
            }
        }
    }

    @Test
    fun `a fast arc is not pulled inward`() {
        // Corner cutting pulled a 120 px arc 2.5 px inward at 24 px spacing, which is
        // why this is an interpolating spline. A dip here thins every fast curve.
        val radius = 120f
        val n = (2 * PI * radius / 24f).toInt()
        val raw = (0..n).map {
            val a = (2 * PI).toFloat() * it / n
            p(cos(a) * radius, sin(a) * radius)
        }
        val line = centreline(raw)
        val worst = line.maxOf { abs(hypot(it.x, it.y) - radius) }
        assertTrue(worst <= 0.02f * radius, "arc pulled ${worst}px off radius (limit ${0.02f * radius})")
    }

    @Test
    fun `a corner between two samples cannot be recovered`() {
        // Pinned limitation, not a wish: with no sample at the vertex the stroke says
        // the pen went straight across the mouth. Any renderer that "fixes" this is
        // inventing ink, so this records the physical outcome instead.
        val raw = vAt(spacing = 60f, vertexSampled = false, secondArmSkip = 31f)
        val line = centreline(raw)
        val vertex = p(100f, 200f)
        assertTrue(raw.none { hypot(it.x - 100f, it.y - 200f) < 1f }, "precondition: no sample at the vertex")
        val tip = inkExtent(vertex, 0f, 1f, line)
        val throat = inkExtent(vertex, 0f, -1f, line)
        assertTrue(tip < 0f, "expected no ink past an unsampled vertex, got $tip")
        assertTrue(throat > 4f * r, "expected the mouth to be bridged, got $throat")
    }

    @Test
    fun `a right angle keeps a round nib corner`() {
        // Ticks and brackets: a right-angle turn at 50 px spacing was chamfered before
        // the fix. A round nib reaches r outward, and sqrt(2) r into the corner.
        val raw = polyline(listOf(p(0f, 300f), p(300f, 300f), p(300f, 0f)), 50f)
        val line = centreline(raw)
        val corner = p(300f, 300f)
        val inv = 1f / sqrt(2f)

        val outward = inkExtent(corner, inv, inv, line, maxR = 40f)
        assertTrue(abs(outward - r) <= 0.1f * r, "outward reach = $outward, expected $r +-10%")

        val inward = inkExtent(corner, -inv, -inv, line, maxR = 40f)
        val diag = sqrt(2f) * r
        assertTrue(abs(inward - diag) <= 0.1f * diag, "inner reach = $inward, expected $diag +-10%")
    }

    @Test
    fun `degenerate input stays finite`() {
        val cases = listOf(
            listOf(p(5f, 5f)),
            listOf(p(5f, 5f), p(5f, 5f), p(5f, 5f)),
            (0..10).map { p(it * 30f, 40f) }.mapIndexed { i, s -> if (i % 3 == 0) s.copy(width = 0f) else s },
            listOf(p(0f, 0f), p(2000f, 0f))
        )
        cases.forEachIndexed { index, raw ->
            val line = centreline(raw)
            assertTrue(line.isNotEmpty(), "case $index produced nothing")
            line.forEach {
                assertTrue(
                    it.x.isFinite() && it.y.isFinite() && it.width.isFinite(),
                    "case $index produced non-finite geometry at (${it.x}, ${it.y}, ${it.width})"
                )
            }
        }
    }

    @Test
    fun `a long jump is subdivided but capped`() {
        // A page-sized jump (the canvas is ~4400 px tall) must not spawn an unbounded
        // number of quads, and must stay straight.
        val line = centreline(listOf(p(0f, 0f), p(1500f, 0f), p(3000f, 0f)))
        assertTrue(line.size <= 2 * 64 + 1, "two 1500 px segments produced ${line.size} points")
        assertTrue(line.size > 10, "expected subdivision, got ${line.size} points")
        line.forEach { assertTrue(abs(it.y) < 0.01f, "straight segment bent to y=${it.y}") }
    }
}
