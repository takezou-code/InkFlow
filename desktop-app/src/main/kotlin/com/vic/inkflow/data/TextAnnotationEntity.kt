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
    val isStamp: Boolean = false
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
    const val FULL_WIDTH_EM = 1.0f
    const val HALF_WIDTH_EM = 0.65f

    /** Line height multiplier used by the tablet for multi-line height. */
    const val LINE_SPACING = 1.2f

    /**
     * Width of [text] in model units at [fontSize].
     *
     * Uses a real font measurement when the platform can supply one and falls
     * back to the coefficient model otherwise, so a headless unit test still
     * gets a deterministic answer.
     */
    fun measureWidth(text: String, fontSize: Float, measurer: ((String, Float) -> Float)? = null): Float {
        if (measurer != null) return measurer(text, fontSize)
        var ems = 0f
        text.forEach { c -> ems += if (c.isWideChar()) FULL_WIDTH_EM else HALF_WIDTH_EM }
        return ems * fontSize
    }

    /**
     * Bounding box of a text annotation in model units.
     *
     * Top edge is the baseline minus one font size; height adds 1.2em per
     * additional line. Returns null for blank text — an empty annotation has no
     * box, so it cannot be hit-tested and should not be drawn.
     */
    fun bounds(
        modelX: Float,
        modelY: Float,
        text: String,
        fontSize: Float,
        measurer: ((String, Float) -> Float)? = null
    ): FloatArray? {
        if (text.isBlank()) return null
        val lines = text.split('\n')
        val width = lines.maxOf { measureWidth(it, fontSize, measurer) }
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
}

/**
 * CJK / full-width test, matching the tablet's `SelectionGeometry.isWideChar`.
 *
 * Covers the CJK blocks the tablet treats as full-width and treats everything
 * else — including emoji outside those ranges — as half-width, which is the
 * tablet's behaviour and therefore the contract.
 */
fun Char.isWideChar(): Boolean {
    val c = code
    return c in 0x2E80..0x9FFF ||
        c in 0xAC00..0xD7AF ||
        c in 0xF900..0xFAFF ||
        c in 0xFF00..0xFF60 ||
        c in 0xFFE0..0xFFE6 ||
        c in 0x3000..0x303E
}