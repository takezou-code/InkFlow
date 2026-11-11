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
import java.util.concurrent.TimeUnit

/**
 * 自動備份排程器。現在只有公開軌：
 *  - 公開（文件/InkFlow）：每天一次，保留份數由使用者設定（預設 1 份），負責換機遷移
 *  - 私有軌已停用；舊版留下的 auto_backups 仍可列出／還原，但不再新增
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
    const val MIN_FREE_BYTES = 200L * 1024 * 1024

    const val KEY_ENABLED = "auto_backup_enabled"
    /** 公開備份保留份數（1～5）。舊版固定 2 份，現在預設 1 份。 */
    const val KEY_PUBLIC_KEEP = "auto_backup_keep_public"
    const val DEFAULT_PUBLIC_KEEP = 1
    val PUBLIC_KEEP_CHOICES = listOf(1, 2, 3, 5)
    /** 舊私有軌固定 1 份；只為讀取舊檔保留。 */
    private const val LEGACY_PRIVATE_KEEP = 1

    data class BackupItem(val source: String, val file: File)

    private fun constraints() = Constraints.Builder()
        .setRequiresStorageNotLow(true)
        .setRequiresBatteryNotLow(true)
        .build()

    /**
     * App 啟動時呼叫；KEEP 政策保證不會重複排程。
     * [quiet]＝靜模式：暫停排程（回靜時已 cancel 的，這裡直接不排回來）。
     * 每次都順手 cancel 舊私有軌，讓升級前排下的工作不再跑。
     */
    fun ensureScheduled(context: Context, quiet: Boolean = false) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(PRIVATE_WORK_NAME)
        if (!isEnabled(context) || quiet) return
        val publicReq = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints())
            .setInputData(workDataOf(KEY_TARGET to TARGET_PUBLIC))
            .addTag(PUBLIC_WORK_NAME)
            .build()
        wm.enqueueUniquePeriodicWork(
            PUBLIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, publicReq
        )
    }

    /**
     * 靜模式開關：進靜 cancel 排程（背景工作停省電），回流光 ensureScheduled 補回。
     * 與使用者自己的「自動備份」開關正交（那個由 [setEnabled] 管）。
     */
    fun setQuiet(context: Context, quiet: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (quiet) {
            wm.cancelUniqueWork(PRIVATE_WORK_NAME)
            wm.cancelUniqueWork(PUBLIC_WORK_NAME)
        } else {
            ensureScheduled(context)
        }
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

    /** 設定頁「立即備份」按鈕用：公開軌跑一次，且一定寫檔（不受指紋跳過限制）。 */
    fun runOnce(context: Context) {
        val wm = WorkManager.getInstance(context)
        val pubReq = OneTimeWorkRequestBuilder<AutoBackupWorker>()
            .setConstraints(constraints())
            .setInputData(workDataOf(KEY_TARGET to TARGET_PUBLIC, KEY_FORCE to true))
            .addTag(ONCE_PUBLIC_WORK_NAME)
            .build()
        wm.enqueueUniqueWork(ONCE_PUBLIC_WORK_NAME, ExistingWorkPolicy.REPLACE, pubReq)
        // 舊版可能還留著私有 one-time 工作，一併收掉。
        wm.cancelUniqueWork(ONCE_WORK_NAME)
    }

    fun autoBackupDir(context: Context): File =
        File(BackupManager.workingDir(context), DIR_NAME).also { it.mkdirs() }

    fun publicKeep(context: Context): Int =
        BackupManager.backupPrefs(context)
            .getInt(KEY_PUBLIC_KEEP, DEFAULT_PUBLIC_KEEP)
            .coerceIn(1, 5)

    /** 改保留份數後立刻輪替，讓「選 1 份」真的只剩 1 份。 */
    fun setPublicKeep(context: Context, keep: Int) {
        BackupManager.backupPrefs(context).edit()
            .putInt(KEY_PUBLIC_KEEP, keep.coerceIn(1, 5))
            .apply()
        pruneOldBackups(context, TARGET_PUBLIC)
    }

    fun keepFor(context: Context, target: String): Int =
        if (target == TARGET_PUBLIC) publicKeep(context) else LEGACY_PRIVATE_KEEP

    fun lastRunKey(target: String) = "auto_backup_last_run_ms_$target"
    fun lastStatusKey(target: String) = "auto_backup_last_status_$target"
    fun lastFpKey(target: String) = "auto_backup_last_fingerprint_$target"

    private fun listZips(dir: File): List<File> =
        runCatching {
            dir.listFiles { f -> f.isFile && f.name.endsWith(".zip") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        }.getOrDefault(emptyList())

    /** 舊私有軌（App 內 auto_backups）；升級後不再新增。 */
    fun listBackups(context: Context): List<File> = listZips(autoBackupDir(context))

    /** 公開區（讀不到就當作沒有，不報錯）。 */
    fun listPublicBackups(context: Context): List<File> =
        listZips(BackupManager.publicBackupDir())

    /** 兩邊合併、由新到舊，附來源標籤；私有標成「舊私有」。 */
    fun listAllBackups(context: Context): List<BackupItem> =
        (listPublicBackups(context).map { BackupItem("公開", it) } +
            listBackups(context).map { BackupItem("舊私有", it) })
            .sortedByDescending { it.file.lastModified() }

    /** 刪除超過上限的最舊自動備份（只砍 auto_*，手動匯出的不動）。傳回刪掉的數量。 */
    fun pruneOldBackups(context: Context, target: String): Int {
        val dir = if (target == TARGET_PUBLIC) BackupManager.publicBackupDir()
        else autoBackupDir(context)
        val files = listZips(dir).filter { it.name.startsWith("auto_") }
        val keep = keepFor(context, target)
        if (files.size <= keep) return 0
        var deleted = 0
        files.drop(keep).forEach { if (it.delete()) deleted++ }
        return deleted
    }

    /** 清掉舊私有軌留下的自動備份。回傳刪掉的份數。 */
    fun clearLegacyPrivateBackups(context: Context): Int {
        var deleted = 0
        listBackups(context)
            .filter { it.name.startsWith("auto_") }
            .forEach { if (it.delete()) deleted++ }
        return deleted
    }

    fun describeBackups(context: Context, target: String): String {
        val files = if (target == TARGET_PUBLIC) listPublicBackups(context) else listBackups(context)
        val mb = files.sumOf { it.length() } / 1024.0 / 1024.0
        val keep = keepFor(context, target)
        return "保留${files.size}/$keep 份（共${String.format("%.1f", mb)}MB）"
    }
}
