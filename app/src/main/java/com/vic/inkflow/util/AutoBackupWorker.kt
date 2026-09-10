package com.vic.inkflow.util

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 每日自動備份。防氾濫三道閘：
 *  1. 指紋比對 — 資料沒變就不寫新檔（排除 auto_backups 目錄自身，避免自己觸發自己）
 *  2. 數量上限 — 只留 [AutoBackupScheduler.MAX_KEEP] 份，多的刪最舊
 *  3. 空間不足 — 可用空間 < 200MB 直接跳過，不寫半殘檔
 */
class AutoBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = BackupManager.backupPrefs(ctx)
        if (!prefs.getBoolean(AutoBackupScheduler.KEY_ENABLED, true)) {
            return Result.success()
        }
        return try {
            val fingerprint = computeFingerprint(ctx)
            if (fingerprint == prefs.getString(AutoBackupScheduler.KEY_LAST_FP, null)) {
                finish(prefs, true, "資料無變更，已跳過（${AutoBackupScheduler.describeBackups(ctx)}）", fingerprint)
                return Result.success()
            }

            val dir = AutoBackupScheduler.autoBackupDir(ctx)
            if (dir.usableSpace < AutoBackupScheduler.MIN_FREE_BYTES) {
                finish(prefs, false, "空間不足，已跳過自動備份", fingerprint = null)
                return Result.success()
            }

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val dest = File(dir, "auto_${stamp}.zip")
            val result = BackupManager.createBackup(ctx, Uri.fromFile(dest)) {}
            result.fold(
                onSuccess = { count ->
                    AutoBackupScheduler.pruneOldBackups(ctx)
                    finish(
                        prefs, true,
                        "備份完成（$count 份文件）·${AutoBackupScheduler.describeBackups(ctx)}",
                        fingerprint
                    )
                },
                onFailure = { e ->
                    dest.delete()
                    finish(prefs, false, "自動備份失敗：${e.message}", fingerprint = null)
                }
            )
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "AutoBackupWorker failed", e)
            prefs.edit()
                .putLong(AutoBackupScheduler.KEY_LAST_RUN_MS, System.currentTimeMillis())
                .putString(AutoBackupScheduler.KEY_LAST_STATUS, "自動備份異常：${e.message}")
                .apply()
            Result.failure()
        }
    }

    private fun finish(
        prefs: android.content.SharedPreferences,
        ok: Boolean,
        status: String,
        fingerprint: String?
    ) {
        prefs.edit()
            .putLong(AutoBackupScheduler.KEY_LAST_RUN_MS, System.currentTimeMillis())
            .putString(AutoBackupScheduler.KEY_LAST_STATUS, (if (ok) "✓ " else "✗ ") + status)
            .apply {
                if (fingerprint != null) putString(AutoBackupScheduler.KEY_LAST_FP, fingerprint)
            }
            .apply()
        Log.i(TAG, status)
    }

    companion object {
        private const val TAG = "AutoBackupWorker"

        /** 資料指紋：DB 時間+大小、各 PDF/圖片時間+總量；不含 auto_backups 目錄。 */
        fun computeFingerprint(context: Context): String {
            val dbFile = context.getDatabasePath("ink_layer_database")
            val workDir = BackupManager.workingDir(context)
            var maxModified = if (dbFile.exists()) dbFile.lastModified() else 0L
            var totalSize = if (dbFile.exists()) dbFile.length() else 0L
            var fileCount = 0
            workDir.listFiles()?.forEach { f ->
                if (f.name == AutoBackupScheduler.DIR_NAME) return@forEach
                if (f.isFile) {
                    fileCount++
                    totalSize += f.length()
                    if (f.lastModified() > maxModified) maxModified = f.lastModified()
                }
            }
            return "$maxModified:$totalSize:$fileCount"
        }
    }
}
