package com.vic.inkflow

import com.vic.inkflow.ui.hubShouldYieldToInk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 樞紐讓路給墨水的仲裁回歸網（touch.md:21「新增手勢分支先補純函數例」）。
 *
 * ## 這個 bug 的完整因果（實測 log 證實）
 *
 * 樞紐（PageWorkspace）與 InkCanvas 各自跑 `pointerInput`，彼此沒有握手。若同一根手指
 * 兩邊都平移 = 雙寫 = 使用者看到「**拖圖片時頁面跟著捲**」。
 *
 * 仲裁依據是 `PointerInputChange.isConsumed`。同一個 Main pass 派的是**同一個**
 * `PointerEvent`，`awaitFirstDown` 拿到的是**同一個 `PointerInputChange` 實例**，
 * 墨水 `consume()` 直接改該物件欄位 → 樞紐讀得到。
 *
 * ⚠️ 但**判斷時機**是關鍵，踩過一次坑：
 * InkFlowProbe log 顯示樞紐的 DOWN 比 InkCanvas 的分支判定**早 17ms**，
 * 所以在 DOWN 當下讀 `isConsumed` 必定是 false → 樞紐以為沒人接手而照捲。
 * 必須延後到「即將施加位移」時才判斷，那時墨水已跑完。
 */
class HubShouldYieldToInkTest {

    @Test
    fun yieldsWhenInkClaimedAndHubGotDelta() {
        // 這就是「拖圖片」那一幀：樞紐算出位移，但墨水已 consume → 必須讓路。
        assertTrue(hubShouldYieldToInk(hubGotDelta = true, inkConsumed = true))
    }

    @Test
    fun doesNotYieldOnBlankPaperPan() {
        // 空白紙面捲頁：墨水沒碰這一指 → 樞紐照捲。
        assertFalse(hubShouldYieldToInk(hubGotDelta = true, inkConsumed = false))
    }

    @Test
    fun noDeltaMeansNoNeedToYield() {
        // 樞紐這一幀沒算出位移（例如還在 touch slop 內）→ 沒東西要搶，不必讓路。
        // 這條讓 slop 起步的那幾幀不會誤中斷樞紐自己的手勢。
        assertFalse(hubShouldYieldToInk(hubGotDelta = false, inkConsumed = true))
        assertFalse(hubShouldYieldToInk(hubGotDelta = false, inkConsumed = false))
    }

    @Test
    fun fullTruthTable() {
        val expected = listOf(
            Triple(true, true, true),
            Triple(true, false, false),
            Triple(false, true, false),
            Triple(false, false, false)
        )
        expected.forEach { (hubGotDelta, inkConsumed, want) ->
            val got = hubShouldYieldToInk(hubGotDelta, inkConsumed)
            val label = "hubGotDelta=$hubGotDelta inkConsumed=$inkConsumed -> $want"
            if (want) assertTrue(label, got) else assertFalse(label, got)
        }
    }
}