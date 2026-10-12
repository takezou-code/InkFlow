package com.vic.inkflow.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 書庫卡片入場錯落的回歸網。
 *
 * 背景：舊版每張卡各開 LaunchedEffect(delay)→visible=true＋AnimatedVisibility，
 * N 個延遲寫入在 measure/layout 途中觸發 composition，撞上 focus 搜尋的預組件
 * 就是 ComposeRuntimeError "pending composition has not been applied"
 *（dropbox 10/09 起 7 次同簽名＝主畫面崩潰）。修法是「一支預算＋純算式」，
 * 這裡釘住算式本身：進度單調、邊界正確、超上限截斷。
 */
class LibraryEntranceTest {

    @Test
    fun budgetZeroMeansNothingVisible() {
        for (i in 0..20) {
            assertEquals(0f, libraryEntranceT(0f, i), 0.001f)
        }
    }

    @Test
    fun budgetFullMeansEverythingVisible() {
        for (i in 0..20) {
            assertEquals(1f, libraryEntranceT(1f, i), 0.001f)
        }
    }

    @Test
    fun firstCardLeadsAndLastCardTrails() {
        // budget 一半時：第 0 張已跑完大半，墊底的還沒開始。
        val first = libraryEntranceT(0.5f, 0)
        val capped = libraryEntranceT(0.5f, LIBRARY_ENTRANCE_CAP)
        assertTrue("first=$first", first > 0.5f)
        assertEquals(0f, capped, 0.001f)
    }

    @Test
    fun beyondCapSharesTheLastSlot() {
        // 超過上限的卡不排隊：與第 CAP 張同一進度，否則文件一多後面的永遠等不到。
        assertEquals(
            libraryEntranceT(0.7f, LIBRARY_ENTRANCE_CAP),
            libraryEntranceT(0.7f, LIBRARY_ENTRANCE_CAP + 50),
            0.001f
        )
    }

    @Test
    fun progressIsMonotoneInBudget() {
        // 同一張卡，預算只進不退：動畫不該倒帶。
        var prev = -1f
        var b = 0f
        while (b <= 1.001f) {
            val t = libraryEntranceT(b, 3)
            assertTrue("budget=$b t=$t prev=$prev", t >= prev)
            prev = t
            b += 0.05f
        }
    }
}
