package com.vic.inkflow.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface StrokeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStroke(stroke: StrokeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoints(points: List<PointEntity>)

    // ── 墓碑隔離（v6 §15.4）────────────────────────────────────────────────
    //
    // 下面這五支「內容／計數」查詢一律帶 deletedAt IS NULL，與 [countLiveStrokes]
    // 同一套語意。這不是可有可無的清理，是兩條正確性不變式：
    //
    // 1) docVersion 自我矛盾：docVersion 的筆數欄位由 TabletSyncServer 呼叫
    //    [getAllStrokesForDocument] 取得，而同一份文件的仲裁計數走 [countLiveStrokes]。
    //    墓碑兩邊算得到不同數字 → 只要這份文件刪過任何一筆，manifest 報的版本與
    //    實際版本永遠對不上，桌面端每輪都會被迫重拉。
    //
    // 2) 刪除永遠傳不到桌面端：桌面端靠「數字變小」發現刪除
    //    （LocalSyncManager.tryDeltaPull 比對 page_counts 後整頁重拉並先刪本地列）。
    //    墓碑留在計數裡，數字就不變，桌面端永遠看不到那筆被刪了。
    //
    // 平板端本機刪除走硬刪除（EditorViewModel 用 deleteStrokesByIds），墓碑只會由
    // TabletSyncServer 套用桌面提案時產生，所以這層過濾不影響平板自己的畫面。
    //
    // 反過來，下面三支「版本／仲裁」查詢（[getStrokeVersion]、[getStrokeVersions]、
    // [tombstoneStrokes]）**絕對不能**加這層過濾：仲裁必須看得見墓碑才知道對端刪過。

    @Transaction
    @Query("SELECT * FROM strokes WHERE documentUri = :documentUri AND pageIndex = :pageIndex AND deletedAt IS NULL")
    fun getStrokesForPage(documentUri: String, pageIndex: Int): Flow<List<StrokeWithPoints>>

    @Transaction
    @Query("SELECT * FROM strokes WHERE documentUri = :documentUri AND pageIndex = :pageIndex AND deletedAt IS NULL")
    suspend fun getStrokesForPageSync(documentUri: String, pageIndex: Int): List<StrokeWithPoints>

    @Transaction
    @Query("SELECT * FROM strokes WHERE documentUri = :documentUri AND deletedAt IS NULL")
    suspend fun getAllStrokesForDocument(documentUri: String): List<StrokeWithPoints>

    @Query("DELETE FROM strokes WHERE id IN (:strokeIds)")
    suspend fun deleteStrokesByIds(strokeIds: List<String>): Int

    /**
     * Per-page stroke counts for incremental sync (`page_counts` verb).
     *
     * This exists because `getAllStrokesForDocument` loads every point to count them,
     * which is exactly the cost incremental pull is trying to avoid on large
     * documents. Two indexed GROUP BY queries replace a full table load.
     *
     * `deletedAt IS NULL` keeps this count in lockstep with [countLiveStrokes];
     * see the tombstone-isolation note above. The (documentUri, pageIndex) index
     * still drives the GROUP BY, so the predicate is a filter, not a scan.
     */
    @Query("SELECT pageIndex AS pageIndex, COUNT(*) AS strokeCount FROM strokes WHERE documentUri = :documentUri AND deletedAt IS NULL GROUP BY pageIndex")
    suspend fun countStrokesByPage(documentUri: String): List<PageStrokeCount>

    @Query("DELETE FROM strokes WHERE documentUri = :documentUri AND pageIndex = :pageIndex")
    suspend fun clearPage(documentUri: String, pageIndex: Int): Int

    @Query("UPDATE strokes SET pageIndex = pageIndex - 1 WHERE documentUri = :documentUri AND pageIndex > :deletedPageIndex")
    suspend fun shiftPageIndicesDown(documentUri: String, deletedPageIndex: Int)

    @Query("UPDATE strokes SET pageIndex = pageIndex + :amount WHERE documentUri = :documentUri AND pageIndex >= :startingIndex")
    suspend fun shiftPageIndicesUp(documentUri: String, startingIndex: Int, amount: Int)

    // For moving a page: Step 1 = change moved page to -1; Step 2 = shift others; Step 3 = change -1 to toIndex
    @Query("UPDATE strokes SET pageIndex = :tempIndex WHERE documentUri = :documentUri AND pageIndex = :fromIndex")
    suspend fun moveToTempIndex(documentUri: String, fromIndex: Int, tempIndex: Int = -1)

    @Query("UPDATE strokes SET pageIndex = pageIndex - 1 WHERE documentUri = :documentUri AND pageIndex > :fromIndex AND pageIndex <= :toIndex")
    suspend fun shiftForMoveDown(documentUri: String, fromIndex: Int, toIndex: Int)

    @Query("UPDATE strokes SET pageIndex = pageIndex + 1 WHERE documentUri = :documentUri AND pageIndex >= :toIndex AND pageIndex < :fromIndex")
    suspend fun shiftForMoveUp(documentUri: String, fromIndex: Int, toIndex: Int)

    @Query("DELETE FROM strokes WHERE documentUri = :documentUri")
    suspend fun deleteStrokesForDocument(documentUri: String)

    @Query("DELETE FROM points WHERE strokeId = :strokeId")
    suspend fun deletePointsForStroke(strokeId: String)

    // ── S1 單畫布：文件座標（docY = pageIndex × stride + boundsTop）─────────
    // S1 只寫不讀：回填＋新寫雙寫；範圍讀切換在 S2。

    /** 與 y 範圍相交的筆（S2 主查詢；S1 僅做一致性對帳）。 */
    @Transaction
    @Query("""
        SELECT * FROM strokes
        WHERE documentUri = :documentUri
          AND deletedAt IS NULL
          AND docY IS NOT NULL AND docY <= :y1
          AND (docY + (boundsBottom - boundsTop)) >= :y0
    """)
    suspend fun getStrokesForRange(documentUri: String, y0: Float, y1: Float): List<StrokeWithPoints>

    /** 頁管理（S4 用）：把 y 閾值以下的墨整批平移。NULL 行不受影響。 */
    @Query("UPDATE strokes SET docY = docY + :dy WHERE documentUri = :documentUri AND docY IS NOT NULL AND docY >= :yThreshold")
    suspend fun shiftDocYBelow(documentUri: String, yThreshold: Float, dy: Float): Int

    /** 頁搬移用：只平移 [y0, y1) 區間內的墨（有 (documentUri, docY) 索引）。NULL 行不受影響。 */
    @Query("UPDATE strokes SET docY = docY + :dy WHERE documentUri = :documentUri AND docY IS NOT NULL AND docY >= :y0 AND docY < :y1")
    suspend fun shiftDocYRange(documentUri: String, y0: Float, y1: Float, dy: Float): Int

    @Query("SELECT COUNT(*) FROM strokes WHERE documentUri = :documentUri AND docY IS NULL")
    suspend fun countMissingDocY(documentUri: String): Int

    /** 冪等全量重算（無 NULL 守衛）：docY = pageIndex × stride + boundsTop。
     * 每次開文件跑一次，頁增刪/紙改尺寸的陳舊值自動修正，無需碰 PdfViewModel。 */
    @Query("UPDATE strokes SET docY = pageIndex * :stride + boundsTop WHERE documentUri = :documentUri")
    suspend fun backfillStrokeDocY(documentUri: String, stride: Float): Int

    /** 不變式抽查：回填公式不合的列數（容差 0.01 防浮點噪聲；>0 即壞）。 */
    @Query("SELECT COUNT(*) FROM strokes WHERE documentUri = :documentUri AND ABS(docY - (pageIndex * :stride + boundsTop)) > 0.01")
    suspend fun countStrokeDocMismatch(documentUri: String, stride: Float): Int

    // ── v6 併發合併（§15.1）──────────────────────────────────────────────

    /**
     * 本機改寫時把版本序號前進一格並換 nonce（§15.1）。
     *
     * 為什麼是 `UPDATE` 而不是改掉傳進來的 entity：entity 帶的是讀取時的舊 version，
     * 在它上面 +1 需要「讀改寫」兩步，中間若被同步交易插入就會算錯版本——而錯的版本
     * 會讓對端以為這份內容比較舊，是最難查的一類 bug。讓 SQLite 在寫入的同一個敘述裡
     * 讀自己的舊值，序號就永遠是「當下真正的舊值 +1」。
     *
     * `versionNonce` 用隨機數而非遞增：它的用途是「同版本兩端各改一次」的決定性
     * tiebreak，必須不可預測（同樣的輸入才會兩端都取小者就贏）。
     */
    @Query("UPDATE strokes SET version = version + 1, versionNonce = :nonce WHERE id = :id")
    suspend fun bumpStrokeVersion(id: String, nonce: Int): Int

    /**
     * 丟掉已進墓碑的筆跡的點。
     *
     * 為什麼需要：墓碑讓 strokes 的列留下來，但 points 是 FK CASCADE 在**硬刪除**時
     * 才會跟著走。寫墓碑不會觸發它，沒有這支查詢的話 points 會隨刪除次數無限膨脹——
     * 而這些點沒有任何仲裁會去讀（仲裁只看 strokes 的 version/nonce/deletedAt），
     * 是純粹的垃圾。
     *
     * `IN (:ids) AND deletedAt IS NOT NULL`：只在真的蓋上墓碑的那些筆跡上生效，
     * 就算呼叫端傳來一份沒被刪掉的 id 進來，也不會誤刪還活著的點。
     */
    @Query("DELETE FROM points WHERE strokeId IN (SELECT id FROM strokes WHERE id IN (:strokeIds) AND deletedAt IS NOT NULL)")
    suspend fun deletePointsForTombstoned(strokeIds: List<String>): Int

    /**
     * 墓碑保留期：過了就把列真的刪掉。
     *
     * 墓碑存在的唯一理由是「另一台裝置可能還沒看到這次刪除」。一旦所有對端都拉過
     * 一輪、比對不到這筆了，留著就只是垃圾——而本機每天都在製造垃圾（每擦一筆多
     * 一列）。保留期取 30 天：遠比 28 秒一輪的同步週期長，涵蓋「桌面端整個禮拜沒
     * 開機」這種真實情況，又不至於讓資料庫無限長大。
     *
     * 冪等：純粹是「比 cutoff 舊就刪」，重跑不影響結果，也不依賴外部狀態。
     */
    @Query("DELETE FROM strokes WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun purgeTombstonedStrokes(cutoff: Long): Int

    /** 單筆的 (version, nonce, deletedAt)，供提案仲裁比對（§15.2）。 */
    @Query("SELECT id AS id, version AS version, versionNonce AS versionNonce, deletedAt AS deletedAt FROM strokes WHERE id = :id")
    suspend fun getStrokeVersion(id: String): StrokeVersionRow?

    /** 活著（非墓碑）的筆數：docVersion／page_counts 都要用這裡的語意。 */
    @Query("SELECT COUNT(*) FROM strokes WHERE documentUri = :documentUri AND deletedAt IS NULL")
    suspend fun countLiveStrokes(documentUri: String): Int

    /** 整份文件的 (id, version, nonce)：一次查完 N 個物件的版本，供逐 op 仲裁。 */
    @Query("SELECT id AS id, version AS version, versionNonce AS versionNonce FROM strokes WHERE documentUri = :documentUri")
    suspend fun getStrokeVersions(documentUri: String): List<StrokeVersionRow>

    /** 刪除＝寫墓碑（§15.4）。回傳受影響列數，0 = 這筆不存在或已是墓碑。 */
    @Query("UPDATE strokes SET deletedAt = :at, version = version + 1, versionNonce = :nonce WHERE id IN (:strokeIds) AND deletedAt IS NULL")
    suspend fun tombstoneStrokes(strokeIds: List<String>, at: Long, nonce: Int): Int

    /** 復活墓碑（§15.4「修改勝」）：對端改過的東西，不該因為一次刪除就憑空消失。 */
    @Query("UPDATE strokes SET deletedAt = NULL WHERE id IN (:strokeIds) AND deletedAt IS NOT NULL")
    suspend fun reviveStrokes(strokeIds: List<String>): Int
}