package com.vic.inkflow.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 拖放排序的規則（手勢之外的那一半）。
 *
 * 使用者回報過「拖完偶爾沒換順序」：放開後格線沒有回到身分順序，於是每一格顯示的
 * 還是「頁碼 N」的舊內容——文件其實換了，畫面看起來沒換。規則抽成純函式後，
 * 這件事就有測試可守，而不是只能靠手機上試。
 */
class PageReorderTest {

    @Test
    fun `a page dropped lower moves to the preview position`() {
        val initial = listOf(0, 1, 2, 3)
        val preview = listOf(0, 2, 1, 3)   // 頁 1 被拖到位置 2
        assertEquals(2, reorderTargetPos(initial, preview, 1))
    }

    @Test
    fun `a page dropped higher moves to the preview position`() {
        val initial = listOf(0, 1, 2, 3)
        val preview = listOf(1, 0, 2, 3)   // 頁 0 被拖到位置 1
        assertEquals(1, reorderTargetPos(initial, preview, 0))
    }

    @Test
    fun `a page dropped where it started does not move`() {
        val initial = listOf(0, 1, 2, 3)
        assertNull(reorderTargetPos(initial, initial, 2))
    }

    @Test
    fun `a page that is not in the snapshot does not move`() {
        val initial = listOf(0, 1, 2, 3)
        assertNull(reorderTargetPos(initial, listOf(0, 1, 2, 9), 9))
    }

    @Test
    fun `the target position comes from the preview order, not the grid position`() {
        // 拖曳中預覽順序已經換位；用格線位置算會算到錯的頁。
        val initial = listOf(0, 1, 2, 3)
        val preview = listOf(3, 0, 1, 2)   // 頁 3 被拖到最前面
        assertEquals(0, reorderTargetPos(initial, preview, 3))
    }
}
