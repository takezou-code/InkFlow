package com.vic.inkflow

import androidx.compose.ui.geometry.Offset
import com.vic.inkflow.ui.handlePressedScale
import com.vic.inkflow.ui.nearestHandleIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 選取把手命中測試的回歸網（touch.md:21「新增手勢分支先補純函數例」）。
 *
 * 這兩個純函數是「把手好不好抓」的全部決定因素：
 * - [nearestHandleIndex]：命中框放大後小圖的四角會互相重疊，必須「最近優先」，
 *   否則重疊時穩定抓錯把手（舊碼是「旋轉固定優先 → indexOfFirst」）。
 * - [handlePressedScale]：按壓放大回饋的插值。
 *
 * ## 把手尺寸不需要縮放補償
 *
 * 這裡曾有一個 [被移除] 的 `handleScaleFor(dpPx, canvasScale) = dpPx / scale`，
 * 意圖是讓把手在螢幕上「不隨紙張縮放變化」。**那個假設是錯的**：
 * `docZoom` 是把紙的 layout 尺寸調大（`listWdp = viewportWpx * max(docZoom,1)`、
 * `Surface.fillMaxWidth(docZoom)`），**不是**對畫布做像素縮放——畫布鏈路上沒有任何
 * `graphicsLayer`（全專案只在跨頁 overlay 用到）。
 *
 * 所以 1 個畫布 px 恆等於 1 個螢幕 px，結果 docZoom=2 時把手只剩螢幕上的 12dp、
 * 4x 只剩 6dp。實機回報「放大後把手變小、整個倒過來」。已移除該函式，
 * 呼叫端直接用 `toPx()`。
 */
class HandleHitTest {

    // ── nearestHandleIndex：重疊時抓最近的 ──

    private val tl = Offset(100f, 100f)
    private val tr = Offset(300f, 100f)
    private val rot = Offset(200f, 20f)

    @Test
    fun picksNearestWithinRadius() {
        // 距離 tl 10、tr 190 → tl
        assertEquals(0, nearestHandleIndex(Offset(110f, 100f), listOf(tl, tr, rot), 24f))
        assertEquals(1, nearestHandleIndex(Offset(280f, 100f), listOf(tl, tr, rot), 24f))
    }

    @Test
    fun returnsMinusOneWhenNothingInRange() {
        assertEquals(-1, nearestHandleIndex(Offset(200f, 500f), listOf(tl, tr, rot), 24f))
    }

    @Test
    fun pointExactlyOnRadiusStillHits() {
        // 邊界要含（<=），差一點點就抓不到會很煩。
        assertEquals(0, nearestHandleIndex(Offset(124f, 100f), listOf(tl, tr, rot), 24f))
    }

    @Test
    fun rotationHandleWinsWhenActuallyClosest() {
        // 舊碼的 bug：旋轉把手固定優先，就算角更近也抓旋轉。
        // 這裡手指在 rot 上（距離 0）而 tl 距離 90 → 必須是 rot。
        val near = listOf(tl, tr, rot)
        val onRot = nearestHandleIndex(rot, near, 24f)
        assertEquals(2, onRot)
    }

    @Test
    fun tieGoesToFirstByDefault() {
        // 四角與旋轉把手在正上方對稱時（圖很矮），同距離該讓先到的角贏。
        val a = Offset(100f, 100f)
        val b = Offset(100f, 140f)
        assertEquals(0, nearestHandleIndex(Offset(100f, 120f), listOf(a, b), 24f))
    }

    @Test
    fun preferLastTiesFlipsTieToLaterCandidate() {
        val a = Offset(100f, 100f)
        val b = Offset(100f, 140f)
        assertEquals(1, nearestHandleIndex(Offset(100f, 120f), listOf(a, b), 24f, preferLastTies = true))
    }

    @Test
    fun emptyCandidatesReturnMinusOne() {
        assertEquals(-1, nearestHandleIndex(Offset(0f, 0f), emptyList(), 24f))
    }

    @Test
    fun bigHitRadiusMakesOverlappingHandlesPickNearest() {
        // 小圖 + 48dp 命中框 = 四角命中區重疊，這是放大命中區後必然的情況。
        // 舊碼 indexOfFirst 會永遠抓 topLeft；新的必須抓真正近的那個。
        val tiny = listOf(Offset(100f, 100f), Offset(130f, 100f), Offset(100f, 130f), Offset(130f, 130f))
        // 手指在右下（130,130）附近：indexOfFirst 會給 0，正確是 3。
        assertEquals(3, nearestHandleIndex(Offset(130f, 130f), tiny, 60f))
        assertEquals(0, nearestHandleIndex(Offset(100f, 100f), tiny, 60f))
    }

    // ── handlePressedScale：按壓放大回饋 ──

    @Test
    fun idleIsUnscaled() {
        assertEquals(1f, handlePressedScale(0f, 1.35f), 1e-4f)
    }

    @Test
    fun fullyPressedHitsMaxScale() {
        assertEquals(1.35f, handlePressedScale(1f, 1.35f), 1e-4f)
    }

    @Test
    fun interpolatesMonotonically() {
        val mid = handlePressedScale(0.5f, 1.35f)
        assertTrue("0 < mid < max", mid > 1f && mid < 1.35f)
    }

    @Test
    fun outOfRangePressedIsClamped() {
        // 動畫值理論上在 0..1，但摻1次 overshoot 或髒值不該讓把手爆掉。
        assertEquals(1.35f, handlePressedScale(5f, 1.35f), 1e-4f)
        assertEquals(1f, handlePressedScale(-3f, 1.35f), 1e-4f)
    }
}