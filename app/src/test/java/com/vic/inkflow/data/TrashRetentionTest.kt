package com.vic.inkflow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 垃圾桶保留期算式的回歸網。
 *
 * 這是純函數（不碰 Android、不碰 Room），釘住三件事：
 *  - [TrashRetention.normalizedDays] 對非法／手改過的偏好值一律回退預設，
 *    不讓使用者把保留期設成「意外為零」；
 *  - [TrashRetention.cutoffMillis] 在「永不清除」時回傳 null（呼叫端據此不清）；
 *  - [TrashRetention.daysLeft] 天數用進位計算（剛過一天算剩 N 天，不是 N-1），
 *    而且永遠不會回負數。
 */
class TrashRetentionTest {

    @Test
    fun invalidChoiceFallsBackToDefault() {
        assertEquals(TrashRetention.DEFAULT_DAYS, TrashRetention.normalizedDays(13))
        assertEquals(TrashRetention.DEFAULT_DAYS, TrashRetention.normalizedDays(-1))
        assertEquals(TrashRetention.DEFAULT_DAYS, TrashRetention.normalizedDays(Int.MAX_VALUE))
    }

    @Test
    fun allChoicesRoundTrip() {
        for (choice in TrashRetention.CHOICE_DAYS) {
            assertEquals(choice, TrashRetention.normalizedDays(choice))
        }
    }

    @Test
    fun cutoffIsNullOnlyWhenRetentionIsForever() {
        val now = 1_000L * TrashRetention.DAY_MS
        assertNull(TrashRetention.cutoffMillis(now, 0))
        assertEquals(now - 7 * TrashRetention.DAY_MS, TrashRetention.cutoffMillis(now, 7))
        assertEquals(now - 15 * TrashRetention.DAY_MS, TrashRetention.cutoffMillis(now, 15))
        assertEquals(now - 30 * TrashRetention.DAY_MS, TrashRetention.cutoffMillis(now, 30))
        // 非法值 → 預設 30 天，不會不小心變成「永不清除」
        assertEquals(now - 30 * TrashRetention.DAY_MS, TrashRetention.cutoffMillis(now, 999))
    }

    @Test
    fun daysLeftFloorsAndNeverGoesNegative() {
        val now = 100L * TrashRetention.DAY_MS
        val retention = 30
        // 刪除時間就是 cutoff 那一刻 → 0 天（今天到期）
        assertEquals(0, TrashRetention.daysLeft(now - retention * TrashRetention.DAY_MS, now, retention))
        // 超過一點點 → 也是 0，不該出現負數
        assertEquals(0, TrashRetention.daysLeft(now - retention * TrashRetention.DAY_MS - 1, now, retention))
        // 剛好還有一天（floor，不多報一天）
        assertEquals(1, TrashRetention.daysLeft(now - (retention - 1) * TrashRetention.DAY_MS, now, retention))
        // 差 1ms 滿一天 → 算 0 天，寧可早一天催促也不晚
        assertEquals(0, TrashRetention.daysLeft(now - retention * TrashRetention.DAY_MS + 1, now, retention))
        // 今天剛刪 → 30 天
        assertEquals(30, TrashRetention.daysLeft(now, now, retention))
        // 永不清除 → null，UI 據此顯示「不會自動清除」
        assertNull(TrashRetention.daysLeft(now, now, 0))
    }
}
