package com.vic.inkflow.render

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Rasterising a PDF page — the one thing every platform does differently.
 *
 *  - **Android**: `android.graphics.pdf.PdfRenderer` (built into the framework).
 *  - **Windows**: PDFBox (the desktop already depends on it).
 *  - **iOS**: PDFKit, which is *better* than both (it is what Preview uses).
 *
 * The editor, the exporter and the sync preview all need "the page as an image",
 * and each of those three engines rasterises at a different DPI with different
 * anti-aliasing. So the shared code asks for a page **in pixels** and gets
 * whatever the platform produces — the shared code never sees a PDF object.
 *
 * The alternative (letting the shared code talk to a PDF library) is what makes
 * an app single-platform: PDFBox is not on Android, PdfRenderer is not on
 * Windows, PDFKit is not on either.
 */
interface PdfEngine {

    /** The page's size in PDF points, or null when the page does not exist. */
    suspend fun pageSize(uri: String, pageIndex: Int): PageBox?

    /**
     * Rasterises a page into an image of the given size.
     *
     * @param widthPx the target width in pixels; the height follows the page's
     *   aspect ratio.
     * @return null when the page cannot be rasterised (a missing file, a broken
     *   page) — the caller decides whether that is an error or an empty page.
     */
    suspend fun renderPage(uri: String, pageIndex: Int, widthPx: Int): ImageBitmap?

    /** How many pages the document has. */
    suspend fun pageCount(uri: String): Int
}

/** A page's box in PDF points. */
data class PageBox(
    val widthPt: Float,
    val heightPt: Float,
    /**
     * The CropBox origin. A page whose CropBox does not start at (0, 0) has to
     * have that origin subtracted from every coordinate, or the ink lands next to
     * the page instead of on it.
     */
    val originX: Float = 0f,
    val originY: Float = 0f
)
