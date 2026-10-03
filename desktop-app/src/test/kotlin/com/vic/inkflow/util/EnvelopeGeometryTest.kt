package com.vic.inkflow.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Geometry invariants for the single shared [EnvelopeUtils].
 *
 * ## What this replaced, and why it had to be replaced
 *
 * `EnvelopeParityTest` used to diff the desktop's port against `TabletReferenceEnvelope`,
 * an unmodified third copy of the tablet's algorithm kept in this same test source
 * set purely so the two could be compared. It was a good test: it caught exactly
 * the failure that mattered â€” a "harmless" tidy-up in the port (a rounding change, a
 * swapped cap direction, a dropped per-sample disc) that no test written against the
 * port alone would ever notice, and it ran against the real synced data rather than
 * synthetic strokes.
 *
 * But it only existed because the algorithm was maintained twice. Now that both apps
 * call one implementation in `:shared`, diffing it against a copy of itself asserts
 * nothing. The drift it guarded against is structurally impossible, so keeping a
 * third copy "just in case" would preserve the hazard rather than the safety.
 *
 * ## What takes its place
 *
 * Absolute invariants that hold for the geometry itself, checked on the same shapes
 * the differential test used plus the real database. The one that matters most is
 * [the outline is exactly the union of the per-sample discs]: `generateEnvelopePath` calls `addOval` at every
 * sample, so the outline's bounds must cover the polyline grown by the widest
 * half-width. That is the per-sample disc whose removal used to punch transparent
 * holes through fast strokes, and a bounds check is the closest a headless JVM test
 * can get to asserting the fill has no gaps.
 */
class EnvelopeGeometryTest {

    private val tolerance = 1e-3f

    private fun assertFinite(label: String, bounds: androidx.compose.ui.geometry.Rect) {
        assertTrue(!bounds.left.isNaN() && !bounds.top.isNaN(), "$label: NaN bounds")
        assertTrue(!bounds.right.isNaN() && !bounds.bottom.isNaN(), "$label: NaN bounds")
        assertTrue(bounds.width.isFinite() && bounds.height.isFinite(), "$label: non-finite bounds")
    }

    /**
     * The load-bearing invariant, and an exact one: the outline is precisely the union
     * of the per-sample discs.
     *
     * `generateEnvelopePath` calls `addOval` at every sample, and every other piece of
     * geometry it emits â€” the offset edges, the Bezier control midpoints, the cap arcs â€”
     * lies on or inside those circles, because an offset point sits at exactly `width/2`
     * from its sample. So the bounds are not merely close to the disc union; they are
     * the disc union.
     *
     * That is the property whose absence used to punch transparent holes through fast
     * strokes: drop the discs and the non-zero winding rule leaves gaps wherever the
     * envelope self-intersects. Asserting exact bounds catches that, and per-sample
     * radii are what makes it exact â€” using the widest radius on every axis would be
     * wrong, and was.
     */
    private fun assertEqualsDiscBounds(label: String, pts: List<StrokePoint>) {
        val bounds = EnvelopeUtils.generateEnvelopePath(pts).getBounds()
        assertFinite(label, bounds)
        assertEquals(pts.minOf { it.x - it.width / 2f }, bounds.left, tolerance, "$label: left")
        assertEquals(pts.minOf { it.y - it.width / 2f }, bounds.top, tolerance, "$label: top")
        assertEquals(pts.maxOf { it.x + it.width / 2f }, bounds.right, tolerance, "$label: right")
        assertEquals(pts.maxOf { it.y + it.width / 2f }, bounds.bottom, tolerance, "$label: bottom")
    }

    /** A stroke shaped like a fast diagonal sweep, which is where envelopes diverge. */
    private fun fastDiagonal(n: Int) = (0 until n).map { i ->
        StrokePoint(
            x = i * 7.3f,
            y = (i * 2.1f) + (i % 5) * 0.4f,
            width = 1f + (i % 9) * 0.55f
        )
    }

    /** A tight curve that forces the outline to self-intersect. */
    private fun tightSpiral(n: Int) = (0 until n).map { i ->
        val t = i * 0.35f
        StrokePoint(
            x = 60f + (1f + t * 0.18f) * kotlin.math.cos(t),
            y = 60f + (1f + t * 0.18f) * kotlin.math.sin(t),
            width = 0.6f + (i % 4) * 0.9f
        )
    }

    @Test
    fun `an empty stroke draws nothing`() {
        val path = EnvelopeUtils.generateEnvelopePath(emptyList())
        assertTrue(path.isEmpty, "an empty stroke must not produce geometry")
    }

    @Test
    fun `a single sample becomes exactly one disc`() {
        val p = StrokePoint(5f, 5f, 4f)
        val b = EnvelopeUtils.generateEnvelopePath(listOf(p)).getBounds()
        assertFinite("single point", b)
        // radius = width/2 = 2, so the outline is a 4x4 box centred on the sample.
        assertEquals(3f, b.left, tolerance, "left")
        assertEquals(3f, b.top, tolerance, "top")
        assertEquals(7f, b.right, tolerance, "right")
        assertEquals(7f, b.bottom, tolerance, "bottom")
    }

    @Test
    fun `every synthetic shape is exactly the union of its discs`() {
        val cases = listOf(
            "two points" to listOf(StrokePoint(0f, 0f, 3f), StrokePoint(20f, 12f, 1.5f)),
            "single point" to listOf(StrokePoint(5f, 5f, 4f)),
            "flat line" to (0..20).map { StrokePoint(it * 6f, 0f, 4f) },
            "vertical line" to (0..20).map { StrokePoint(0f, it * 6f, 2f) },
            "fast diagonal" to fastDiagonal(40),
            "tight spiral" to tightSpiral(60),
            "repeats" to (0..10).map { StrokePoint(if (it % 3 == 0) 2f else it * 4f, 1f, 2f) },
            "one point only at start" to
                listOf(StrokePoint(0f, 0f, 1f)) + (1..10).map { StrokePoint(it * 3f, 0f, 1f) }
        )
        cases.forEach { (name, pts) -> assertEqualsDiscBounds(name, pts) }
    }

    @Test
    fun `outline is exactly the disc union on the real synced database`() {
        val db = File(System.getProperty("user.home"), ".inkflow/inkflow.db")
        if (!db.exists()) {
            println("SKIP: no desktop database at ${db.absolutePath}")
            return
        }
        // Read the actual rows the tablet sent, via the real DAO so the point order
        // (which is the polyline order) is preserved.
        val dm = com.vic.inkflow.data.DatabaseManager(db.absolutePath).also { it.connect() }
        try {
            val uris = dm.getAllDocuments().map { it.uri }
            var compared = 0
            var points = 0
            uris.forEach { uri ->
                dm.getAllStrokesForDocument(uri).forEach { swp ->
                    if (swp.points.size < 2) return@forEach
                    // A capped slice: a 1,174-point stroke is slow to path and adds
                    // nothing once 120 points have already been checked.
                    val slice = swp.points.take(120).map {
                        StrokePoint(it.x, it.y, it.width)
                    }
                    assertEqualsDiscBounds("synced stroke ${swp.stroke.id.take(8)}", slice)
                    compared++
                    points += slice.size
                }
            }
            println("checked $compared synced strokes ($points points) for exact disc-union bounds")
            assertTrue(compared > 0, "expected synced strokes to check, found none")
        } finally {
            dm.disconnect()
        }
    }

    @Test
    fun `a zero width must still draw and must not poison cached geometry`() {
        // Guards the fallback path: the tablet's rows can carry a missing/zero width,
        // and a NaN would be cached into whatever holds the outline afterwards.
        val db = File(System.getProperty("user.home"), ".inkflow/inkflow.db")
        if (!db.exists()) return
        val dm = com.vic.inkflow.data.DatabaseManager(db.absolutePath).also { it.connect() }
        try {
            var checked = 0
            dm.getAllDocuments().forEach { doc ->
                dm.getAllStrokesForDocument(doc.uri).forEach { swp ->
                    val s = swp.points.take(60)
                    if (s.size < 2) return@forEach
                    val pts = s.mapIndexed { i, p ->
                        // Zero every third width to exercise the fallback.
                        StrokePoint(p.x, p.y, if (i % 3 == 0) 0f else p.width)
                    }
                    val b = EnvelopeUtils.generateEnvelopePath(pts).getBounds()
                    assertTrue(!b.left.isNaN() && !b.top.isNaN(), "NaN bounds for stroke")
                    assertTrue(b.width.isFinite() && b.height.isFinite(), "non-finite bounds")
                    checked++
                }
            }
            println("checked $checked strokes with zeroed widths")
            assertTrue(checked > 0)
        } finally {
            dm.disconnect()
        }
    }
}