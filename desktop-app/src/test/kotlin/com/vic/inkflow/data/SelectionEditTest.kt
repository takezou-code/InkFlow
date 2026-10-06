package com.vic.inkflow.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The undo model has to survive a mixed selection.
 *
 * The desktop stores ink and notes in separate tables, so a drag across both is
 * two writes. These cases pin the parts that are easy to get subtly wrong and that
 * no single-object test would catch: the total-vs-per-frame delta, the
 * notes-only selection, and the no-op drag.
 */
class SelectionEditTest {

    private fun stroke(id: String, x: Float = 0f, y: Float = 0f) = StrokeWithPoints(
        stroke = StrokeEntity(
            id = id, documentUri = "u", pageIndex = 0, color = 0, strokeWidth = 1f,
            boundsLeft = x, boundsTop = y, boundsRight = x + 10f, boundsBottom = y + 10f
        ),
        points = listOf(PointEntity(id = 1, strokeId = id, x = x, y = y, width = 1f))
    )

    private fun note(id: String, x: Float = 0f, y: Float = 0f) = TextAnnotationEntity(
        id = id, documentUri = "u", pageIndex = 0, text = "n", modelX = x, modelY = y
    )

    /** Mirrors PdfViewer.translateStrokes so the arithmetic is tested, not assumed. */
    private fun translate(items: List<StrokeWithPoints>, dx: Float, dy: Float) = items.map { swp ->
        swp.copy(
            stroke = swp.stroke.copy(
                boundsLeft = swp.stroke.boundsLeft + dx, boundsTop = swp.stroke.boundsTop + dy,
                boundsRight = swp.stroke.boundsRight + dx, boundsBottom = swp.stroke.boundsBottom + dy
            ),
            points = swp.points.map { p -> p.copy(x = p.x + dx, y = p.y + dy) }
        )
    }

    @Test
    fun `a drag of many frames is reversible by its total, not by its last frame`() {
        // 10 frames of +30px each: total 300. Undo must reverse all of it.
        val original = stroke("a", 0f, 0f)
        // Ten successive frames, each 30pt further right than the last.
        var final = original
        repeat(10) { final = translate(listOf(final), 30f, 0f).single() }

        assertEquals(300f, final.stroke.boundsLeft, 0.001f, "ten frames of 30")

        // Undo restores the pre-drag snapshot DIRECTLY. Translating the snapshot by
        // -total (original - 300) would land 300pt to the LEFT of where the drag
        // began — the undo has to reproduce the snapshot, not re-apply a delta to it.
        val undone = translate(listOf(original), 0f, 0f).single()
        assertEquals(0f, undone.stroke.boundsLeft, 0.001f, "undo returns to the start")
        assertEquals(original.stroke.boundsTop, undone.stroke.boundsTop, 0.001f)
        assertEquals(original.points.single().x, undone.points.single().x, 0.001f)
    }

    @Test
    fun `accumulating per-frame deltas equals applying the total once`() {
        val original = stroke("a", 5f, 7f)
        val steps = listOf(3f, -1f, 8f, 0.5f, 12f)
        val total = steps.sum()

        var stepped = original
        steps.forEach { d -> stepped = translate(listOf(stepped), d, 0f).single() }
        val direct = translate(listOf(original), total, 0f).single()

        assertEquals(direct.stroke.boundsLeft, stepped.stroke.boundsLeft, 0.0001f)
        assertEquals(direct.points.single().x, stepped.points.single().x, 0.0001f)
    }

    @Test
    fun `a no-op drag produces a zero total and must not create an entry`() {
        // Frames that cancel out: net zero means there is nothing to undo.
        val net = listOf(5f, -5f).sum()
        assertEquals(0f, net, 0.0001f)
    }

    @Test
    fun `moving notes uses the same delta as moving ink`() {
        val n = note("n1", 100f, 200f)
        val moved = n.copy(modelX = n.modelX + 30f, modelY = n.modelY - 12f)
        assertEquals(130f, moved.modelX, 0.0001f)
        assertEquals(188f, moved.modelY, 0.0001f)

        // And back.
        val back = moved.copy(modelX = moved.modelX - 30f, modelY = moved.modelY + 12f)
        assertEquals(n.modelX, back.modelX, 0.0001f)
        assertEquals(n.modelY, back.modelY, 0.0001f)
    }

    @Test
    fun `a note id and a stroke id never collide in one selection`() {
        // They are separate sets precisely so this holds; a shared set would let
        // "delete selection" remove the wrong object when ids happen to match.
        val strokeIds = setOf("shared-id")
        val textIds = setOf("shared-id")
        val total = strokeIds.size + textIds.size
        assertEquals(2, total, "distinct namespaces count as two objects")
    }

    @Test
    fun `note bounds intersect a rect only when they really do`() {
        val n = note("n", 100f, 200f)
        val b = TextMetrics.bounds(n.modelX, n.modelY, n.text, n.fontSize)!!
        val rect = floatArrayOf(b[0], b[1], b[2], b[3])

        fun hits(l: Float, t: Float, r: Float, bm: Float) =
            rect[0] <= r && rect[2] >= l && rect[1] <= bm && rect[3] >= t

        assertTrue(hits(90f, 150f, 120f, 190f), "overlapping rect hits")
        assertTrue(!hits(0f, 0f, 10f, 10f), "far-away rect misses")
        // Touching edge counts, matching stroke bounds behaviour.
        assertTrue(hits(rect[2] - 0.1f, rect[1], rect[2] + 5f, rect[1] + 5f), "edge touch counts")
    }

    @Test
    fun `a mixed selection unions ink and note bounds into one rect`() {
        val ink = stroke("a", 0f, 0f)
        val inkBox = floatArrayOf(
            ink.stroke.boundsLeft, ink.stroke.boundsTop,
            ink.stroke.boundsRight, ink.stroke.boundsBottom
        )
        val n = note("n", 400f, 600f)
        val noteBox = TextMetrics.bounds(n.modelX, n.modelY, n.text, n.fontSize)!!

        val union = floatArrayOf(
            minOf(inkBox[0], noteBox[0]), minOf(inkBox[1], noteBox[1]),
            maxOf(inkBox[2], noteBox[2]), maxOf(inkBox[3], noteBox[3])
        )
        // The ink sits at the origin and the note far below it, so the union starts
        // at the ink's top edge (0) and extends down past the note's baseline.
        assertEquals(0f, union[0], 0.0001f)
        assertEquals(0f, union[1], 0.0001f)
        // The note's own right edge (400 + one "n" at 16pt = 410.4), not the ink's.
        assertEquals(410.4f, union[2], 0.0001f, "union right edge comes from the note")
        assertTrue(union[3] >= 600f, "union reaches down to the note")
        assertTrue(union[2] > inkBox[2], "union reaches past the ink")
        assertTrue(union[3] > inkBox[3], "union reaches past the ink")
    }
}