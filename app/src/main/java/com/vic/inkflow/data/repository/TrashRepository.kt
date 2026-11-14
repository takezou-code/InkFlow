package com.vic.inkflow.data.repository

import com.vic.inkflow.data.Document
import com.vic.inkflow.data.TrashedDocument
import kotlinx.coroutines.flow.Flow

/**
 * Local-only trash. Deliberately **not** part of the shared `DocumentRepository`:
 * the desktop has no trash UI and the sync protocol has no document-level ops,
 * so a shared contract would only invite two platforms to disagree.
 *
 * Lifecycle: [markDeleted] hides a document everywhere (library, sync manifest,
 * backup) without touching its file or annotations. [restore] brings it back.
 * Only [getExpired] + the purge path — which goes through `PageOps` and the
 * physical-file cleanup in `DocumentViewModel` — may truly delete anything.
 */
interface TrashRepository {

    fun getTrashed(): Flow<List<TrashedDocument>>

    /** Soft delete. Returns rows affected (0 = unknown URI). */
    suspend fun markDeleted(uri: String, timestamp: Long = System.currentTimeMillis()): Int

    /** Clears the trash flag. Returns rows affected. */
    suspend fun restore(uri: String): Int

    /** Documents whose `deletedAt` is older than [cutoff]. */
    suspend fun getExpired(cutoff: Long): List<Document>

    /** Hard-deletes the row only. Callers must purge annotations/files first. */
    suspend fun deleteRow(uri: String): Int
}
