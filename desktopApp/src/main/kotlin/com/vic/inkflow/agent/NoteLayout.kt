package com.vic.inkflow.agent

import com.vic.inkflow.data.LessonNote
import com.vic.inkflow.data.NoteBlock
import com.vic.inkflow.data.TextMetrics
import com.vic.inkflow.ui.wrapTextLines

/**
 * One block of a note, placed on a page.
 *
 * @property text the text as it will be stored (a bullet carries its bullet
 *   character, an example carries its Q/A lines).
 * @property modelX the left edge, in model units.
 * @property baselineY the **first line's baseline**, in model units — the same
 *   convention the `text_annotations` table uses. Storing a top edge here
 *   would put every line one font-size too low.
 * @property fontSize in model units (PDF points), so it scales with the page.
 * @property boxWidth the wrap width, in model units.
 */
data class PlacedText(
    val text: String,
    val modelX: Float,
    val baselineY: Float,
    val fontSize: Float,
    val boxWidth: Float
)

/**
 * The outcome of laying a note out on a page.
 *
 * @property placed the blocks that fit.
 * @property overflow the blocks that did not fit. They are reported rather
 *   than silently dropped: a study note that quietly loses its quiz is worse
 *   than one that says "three blocks did not fit".
 */
data class NoteLayoutResult(
    val placed: List<PlacedText>,
    val overflow: List<NoteBlock>
)

/**
 * Turns a [LessonNote] into positioned text blocks.
 *
 * Why this is a separate, pure function
 * ------------------------------------
 * The placement rules (font sizes, the gap between blocks, where a page ends)
 * are the part most likely to be wrong, and they are the part hardest to
 * check by looking at the screen: an annotation one line too low still looks
 * like text. Keeping the layout pure makes it testable — how many lines a
 * block takes, whether it overflows, where the next block starts.
 *
 * The wrapping is the **shared** algorithm (`:shared`'s `wrapTextLines`), so
 * the desktop and the tablet break a line in the same place, which matters
 * because these annotations sync between them.
 */
object NoteLayout {

    /** Headings are bigger; everything else is body text. */
    const val HEADING_SIZE = 22f
    const val BODY_SIZE = 16f

    /** The margin around the note, in model units. */
    const val MARGIN = 48f

    /** The gap between two blocks, in model units. */
    const val GAP = 10f

    /**
     * Lays [note] out on a page.
     *
     * @param pageWidthPt the page width in model units.
     * @param pageHeightPt the page height in model units.
     * @param startBaselineY the baseline of the first line. The caller decides
     *   where the note starts — below whatever is already on the page.
     */
    fun layout(
        note: LessonNote,
        pageWidthPt: Float,
        pageHeightPt: Float,
        startBaselineY: Float
    ): NoteLayoutResult {
        val boxWidth = (pageWidthPt - 2 * MARGIN).coerceAtLeast(80f)
        val placed = ArrayList<PlacedText>()
        val overflow = ArrayList<NoteBlock>()
        var baseline = startBaselineY

        for (block in note.blocks) {
            val raw = textOf(block)
            if (raw.isBlank()) continue
            val fontSize = if (block is NoteBlock.Heading) HEADING_SIZE else BODY_SIZE
            val lines = wrapTextLines(raw, fontSize, boxWidth)
            // The text is stored **pre-wrapped**, with the breaks the shared
            // algorithm chose.
            //
            // The alternative is to store the wrap width and let each app wrap
            // for itself, but the desktop does not wrap its own rendering yet
            // (it stores and forwards the field for the tablet). Two apps that
            // break the lines differently would show the same note as two
            // different things, so the breaks are baked into the text and both
            // sides draw exactly the same lines.
            val text = lines.joinToString("\n")
            // The block's last baseline is `baseline + (lines - 1) * lineSpacing`.
            val lastBaseline = baseline + (lines.size - 1) * fontSize * TextMetrics.LINE_SPACING
            if (lastBaseline > pageHeightPt - MARGIN) {
                overflow += block
                continue
            }
            placed += PlacedText(
                text = text,
                modelX = MARGIN,
                baselineY = baseline,
                fontSize = fontSize,
                boxWidth = boxWidth
            )
            // Next block starts one line height below this block's last line,
            // plus the gap.
            baseline = lastBaseline + fontSize * TextMetrics.LINE_SPACING + GAP
        }

        return NoteLayoutResult(placed, overflow)
    }

    /**
     * The text a block is stored as.
     *
     * The note's vocabulary is richer than a text annotation can express, so
     * each kind is flattened into text: a question and its answer become two
     * lines, a quiz becomes its question, its options and the answer. The
     * alternative — a second table for note blocks — would need a protocol
     * change to sync, and the protocol is versioned and both ends must agree.
     */
    fun textOf(block: NoteBlock): String = when (block) {
        is NoteBlock.Heading -> block.text
        is NoteBlock.Paragraph -> block.text
        is NoteBlock.Bullet -> "• ${block.text}"
        is NoteBlock.Example -> "Q: ${block.question}\nA: ${block.answer}"
        is NoteBlock.Flashcard -> "Q: ${block.front}\nA: ${block.back}"
        is NoteBlock.Quiz -> buildString {
            append("Q: ").append(block.question)
            block.options.forEachIndexed { index, option ->
                append('\n').append(index + 1).append(") ").append(option)
            }
            append("\nAnswer: ").append(block.options.getOrNull(block.answerIndex).orEmpty())
        }
    }
}
