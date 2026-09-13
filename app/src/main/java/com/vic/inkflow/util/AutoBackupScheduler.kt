package com.vic.inkflow.util

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 自動備份排程器。雙軌制：
 *  - 私有（App 內 auto_backups）：只留 [PRIVATE_KEEP] 份，每 6 小時跑一次，保當下進度
 *  - 公開（文件/InkFlow）：留 [PUBLIC_KEEP] 份，每天一次，負責換機遷移
 * 沒變更不寫檔；公開區輪替只砍 auto_*，手動匯出的包永遠保留。
 */
object AutoBackupScheduler {
    const val TARGET_PRIVATE = "private"
    const val TARGET_PUBLIC = "public"
    const val KEY_TARGET = "backup_target"
    const val KEY_FORCE = "backup_force"

    const val PRIVATE_WORK_NAME = "inkflow_auto_backup_private"
    const val PUBLIC_WORK_NAME = "inkflow_auto_backup_public"
    const val ONCE_WORK_NAME = "inkflow_auto_backup_once"
    const val ONCE_PUBLIC_WORK_NAME = "inkflow_auto_backup_once_public"
    const val DIR_NAME = "auto_backups"
    const val PRIVATE_KEEP = 1
    const val PUBLIC_KEEP = 2
    const val MIN_FREE_BYTES = 200L * 1024 * 1024

    const val KEY_ENABLED = "auto_backup_enabled"

    data class BackupItem(val source: String, val file: File)

    private fun constraints() = Constraints.Builder()
        .setRequiresStorageNotLow(true)
        .setRequiresBatteryNotLow(true)
        .build()

    /** App 啟動時呼叫；KEEP 政策保證不會重複排程。 */
    fun ensureScheduled(context: Context) {
        if (!isEnabled(context)) return
        val wm = WorkManager.getInstance(context)
        val privateReq = PeriodicWorkRequestBuilder<AutoBackupWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints())
            .setInputData(workDataOf(KEY_TARGET to TARGET_PRIVATE))
            .addTag(PRIVATE_WORK_NAME)
            .build()
        wm.enqueueUniquePeriodicWork(
            PRIVATE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, privateReq
        )
        val publicReq = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints())
            .setInputData(workDataOf(KEY_TARGET to TARGET_PUBLIC))
            .addTag(PUBLIC_WORK_NAME)
            .build()
        wm.enqueueUniquePeriodicWork(
            PUBLIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, publicReq
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        BackupManager.backupPrefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        val wm = WorkManager.getInstance(context)
        if (enabled) ensureScheduled(context)
        else {
            wm.cancelUniqueWork(PRIVATE_WORK_NAME)
            wm.cancelUniqueWork(PUBLIC_WORK_NAME)
        }
    }

    fun isEnabled(context: Context): Boolean =
        BackupManager.backupPrefs(context).getBoolean(KEY_ENABLED, true)

    /** 設定頁「立即備份」按鈕用：私有＋公開各跑一次，且一定寫檔（不受指紋跳過限制）。 */
    fun runOnce(context: Context) {
        val wm = WorkManager.getInstance(context)
        val privReq = OneTimeWorkRequestBuilder<AutoBackupWorker>()
            .setConstraints(constraints())
            .setInputData(workDataOf(KEY_TARGET to TARGET_PRIVATE, KEY_FORCE to true))
            .addTag(ONCE_WORK_NAME)
            .build()
        wm.enqueueUniqueWork(ONCE_WORK_NAME, ExistingWorkPolicy.REPLACE, privReq)
        val pubReq = OneTimeWorkRequestBuilder<AutoBackupWorker>()
            .setConstraints(constraints())
            .setInputData(workDataOf(KEY_TARGET to TARGET_PUBLIC, KEY_FORCE to true))
            .addTag(ONCE_PUBLIC_WORK_NAME)
            .build()
        wm.enqueueUniqueWork(ONCE_PUBLIC_WORK_NAME, ExistingWorkPolicy.REPLACE, pubReq)
    }

    fun autoBackupDir(context: Context): File =
        File(BackupManager.workingDir(context), DIR_NAME).also { it.mkdirs() }

    fun keepFor(target: String): Int =
        if (target == TARGET_PUBLIC) PUBLIC_KEEP else PRIVATE_KEEP

    fun lastRunKey(target: String) = "auto_backup_last_run_ms_$target"
    fun lastStatusKey(target: String) = "auto_backup_last_status_$target"
    fun lastFpKey(target: String) = "auto_backup_last_fingerprint_$target"

    private fun listZips(dir: File): List<File> =
        runCatching {
            dir.listFiles { f -> f.isFile && f.name.endsWith(".zip") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        }.getOrDefault(emptyList())

    /** 由新到舊排列。 */
    fun listBackups(context: Context): List<File> = listZips(autoBackupDir(context))

    /** 公開區（讀不到就當作沒有，不報錯）。 */
    fun listPublicBackups(context: Context): List<File> =
        listZips(BackupManager.publicBackupDir())

    /** 兩邊合併、由新到舊，附來源標籤。 */
    fun listAllBackups(context: Context): List<BackupItem> =
        (listBackups(context).map { BackupItem("自動", it) } +
            listPublicBackups(context).map { BackupItem("公開", it) })
            .sortedByDescending { it.file.lastModified() }

    /** 刪除超過上限的最舊檔案（只砍 auto_*，手動匯出的不動）。傳回刪掉的數量。 */
    fun pruneOldBackups(context: Context, target: String): Int {
        val dir = if (target == TARGET_PUBLIC) BackupManager.publicBackupDir()
        else autoBackupDir(context)
        val files = listZips(dir).filter { it.name.startsWith("auto_") }
        val keep = keepFor(target)
        if (files.size <= keep) return 0
        var deleted = 0
        files.drop(keep).forEach { if (it.delete()) deleted++ }
        return deleted
    }

    fun describeBackups(context: Context, target: String): String {
        val files = if (target == TARGET_PUBLIC) listPublicBackups(context) else listBackups(context)
        val mb = files.sumOf { it.length() } / 1024.0 / 1024.0
        val keep = keepFor(target)
        return "保留${files.size}/$keep 份（共${String.format("%.1f", mb)}MB）"
    }
}
