package com.vic.inkflow.data

import java.util.UUID

/**
 * A text annotation (the tablet's "note" object).
 *
 * Field-for-field with the tablet's `text_annotations` table so the same rows
 * can travel in either direction without translation.
 *
 * ## The one field that is easy to get wrong
 *
 * [modelY] is the **first line's baseline**, not the top-left corner. Every
 * bounding-box calculation has to subtract the ascent to get a top edge. Using
 * modelY as a top edge puts every label about one font-size too low, which is
 * the classic "the text is there but the hit box missed it" bug — you can see it
 * on screen and still be unable to select it.
 *
 * [docY] carries the same meaning as on strokes: the anchor inside the
 * continuous document canvas, NULL for data that predates the tablet's
 * backfill. The anchor for text is its modelY. As with strokes, the desktop must
 * not invent a value — a guessed stride misplaces the row.
 */
data class TextAnnotationEntity(
    val id: String = UUID.randomUUID().toString(),
    val documentUri: String,
    val pageIndex: Int,
    val docY: Float? = null,
    val text: String,
    val modelX: Float,
    val modelY: Float,
    val fontSize: Float = 16f,
    val colorArgb: Int = 0xFF000000.toInt(),
    val isStamp: Boolean = false,
    /**
     * Text-box wrap width in model units, or NULL for pre-v30 rows that predate
     * the tablet's text-box feature (those keep the old "hug the content, break
     * only on \n" behaviour).
     *
     * Carried so a tablet-authored box survives a desktop sync round-trip. The
     * desktop does not yet wrap its own rendering to this width — it stores and
     * forwards the field so the tablet does not silently lose the box.
     */
    val boxWidth: Float? = null,
    /** 0 = left, 1 = centre, 2 = right (matches the tablet's `alignment` column). */
    val alignment: Int = 0,
    // ── v6 merge columns: identical semantics to StrokeEntity (§15.1) ────────
    val version: Int = 1,
    val versionNonce: Int = 0,
    val deletedAt: Long? = null
)

/**
 * Width model for text, matching the tablet's `SelectionGeometry`.
 *
 * These two coefficients are the contract between the two apps: if the desktop
 * measures a CJK string as half its real width, the selection box is too narrow
 * and the label cannot be grabbed even though the user is pointing straight at
 * it. Full-width glyphs advance 1.0 em, everything else 0.65 em.
 */
object TextMetrics {
    /** Line height multiplier used by the tablet for multi-line height. */
    const val LINE_SPACING = 1.2f

    /**
     * Width of [text] in model units at [fontSize].
     *
     * Uses a real font measurement when the platform can supply one and falls
     * back to the coefficient model otherwise, so a headless unit test still
     * gets a deterministic answer.
     *
     * The coefficient model itself is the **shared** one
     * (`:shared`'s `com.vic.inkflow.ui.measureTextWidth`): full-width glyphs
     * advance 1.0 em, everything else 0.65 em. It used to be a second copy here,
     * with a different set of CJK ranges — so a note containing a full-width
     * space measured wider on one device than on the other, and the hit box is
     * drawn from that measurement.
     */
    fun measureWidth(text: String, fontSize: Float, measurer: ((String, Float) -> Float)? = null): Float =
        measurer?.invoke(text, fontSize) ?: com.vic.inkflow.ui.measureTextWidth(text, fontSize)

    /**
     * Bounding box of a text annotation in model units.
     *
     * Top edge is the baseline minus one font size; height adds 1.2em per
     * additional line. When [boxWidth] is set the annotation is a text box — the
     * box is that wide and the lines are the wrapped ones, matching the tablet's
     * `textAnnotationHitRect`. Returns null for blank text — an empty annotation
     * has no box, so it cannot be hit-tested and should not be drawn.
     *
     * ## Known divergence from the tablet's lasso box — read before changing
     *
     * This box grows **downwards** from the first baseline, which is what the
     * drawing does (the first line's baseline is `modelY`, the following lines
     * are below it) and what the tablet's own *tap* box does
     * (`textAnnotationHitRect`).
     *
     * The tablet's *lasso/erase* box (`:shared`'s `textEstimatedBounds`) grows
     * **upwards** instead, so a multi-line note has two different boxes inside
     * the tablet app and a third one here.
     *
     * The rule is not unified yet on purpose: the fix is a **behaviour change on
     * the tablet** (which box the lasso and the eraser use), and it needs a
     * device to verify. The width and the wrap algorithm, on the other hand,
     * *are* already shared — see `measureWidth` and `wrapLines` below.
     */
    fun bounds(
        modelX: Float,
        modelY: Float,
        text: String,
        fontSize: Float,
        boxWidth: Float? = null,
        measurer: ((String, Float) -> Float)? = null
    ): FloatArray? {
        if (text.isBlank()) return null
        val boxed = boxWidth != null && boxWidth > 0f
        val lines = if (boxed) wrapLines(text, fontSize, boxWidth!!, measurer) else text.split('\n')
        val width = if (boxed) boxWidth!! else lines.maxOf { measureWidth(it, fontSize, measurer) }
        // [modelY] is the first baseline. Height covers that line plus 1.2em for
        // every extra line, so the box grows downward from the first line only.
        val extraLines = lines.size - 1
        return floatArrayOf(
            modelX,
            modelY - fontSize,
            modelX + width,
            modelY + extraLines * fontSize * LINE_SPACING
        )
    }

    /** [bounds] for a whole annotation, including its text-box width. */
    fun boundsFor(ann: TextAnnotationEntity, measurer: ((String, Float) -> Float)? = null): FloatArray? =
        bounds(ann.modelX, ann.modelY, ann.text, ann.fontSize, ann.boxWidth, measurer)

    /**
     * Wrap [text] to [boxWidth] model units — **the shared algorithm**
     * (`:shared`'s `com.vic.inkflow.ui.wrapTextLines`), so the two apps agree on
     * where a box breaks. `\n` is always a hard break; CJK breaks per character; a
     * Latin run moves whole unless it is wider than the box.
     *
     * This used to be a second implementation here. The two had drifted: the
     * width rule underneath them differed (fixed — see `measureWidth`), and a
     * second copy of a wrapping algorithm is a second chance to break a box in a
     * different place on the other device.
     */
    fun wrapLines(
        text: String,
        fontSize: Float,
        boxWidth: Float,
        measurer: ((String, Float) -> Float)? = null
    ): List<String> = com.vic.inkflow.ui.wrapTextLines(text, fontSize, boxWidth, measurer)

    /** Horizontal offset of a line inside its box, in model units (left/centre/right). */
    fun alignOffset(lineWidth: Float, boxWidth: Float, alignment: Int): Float = when (alignment) {
        1 -> ((boxWidth - lineWidth) / 2f).coerceAtLeast(0f)
        2 -> (boxWidth - lineWidth).coerceAtLeast(0f)
        else -> 0f
    }

}

/**
 * CJK / full-width test — **the shared rule**, not a second copy of it.
 *
 * The two apps used to disagree here, and the disagreement was invisible: the
 * desktop's copy covered `0x3000..0x303E`, `0xFFE0..0xFFE6` and stopped at
 * `0x9FFF`; the tablet's covered `0x2E80..0xA4CF`, `0xFE30..0xFE4F` and
 * `0xFF00..0xFFEF`. So a note containing "　" (full-width space), "ｱ" (half-width
 * katakana) or a Yi syllable measured **wider on one device than on the other** —
 * and the hit box is drawn from that measurement, so the label is visible and
 * cannot be grabbed.
 *
 * The rule now lives in one place (`:shared`'s `com.vic.inkflow.ui.isWideChar`,
 * which is the tablet's, because the tablet is the writer), and the desktop asks
 * it. Emoji outside those ranges stay half-width, on both sides.
 */
fun Char.isWideChar(): Boolean = com.vic.inkflow.ui.isWideChar(this)
