package com.vic.inkflow.data

import com.vic.inkflow.data.store.StrokeStore
import com.vic.inkflow.data.toModel
import com.vic.inkflow.data.toRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * The desktop's stroke store — the **mirror** side.
 *
 * ## What a mirror does and does not have
 *
 * The desktop receives the tablet's decisions; it does not arbitrate. So this
 * implements only [StrokeStore] — the read/write half of the contract — and
 * deliberately does **not** implement the arbitration or the authoring halves:
 *
 *  - no version bumping: the tablet owns the versions,
 *  - no tombstones: the tablet owns the deletes,
 *  - no page moves: the tablet moves pages and tells us the result.
 *
 * That used to be an accident of the interface's shape ("the mirror implements
 * what it can and throws for the rest"). It is now an explicit statement in the
 * contract, so the shared editor core can ask "who owns this?" instead of
 * discovering it at run time.
 *
 * ## The rows stay the desktop's
 *
 * The desktop's tables are its own (JDBC over SQLite, with a schema the desktop
 * owns — see `DatabaseManager`). This class is the *adapter*: the shared model on
 * the way in and out, the desktop's rows inside.
 */
class DesktopStrokeStore(
    private val db: DatabaseManager
) : StrokeStore {

    override suspend fun insertStroke(stroke: Stroke) {
        db.saveStroke(stroke.toRow(), emptyList())
    }

    override suspend fun saveStrokeWithPoints(stroke: Stroke, points: List<Point>) =
        saveStrokeWithPointsNow(stroke, points)

    /**
     * The same write, without the coroutine.
     *
     * The desktop's sync runs on plain threads (the socket streams are blocking),
     * so the ingest path cannot suspend. The contract is `suspend` because the
     * other platforms' stores may be genuinely asynchronous; the desktop's are not,
     * so it offers both and the thread-bound code calls the plain one.
     */
    fun saveStrokeWithPointsNow(stroke: Stroke, points: List<Point>) {
        db.saveStroke(stroke.toRow(), points.map { it.toRow() })
    }

    override fun getStrokesForPage(documentUri: String, pageIndex: Int): Flow<List<StrokeWithSamples>> =
        flow {
            // The desktop's store is not reactive: it is written by the sync
            // server, which tells the UI to reload. A flow that re-reads on every
            // collection is the honest shape for that (and it keeps the shared
            // contract free of "how does this platform signal changes?").
            emit(db.getStrokesForPage(documentUri, pageIndex).map { it.toModel() })
        }

    override suspend fun getStrokesForPageSync(documentUri: String, pageIndex: Int): List<StrokeWithSamples> =
        db.getStrokesForPage(documentUri, pageIndex).map { it.toModel() }

    override suspend fun getAllStrokesForDocument(documentUri: String): List<StrokeWithSamples> =
        db.getAllStrokesForDocument(documentUri).map { it.toModel() }

    override suspend fun countStrokesByPage(documentUri: String): List<PageStrokeCount> =
        db.countStrokesByPage(documentUri).map { (page, count) -> PageStrokeCount(page, count) }

    override suspend fun deleteStrokesByIds(strokeIds: List<String>): Int {
        var deleted = 0
        for (id in strokeIds) {
            db.deleteStroke(id)
            deleted++
        }
        return deleted
    }

    override suspend fun deleteStrokesForDocument(documentUri: String) {
        db.deleteStrokesForDocument(documentUri)
    }

    override suspend fun deletePointsForStroke(strokeId: String) {
        db.deleteStroke(strokeId)
    }

    override suspend fun replaceStrokeSnapshots(snapshots: List<StrokeWithSamples>) =
        replaceStrokeSnapshotsNow(snapshots)

    /** The same write, without the coroutine — see [saveStrokeWithPointsNow]. */
    fun replaceStrokeSnapshotsNow(snapshots: List<StrokeWithSamples>) {
        val documentUri = snapshots.firstOrNull()?.stroke?.documentUri ?: return
        db.replaceStrokesForDocument(documentUri, snapshots.map { it.toRow() })
    }
}
