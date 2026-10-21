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
 *
 * 2026-10-21 搬到 `:shared`：它描述的是「頁面怎麼動」而不是「資料庫怎麼存」，
 * 兩邊都要用（桌面的鏡像與平板的編輯器都要能增刪搬頁）。
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
     * 回收過期的筆跡／文字墓碑（§15.4）。回傳實際刪掉的列數。
     *
     * 放這裡而不是各自 repository：兩張表必須**一起**清，且要跟其他跨表操作走同一個
     * 交易邊界（`docs/specs/data.md`：跨表操作一律走 PageOps）。
     *
     * 冪等且無副作用：純粹「比 cutoff 舊就刪」，重跑結果一樣。保留期由呼叫端決定，
     * 這裡不做政策決定。
     */
    suspend fun purgeExpiredTombstones(cutoff: Long): Int

    /**
     * S1 單畫布 docY 全量回填（無 NULL 守衛，冪等）。
     * docY = pageIndex × stride + 各自的 y 錨點；stride 必須是開檔時的 live modelH。
     */
    suspend fun backfillAllDocY(documentUri: String, stride: Float)

    /** 門檻以下的墨整批平移（頁增刪用）。收斂前 util/PdfViewModel 對 3 張表各寫一遍。 */
    suspend fun shiftDocYBelow(documentUri: String, yThreshold: Float, dy: Float)

    /** 只平移 [y0, y1) 區間內的墨（頁搬移用）。收斂前 util/PdfViewModel 對 3 張表各寫一遍。 */
    suspend fun shiftDocYRange(documentUri: String, y0: Float, y1: Float, dy: Float)

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

/**
 * 頁搬移三步驟所需的最小面。5 張表的 repository 都實作它，
 * 所以 [PageOps.movePage] 能用同一段邏輯套 5 張表而不必重複 5 次。
 */
interface PageShiftTarget {
    suspend fun moveToTempIndex(documentUri: String, fromIndex: Int, tempIndex: Int = -1)
    suspend fun shiftForMoveDown(documentUri: String, fromIndex: Int, toIndex: Int)
    suspend fun shiftForMoveUp(documentUri: String, fromIndex: Int, toIndex: Int)
}
