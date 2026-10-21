package com.vic.inkflow.data.repository

import androidx.room.withTransaction
import com.vic.inkflow.data.AppDatabase
import com.vic.inkflow.data.MergeVersion
import com.vic.inkflow.data.PageStrokeCount
import com.vic.inkflow.data.Stroke
import com.vic.inkflow.data.StrokeDao
import com.vic.inkflow.data.StrokeVersionRow
import com.vic.inkflow.data.StrokeWithSamples
import com.vic.inkflow.data.toModel
import com.vic.inkflow.data.toRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 筆跡資料存取邊界的 **Room 實作**。
 *
 * 介面在 `:shared`（`StrokeRepository`），講共用文件模型；這裡是儲存的形狀。
 * 映射發生在邊界：進來是模型，出去是 row。
 *
 * [db] 只在 [saveStrokeWithPoints] 需要（跨 stroke+points 兩張表的交易）；
 * 單表操作一律直接委派給 DAO。
 */
class RoomStrokeRepository(
    private val db: AppDatabase,
    private val dao: StrokeDao
) : StrokeRepository {

    override suspend fun insertStroke(stroke: Stroke) = dao.insertStroke(stroke.toRow())

    override suspend fun insertPoints(points: List<com.vic.inkflow.data.Point>) =
        dao.insertPoints(points.map { it.toRow() })

    override suspend fun saveStrokeWithPoints(stroke: Stroke, points: List<com.vic.inkflow.data.Point>) {
        db.withTransaction {
            dao.insertStroke(stroke.toRow())
            if (points.isNotEmpty()) dao.insertPoints(points.map { it.toRow() })
        }
    }

    override fun getStrokesForPage(documentUri: String, pageIndex: Int): Flow<List<StrokeWithSamples>> =
        dao.getStrokesForPage(documentUri, pageIndex).map { rows -> rows.map { it.toModel() } }

    override suspend fun getStrokesForPageSync(documentUri: String, pageIndex: Int): List<StrokeWithSamples> =
        dao.getStrokesForPageSync(documentUri, pageIndex).map { it.toModel() }

    override suspend fun getAllStrokesForDocument(documentUri: String): List<StrokeWithSamples> =
        dao.getAllStrokesForDocument(documentUri).map { it.toModel() }

    override suspend fun getStrokesForRange(documentUri: String, y0: Float, y1: Float): List<StrokeWithSamples> =
        dao.getStrokesForRange(documentUri, y0, y1).map { it.toModel() }

    override suspend fun countStrokesByPage(documentUri: String): List<PageStrokeCount> =
        dao.countStrokesByPage(documentUri)

    override suspend fun deleteStrokesByIds(strokeIds: List<String>): Int =
        dao.deleteStrokesByIds(strokeIds)

    override suspend fun tombstoneStrokesByIds(strokeIds: List<String>): Int {
        if (strokeIds.isEmpty()) return 0
        // 點也一起收：墓碑讓列留下來，但 points 若留著就是純浪費——這筆已經不存在了，
        // 沒有任何仲裁會去比對它的點。留著只會讓 points 表隨刪除次數無限膨脹。
        //
        // 同一交易：UPDATE＋DELETE 各自 auto-commit 就是兩次 fsync；橡皮擦最高
        // 40 批／秒（24ms 節流），fsync 次數直接決定連擦跟不跟得上手指。
        return db.withTransaction {
            val tombstoned = dao.tombstoneStrokes(strokeIds, System.currentTimeMillis(), MergeVersion.nextNonce())
            if (tombstoned > 0) dao.deletePointsForTombstoned(strokeIds)
            tombstoned
        }
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
    override suspend fun replaceStrokeSnapshots(snapshots: List<StrokeWithSamples>) {
        db.withTransaction {
            snapshots.forEach { swp ->
                dao.deletePointsForStroke(swp.stroke.id)
                dao.insertStroke(swp.stroke.toRow())
                if (swp.points.isNotEmpty()) dao.insertPoints(swp.points.map { it.toRow() })
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
