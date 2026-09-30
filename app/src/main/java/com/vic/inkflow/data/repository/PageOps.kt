package com.vic.inkflow.data.repository

/**
 * 頁操作的跨表收斂（P2b）。
 *
 * 收斂前的樣貌：insertPageAfter / deletePageAt / movePage / backfillDocY 這四個操作，
 * 在 PageOpJournal、util/PdfViewModel、ui/EditorViewModel 裡各自對 5 張表重複寫一遍
 * （strokes / text_annotations / image_annotations / bookmarks / math_sources），
 * 合計 20 段幾乎逐字相同的程式碼。任何一張表新增或改名都要記得同步改 20 處，
 * 漏一處就是「刪頁後書籤錯位」「搬頁後公式跑到別頁」這類只在真機才發現的 bug。
 *
 * 這裡把它收成單一邊界。改這裡的實作＝一次改完 5 張表，漏不掉。
 *
 * 刻意保持原本的呼叫順序（tempIndex → shift → tempIndex 回填），
 * 語意與重構前逐行等價，不是重新設計。
 */
interface PageOps {

    /** 在 [afterIndex] 之後插入 [amount] 頁：該頁之後所有標註的 pageIndex 後移。 */
    suspend fun insertPagesAfter(documentUri: String, afterIndex: Int, amount: Int = 1)

    /** 刪除第 [index] 頁：先清該頁標註，再把後面所有標註往前移一格。 */
    suspend fun deletePage(documentUri: String, index: Int)

    /** 把第 [fromIndex] 頁搬到第 [toIndex] 頁。 */
    suspend fun movePage(documentUri: String, fromIndex: Int, toIndex: Int)

    /** 整份文件刪除時的跨表清理。 */
    suspend fun deleteAllForDocument(documentUri: String)

    /**
     * S1 單畫布 docY 全量回填（無 NULL 守衛，冪等）。
     * docY = pageIndex × stride + 各自的 y 錨點；stride 必須是開檔時的 live modelH。
     */
    suspend fun backfillAllDocY(documentUri: String, stride: Float)

    /** docY 不變量抽查結果。任一項 > 0 即代表回填不完整或紙張尺寸中途變過。 */
    suspend fun docYSanity(documentUri: String, stride: Float): DocYSanity
}

data class DocYSanity(
    val missingStrokes: Int,
    val missingTexts: Int,
    val missingImages: Int,
    val mismatchedStrokes: Int,
    val mismatchedTexts: Int,
    val mismatchedImages: Int
) {
    val isClean: Boolean
        get() = missingStrokes == 0 && missingTexts == 0 && missingImages == 0 &&
                mismatchedStrokes == 0 && mismatchedTexts == 0 && mismatchedImages == 0
}

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

    override suspend fun backfillAllDocY(documentUri: String, stride: Float) {
        strokes.backfillStrokeDocY(documentUri, stride)
        texts.backfillTextDocY(documentUri, stride)
        images.backfillImageDocY(documentUri, stride)
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

/**
 * 頁搬移三步驟所需的最小面。5 張表的 repository 都實作它，
 * 所以 [PageOps.movePage] 能用同一段邏輯套 5 張表而不必重複 5 次。
 */
interface PageShiftTarget {
    suspend fun moveToTempIndex(documentUri: String, fromIndex: Int, tempIndex: Int = -1)
    suspend fun shiftForMoveDown(documentUri: String, fromIndex: Int, toIndex: Int)
    suspend fun shiftForMoveUp(documentUri: String, fromIndex: Int, toIndex: Int)
}
