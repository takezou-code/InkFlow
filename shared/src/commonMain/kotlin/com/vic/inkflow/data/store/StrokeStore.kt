package com.vic.inkflow.data.store

import com.vic.inkflow.data.PageStrokeCount
import com.vic.inkflow.data.Point
import com.vic.inkflow.data.Stroke
import com.vic.inkflow.data.StrokeWithSamples
import kotlinx.coroutines.flow.Flow

/**
 * Storing strokes — the part **every** platform can do.
 *
 * ## Why this is split from the author-only halves
 *
 * The tablet is the **author**: it owns the document, it arbitrates concurrent
 * edits, it keeps tombstones and version numbers, it moves pages.
 *
 * The desktop is a **mirror**: it shows what the tablet has, and it proposes
 * changes. A mirror has no version columns to bump, no tombstones to write, and
 * never moves a page on its own — it receives the tablet's decisions.
 *
 * So the contract is three interfaces:
 *  - [StrokeStore] — read/write the strokes. **Both** platforms implement it.
 *  - [StrokeArbitration] — version/tombstone bookkeeping. The author only.
 *  - [StrokeAuthoring] — the structural edits (page moves, the docY backfill).
 *    The author only.
 *
 * An interface that demands `bumpStrokeVersion` from a mirror is one the mirror
 * can only half-implement, and a half-implemented interface is where "throws
 * UnsupportedOperationException" quietly becomes "nobody ever calls this on the
 * desktop" — which is fine until the shared editor core *does* call it.
 */
interface StrokeStore {

    suspend fun insertStroke(stroke: Stroke)

    /** 筆＋點一起存。呼叫端常常忘記點，所以有這個。 */
    suspend fun saveStrokeWithPoints(stroke: Stroke, points: List<Point>)

    fun getStrokesForPage(documentUri: String, pageIndex: Int): Flow<List<StrokeWithSamples>>
    suspend fun getStrokesForPageSync(documentUri: String, pageIndex: Int): List<StrokeWithSamples>
    suspend fun getAllStrokesForDocument(documentUri: String): List<StrokeWithSamples>

    /**
     * Per-page stroke counts, for the `page_counts` sync verb. A GROUP BY query —
     * not a full load — because the verb exists precisely to avoid loading the
     * whole document on large files.
     */
    suspend fun countStrokesByPage(documentUri: String): List<PageStrokeCount>

    suspend fun deleteStrokesByIds(strokeIds: List<String>): Int
    suspend fun deleteStrokesForDocument(documentUri: String)
    suspend fun deletePointsForStroke(strokeId: String)

    /**
     * 快照整批覆蓋（移動／縮放的寫入漏斗）。
     * 用這個，不要自己拼 deletePointsForStroke + insertStroke + insertPoints。
     * 見實作的註解：覆蓋會把序號蓋回舊值，必須在同一交易里 bump。
     */
    suspend fun replaceStrokeSnapshots(snapshots: List<StrokeWithSamples>)
}
