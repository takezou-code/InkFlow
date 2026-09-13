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
 * 自動備份（雙軌：private 每 6 小時留 1 份 / public 每天留 2 份）。防氾濫三道閘：
 *  1. 指紋比對 — 資料沒變就不寫新檔（排除 auto_backups 目錄自身，避免自己觸發自己）
 *  2. 數量上限 — 超過上限刪最舊；公開區只砍 auto_*，手動匯出的不動
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
        val target = inputData.getString(
            AutoBackupScheduler.KEY_TARGET
        ) ?: AutoBackupScheduler.TARGET_PRIVATE
        val runKey = AutoBackupScheduler.lastRunKey(target)
        val statusKey = AutoBackupScheduler.lastStatusKey(target)
        val fpKey = AutoBackupScheduler.lastFpKey(target)
        return try {
            val force = inputData.getBoolean(AutoBackupScheduler.KEY_FORCE, false)
            val fingerprint = computeFingerprint(ctx)
            if (!force && fingerprint == prefs.getString(fpKey, null)) {
                finish(prefs, runKey, statusKey, true,
                    "資料無變更，已跳過（${AutoBackupScheduler.describeBackups(ctx, target)}）", fingerprint, fpKey)
                return Result.success()
            }

            val dir = if (target == AutoBackupScheduler.TARGET_PUBLIC) BackupManager.publicBackupDir()
            else AutoBackupScheduler.autoBackupDir(ctx)
            dir.mkdirs()
            if (dir.usableSpace < AutoBackupScheduler.MIN_FREE_BYTES) {
                finish(prefs, runKey, statusKey, false, "空間不足，已跳過自動備份", null, fpKey)
                return Result.success()
            }

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val dest = File(dir, "auto_${stamp}.zip")
            val result = BackupManager.createBackup(ctx, Uri.fromFile(dest)) {}
            result.fold(
                onSuccess = { count ->
                    AutoBackupScheduler.pruneOldBackups(ctx, target)
                    finish(
                        prefs, runKey, statusKey, true,
                        "備份完成（$count 份文件）·${AutoBackupScheduler.describeBackups(ctx, target)}",
                        fingerprint, fpKey
                    )
                },
                onFailure = { e ->
                    dest.delete()
                    finish(prefs, runKey, statusKey, false, "自動備份失敗：${e.message}", null, fpKey)
                }
            )
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "AutoBackupWorker failed", e)
            prefs.edit()
                .putLong(runKey, System.currentTimeMillis())
                .putString(statusKey, "自動備份異常：${e.message}")
                .apply()
            Result.failure()
        }
    }

    private fun finish(
        prefs: android.content.SharedPreferences,
        runKey: String,
        statusKey: String,
        ok: Boolean,
        status: String,
        fingerprint: String?,
        fpKey: String
    ) {
        prefs.edit()
            .putLong(runKey, System.currentTimeMillis())
            .putString(statusKey, (if (ok) "✓ " else "✗ ") + status)
            .apply {
                if (fingerprint != null) putString(fpKey, fingerprint)
            }
            .apply()
        Log.i(TAG, "[$runKey] $status")
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
