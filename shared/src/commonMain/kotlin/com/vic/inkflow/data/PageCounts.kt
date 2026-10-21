package com.vic.inkflow.data

/**
 * Per-page counts and merge versions — the small rows the sync arbitration and the
 * `page_counts` verb need.
 *
 * ## Why they moved to the shared module
 *
 * They are pure data with no Room annotations, and they are part of the *contract*
 * (the tablet's per-page counts are compared against the desktop's, and the merge
 * version rows are what the arbiter ranks). A contract that lives in one app's
 * source set is a contract the other app cannot use — which is how the two ends
 * ended up with two different shapes of "a version row" in the first place.
 *
 * They keep their field names because these rows are also what the sync payloads
 * carry (`page_counts` is a `List<PageCountEntry>` on the wire — see
 * `com.vic.inkflow.sync.PageCountEntry`, which is the shared model counterpart of
 * [PageStrokeCount] / [PageTextCount]).
 */

/**
 * One row of a per-page `COUNT(*) ... GROUP BY pageIndex` query.
 *
 * Room maps these by column name, so the queries must alias to exactly
 * `pageIndex` plus the count column below. Used by the `page_counts` sync verb:
 * comparing counts per page is what lets a sync pull only the pages that changed
 * instead of the whole document.
 */
data class PageStrokeCount(
    val pageIndex: Int,
    val strokeCount: Int
)

/**
 * Text-annotation counterpart of [PageStrokeCount], for the same verb.
 */
data class PageTextCount(
    val pageIndex: Int,
    val textCount: Int
)

/**
 * One object's merge version (v6 §15.1): the exact triple the arbiter compares.
 *
 * `deletedAt` is deliberately absent: a tombstone is not a competing write to be
 * ranked, it is an absence to be reconciled (modify-wins, §15.4), so arbitration
 * reads the version of the live row plus a separate liveness query.
 */
data class StrokeVersion(
    val version: Int,
    val versionNonce: Int,
    /** 墓碑時間戳；null = 活著。刪除仲裁需要（§15.4）。 */
    val deletedAt: Long? = null
)

/** [StrokeVersion] 加上 id：文件級一次查詢需要知道每個版本屬於哪一筆。 */
data class StrokeVersionRow(
    val id: String,
    val version: Int,
    val versionNonce: Int,
    val deletedAt: Long? = null
)
