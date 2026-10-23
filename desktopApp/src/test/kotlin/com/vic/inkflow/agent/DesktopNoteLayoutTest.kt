package com.vic.inkflow.agent

import com.vic.inkflow.data.LessonNote
import com.vic.inkflow.data.NoteBlock
import com.vic.inkflow.data.PlacedText
import com.vic.inkflow.data.Stroke
import com.vic.inkflow.data.TextAnnotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop's note landing.
 *
 * The layout itself is the **shared** pipeline (`:shared`'s `paginateAiBlocks`),
 * which has its own tests. What is desktop-specific — and pinned here — is:
 *
 *  1. where the note starts (below what is already on the page, never on top of
 *     it — the bug this replaced started every note at the top margin);
 *  2. that a page with no room is *refused* rather than written over;
 *  3. that the note becomes pre-wrapped text boxes at the shared content width;
 *  4. that leftovers are counted, not silently dropped.
 */
class DesktopNoteLayoutTest {

    private val pageWidth = 595f
    private val pageHeight = 842f

    private val note = LessonNote(
        title = "Derivatives",
        blocks = listOf(
            NoteBlock.Heading("Chapter 3"),
            NoteBlock.Paragraph("A derivative is a rate of change."),
            NoteBlock.Bullet("d/dx x^2 = 2x"),
            NoteBlock.Example("d/dx x^2", "2x")
        )
    )

    private fun layout(
        note: LessonNote = this.note,
        contentBottom: Float? = 0f,
        height: Float = pageHeight
    ) = DesktopNoteLayout.layout(note, pageWidth, height, contentBottom)

    private fun texts(page: DesktopNotePage): List<PlacedText> = page.placed

    @Test
    fun anEmptyPageStartsAtTheTopMargin() {
        val page = assertNotNull(layout(contentBottom = 0f))
        // First line's baseline = top margin + its own font size (heading 22).
        assertEquals(DesktopNoteLayout.MARGIN_TOP + DesktopNoteLayout.HEADING_SIZE, texts(page).first().modelY)
    }

    @Test
    fun aPageWithInkContinuesBelowIt() {
        val page = assertNotNull(layout(contentBottom = 400f))
        val firstTop = texts(page).first().modelY - DesktopNoteLayout.HEADING_SIZE
        // contentBottom + safety pad, never the top margin.
        assertEquals(400f + com.vic.inkflow.data.CONTINUE_SAFETY_PAD, firstTop)
        assertTrue(
            firstTop >= 400f,
            "the note must start below the existing ink, not on top of it"
        )
    }

    @Test
    fun aFullPageIsRefusedRatherThanWrittenOver() {
        // 842 − 64 margins leaves nothing below 800.
        assertNull(layout(contentBottom = 800f))
    }

    @Test
    fun anUnmeasurablePageFallsBackToTheTopMargin() {
        // A stamped annotation cannot be measured — the layout must not crash
        // or invent a bottom; it starts at the top, as it always did.
        val page = assertNotNull(layout(contentBottom = null))
        assertEquals(DesktopNoteLayout.MARGIN_TOP + DesktopNoteLayout.HEADING_SIZE, texts(page).first().modelY)
    }

    @Test
    fun headingsAreBiggerThanBodyText() {
        val placed = texts(assertNotNull(layout()))
        assertEquals(DesktopNoteLayout.HEADING_SIZE, placed[0].fontSize)
        assertEquals(DesktopNoteLayout.BODY_SIZE, placed[1].fontSize)
    }

    @Test
    fun everyBlockIsAPreWrappedTextBox() {
        val placed = texts(assertNotNull(layout()))
        val contentWidth = pageWidth - DesktopNoteLayout.MARGIN * 2
        for (text in placed) {
            assertEquals(contentWidth, text.boxWidth)
            assertEquals(DesktopNoteLayout.MARGIN, text.modelX)
            // Stored pre-wrapped: re-wrapping the stored lines gives them back.
            assertEquals(
                text.text.split("\n"),
                com.vic.inkflow.data.TextMetrics.wrapLines(text.text, text.fontSize, text.boxWidth)
            )
        }
    }

    @Test
    fun blocksDoNotOverlap() {
        val placed = texts(assertNotNull(layout()))
        for (i in 0 until placed.size - 1) {
            val current = placed[i]
            val next = placed[i + 1]
            val currentLineH = current.fontSize * com.vic.inkflow.data.TextMetrics.LINE_SPACING
            val currentLines = current.text.split("\n").size
            assertTrue(
                next.modelY >= current.modelY + (currentLines - 1) * currentLineH,
                "block $i and block ${i + 1} overlap"
            )
        }
    }

    @Test
    fun aNoteThatDoesNotFitReportsTheLeftovers() {
        val long = LessonNote(
            title = "long",
            blocks = List(12) { NoteBlock.Paragraph("這是一段很長的內容，用來把一頁填滿。".repeat(4)) }
        )
        val page = assertNotNull(layout(note = long))
        assertTrue(page.placed.isNotEmpty(), "some of the note must land")
        assertTrue(page.dropped > 0, "the rest must be reported, not dropped silently")
    }

    @Test
    fun blocksAreFlattenedIntoPageText() {
        val page = assertNotNull(
            layout(
                note = LessonNote(
                    title = "t",
                    blocks = listOf(
                        NoteBlock.Bullet("a point"),
                        NoteBlock.Quiz("pick", listOf("a", "b"), 0)
                    )
                )
            )
        )
        val stored = texts(page).map { it.text }
        assertEquals("• a point", stored[0])
        assertEquals("Q: pick\n1) a\n2) b\nAnswer: a", stored[1])
    }

    @Test
    fun theContentBottomIsMeasuredTheWayThePageIsDrawn() {
        val strokes = listOf(
            Stroke(
                id = "s", documentUri = "doc", pageIndex = 0, color = 0, strokeWidth = 2f,
                boundsLeft = 0f, boundsTop = 280f, boundsRight = 100f, boundsBottom = 300f
            )
        )
        val texts = listOf(
            TextAnnotation(
                id = "t", documentUri = "doc", pageIndex = 0,
                text = "a\nb\nc", modelX = 48f, modelY = 100f, fontSize = 16f
            )
        )
        // baseline 100 + 2 lines of 1.2 em + 0.2 em of descent.
        assertEquals(100f + 2 * 16f * 1.2f + 16f * 0.2f, DesktopNoteLayout.contentBottomOf(emptyList(), texts))
        assertEquals(300f, DesktopNoteLayout.contentBottomOf(strokes, emptyList()))
        assertEquals(300f, DesktopNoteLayout.contentBottomOf(strokes, texts), "the lowest of the two wins")
    }

    @Test
    fun aStampedPageCannotBeMeasured() {
        val stamp = TextAnnotation(
            id = "t", documentUri = "doc", pageIndex = 0,
            text = "seal", modelX = 48f, modelY = 100f, fontSize = 16f, isStamp = true
        )
        assertNull(DesktopNoteLayout.contentBottomOf(emptyList(), listOf(stamp)))
    }
}
