package com.vic.inkflow.util

import androidx.compose.ui.graphics.ImageBitmap
import com.vic.inkflow.render.PageBox
import com.vic.inkflow.render.PdfEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The desktop's PDF engine — PDFBox, the same engine the desktop already used.
 *
 * ## Why it wraps [PdfManager] instead of calling PDFBox directly
 *
 * The desktop's `PdfManager` already does the careful part: it caches open
 * documents, clamps the DPI so a huge page cannot blow the memory budget, keeps
 * the raster and the geometry **from the same render** (so the ink can never be
 * positioned against a differently-read page box), and memoises thumbnails.
 *
 * A second, thinner PDF path would throw all of that away — and the bug it
 * prevents ("the ink is on the next page") is exactly the kind that is invisible
 * until someone opens a specific PDF.
 *
 * ## DPI, not pixels
 *
 * The contract asks for a width in pixels; PDFBox rasterises in DPI. The
 * conversion goes through the page's own width, so a caller that asks for
 * 800 px gets 800 px whatever the page size is.
 */
internal class DesktopPdfEngine : PdfEngine {

    override suspend fun pageCount(uri: String): Int = withContext(Dispatchers.IO) {
        when (val r = PdfManager.readPageInfo(uri, 0)) {
            is PdfResult.Ok -> r.value.pageCount
            is PdfResult.Err -> 0
        }
    }

    override suspend fun pageSize(uri: String, pageIndex: Int): PageBox? =
        withContext(Dispatchers.IO) {
            when (val r = PdfManager.readPageInfo(uri, pageIndex)) {
                is PdfResult.Ok -> r.value.box.let {
                    com.vic.inkflow.render.PageBox(
                        widthPt = it.widthPt,
                        heightPt = it.heightPt,
                        originX = it.originX,
                        originY = it.originY
                    )
                }
                is PdfResult.Err -> null
            }
        }

    override suspend fun renderPage(uri: String, pageIndex: Int, widthPx: Int): ImageBitmap? =
        withContext(Dispatchers.IO) {
            if (widthPx <= 0) return@withContext null
            when (val r = PdfManager.renderPageAtWidth(uri, pageIndex, widthPx)) {
                is PdfResult.Ok -> r.value
                is PdfResult.Err -> null
            }
        }
}
