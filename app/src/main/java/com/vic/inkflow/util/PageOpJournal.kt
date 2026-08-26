package com.vic.inkflow.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.vic.inkflow.data.AppDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.runBlocking
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
     * Called once at app start (from AppDatabase init) to finish or roll back any
     * operation interrupted by process death.
     */
    fun reconcilePending(context: Context, db: AppDatabase) {
        val entry = read(context) ?: return
        Log.w(TAG, "Found pending page op from previous session: $entry")
        try {
            val uri = Uri.parse(entry.documentUri)
            if (uri.scheme == "file" && entry.pageCountBefore > 0) {
                runBlocking {
                    val actual = PdfManager.getPdfPageCount(uri)
                    if (actual > 0 && actual != entry.pageCountBefore) {
                        db.withTransaction {
                            applyForwardLocked(db, entry)
                        }
                        Log.w(TAG, "Replayed DB shift for ${entry.op} on ${entry.documentUri}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "reconcilePending failed — manual page/annotation check may be needed", e)
        } finally {
            clear(context)
            cleanupOldBackups(context)
        }
    }

    private suspend fun applyForwardLocked(db: AppDatabase, entry: Entry) {
        when (entry.op) {
            "insert" -> {
                val idx = entry.indices.first()
                db.strokeDao().shiftPageIndicesUp(entry.documentUri, idx, entry.count)
                db.textAnnotationDao().shiftPageIndicesUp(entry.documentUri, idx, entry.count)
                db.imageAnnotationDao().shiftPageIndicesUp(entry.documentUri, idx, entry.count)
                db.bookmarkDao().shiftPageIndicesUp(entry.documentUri, idx, entry.count)
            }
            "delete" -> {
                for (index in entry.indices.sortedDescending()) {
                    with(db.strokeDao()) { clearPage(entry.documentUri, index); shiftPageIndicesDown(entry.documentUri, index) }
                    with(db.textAnnotationDao()) { deleteForPage(entry.documentUri, index); shiftPageIndicesDown(entry.documentUri, index) }
                    with(db.imageAnnotationDao()) { deleteForPage(entry.documentUri, index); shiftPageIndicesDown(entry.documentUri, index) }
                    with(db.bookmarkDao()) { deleteForPage(entry.documentUri, index); shiftPageIndicesDown(entry.documentUri, index) }
                }
            }
            "move" -> {
                val from = entry.indices.first()
                val to = entry.toIndex
                with(db.strokeDao()) { moveToTempIndex(entry.documentUri, from, -1); if (from < to) shiftForMoveDown(entry.documentUri, from, to) else shiftForMoveUp(entry.documentUri, from, to); moveToTempIndex(entry.documentUri, -1, to) }
                with(db.textAnnotationDao()) { moveToTempIndex(entry.documentUri, from, -1); if (from < to) shiftForMoveDown(entry.documentUri, from, to) else shiftForMoveUp(entry.documentUri, from, to); moveToTempIndex(entry.documentUri, -1, to) }
                with(db.imageAnnotationDao()) { moveToTempIndex(entry.documentUri, from, -1); if (from < to) shiftForMoveDown(entry.documentUri, from, to) else shiftForMoveUp(entry.documentUri, from, to); moveToTempIndex(entry.documentUri, -1, to) }
                with(db.bookmarkDao()) { moveToTempIndex(entry.documentUri, from, -1); if (from < to) shiftForMoveDown(entry.documentUri, from, to) else shiftForMoveUp(entry.documentUri, from, to); moveToTempIndex(entry.documentUri, -1, to) }
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
