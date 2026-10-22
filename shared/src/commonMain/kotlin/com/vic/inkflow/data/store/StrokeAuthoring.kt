package com.vic.inkflow.data.store

import com.vic.inkflow.data.StrokeWithSamples

/**
 * The structural edits to a document's strokes — the **author** only.
 *
 * ## What belongs here
 *
 * The operations that change the *shape* of a document rather than its content:
 * moving a page, deleting a page, and the S1 single-canvas `docY` backfill.
 *
 * A mirror never performs them: it receives the author's result. The desktop
 * learns that a page moved because the tablet tells it (a full document replace,
 * or the `page_counts` diff), and it must not do the move itself — if it did, the
 * two sides' page indices would drift and the ink would end up on the wrong page.
 *
 * ## The docY family
 *
 * `docY` is the document-space anchor of a stroke: `pageIndex × stride + top`.
 * A page move or a page delete shifts it for everything below, and a document
 * imported from an older version may not have it at all — which is what the
 * "missing" and "mismatch" counters are for (§ S1, `docs/specs/data.md`).
 */
interface StrokeAuthoring {

    suspend fun clearPage(documentUri: String, pageIndex: Int): Int

    suspend fun getStrokesForRange(documentUri: String, y0: Float, y1: Float): List<StrokeWithSamples>

    // ── 頁操作五件套（與 Text/Image/Bookmark/Math 四表對齊）────────────────
    suspend fun shiftPageIndicesDown(documentUri: String, deletedPageIndex: Int)
    suspend fun shiftPageIndicesUp(documentUri: String, startingIndex: Int, amount: Int)

    // ── S1 單畫布 docY（= pageIndex × stride + boundsTop）───────────────────
    suspend fun shiftDocYBelow(documentUri: String, yThreshold: Float, dy: Float): Int
    suspend fun shiftDocYRange(documentUri: String, y0: Float, y1: Float, dy: Float): Int
    suspend fun countMissingDocY(documentUri: String): Int
    suspend fun backfillStrokeDocY(documentUri: String, stride: Float): Int
    suspend fun countStrokeDocMismatch(documentUri: String, stride: Float): Int
}
