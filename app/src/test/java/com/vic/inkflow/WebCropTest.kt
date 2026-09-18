package com.vic.inkflow

import com.vic.inkflow.ui.WebCrop
import com.vic.inkflow.ui.fitMathSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S1/S2 截圖裁剪＋數學圖放置測試（JVM、全離線）：
 *  - cssToDevice：CSS px → device px
 *  - cropWindowPx：窗內／窗外／過小／過大／無效
 *  - fitMathSize：等比映射、小圖不拉伸、超寬超高收斂、無效輸入
 */
class WebCropTest {

    @Test fun cssToDevice_scales() {
        assertEquals(31, WebCrop.cssToDevice(10.4f, 3f))
        assertEquals(1, WebCrop.cssToDevice(0.5f, 2f))
        assertEquals(0, WebCrop.cssToDevice(0f, 3f))
        assertEquals(3000, WebCrop.cssToDevice(1000f, 3f))
    }

    @Test fun crop_insideWindow() {
        val c = WebCrop.cropWindowPx(WebCrop.CssBox(10f, 20f, 100f, 50f), 2f, 5, 5, 1000, 1000)
        // l=5+20+2=27 t=5+40+2=47 r=5+220-2=223 b=5+140-2=143
        assertEquals(WebCrop.CropPx(27, 47, 223, 143), c)
    }

    @Test fun crop_outsideWindow_null() {
        assertNull(WebCrop.cropWindowPx(WebCrop.CssBox(-500f, -500f, 10f, 10f), 1f, 0, 0, 1000, 1000))
        assertNull(WebCrop.cropWindowPx(WebCrop.CssBox(2000f, 2000f, 10f, 10f), 1f, 0, 0, 1000, 1000))
    }

    @Test fun crop_tooSmall_null() {
        assertNull(WebCrop.cropWindowPx(WebCrop.CssBox(0f, 0f, 5f, 5f), 1f, 0, 0, 1000, 1000))
        assertNull(WebCrop.cropWindowPx(WebCrop.CssBox(0f, 0f, 0f, 50f), 1f, 0, 0, 1000, 1000))
        assertNull(WebCrop.cropWindowPx(WebCrop.CssBox(0f, 0f, 50f, 50f), 0f, 0, 0, 1000, 1000))
    }

    @Test fun crop_tooBig_null() {
        // 全窗 1000x1000 的 80% = 800000；2000x2000 框 clamp 後超標
        assertNull(WebCrop.cropWindowPx(WebCrop.CssBox(0f, 0f, 2000f, 2000f), 1f, 0, 0, 1000, 1000))
    }

    @Test fun crop_partialOverlap_clamped() {
        // 左上溢出：clamp 後 0,0,98,98 → 有效
        val c = WebCrop.cropWindowPx(WebCrop.CssBox(-50f, -50f, 150f, 150f), 1f, 0, 0, 1000, 1000)
        assertEquals(WebCrop.CropPx(0, 0, 98, 98), c)
    }

    @Test fun fit_fullWidthRender_unchanged() {
        // KaTeX 1500px 渲染 → 剛好內容寬（與舊行為一致）
        val (w, h) = fitMathSize(1500, 300, 500f, 700f)
        assertEquals(500f, w)
        assertEquals(100f, h)
    }

    @Test fun fit_smallShot_noStretch() {
        // 300px 行內小圖 → 等比小塊，不拉伸全寬
        val (w, h) = fitMathSize(300, 60, 500f, 700f)
        assertEquals(100f, w)
        assertEquals(20f, h)
    }

    @Test fun fit_overWide_shrinks() {
        val (w, h) = fitMathSize(3000, 600, 500f, 700f)
        assertEquals(500f, w)
        assertEquals(100f, h)
    }

    @Test fun fit_overTall_shrinks() {
        val (w, h) = fitMathSize(1500, 3000, 500f, 700f)
        assertEquals(700f, h)
        assertTrue(w > 0f && w < 500f)
    }

    @Test fun fit_invalid_zero() {
        assertEquals(0f to 0f, fitMathSize(0, 10, 500f, 700f))
        assertEquals(0f to 0f, fitMathSize(10, 0, 500f, 700f))
        assertEquals(0f to 0f, fitMathSize(100, 100, 0f, 700f))
    }

    @Test fun nearBg_tintVsText() {
        val beigeBg = (235 shl 16) or (230 shl 8) or 210 // 米黃殘留
        // 底色自身與鄰近色算接近
        assertTrue(WebCrop.nearBg((235 shl 16) or (230 shl 8) or 210, 235, 230, 210))
        assertTrue(WebCrop.nearBg((232 shl 16) or (228 shl 8) or 208, 235, 230, 210))
        // 黑字永遠不算底
        assertTrue(!WebCrop.nearBg((10 shl 16) or (10 shl 8) or 10, 235, 230, 210))
        // 純白紙像素對米黃底不算接近（留給白紙邏輯，flatten 只壓底色族）
        assertTrue(!WebCrop.nearBg(-0x1, 235, 230, 210))
        // 自訂容限
        assertTrue(WebCrop.nearBg((200 shl 16) or (200 shl 8) or 200, 235, 230, 210, tol = 40))
    }
}
