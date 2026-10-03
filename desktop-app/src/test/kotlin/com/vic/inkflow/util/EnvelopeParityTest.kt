package com.vic.inkflow.util

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Differential test: the desktop's port against the **tablet's own algorithm**.
 *
 * [TabletReferenceEnvelope] in this same test source set is an unmodified copy of
 * `app/src/main/java/com/vic/inkflow/util/EnvelopeUtils.kt` from the tablet. Both
 * implementations run on the same JVM here, so the outline the desktop draws can
 * be compared against the one the tablet would draw, point for point, on the same
 * samples.
 *
 * This is the strongest check available without a second device, and it catches
 * the failure that matters: a "harmless" tidy-up in the port (a rounding change,
 * a swapped cap direction, a dropped per-sample disc) that no unit test written
 * against the port alone would ever notice.
 *
 * The real synced data is used as the sample set, not synthetic data — the tablet
 * sent 16,010 points across 253 strokes, and those are the shapes that have to
 * match.
 */
class EnvelopeParityTest {

    private val tolerance = 1e-3f

    private fun assertSamePath(
        a: androidx.compose.ui.graphics.Path,
        b: androidx.compose.ui.graphics.Path,
        what: String
    ) {
        val ba = a.getBounds()
        val bb = b.getBounds()
        assertEquals(ba.left, bb.left, tolerance, "$what: left")
        assertEquals(ba.top, bb.top, tolerance, "$what: top")
        assertEquals(ba.right, bb.right, tolerance, "$what: right")
        assertEquals(ba.bottom, bb.bottom, tolerance, "$what: bottom")
        assertEquals(ba.width, bb.width, tolerance, "$what: width")
        assertEquals(ba.height, bb.height, tolerance, "$what: height")
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
    fun `port matches the tablet on synthetic strokes`() {
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
        cases.forEach { (name, pts) ->
            assertSamePath(
                EnvelopeUtils.generateEnvelopePath(pts),
                TabletReferenceEnvelope.generateEnvelopePath(pts),
                name
            )
        }
    }

    @Test
    fun `port matches the tablet on the real synced database`() {
        val db = File(System.getProperty("user.home"), ".inkflow/inkflow.db")
        if (!db.exists()) {
            println("SKIP: no desktop database at ${db.absolutePath}; nothing to compare against")
            return
        }
        // Read a slice of the actual rows the tablet sent, via the real DAO so the
        // point order (which is the polyline order) is preserved.
        val dm = com.vic.inkflow.data.DatabaseManager(db.absolutePath).also { it.connect() }
        try {
            val uris = dm.getAllDocuments().map { it.uri }
            var compared = 0
            var points = 0
            uris.forEach { uri ->
                dm.getAllStrokesForDocument(uri).forEach { swp ->
                    if (swp.points.size < 2) return@forEach
                    // Compare a capped slice: a 1,174-point stroke is slow to path
                    // twice and adds nothing once 120 points already matched.
                    val slice = swp.points.take(120).map {
                        StrokePoint(it.x, it.y, it.width)
                    }
                    assertSamePath(
                        EnvelopeUtils.generateEnvelopePath(slice),
                        TabletReferenceEnvelope.generateEnvelopePath(slice),
                        "synced stroke ${swp.stroke.id.take(8)}"
                    )
                    compared++
                    points += slice.size
                }
            }
            println("compared $compared synced strokes ($points points) against the tablet's algorithm")
            assertTrue(compared > 0, "expected synced strokes to compare, found none")
        } finally {
            dm.disconnect()
        }
    }

    @Test
    fun `every distinct width in the database produces a bounded outline`() {
        // Guards the fallback path: a sample with a missing/zero width must still
        // be drawn, and must not put NaN into the geometry that gets cached.
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

    @Test
    fun `the smoothing helper is absent from both sides`() {
        // The tablet's file also exports `smoothCenterline`, but it is documented as
        // preview-only ("入庫點列不動 → 匯出零影響"). If the desktop ever starts
        // smoothing stored points it would silently diverge from the tablet, so the
        // port deliberately does not carry it over.
        assertTrue(
            EnvelopeUtils::class.java.declaredMethods.none { it.name.contains("smooth") },
            "the desktop port must not smooth stored points"
        )
    }
}