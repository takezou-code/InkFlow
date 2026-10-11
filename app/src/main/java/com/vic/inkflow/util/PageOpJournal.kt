package com.vic.inkflow.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.vic.inkflow.data.AppDatabase
import com.vic.inkflow.data.repository.InkFlowRepositories
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Crash-safety journal for destructive page operations (insert / delete / move).
 *
 * Protocol per operation:
 *   1. copy the target PDF into a backup file
 *   2. write entry with stage="prepared"
 *   3. mutate the PDF file (atomic rename inside PdfManager)
 *   4. rewrite entry with stage="file_done"
 *   5. apply the DB transaction
 *   6. clear journal + delete backup
 *
 * If the process dies between 3 and 5 the journal survives; on next launch
 * [reconcilePending] replays the DB shift so annotations stay aligned with pages.
 * If it dies before 3, the file count is unchanged and the journal is simply cleared.
 */
object PageOpJournal {
    private const val TAG = "PageOpJournal"
    private const val FILE_NAME = "pending_page_op.json"
    private const val BACKUP_DIR = "page_op_backups"
    private const val BACKUP_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    data class Entry(
        val op: String,
        val documentUri: String,
        val indices: List<Int>,
        val count: Int,
        val toIndex: Int,
        val pageCountBefore: Int,
        var stage: String
    )

    private fun journalFile(context: Context): File = File(context.filesDir, FILE_NAME)
    private fun backupDir(context: Context): File =
        File(context.filesDir, BACKUP_DIR).also { it.mkdirs() }

    fun write(context: Context, entry: Entry) {
        runCatching {
            val json = JSONObject()
                .put("op", entry.op)
                .put("documentUri", entry.documentUri)
                .put("indices", JSONArray(entry.indices))
                .put("count", entry.count)
                .put("toIndex", entry.toIndex)
                .put("pageCountBefore", entry.pageCountBefore)
                .put("stage", entry.stage)
            journalFile(context).writeText(json.toString())
        }.onFailure { Log.e(TAG, "write failed", it) }
    }

    fun markFileDone(context: Context, entry: Entry) {
        entry.stage = "file_done"
        write(context, entry)
    }

    fun read(context: Context): Entry? = runCatching {
        val f = journalFile(context)
        if (!f.exists()) return null
        val json = JSONObject(f.readText())
        Entry(
            op = json.getString("op"),
            documentUri = json.getString("documentUri"),
            indices = json.optJSONArray("indices")?.let { arr -> (0 until arr.length()).map { arr.getInt(it) } } ?: emptyList(),
            count = json.optInt("count", 1),
            toIndex = json.optInt("toIndex", -1),
            pageCountBefore = json.optInt("pageCountBefore", -1),
            stage = json.optString("stage", "prepared")
        )
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { journalFile(context).delete() }
    }

    /** Copies [source] into the backup dir; returns null on failure. */
    fun backupFile(context: Context, source: File): File? = runCatching {
        val dest = File(backupDir(context), "${System.currentTimeMillis()}_${source.name}")
        source.copyTo(dest, overwrite = true)
        dest
    }.getOrNull()

    /** Restores [backup] over [target]. Returns true when the target is guaranteed intact. */
    fun restoreBackup(context: Context, backup: File, target: File): Boolean = runCatching {
        if (!backup.exists()) return true
        val tmp = File(target.parent, "${target.nameWithoutExtension}.restore_${System.currentTimeMillis()}.pdf")
        backup.copyTo(tmp, overwrite = true)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        true
    }.getOrElse { Log.e(TAG, "restoreBackup failed", it); false }

    fun deleteBackup(context: Context, backup: File) {
        runCatching { backup.delete() }
    }

    /**
     * P0：把重放丟到 app-lifetime scope 跑，呼叫端不用同步等。
     *
     * 改前 AppDatabase.getDatabase()（Application 初始化）裡是 runBlocking 等
     * 「讀 PDF 頁數 ＋ 五張表交易」跑完——大 PDF 就是一次同步磁碟 I/O 加 Room
     * 交易，ANR 風險。scope 放在這裡而不是 AppDatabase，是為了讓 AppDatabase
     * 不需要引用 coroutine 型別（那會和 Room/KSP 形成跨檔型別循環）。
     */
    /** app-lifetime scope：SupervisorJob 讓重放失敗不拖垮其他任務。 */
    private val reconcileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun scheduleReconcile(context: Context, db: AppDatabase) {
        reconcileScope.launch(Dispatchers.IO) {
            runCatching { reconcilePending(context, db) }
                .onFailure { Log.e(TAG, "journal reconcile failed", it) }
            // 墓碑回收（§15.4）：跟在崩潰重放之後，同一個 app-lifetime scope、同樣不擋開檔。
            //
            // 為什麼放在啟動而不是每次刪除：刪除是熱路徑（每擦一筆一次），回收是冷路徑
            // （一天可能只有零到一次），混在一起只會讓擦筆變慢。而且這裡已經有現成的
            // 「啟動時在背景掃一次、不阻擋使用者」的形狀，不必另開一套排程。
            runCatching { purgeExpiredTombstones(db) }
                .onFailure { Log.e(TAG, "tombstone purge failed", it) }
        }
    }

    /**
     * 墓碑保留期：30 天。
     *
     * 墓碑存在的唯一理由是「對端可能還沒看到這次刪除」。30 天遠長於 28 秒一輪的同步
     * 週期，涵蓋「桌面端整個禮拜沒開機」這種真實情況；又不至於讓資料庫無限長大
     * （每天擦一筆就是一天一列）。純粹是政策常數，不是技術限制——真要更保守就調大它。
     */
    private const val TOMBSTONE_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

    /** 回傳實際回收的列數；失敗不回擲（呼叫端用 runCatching 包住）。 */
    suspend fun purgeExpiredTombstones(db: AppDatabase): Int {
        val cutoff = System.currentTimeMillis() - TOMBSTONE_RETENTION_MS
        val repos = InkFlowRepositories(db)
        val purged = repos.transaction { repos.pageOps.purgeExpiredTombstones(cutoff) }
        if (purged > 0) Log.i(TAG, "purged $purged expired tombstone(s) older than $TOMBSTONE_RETENTION_MS ms")
        return purged
    }

    /**
     * Called once at app start (from AppDatabase init) to finish or roll back any
     * operation interrupted by process death.
     *
     * P0：改成 suspend。內部本來就全是 suspend（applyForwardLocked 尤其），
     * 所以只把外層形狀改掉即可。正常路徑請走 [scheduleReconcile]。
     */
    suspend fun reconcilePending(context: Context, db: AppDatabase) {
        val repos = InkFlowRepositories(db)
        val entry = read(context) ?: return
        Log.w(TAG, "Found pending page op from previous session: $entry")
        try {
            val uri = Uri.parse(entry.documentUri)
            if (uri.scheme == "file" && entry.pageCountBefore > 0) {
                val actual = PdfManager.getPdfPageCount(uri)
                if (actual > 0 && actual != entry.pageCountBefore) {
                    repos.transaction {
                        applyForwardLocked(repos, entry)
                        // S1 docY 同搬：重放只在崩潰窗口發生，用首頁高重算（讀不到就跳過，不更壞）。
                        PdfManager.readFirstPageSize(context, uri)?.second
                            ?.takeIf { it > 0f }?.let { stride ->
                                repos.pageOps.backfillAllDocY(entry.documentUri, stride)
                            }
                    }
                    Log.w(TAG, "Replayed DB shift for ${entry.op} on ${entry.documentUri}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "reconcilePending failed — manual page/annotation check may be needed", e)
        } finally {
            clear(context)
            cleanupOldBackups(context)
        }
    }

    /**
     * P2：跨 5 張表的頁操作收斂到 [PageOps]，這裡原本是 5 段逐字相同的程式碼
     * （insert/delete/move 各對 strokes/text/image/bookmark/math 各寫一遍）。
     * 語意與收斂前逐行等價。
     */
    private suspend fun applyForwardLocked(repos: InkFlowRepositories, entry: Entry) {
        when (entry.op) {
            "insert" -> {
                repos.pageOps.insertPagesAfter(entry.documentUri, entry.indices.first(), entry.count)
            }
            "delete" -> {
                // 從後往前刪，否則前面的刪除會移動後面的索引。
                for (index in entry.indices.sortedDescending()) {
                    repos.pageOps.deletePage(entry.documentUri, index)
                }
            }
            "move" -> {
                repos.pageOps.movePage(entry.documentUri, entry.indices.first(), entry.toIndex)
            }
        }
    }

    private fun cleanupOldBackups(context: Context) {
        runCatching {
            val cutoff = System.currentTimeMillis() - BACKUP_MAX_AGE_MS
            backupDir(context).listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        }
    }
}
