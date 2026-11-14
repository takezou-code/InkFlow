package com.vic.inkflow.util

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Daily trash cleanup. One periodic job for both retention values: the worker
 * re-reads [TrashSettings] each run, so changing 30 → 7 days needs no reschedule.
 *
 * Constraints mirror `AutoBackupScheduler`: don't run when storage or battery is
 * low — a purge can delete many PDFs at once.
 */
object TrashCleanupScheduler {
    const val WORK_NAME = "inkflow_trash_cleanup"
    const val ONCE_WORK_NAME = "inkflow_trash_cleanup_once"

    private fun constraints() = Constraints.Builder()
        .setRequiresStorageNotLow(true)
        .setRequiresBatteryNotLow(true)
        .build()

    /** KEEP policy: calling this on every launch must not reset the daily timer. */
    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<TrashCleanupWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** After the user shortens retention: expire the newly-overdue docs right away. */
    fun runOnce(context: Context) {
        val request = OneTimeWorkRequestBuilder<TrashCleanupWorker>()
            .setConstraints(constraints())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ONCE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}
