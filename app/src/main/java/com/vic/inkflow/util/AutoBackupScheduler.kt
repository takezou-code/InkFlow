package com.vic.inkflow.util

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 自動備份排程器。政策：每天一次、只留 [MAX_KEEP] 份、沒變更不寫檔。
 * 檔案數恆 ≤ 3，總量恆 ≤ 3 倍工作集，不會氾濫。
 */
object AutoBackupScheduler {
    const val PERIODIC_WORK_NAME = "inkflow_auto_backup_daily"
    const val ONCE_WORK_NAME = "inkflow_auto_backup_once"
    const val DIR_NAME = "auto_backups"
    const val MAX_KEEP = 3
    const val MIN_FREE_BYTES = 200L * 1024 * 1024

    const val KEY_ENABLED = "auto_backup_enabled"
    const val KEY_LAST_RUN_MS = "auto_backup_last_run_ms"
    const val KEY_LAST_STATUS = "auto_backup_last_status"
    const val KEY_LAST_FP = "auto_backup_last_fingerprint"

    private fun constraints() = Constraints.Builder()
        .setRequiresStorageNotLow(true)
        .setRequiresBatteryNotLow(true)
        .build()

    /** App 啟動時呼叫；KEEP 政策保證不會重複排程。 */
    fun ensureScheduled(context: Context) {
        if (!isEnabled(context)) return
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints())
            .addTag(PERIODIC_WORK_NAME)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        BackupManager.backupPrefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) ensureScheduled(context)
        else WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
    }

    fun isEnabled(context: Context): Boolean =
        BackupManager.backupPrefs(context).getBoolean(KEY_ENABLED, true)

    /** 設定頁「立即備份」按鈕用。 */
    fun runOnce(context: Context): UUID {
        val request = OneTimeWorkRequestBuilder<AutoBackupWorker>()
            .setConstraints(constraints())
            .addTag(ONCE_WORK_NAME)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONCE_WORK_NAME, ExistingWorkPolicy.APPEND, request
        )
        return request.id
    }

    fun autoBackupDir(context: Context): File =
        File(BackupManager.workingDir(context), DIR_NAME).also { it.mkdirs() }

    /** 由新到舊排列。 */
    fun listBackups(context: Context): List<File> =
        autoBackupDir(context).listFiles { f -> f.isFile && f.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** 刪除超過上限的最舊檔案。傳回刪掉的數量。 */
    fun pruneOldBackups(context: Context): Int {
        val files = listBackups(context)
        if (files.size <= MAX_KEEP) return 0
        var deleted = 0
        files.drop(MAX_KEEP).forEach { if (it.delete()) deleted++ }
        return deleted
    }

    fun describeBackups(context: Context): String {
        val files = listBackups(context)
        val mb = files.sumOf { it.length() } / 1024.0 / 1024.0
        return "保留${files.size}/$MAX_KEEP 份（共${String.format("%.1f", mb)}MB）"
    }
}
