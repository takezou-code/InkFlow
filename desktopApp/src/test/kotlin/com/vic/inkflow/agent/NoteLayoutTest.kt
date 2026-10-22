package com.vic.inkflow.agent

import com.vic.inkflow.data.LessonNote
import com.vic.inkflow.data.NoteBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The note layout — the part of the AI feature that is easy to get quietly
 * wrong, because a block one line too low still looks like text.
 */
class NoteLayoutTest {

    private val note = LessonNote(
        title = "Derivatives",
        blocks = listOf(
            NoteBlock.Heading("Chapter 3"),
            NoteBlock.Paragraph("A derivative is a rate of change."),
            NoteBlock.Bullet("d/dx x^2 = 2x"),
            NoteBlock.Example("d/dx x^2", "2x"),
            NoteBlock.Flashcard("d/dx x^2?", "2x"),
            NoteBlock.Quiz("d/dx x^2?", listOf("2x", "x^2"), 0)
        )
    )

    private fun layout(startBaseline: Float = 64f, pageHeight: Float = 800f) =
        NoteLayout.layout(note, pageWidthPt = 595f, pageHeightPt = pageHeight, startBaselineY = startBaseline)

    @Test
    fun everyBlockIsPlaced() {
        val result = layout()
        assertEquals(6, result.placed.size)
        assertTrue(result.overflow.isEmpty())
    }

    @Test
    fun theFirstBlockStartsWhereItWasToldTo() {
        val result = layout(startBaseline = 100f)
        assertEquals(100f, result.placed.first().baselineY)
    }

    @Test
    fun blocksDoNotOverlap() {
        val placed = layout().placed
        for (i in 0 until placed.size - 1) {
            val current = placed[i]
            val next = placed[i + 1]
            val currentHeight = current.fontSize * 1.2f
            assertTrue(
                next.baselineY >= current.baselineY + currentHeight,
                "block $i and block ${i + 1} overlap"
            )
        }
    }

    @Test
    fun headingsAreBiggerThanBodyText() {
        val placed = layout().placed
        assertEquals(NoteLayout.HEADING_SIZE, placed.first().fontSize)
        assertEquals(NoteLayout.BODY_SIZE, placed[1].fontSize)
    }

    @Test
    fun theTextIsStoredPreWrapped() {
        // A long paragraph must arrive at the tablet with its line breaks
        // already in it: the desktop does not wrap its own rendering, so
        // storing the breaks is what makes both apps draw the same lines.
        val long = LessonNote(
            title = "t",
            blocks = listOf(NoteBlock.Paragraph("word ".repeat(120).trim()))
        )
        val placed = NoteLayout.layout(long, 595f, 800f, 64f).placed
        assertEquals(1, placed.size)
        assertTrue(placed.first().text.contains("\n"), "the text should already be wrapped")
    }

    @Test
    fun aBlockThatDoesNotFitIsReportedNotDropped() {
        val result = layout(startBaseline = 700f, pageHeight = 760f)
        assertTrue(result.overflow.isNotEmpty(), "the last blocks should not fit")
        assertTrue(result.placed.size < 6)
    }

    @Test
    fun blocksAreFlattenedIntoText() {
        assertEquals("• a point", NoteLayout.textOf(NoteBlock.Bullet("a point")))
        assertEquals("Q: q\nA: a", NoteLayout.textOf(NoteBlock.Example("q", "a")))
        assertEquals("Q: f\nA: b", NoteLayout.textOf(NoteBlock.Flashcard("f", "b")))
        assertEquals(
            "Q: pick\n1) a\n2) b\nAnswer: a",
            NoteLayout.textOf(NoteBlock.Quiz("pick", listOf("a", "b"), 0))
        )
    }
}
