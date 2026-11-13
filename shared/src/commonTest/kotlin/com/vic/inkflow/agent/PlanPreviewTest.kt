package com.vic.inkflow.agent

import com.vic.inkflow.data.TextAnnotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class PlanPreviewTest {

    private fun ann(
        id: String,
        text: String = "text $id",
        page: Int = 0,
        modelY: Float = 100f,
        fontSize: Float = 16f,
        boxWidth: Float? = null,
        alignment: Int = 0,
        flowId: String? = null,
        docY: Float? = null,
        isStamp: Boolean = false
    ) = TextAnnotation(
        id = id,
        documentUri = "file:///doc.pdf",
        pageIndex = page,
        docY = docY,
        text = text,
        modelX = 72f,
        modelY = modelY,
        fontSize = fontSize,
        boxWidth = boxWidth,
        alignment = alignment,
        flowId = flowId,
        isStamp = isStamp
    )

    private fun plan(vararg ops: EditOp) = EditPlan(ops.toList())

    private val empty: List<TextAnnotation> = emptyList()

    @Test
    fun `an empty plan proposes the document unchanged`() {
        val current = listOf(ann("a", page = 1), ann("b", page = 0))
        val preview = previewEditPlan("file:///doc.pdf", current, plan())
        assertTrue(preview.isNoop)
        assertEquals(listOf("b", "a"), preview.proposed.map { it.id })
        assertTrue(preview.deleted.isEmpty())
        assertTrue(preview.problems.isEmpty())
    }

    @Test
    fun `upsert without id adds a new annotation and marks it added`() {
        val preview = previewEditPlan(
            "file:///doc.pdf",
            listOf(ann("keep")),
            plan(EditOp.UpsertText(id = null, pageIndex = 0, text = "new block", modelX = 72f, modelY = 200f))
        )
        val added = preview.proposed.single { it.id in preview.addedIds }
        assertEquals("new block", added.text)
        assertEquals(0, added.pageIndex)
        assertEquals(2, preview.proposed.size)
        assertEquals(setOf(added.id), preview.addedIds)
        assertTrue(preview.changedIds.isEmpty())
    }

    @Test
    fun `upsert with an unknown id inserts keeping the model id`() {
        val preview = previewEditPlan(
            "file:///doc.pdf",
            empty,
            plan(EditOp.UpsertText(id = "model-id-1", pageIndex = 2, text = "from thin air", modelX = 10f, modelY = 30f))
        )
        assertEquals(listOf("model-id-1"), preview.proposed.map { it.id })
        assertEquals(setOf("model-id-1"), preview.addedIds)
        assertTrue(preview.problems.isEmpty())
    }

    @Test
    fun `replace keeps flowId docY and boxWidth when the op omits them`() {
        val original = ann("t1", text = "old", flowId = "ai-123", docY = 1500.5f, boxWidth = 480f)
        val preview = previewEditPlan(
            "file:///doc.pdf",
            listOf(original),
            plan(EditOp.UpsertText(id = "t1", pageIndex = 0, text = "rewritten", modelX = 72f, modelY = 300f))
        )
        val after = preview.proposed.single()
        assertEquals("rewritten", after.text)
        assertEquals("ai-123", after.flowId)
        assertEquals(1500.5f, after.docY)
        assertEquals(480f, after.boxWidth)
        assertEquals(300f, after.modelY)
        assertEquals(setOf("t1"), preview.changedIds)
        assertTrue(preview.addedIds.isEmpty())
    }

    @Test
    fun `replace with explicit boxWidth overrides the kept one`() {
        val original = ann("t1", boxWidth = 480f)
        val preview = previewEditPlan(
            "file:///doc.pdf",
            listOf(original),
            plan(EditOp.UpsertText(id = "t1", pageIndex = 0, text = "narrow", modelX = 72f, modelY = 100f, boxWidth = 240f))
        )
        assertEquals(240f, preview.proposed.single().boxWidth)
    }

    @Test
    fun `replace preserves a stamp instead of converting it`() {
        val original = ann("stamp", isStamp = true)
        val preview = previewEditPlan(
            "file:///doc.pdf",
            listOf(original),
            plan(EditOp.UpsertText(id = "stamp", pageIndex = 0, text = "still a stamp", modelX = 72f, modelY = 100f))
        )
        assertTrue(preview.proposed.single().isStamp)
    }

    @Test
    fun `delete removes from the proposal and ghosts the original`() {
        val current = listOf(ann("keep"), ann("drop"))
        val preview = previewEditPlan(
            "file:///doc.pdf",
            current,
            plan(EditOp.DeleteText(id = "drop"))
        )
        assertEquals(listOf("keep"), preview.proposed.map { it.id })
        assertEquals(listOf("drop"), preview.deleted.map { it.id })
        assertTrue(preview.isNoop.not())
    }

    @Test
    fun `delete of a missing id is reported, not silently accepted`() {
        val current = listOf(ann("keep"))
        val preview = previewEditPlan(
            "file:///doc.pdf",
            current,
            plan(EditOp.DeleteText(id = "nope"))
        )
        assertEquals(listOf("keep"), preview.proposed.map { it.id })
        assertEquals(1, preview.problems.size)
        assertEquals(emptyList(), preview.deleted)
    }

    @Test
    fun `stroke ops are reported as unsupported and change nothing`() {
        val current = listOf(ann("keep"))
        val preview = previewEditPlan(
            "file:///doc.pdf",
            current,
            plan(EditOp.DeleteStroke(id = "s1"))
        )
        assertEquals(listOf("keep"), preview.proposed.map { it.id })
        assertEquals(1, preview.problems.size)
    }

    @Test
    fun `proposed is ordered by page, then baseline, then id`() {
        val current = listOf(
            ann("a", page = 2, modelY = 50f),
            ann("b", page = 0, modelY = 200f),
            ann("c", page = 0, modelY = 100f),
        )
        val preview = previewEditPlan("file:///doc.pdf", current, plan())
        assertEquals(listOf("c", "b", "a"), preview.proposed.map { it.id })
    }
}
