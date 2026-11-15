package com.vic.inkflow.util

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import com.vic.inkflow.data.AppDatabase
import com.vic.inkflow.data.Point
import com.vic.inkflow.data.StrokeSamplesCodec
import com.vic.inkflow.data.StrokeSamplesEntity
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v34 點位搬遷的背景搬運工：把舊 `points` 表（一點一 row）逐筆打包進 `stroke_samples` BLOB。
 *
 * 為什麼不在 migration 裡做（2026-11-15 實測）：
 * 這份 DB 有 154 萬個點、55,413 筆。放在 migration 裡＝**開檔凍結**：
 * - 逐筆查詢寫法：18.5 秒
 * - 單次掃描寫法：39 秒（Android CursorWindow 分頁 + 4.4 萬次 insert）
 * 兩種都試過、都量過。使用者對「等」的容忍度是零，所以改成背景分批：
 * 開檔只建表（毫秒級），搬完之前讀取走舊表 fallback（見 RoomStrokeRepository.assemble）。
 *
 * 設計：
 * - **冪等**：只挑「還沒有 BLOB 的筆」，重跑（包含被殺掉後重來）不會覆蓋新資料。
 * - **可中斷**：每批 200 筆一個交易，`yield()` 讓出主執行緒／IO 佇列。
 * - **有界**：一次只有 200 筆的點在記憶體裡。
 * - 0 點的筆（墓碑）也寫一列空 BLOB，否則它會永遠留在待辦清單裡。
 * - 單一實例（AtomicBoolean）：重複呼叫直接返回。
 */
object StrokeSamplesBackfill {

    private const val TAG = "StrokeSamplesBackfill"
    private const val BATCH = 500
    private val running = AtomicBoolean(false)

    /** 必須在背景執行（MainActivity 以 `lifecycleScope.launch(Dispatchers.IO)` 呼叫）。 */
    suspend fun run(context: Context) {
        if (!running.compareAndSet(false, true)) return
        val startedAt = SystemClock.elapsedRealtime()
        var total = 0
        try {
            val db = AppDatabase.getDatabase(context)
            val dao = db.strokeDao()
            while (true) {
                val ids = dao.strokeIdsMissingSamples(BATCH)
                if (ids.isEmpty()) break
                db.withTransaction {
                    for (id in ids) {
                        val rows = dao.getLegacyPointRows(id)
                        val pts = if (rows.isEmpty()) {
                            emptyList()
                        } else {
                            rows.mapIndexed { i, r ->
                                Point(id = i.toLong(), strokeId = id, x = r.x, y = r.y, width = r.width)
                            }
                        }
                        dao.insertSamples(
                            listOf(
                                StrokeSamplesEntity(
                                    strokeId = id,
                                    count = pts.size,
                                    data = StrokeSamplesCodec.pack(pts)
                                )
                            )
                        )
                    }
                }
                total += ids.size
                // 讓出：搬遷不該跟使用者的滑動／渲染搶 IO。
                yield()
            }
            Log.i(TAG, "done: $total strokes in ${SystemClock.elapsedRealtime() - startedAt}ms")
        } catch (t: Throwable) {
            // 搬不完不是致命錯誤：讀取有 fallback，下次啟動會續搬。
            Log.e(TAG, "failed after $total strokes", t)
        } finally {
            running.set(false)
        }
    }
}
