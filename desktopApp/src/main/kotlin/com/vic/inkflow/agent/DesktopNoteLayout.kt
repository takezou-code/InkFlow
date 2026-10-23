package com.vic.inkflow.agent

import com.vic.inkflow.data.AiBlock
import com.vic.inkflow.data.AiTextBlock
import com.vic.inkflow.data.AiTextChunk
import com.vic.inkflow.data.DEFAULT_AI_FONT_SIZE
import com.vic.inkflow.data.ImageAnnotation
import com.vic.inkflow.data.LessonNote
import com.vic.inkflow.data.NoteBlock
import com.vic.inkflow.data.Placed
import com.vic.inkflow.data.PlacedText
import com.vic.inkflow.data.Stroke
import com.vic.inkflow.data.TextAnnotation
import com.vic.inkflow.data.TextMetrics
import com.vic.inkflow.data.continueTop
import com.vic.inkflow.data.measureContentBottom
import com.vic.inkflow.data.paginateAiBlocks

/**
 * What landing a note on the reader's page actually did.
 *
 * The outcomes are distinct because the user has to be told which one happened:
 * "wrote 3 blocks" and "the page is full" are different problems, and silently
 * reporting "failed" for both sends the user looking in the wrong place.
 */
sealed interface NoteLanding {

    /** The whole note became annotations. */
    data class Placed(val annotations: Int) : NoteLanding

    /**
     * Part of the note became annotations; the rest did not fit on this page.
     *
     * @property annotations how many text annotations were written.
     * @property dropped how many blocks were left over.
     */
    data class Overflow(val annotations: Int, val dropped: Int) : NoteLanding

    /** The page is written to the bottom; even two lines would overlap. */
    data object NoRoom : NoteLanding

    /** No reader is bound, so there is no page to land on. */
    data object NoPage : NoteLanding

    /** The annotations were laid out but the database refused them. */
    data object SaveFailed : NoteLanding
}

/**
 * One page's worth of a note: what fits, and how much did not.
 *
 * @property placed the annotations, in reading order. Already positioned: text
 *   is pre-wrapped and carries the box width it was wrapped to.
 * @property dropped how many blocks were pushed past the bottom of this page.
 */
data class DesktopNotePage(
    val placed: List<PlacedText>,
    val dropped: Int
)

/**
 * Lays an AI note out on **the page the reader is looking at**.
 *
 * ## Why this is not the tablet's whole pipeline
 *
 * The tablet gives an AI import its own pages: it has a page model, so
 * `placePages` can open pages after the anchor and continue there. The desktop
 * has no page model — its documents are somebody's PDFs and `pageCount` comes
 * from the PDF itself — so a note cannot get a page of its own. It has to fit on
 * the page in front of the user or not go on that page at all.
 *
 * Everything up to *where the note starts* is the shared pipeline
 * (`:shared`'s `measureContentBottom` / `continueTop` / `paginateAiBlocks`), so
 * the line breaks, the line height and the "does it fit" rule are the same as
 * the tablet's. Only the page-count question is desktop-specific.
 *
 * ## The bug this replaced
 *
 * The desktop used to start every note at `MARGIN + BODY_SIZE` — the top of the
 * page — regardless of what was already on it. On a page with ink or printed
 * text, the note was written **on top of** the existing content. Measuring the
 * bottom first is the fix, and it is the reason the desktop no longer keeps its
 * own `NoteLayout`.
 */
object DesktopNoteLayout {

    /** The margin around the note, in model units (the tablet's `AI_MARGIN_H`). */
    const val MARGIN = 48f

    /** The top margin: where a note starts on a page that is otherwise empty. */
    const val MARGIN_TOP = 64f

    /** The bottom margin: past this, nothing is placed. */
    const val MARGIN_BOTTOM = 64f

    /** Headings are bigger; everything else is body text. */
    const val HEADING_SIZE = 22f
    const val BODY_SIZE = DEFAULT_AI_FONT_SIZE

    /** The gap between two blocks, in model units. */
    const val GAP = 14f

    /**
     * Line metrics for the layout: `[ascent, descent, leading]` at `size`.
     *
     * These are the numbers the desktop **draws** with: `TextMetrics` lines a
     * text box at `LINE_SPACING` (1.2 em) and its box bottom adds a fifth of an
     * em of descent. The tablet injects `Paint.fontMetrics` here instead; the
     * layout is the same function either way.
     */
    val fontMetrics: (Float) -> FloatArray =
        { size -> floatArrayOf(-size, size * 0.2f, 0f) }

    /** Line height and descent at [size], for measuring what is already on a page. */
    private val contentMetrics: (Float) -> Pair<Float, Float> =
        { size -> (size * TextMetrics.LINE_SPACING) to (size * 0.2f) }

    /**
     * The note as the shared pipeline's input.
     *
     * Each block becomes one text chunk, with its own font size: a heading is
     * [HEADING_SIZE], everything else [BODY_SIZE]. The chunk body is
     * [NoteBlock.asPlainText] — the *shared* flattening, so the tablet reading
     * the same annotation back sees the same string.
     */
    fun blocksOf(note: LessonNote): List<AiBlock> = note.blocks.mapIndexedNotNull { index, block ->
        val text = block.asPlainText().trim()
        if (text.isEmpty()) return@mapIndexedNotNull null
        AiTextBlock(
            AiTextChunk(
                id = "n$index",
                title = block.text.take(20),
                body = text,
                fontSize = if (block is NoteBlock.Heading) HEADING_SIZE else BODY_SIZE
            )
        )
    }

    /**
     * The bottom of what is already on the page, in model units.
     *
     * `null` means "cannot be measured" — a stamped annotation's height is not
     * recoverable — in which case the note starts at the top margin and may
     * overlap. That is the old behaviour, kept only for the case where there is
     * nothing better to do; every ordinary page measures.
     */
    fun contentBottomOf(
        strokes: List<Stroke>,
        texts: List<TextAnnotation>,
        images: List<ImageAnnotation> = emptyList()
    ): Float? = measureContentBottom(strokes, texts, images, contentMetrics)

    /**
     * Where the note's first line starts, or `null` when the page has no room
     * for even two lines (the shared rule: below two lines plus a gap is not
     * worth starting).
     */
    fun startTopFor(contentBottom: Float?, pageHeight: Float): Float? {
        if (pageHeight <= 0f) return null
        val bottom = contentBottom ?: return MARGIN_TOP
        return continueTop(
            contentBottom = bottom,
            modelH = pageHeight,
            importLineH = BODY_SIZE * TextMetrics.LINE_SPACING,
            marginTop = MARGIN_TOP,
            marginBottom = MARGIN_BOTTOM,
            blockGap = GAP
        )
    }

    /**
     * Lays [note] out on one page.
     *
     * @param contentBottom the bottom of what is already on the page, from
     *   [contentBottomOf]; `null` for "unmeasurable".
     * @return `null` when the page has no room; otherwise what fits and what
     *   did not. Pages after the first are **counted, not placed** — the
     *   desktop has no page to put them on.
     */
    fun layout(
        note: LessonNote,
        pageWidth: Float,
        pageHeight: Float,
        contentBottom: Float?
    ): DesktopNotePage? {
        if (pageWidth <= 0f || pageHeight <= 0f) return null
        val startTop = startTopFor(contentBottom, pageHeight) ?: return null
        val blocks = blocksOf(note)
        if (blocks.isEmpty()) return DesktopNotePage(emptyList(), 0)

        val pages = paginateAiBlocks(
            blocks = blocks,
            rendered = emptyMap(),
            modelW = pageWidth,
            modelH = pageHeight,
            marginH = MARGIN,
            marginTop = MARGIN_TOP,
            marginBottom = MARGIN_BOTTOM,
            blockGap = GAP,
            startTop = startTop,
            fontMetrics = fontMetrics
        )
        val first = pages.firstOrNull().orEmpty()
        val placed = first.mapNotNull { (it as? Placed.T)?.t }
        // The rest of the note is on pages this app cannot open. A block that
        // was split across the page break counts once, which is why this is a
        // count of *leftovers*, not a subtraction of block counts.
        val dropped = pages.drop(1).sumOf { it.size }
        return DesktopNotePage(placed = placed, dropped = dropped)
    }
}
