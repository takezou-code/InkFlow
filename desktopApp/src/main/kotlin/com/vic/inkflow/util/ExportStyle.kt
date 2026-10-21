package com.vic.inkflow.util

/**
 * Export constants shared by the desktop exporter.
 *
 * Kept apart from the drawing code so the *numbers* can be asserted without
 * producing a PDF, and so a divergence from the tablet's exporter is visible in one
 * place rather than scattered through a drawing routine.
 */
object ExportStyle {
    /** Matches the tablet's `PdfExporter`: a translucent fill, whatever the colour. */
    const val highlighterAlpha = 0.4f

    /** Opaque ink. The colour's own alpha byte is honoured elsewhere. */
    const val inkAlpha = 1f

    /**
     * Circle-to-cubic-Bezier factor, 4*(sqrt(2)-1)/3.
     *
     * The standard constant for approximating a quarter circle with one cubic. Using
     * a polygon with too few sides instead is visible as a faceted circle at print
     * size, and using 0.5 makes it visibly squashed.
     */
    const val ELLIPSE_K = 0.5522848f

    /** Quarter arcs needed to close an ellipse. */
    const val ellipseSegments = 4

    /**
     * Fallback model page size in points (A4).
     *
     * Only used when a document's real page size is unknown. Real exports pass the
     * page's own dimensions, because assuming A4 for a Letter document shifts every
     * annotation.
     */
    const val MODEL_W = 595f
    const val MODEL_H = 842f

    /**
     * Raster scale for anything that must be drawn as an image rather than as
     * vectors (none today, kept for the image-annotation path).
     */
    const val BITMAP_SCALE = 2f
}