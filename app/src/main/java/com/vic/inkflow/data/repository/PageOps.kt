package com.vic.inkflow.data.repository

/**
 * 頁操作跨表收斂的 **Room 實作**。
 *
 * 介面在 `:shared`（`PageOps`）；這裡是它對五張表的實際呼叫順序。
 * 呼叫順序（tempIndex → shift → tempIndex 回填）與重構前逐行等價，不是重新設計。
 */
class RoomPageOps(
    private val strokes: StrokeRepository,
    private val texts: TextAnnotationRepository,
    private val images: ImageAnnotationRepository,
    private val bookmarks: BookmarkRepository,
    private val mathSources: MathSourceRepository
) : PageOps {

    override suspend fun insertPagesAfter(documentUri: String, afterIndex: Int, amount: Int) {
        strokes.shiftPageIndicesUp(documentUri, afterIndex, amount)
        texts.shiftPageIndicesUp(documentUri, afterIndex, amount)
        images.shiftPageIndicesUp(documentUri, afterIndex, amount)
        bookmarks.shiftPageIndicesUp(documentUri, afterIndex, amount)
        mathSources.shiftPageIndicesUp(documentUri, afterIndex, amount)
    }

    override suspend fun deletePage(documentUri: String, index: Int) {
        // strokes 走 clearPage（DELETE ... pageIndex = index），其餘四表走 deleteForPage。
        // 兩者 SQL 等價，但保留原本的分流以免改變既有查詢計畫。
        strokes.clearPage(documentUri, index)
        texts.deleteForPage(documentUri, index)
        images.deleteForPage(documentUri, index)
        bookmarks.deleteForPage(documentUri, index)
        mathSources.deleteForPage(documentUri, index)

        strokes.shiftPageIndicesDown(documentUri, index)
        texts.shiftPageIndicesDown(documentUri, index)
        images.shiftPageIndicesDown(documentUri, index)
        bookmarks.shiftPageIndicesDown(documentUri, index)
        mathSources.shiftPageIndicesDown(documentUri, index)
    }

    override suspend fun movePage(documentUri: String, fromIndex: Int, toIndex: Int) {
        // 兩階段搬移：先把搬走的那頁標成 -1 暫存，再位移其他頁，最後把 -1 落到目標位置。
        // 這是必要的——直接改會讓中間頁互相覆蓋。
        val down = fromIndex < toIndex

        suspend fun step(target: PageShiftTarget) {
            target.moveToTempIndex(documentUri, fromIndex, -1)
            if (down) target.shiftForMoveDown(documentUri, fromIndex, toIndex)
            else target.shiftForMoveUp(documentUri, fromIndex, toIndex)
            target.moveToTempIndex(documentUri, -1, toIndex)
        }

        step(strokes); step(texts); step(images); step(bookmarks); step(mathSources)
    }

    override suspend fun deleteAllForDocument(documentUri: String) {
        strokes.deleteStrokesForDocument(documentUri)
        texts.deleteForDocument(documentUri)
        images.deleteForDocument(documentUri)
        bookmarks.deleteForDocument(documentUri)
        mathSources.deleteForDocument(documentUri)
    }

    override suspend fun purgeExpiredTombstones(cutoff: Long): Int {
        // 先刪 strokes：它的 points 由 FK CASCADE 跟著走，不會留下孤兒點。
        // 文字註解沒有子表，直接刪。
        val s = strokes.purgeTombstonedStrokes(cutoff)
        val t = texts.purgeTombstonedTexts(cutoff)
        return s + t
    }

    override suspend fun backfillAllDocY(documentUri: String, stride: Float) {
        strokes.backfillStrokeDocY(documentUri, stride)
        texts.backfillTextDocY(documentUri, stride)
        images.backfillImageDocY(documentUri, stride)
    }

    override suspend fun shiftDocYBelow(documentUri: String, yThreshold: Float, dy: Float) {
        strokes.shiftDocYBelow(documentUri, yThreshold, dy)
        texts.shiftDocYBelow(documentUri, yThreshold, dy)
        images.shiftDocYBelow(documentUri, yThreshold, dy)
    }

    override suspend fun shiftDocYRange(documentUri: String, y0: Float, y1: Float, dy: Float) {
        strokes.shiftDocYRange(documentUri, y0, y1, dy)
        texts.shiftDocYRange(documentUri, y0, y1, dy)
        images.shiftDocYRange(documentUri, y0, y1, dy)
    }

    override suspend fun docYSanity(documentUri: String, stride: Float) = DocYSanity(
        missingStrokes = strokes.countMissingDocY(documentUri),
        missingTexts = texts.countMissingDocY(documentUri),
        missingImages = images.countMissingDocY(documentUri),
        mismatchedStrokes = strokes.countStrokeDocMismatch(documentUri, stride),
        mismatchedTexts = texts.countTextDocMismatch(documentUri, stride),
        mismatchedImages = images.countImageDocMismatch(documentUri, stride)
    )
}
