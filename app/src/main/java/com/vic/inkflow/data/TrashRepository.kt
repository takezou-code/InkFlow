package com.vic.inkflow.data

import com.vic.inkflow.data.repository.TrashRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * A document sitting in the trash, plus when it got there.
 *
 * Kept in the app module (not `:shared`) on purpose: the trash is **local-only**.
 * The sync protocol has no document-level delete op, so the desktop never sees
 * this type and cannot delete a tablet document through it.
 */
data class TrashedDocument(
    val document: Document,
    val deletedAt: Long
)

/** Pure retention arithmetic, unit-tested without Android. */
object TrashRetention {
    const val DAY_MS = 24L * 60L * 60L * 1000L

    const val DEFAULT_DAYS = 30

    /** `0` means "keep forever until the user purges manually". */
    val CHOICE_DAYS = listOf(7, 15, 30, 0)

    fun normalizedDays(days: Int): Int = if (days in CHOICE_DAYS) days else DEFAULT_DAYS

    /** Cutoff for "deleted before this instant"; `null` when retention is off. */
    fun cutoffMillis(now: Long, days: Int): Long? {
        val d = normalizedDays(days)
        return if (d == 0) null else now - d * DAY_MS
    }

    /**
     * Whole days left before auto-purge; `null` when retention is off.
     *
     * Floored, not ceiled: "剩 29 天" must mean at least 29 full days remain.
     * Exactly at the cutoff reports 0 ("今天到期"), never a negative number.
     */
    fun daysLeft(deletedAt: Long, now: Long, days: Int): Int? {
        val cutoff = cutoffMillis(now, days) ?: return null
        val left = deletedAt - cutoff
        if (left <= 0L) return 0
        return (left / DAY_MS).toInt()
    }
}

class RoomTrashRepository(
    private val dao: DocumentDao
) : TrashRepository {

    override fun getTrashed(): Flow<List<TrashedDocument>> =
        dao.getTrashedDocuments().map { rows ->
            rows.mapNotNull { row ->
                row.deletedAt?.let { TrashedDocument(row.toModel(), it) }
            }
        }

    override suspend fun markDeleted(uri: String, timestamp: Long): Int =
        dao.markDeleted(uri, timestamp)

    override suspend fun restore(uri: String): Int = dao.restore(uri)

    override suspend fun getExpired(cutoff: Long): List<Document> =
        dao.getExpiredTrashed(cutoff).map { it.toModel() }

    override suspend fun deleteRow(uri: String): Int = dao.delete(uri)
}
