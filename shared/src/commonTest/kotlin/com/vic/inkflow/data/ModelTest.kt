package com.vic.inkflow.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The document model's semantics, pinned.
 *
 * The *shape* of the model (which field names go on the wire) is verified where
 * a JSON library exists — `desktop-app`'s and `:app`'s test source sets, by
 * round-tripping through Gson. What is verified here is the part that has no
 * library: the defaults and the nullability, because those are the semantics
 * that both platforms silently disagree about when they each have their own
 * copy of the type.
 */
class ModelTest {

    @Test
    fun `a stroke does not invent which tool drew it`() {
        val stroke = Stroke(id = "s1", documentUri = "file:///a.pdf", pageIndex = 0, color = 0, strokeWidth = 2f)
        // The wire may not carry the tool at all (the desktop has no column for
        // it), and Gson does not run Kotlin's defaults — so the model says
        // "absent", and the storage mapping decides what absent means.
        assertNull(stroke.toolKind)
        assertEquals(1, stroke.version)
        assertEquals(0, stroke.versionNonce)
        assertNull(stroke.deletedAt)
        assertNull(stroke.shapeType)
        assertEquals(false, stroke.isHighlighter)
    }

    @Test
    fun `an absent anchor stays absent`() {
        // A missing docY must stay null, never 0f: the anchor is
        // "pageIndex × stride + top", and a guessed zero drops the stroke at the
        // top of the canvas instead of on its page. The receiving side is told
        // not to invent it — so the model must not either.
        val stroke = Stroke(id = "s1", documentUri = "file:///a.pdf", pageIndex = 2, color = 0, strokeWidth = 2f)
        assertNull(stroke.docY)
        val text = TextAnnotation(id = "t1", documentUri = "file:///a.pdf", pageIndex = 2, text = "hi", modelX = 1f, modelY = 2f)
        assertNull(text.docY)
        val image = ImageAnnotation(
            id = "i1", documentUri = "file:///a.pdf", pageIndex = 2,
            uri = "file:///i.png", modelX = 0f, modelY = 0f, modelWidth = 10f, modelHeight = 10f
        )
        assertNull(image.docY)
    }

    @Test
    fun `text defaults to black 16pt left aligned and unboxed`() {
        val text = TextAnnotation(id = "t1", documentUri = "file:///a.pdf", pageIndex = 0, text = "hi", modelX = 0f, modelY = 0f)
        assertEquals(0xFF000000.toInt(), text.colorArgb)
        assertEquals(16f, text.fontSize)
        assertEquals(0, text.alignment)
        assertNull(text.boxWidth)
        assertEquals(false, text.isStamp)
    }

    @Test
    fun `the merge columns behave as the merge rule expects`() {
        val a = Stroke(id = "s1", documentUri = "u", pageIndex = 0, color = 0, strokeWidth = 1f, version = 3, versionNonce = 7)
        val b = a.copy(version = 3, versionNonce = 8)
        val c = a.copy(version = 4)

        // Equal version + equal nonce = the same content.
        assertEquals(a, a.copy())
        // Equal version, different nonce: the tiebreak decides, so they differ.
        assertNotEquals(a, b)
        // A higher version always wins, whatever the nonce.
        assertNotEquals(a, c)
    }

    @Test
    fun `a document and a folder carry no implicit clock`() {
        // The model must not read the clock: a value that changes between two
        // equal documents makes "did anything change?" unanswerable.
        val doc = Document(uri = "file:///a.pdf", displayName = "a.pdf")
        assertEquals(0L, doc.lastOpenedAt)
        assertEquals(0, doc.lastPageIndex)
        assertEquals(false, doc.isFavorite)
        assertNull(doc.folderId)

        val folder = Folder(id = "f1", name = "Notes")
        assertNull(folder.parentFolderId)
        assertEquals(0, folder.sortOrder)
        assertEquals(0L, folder.createdAt)
        assertEquals(0L, folder.updatedAt)
    }

    @Test
    fun `a stroke carries its points in order`() {
        val swp = StrokeWithSamples(
            stroke = Stroke(id = "s1", documentUri = "u", pageIndex = 0, color = 0, strokeWidth = 1f),
            points = listOf(
                Point(strokeId = "s1", x = 0f, y = 0f),
                Point(strokeId = "s1", x = 1f, y = 1f)
            )
        )
        assertEquals(listOf(0f, 1f), swp.points.map { it.x })
    }
}
