package com.vic.inkflow

import com.vic.inkflow.ui.centeredImageOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 圖片插入落位的回歸網。
 *
 * 背景：插圖從「點紙面空白就開圖庫、圖落在點擊處」改成「工具列按鈕開圖庫、圖落在頁面中央」。
 * 落位數學抽成純函數 [centeredImageOrigin] 才能在 JVM 上驗（EditorViewModel 本身要 Android 依賴）。
 */
class CenteredImageOriginTest {

    @Test
    fun centersOnPage() {
        // 頁 595x842、圖 300x200 → 左右各留 147.5、上下各留 321
        val o = centeredImageOrigin(595f, 842f, 300f, 200f)
        assertEquals(147.5f, o.x, 1e-3f)
        assertEquals(321f, o.y, 1e-3f)
    }

    @Test
    fun resultIsActuallyCentered() {
        val pageW = 595f
        val pageH = 842f
        val imgW = 400f
        val imgH = 500f
        val o = centeredImageOrigin(pageW, pageH, imgW, imgH)
        // 圖心 == 頁心
        assertEquals(pageW / 2f, o.x + imgW / 2f, 1e-3f)
        assertEquals(pageH / 2f, o.y + imgH / 2f, 1e-3f)
    }

    @Test
    fun imageWiderThanPageClampsToZeroNotNegative() {
        // 圖比頁寬 → 數學上 (595-800)/2 是負的，必須夾 0（負座標會讓圖跑到紙外且捲不到）。
        val o = centeredImageOrigin(595f, 842f, 800f, 200f)
        assertEquals(0f, o.x, 1e-3f)
        assertTrue("x 不可為負", o.x >= 0f)
    }

    @Test
    fun imageTallerThanPageClampsToZero() {
        val o = centeredImageOrigin(595f, 842f, 300f, 1200f)
        assertEquals(0f, o.y, 1e-3f)
        assertTrue("y 不可為負", o.y >= 0f)
    }

    @Test
    fun imageExactlyPageSizeSitsAtOrigin() {
        val o = centeredImageOrigin(595f, 842f, 595f, 842f)
        assertEquals(0f, o.x, 1e-3f)
        assertEquals(0f, o.y, 1e-3f)
    }

    @Test
    fun imageFillingPageWidthCentersVertically() {
        // 常見情形：computeInitialImageSize 寬固定頁寬 80%，高的話上下置中。
        val o = centeredImageOrigin(595f, 842f, 595f * 0.8f, 300f)
        assertTrue("應水平置中", o.x > 0f)
        assertTrue("應垂直置中", o.y > 0f)
    }

    @Test
    fun degenerateInputsNeverProduceNegativeOrigin() {
        val o = centeredImageOrigin(0f, 0f, 0f, 0f)
        assertEquals(0f, o.x, 1e-3f)
        assertEquals(0f, o.y, 1e-3f)
    }

    @Test
    fun alwaysFitsInsidePageWhenPossible() {
        val pageW = 500f
        val pageH = 700f
        listOf(50f to 50f, 200f to 300f, 499f to 699f, 800f to 900f).forEach { (w, h) ->
            val o = centeredImageOrigin(pageW, pageH, w, h)
            assertTrue("w=$w x=${o.x}", o.x >= 0f)
            assertTrue("h=$h y=${o.y}", o.y >= 0f)
            // 圖比頁小時必須完整落在頁內
            if (w <= pageW) assertTrue("w=$w 溢出右邊", o.x + w <= pageW + 1e-3f)
            if (h <= pageH) assertTrue("h=$h 溢出下邊", o.y + h <= pageH + 1e-3f)
        }
    }
}