package com.vic.inkflow.data.repository

import androidx.room.withTransaction
import com.vic.inkflow.data.AppDatabase
import com.vic.inkflow.data.StrokeDao
import com.vic.inkflow.data.StrokeEntity
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

    suspend fun deleteStrokesByIds(strokeIds: List<String>): Int
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

    override suspend fun getStrokesForRange(documentUri: String, y0: Float, y1: Float): List<StrokeWithPoints> =
        dao.getStrokesForRange(documentUri, y0, y1)

    override suspend fun deleteStrokesByIds(strokeIds: List<String>): Int =
        dao.deleteStrokesByIds(strokeIds)

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
}
