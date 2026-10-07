package com.vic.inkflow.data

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
