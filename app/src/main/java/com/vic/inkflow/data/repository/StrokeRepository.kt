package com.vic.inkflow.data.repository

import androidx.room.withTransaction
import com.vic.inkflow.data.AppDatabase
import com.vic.inkflow.data.PageStrokeCount
import com.vic.inkflow.data.StrokeDao
import com.vic.inkflow.data.StrokeEntity
import com.vic.inkflow.data.MergeVersion
import com.vic.inkflow.data.StrokeVersionRow
import com.vic.inkflow.data.StrokeWithPoints
import com.vic.inkflow.data.PointEntity
import kotlinx.coroutines.flow.Flow

/**
 * 筆跡的資料存取邊界（P2 repository 層）。
 *
 * 存在的理由不是「看起來比較乾淨」，而是三個具體好處：
 *  1. 可測：ViewModel 只依賴這個介面，測試傳 hand-written fake 即可，
 *     不需要 Robolectric 也不需要真 Room。
 *  2. 可改：SQL 查詢最佳化（目前明顯有 N+1）可以只改 Room 實作，不動呼叫端。
 *  3. 位置正確：Android 頁面切換那套「五件套」邏輯原本散在 ViewModel 與
 *     util/PageOpJournal 兩處各寫一遍，收斂介面才有機會合併。
 *
 * 簽名刻意與 [StrokeDao] 一一對應（只是拿掉 Room 註解與 suspend 的實作外包給
 * RoomStrokeRepository），這樣遷移呼叫端是純機械替換，不改行為。
 */
interface StrokeRepository : PageShiftTarget {

    suspend fun insertStroke(stroke: StrokeEntity)
    suspend fun insertPoints(points: List<PointEntity>)

    /** 筆＋點一起存。Room 端是兩次 insert，呼叫端過去常常忘記點。 */
    suspend fun saveStrokeWithPoints(stroke: StrokeEntity, points: List<PointEntity>)

    fun getStrokesForPage(documentUri: String, pageIndex: Int): Flow<List<StrokeWithPoints>>
    suspend fun getStrokesForPageSync(documentUri: String, pageIndex: Int): List<StrokeWithPoints>
    suspend fun getAllStrokesForDocument(documentUri: String): List<StrokeWithPoints>
    suspend fun getStrokesForRange(documentUri: String, y0: Float, y1: Float): List<StrokeWithPoints>

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
     * 與 [deleteStrokesByIds] 的差別不是風格，是**同步語意**。硬刪除讓對端無從得知
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
    suspend fun replaceStrokeSnapshots(snapshots: List<StrokeWithPoints>)

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

/**
 * Room 實作。
 *
 * [db] 只在 [saveStrokeWithPoints] 需要（跨 stroke+points 兩張表的交易）；
 * 單表操作一律直接委派給 DAO。
 */
class RoomStrokeRepository(
    private val db: AppDatabase,
    private val dao: StrokeDao
) : StrokeRepository {

    override suspend fun insertStroke(stroke: StrokeEntity) = dao.insertStroke(stroke)

    override suspend fun insertPoints(points: List<PointEntity>) = dao.insertPoints(points)

    override suspend fun saveStrokeWithPoints(stroke: StrokeEntity, points: List<PointEntity>) {
        db.withTransaction {
            dao.insertStroke(stroke)
            if (points.isNotEmpty()) dao.insertPoints(points)
        }
    }

    override fun getStrokesForPage(documentUri: String, pageIndex: Int): Flow<List<StrokeWithPoints>> =
        dao.getStrokesForPage(documentUri, pageIndex)

    override suspend fun getStrokesForPageSync(documentUri: String, pageIndex: Int): List<StrokeWithPoints> =
        dao.getStrokesForPageSync(documentUri, pageIndex)

    override suspend fun getAllStrokesForDocument(documentUri: String): List<StrokeWithPoints> =
        dao.getAllStrokesForDocument(documentUri)

    override suspend fun countStrokesByPage(documentUri: String): List<PageStrokeCount> =
        dao.countStrokesByPage(documentUri)

    override suspend fun getStrokesForRange(documentUri: String, y0: Float, y1: Float): List<StrokeWithPoints> =
        dao.getStrokesForRange(documentUri, y0, y1)

    override suspend fun deleteStrokesByIds(strokeIds: List<String>): Int =
        dao.deleteStrokesByIds(strokeIds)

    override suspend fun tombstoneStrokesByIds(strokeIds: List<String>): Int {
        if (strokeIds.isEmpty()) return 0
        // 點也一起收：墓碑讓列留下來，但 points 若留著就是純浪費——這筆已經不存在了，
        // 沒有任何仲裁會去比對它的點。留著只會讓 points 表隨刪除次數無限膨脹。
        val tombstoned = dao.tombstoneStrokes(strokeIds, System.currentTimeMillis(), MergeVersion.nextNonce())
        if (tombstoned > 0) dao.deletePointsForTombstoned(strokeIds)
        return tombstoned
    }

    override suspend fun clearPage(documentUri: String, pageIndex: Int): Int =
        dao.clearPage(documentUri, pageIndex)

    override suspend fun deleteStrokesForDocument(documentUri: String) =
        dao.deleteStrokesForDocument(documentUri)

    override suspend fun deletePointsForStroke(strokeId: String) = dao.deletePointsForStroke(strokeId)

    override suspend fun shiftPageIndicesDown(documentUri: String, deletedPageIndex: Int) =
        dao.shiftPageIndicesDown(documentUri, deletedPageIndex)

    override suspend fun shiftPageIndicesUp(documentUri: String, startingIndex: Int, amount: Int) =
        dao.shiftPageIndicesUp(documentUri, startingIndex, amount)

    override suspend fun moveToTempIndex(documentUri: String, fromIndex: Int, tempIndex: Int) =
        dao.moveToTempIndex(documentUri, fromIndex, tempIndex)

    override suspend fun shiftForMoveDown(documentUri: String, fromIndex: Int, toIndex: Int) =
        dao.shiftForMoveDown(documentUri, fromIndex, toIndex)

    override suspend fun shiftForMoveUp(documentUri: String, fromIndex: Int, toIndex: Int) =
        dao.shiftForMoveUp(documentUri, fromIndex, toIndex)

    override suspend fun shiftDocYBelow(documentUri: String, yThreshold: Float, dy: Float): Int =
        dao.shiftDocYBelow(documentUri, yThreshold, dy)

    override suspend fun shiftDocYRange(documentUri: String, y0: Float, y1: Float, dy: Float): Int =
        dao.shiftDocYRange(documentUri, y0, y1, dy)

    override suspend fun countMissingDocY(documentUri: String): Int = dao.countMissingDocY(documentUri)

    override suspend fun backfillStrokeDocY(documentUri: String, stride: Float): Int =
        dao.backfillStrokeDocY(documentUri, stride)

    override suspend fun countStrokeDocMismatch(documentUri: String, stride: Float): Int =
        dao.countStrokeDocMismatch(documentUri, stride)

    override suspend fun bumpStrokeVersion(id: String): Int = dao.bumpStrokeVersion(id, MergeVersion.nextNonce())

    /**
     * 快照整批覆蓋＋序號前進（移動／縮放的寫入漏斗）。
     *
     * **不能只覆蓋不 bump。** 這裡走的是 insertStroke（REPLACE 整列），而呼叫端傳進來的
     * swp.stroke 帶的是讀取時的**舊 version** —— REPLACE 會把序號蓋回舊值，仲裁就再也
     * 看不出這筆被改過。筆數、座標都變了但 version 不動，正是「修改 vs 修改時平板恆輸」
     * 的那個 bug。
     *
     * 為什麼在同一個交易里：bump 的 UPDATE 讀的是 SQLite 在寫入當下的舊值（見
     * [StrokeDao.bumpStrokeVersion] 的註解），覆蓋與遞增必須原子，否則中間插入的
     * 同步交易會讓序號算錯。
     *
     * 空快照不 bump：replaceStrokeSnapshots 的呼叫端都先判了非空才進來，這裡不再
     * 設防，保持單一職責。
     */
    override suspend fun replaceStrokeSnapshots(snapshots: List<StrokeWithPoints>) {
        db.withTransaction {
            snapshots.forEach { swp ->
                dao.deletePointsForStroke(swp.stroke.id)
                dao.insertStroke(swp.stroke)
                if (swp.points.isNotEmpty()) dao.insertPoints(swp.points)
                dao.bumpStrokeVersion(swp.stroke.id, MergeVersion.nextNonce())
            }
        }
    }

    override suspend fun getStrokeVersions(documentUri: String): List<StrokeVersionRow> = dao.getStrokeVersions(documentUri)

    override suspend fun getStrokeVersion(id: String): StrokeVersionRow? = dao.getStrokeVersion(id)

    override suspend fun countLiveStrokes(documentUri: String): Int = dao.countLiveStrokes(documentUri)

    override suspend fun purgeTombstonedStrokes(cutoff: Long): Int = dao.purgeTombstonedStrokes(cutoff)

    override suspend fun tombstoneStrokes(strokeIds: List<String>): Int =
        dao.tombstoneStrokes(strokeIds, System.currentTimeMillis(), MergeVersion.nextNonce())

    override suspend fun reviveStrokes(strokeIds: List<String>): Int = dao.reviveStrokes(strokeIds)
}
