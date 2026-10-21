package com.vic.inkflow.data.repository

import com.vic.inkflow.data.PageStrokeCount
import com.vic.inkflow.data.StrokeVersionRow
import com.vic.inkflow.data.StrokeWithSamples
import kotlinx.coroutines.flow.Flow

/**
 * 筆跡的資料存取邊界（repository 層）。
 *
 * 存在的理由不是「看起來比較乾淨」，而是三個具體好處：
 *  1. 可測：ViewModel 只依賴這個介面，測試傳 hand-written fake 即可，
 *     不需要 Robolectric 也不需要真 Room。
 *  2. 可改：SQL 查詢最佳化（目前明顯有 N+1）可以只改 Room 實作，不動呼叫端。
 *  3. 位置正確：Android 頁面切換那套「五件套」邏輯原本散在 ViewModel 與
 *     util/PageOpJournal 兩處各寫一遍，收斂介面才有機會合併。
 *
 * ## 為什麼介面在 `:shared`、而型別是**共用模型**
 *
 * 這個介面 2026-10-21 從 `:app` 搬到 `:shared`：它描述的是「一份文件長什麼樣」，
 * 而不是「Android 的資料庫長什麼樣」——所以它必須講**共用的文件模型**
 * （`StrokeWithSamples`），不能講 `:app` 的 Room row。搬到共用模組之後，
 * 編輯器核心（下一階段要下沉的那部分）可以在 `:shared` 裡直接用這個介面，
 * 而不必知道後面是 Room、JDBC 還是 iOS 的資料庫。
 *
 * 儲存的形狀（Room 的 row、JDBC 的 row）是**平台層**的事：`:app` 的
 * `RoomStrokeRepository` 負責在邊界上把 row 映射成模型。
 */
interface StrokeRepository : PageShiftTarget {

    suspend fun insertStroke(stroke: com.vic.inkflow.data.Stroke)
    suspend fun insertPoints(points: List<com.vic.inkflow.data.Point>)

    /** 筆＋點一起存。Room 端是兩次 insert，呼叫端過去常常忘記點。 */
    suspend fun saveStrokeWithPoints(stroke: com.vic.inkflow.data.Stroke, points: List<com.vic.inkflow.data.Point>)

    fun getStrokesForPage(documentUri: String, pageIndex: Int): Flow<List<StrokeWithSamples>>
    suspend fun getStrokesForPageSync(documentUri: String, pageIndex: Int): List<StrokeWithSamples>
    suspend fun getAllStrokesForDocument(documentUri: String): List<StrokeWithSamples>
    suspend fun getStrokesForRange(documentUri: String, y0: Float, y1: Float): List<StrokeWithSamples>

    /**
     * Per-page stroke counts, for the `page_counts` sync verb. A GROUP BY query —
     * not a full load — because the verb exists precisely to avoid loading the
     * whole document on large files.
     */
    suspend fun countStrokesByPage(documentUri: String): List<PageStrokeCount>

    suspend fun deleteStrokesByIds(strokeIds: List<String>): Int

    /**
     * 刪除「使用者看得見的」筆跡：寫墓碑而不是硬刪除（§15.4）。
     *
     * 與 [deleteStrokesByIds] 的差別不是風格，是**同步語意**。硬刪讓對端無從得知
     * 「這裡曾經刪過東西」，於是「刪除 vs 對方修改」這條規則根本沒有狀態可比；墓碑
     * 留下可比較的 (version, nonce, deletedAt)，仲裁才能給出「修改勝」。
     *
     * **不要把所有 deleteStrokesByIds 換成這個。** 那個方法的三種用途語意不同：
     *   - undo/redo 一筆 AddStroke：這筆從來沒被同步看過，硬刪才對，寫墓碑只會留下
     *     永遠沒人回收的幽靈列（redo 又會 insertStroke 把它蓋回來，deletedAt 歸零，
     *     等於刪除紀錄被抹掉，行為跟硬刪一樣但多走一圈）。
     *   - AddSelectionCopies / ExtractToNewPage 的復原：同上，這些物件本機自生自滅。
     *   - 頁面／文件層級的結構操作：見 clearPage／deleteStrokesForDocument，兩者都是
     *     整頁或整份文件消失，不該逐筆留墓碑。
     * 只有「刪掉別人可能也看得見、也可能同時在改的東西」才用這個。
     */
    suspend fun tombstoneStrokesByIds(strokeIds: List<String>): Int

    suspend fun clearPage(documentUri: String, pageIndex: Int): Int
    suspend fun deleteStrokesForDocument(documentUri: String)
    suspend fun deletePointsForStroke(strokeId: String)

    // ── 頁操作五件套（與 Text/Image/Bookmark/Math 四表對齊）────────────────
    suspend fun shiftPageIndicesDown(documentUri: String, deletedPageIndex: Int)
    suspend fun shiftPageIndicesUp(documentUri: String, startingIndex: Int, amount: Int)

    // ── S1 單畫布 docY（= pageIndex × stride + boundsTop）───────────────────
    suspend fun shiftDocYBelow(documentUri: String, yThreshold: Float, dy: Float): Int
    suspend fun shiftDocYRange(documentUri: String, y0: Float, y1: Float, dy: Float): Int
    suspend fun countMissingDocY(documentUri: String): Int
    suspend fun backfillStrokeDocY(documentUri: String, stride: Float): Int
    suspend fun countStrokeDocMismatch(documentUri: String, stride: Float): Int

    // ── v6 併發合併（§15.1／§15.2／§15.4）───────────────────────────────

    /** 本機改寫一筆筆跡：前進版本序號並換 tiebreak nonce。0 = 這筆不存在。 */
    suspend fun bumpStrokeVersion(id: String): Int

    /**
     * 快照整批覆蓋＋序號前進（移動／縮放的寫入漏斗）。
     * 用這個，不要自己拼 deletePointsForStroke + insertStroke + insertPoints。
     * 見實作的註解：覆蓋會把序號蓋回舊值，必須在同一交易里 bump。
     */
    suspend fun replaceStrokeSnapshots(snapshots: List<StrokeWithSamples>)

    /** 整份文件的 (id, version, nonce)，供提案逐 op 仲裁（§15.2）。 */
    suspend fun getStrokeVersions(documentUri: String): List<StrokeVersionRow>

    /** 單筆的合併狀態，含墓碑；刪除仲裁需要（§15.4）。 */
    suspend fun getStrokeVersion(id: String): StrokeVersionRow?

    /** 活著（非墓碑）的筆數。docVersion 與 page_counts 都用這個語意。 */
    suspend fun countLiveStrokes(documentUri: String): Int

    /** 回收比 [cutoff] 更早的墓碑。回傳刪掉幾列。 */
    suspend fun purgeTombstonedStrokes(cutoff: Long): Int

    /** 刪除＝寫墓碑（§15.4）。回傳實際寫入的列數。 */
    suspend fun tombstoneStrokes(strokeIds: List<String>): Int

    /** 復活墓碑（§15.4 修改勝）。 */
    suspend fun reviveStrokes(strokeIds: List<String>): Int
}
