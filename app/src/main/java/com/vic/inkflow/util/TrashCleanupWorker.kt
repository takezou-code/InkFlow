package com.vic.inkflow.util

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vic.inkflow.data.AppDatabase
import com.vic.inkflow.data.TrashPurger
import com.vic.inkflow.data.TrashRetention
import com.vic.inkflow.data.repository.InkFlowRepositories

/**
 * Purges trashed documents whose retention window has elapsed.
 *
 * A retention of 0 ("keep forever") short-circuits before touching the DB. Each
 * document is purged in its own `runCatching` so one broken file cannot stop the
 * rest of the cleanup; failures are logged and retried next day.
 */
class TrashCleanupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val cutoff = TrashRetention.cutoffMillis(
            System.currentTimeMillis(),
            TrashSettings.retentionDays(ctx)
        ) ?: return Result.success()

        val repos = InkFlowRepositories(AppDatabase.getDatabase(ctx))
        val expired = runCatching { repos.trash.getExpired(cutoff) }
            .onFailure { Log.e(TAG, "could not list expired trash", it) }
            .getOrDefault(emptyList())

        var purged = 0
        for (document in expired) {
            runCatching { TrashPurger.purge(ctx, repos, document.uri) }
                .onSuccess { purged++ }
                .onFailure { Log.e(TAG, "purge failed: ${document.uri}", it) }
        }
        Log.i(TAG, "trash cleanup: purged $purged/${expired.size}")
        return Result.success()
    }

    companion object {
        private const val TAG = "TrashCleanupWorker"
    }
}
