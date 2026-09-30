package com.vic.inkflow.data.repository

import androidx.room.withTransaction
import com.vic.inkflow.data.AppDatabase

/**
 * 所有 repository 的持有者 ＋ 跨 repository 的交易邊界（P2）。
 *
 * 為什麼需要這個容器：改之前 ViewModel/Composable 直接持有 [AppDatabase]，
 * 20 個檔案都碰得到它，而且跨 5 張表的頁操作必須寫 `db.withTransaction { ... }`，
 * 等於每個呼叫端都要自己知道「哪些表要同一步驟」。有了容器之後：
 *
 *  - 呼叫端只依賴 [InkFlowRepositories]，不再看得見 Room
 *  - 交易範圍變成一個明確的 `repos.transaction { ... }`，可以收斂成一個複合操作
 *  - 測試只要整個換成假的容器
 *
 * [transaction] 底下就是 Room 的 `withTransaction`，語意與原本 19 處完全相同
 * （可重入、序列化、失敗回滾），不是新語意。
 */
class InkFlowRepositories(private val db: AppDatabase) {

    val strokes = RoomStrokeRepository(db, db.strokeDao())
    val documents = RoomDocumentRepository(db, db.documentDao())
    val folders = RoomFolderRepository(db, db.folderDao())
    val texts = RoomTextAnnotationRepository(db, db.textAnnotationDao())
    val images = RoomImageAnnotationRepository(db, db.imageAnnotationDao())
    val bookmarks = RoomBookmarkRepository(db, db.bookmarkDao())
    val mathSources = RoomMathSourceRepository(db, db.mathSourceDao())
    val documentPreferences = RoomDocumentPreferenceRepository(db, db.documentPreferenceDao())

    /** 頁操作的跨表收斂：insert/delete/move 一個動作套 5 張表，不用在呼叫端重複寫。 */
    val pageOps: PageOps = RoomPageOps(strokes, texts, images, bookmarks, mathSources)

    /** 跨 repository 的單一交易邊界。可重入。 */
    suspend fun <T> transaction(block: suspend InkFlowRepositories.() -> T): T =
        db.withTransaction { block() }
}
