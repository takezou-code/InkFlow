package com.vic.inkflow

import com.vic.inkflow.ui.WebCrop
import com.vic.inkflow.ui.fitMathSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // 窗內但面積超全窗 80%（896x896 = 802816 > 800000）→ 視為整頁誤裁
        assertNull(WebCrop.cropWindowPx(WebCrop.CssBox(0f, 0f, 900f, 900f), 1f, 0, 0, 1000, 1000))
    }

    @Test fun crop_partialOverlap_null() {
        // 左上溢出**不再 clamp 硬裁**：clamp 會把頁面標題／AI 輸入框裁進來（實機踩過）。
        // 正確路徑是裁前先 scrollIntoView 重量（見 prepareJs），捲不動就退文字／KaTeX。
        assertNull(WebCrop.cropWindowPx(WebCrop.CssBox(-50f, -50f, 150f, 150f), 1f, 0, 0, 1000, 1000))
        // 右下溢出同理
        assertNull(WebCrop.cropWindowPx(WebCrop.CssBox(900f, 900f, 300f, 300f), 1f, 0, 0, 1000, 1000))
        // 貼齊邊界（含 inset 的合法情形）仍要能裁
        val c = WebCrop.cropWindowPx(WebCrop.CssBox(100f, 100f, 200f, 200f), 1f, 0, 0, 1000, 1000)
        assertEquals(WebCrop.CropPx(102, 102, 298, 298), c)
    }

    @Test fun prepareJs_pointsAtElementAndScrolls() {
        val js = WebCrop.prepareJs(3)
        assertTrue("要用 collect 留下的節點表", js.contains("__inkcropEls"))
        assertTrue("要指名第 3 個節點", js.contains("els[3]"))
        assertTrue("要捲進畫面", js.contains("scrollIntoView"))
        assertTrue("要置中才不會又貼邊", js.contains("block:'center'"))
        assertTrue("要回報捲動後的新框與 viewport", js.contains("innerWidth") && js.contains("innerHeight"))
        assertTrue("要回報 scrollY 供穩定判定", js.contains("scrollY"))
    }

    @Test fun probeJs_measuresWithoutScrolling() {
        val js = WebCrop.probeJs(2)
        assertTrue("要用節點表", js.contains("__inkcropEls"))
        assertTrue("要指名第 2 個節點", js.contains("els[2]"))
        assertTrue("要回報 scrollY", js.contains("scrollY"))
        assertTrue("要偵測浮動層遮擋", js.contains("elementFromPoint"))
        assertFalse("探針不可捲動（否則穩定性判定會自己製造位移）", js.contains("scrollIntoView"))
    }

    @Test fun hideOverlays_onlyTouchesFloatingLayers() {
        val js = WebCrop.hideOverlaysJs(1)
        assertTrue("要有節點表", js.contains("__inkcropEls"))
        assertTrue("要看整疊命中元素", js.contains("elementsFromPoint"))
        assertTrue("只處理 fixed", js.contains("'fixed'"))
        assertTrue("只處理 sticky", js.contains("'sticky'"))
        assertTrue("要記下還原清單", js.contains("__inkcropHidden"))
        assertTrue("要先存原值", js.contains("__inkVis"))
        val back = WebCrop.restoreOverlaysJs()
        assertTrue("要從同一份清單還原", back.contains("__inkcropHidden"))
        assertTrue("要還原 visibility", back.contains("visibility"))
    }

    @Test fun parsePrepare_readsEvaluatedString() {
        // evaluateJavascript 對字串值會再包一層引號，解析要吃得下
        val pr = WebCrop.parsePrepare("\"OK:10,20,100,50,800,600\"")!!
        assertEquals(WebCrop.CssBox(10f, 20f, 100f, 50f), pr.box)
        assertEquals(800f, pr.viewW)
        assertEquals(600f, pr.viewH)
        assertEquals(0f, pr.scrollY)
        // 七欄：cov（1＝被浮動層蓋住）
        val prCov = WebCrop.parsePrepare("\"OK:10,20,100,50,800,600,1\"")!!
        assertTrue("cov=1 要判定為被遮擋", prCov.covered)
        // 八欄：cov＋scrollY
        val pr8 = WebCrop.parsePrepare("\"OK:10,20,100,50,800,600,0,1234.5\"")!!
        assertFalse(pr8.covered)
        assertEquals(1234.5f, pr8.scrollY)
        assertNull(WebCrop.parsePrepare("null"))
        assertNull(WebCrop.parsePrepare("\"NONE\""))
        assertNull(WebCrop.parsePrepare("\"DETACHED\""))
        assertNull(WebCrop.parsePrepare("\"OK:1,2\""))
        assertNull(WebCrop.parsePrepare(null))
        // 零尺寸（節點離線後 getBoundingClientRect 回 0）視為失敗
        assertNull(WebCrop.parsePrepare("\"OK:0,0,0,0,800,600\""))
    }

    @Test fun insideViewport_rejectsOversize() {
        val vw = 800f
        val vh = 600f
        assertTrue(WebCrop.insideViewport(WebCrop.CssBox(0f, 0f, 800f, 600f), vw, vh))
        assertTrue(WebCrop.insideViewport(WebCrop.CssBox(-0.5f, -0.5f, 700f, 500f), vw, vh))
        // 比視窗寬 → 捲也救不了，必須退文字
        assertTrue(!WebCrop.insideViewport(WebCrop.CssBox(0f, 0f, 1400f, 200f), vw, vh))
        assertTrue(!WebCrop.insideViewport(WebCrop.CssBox(0f, 0f, 200f, 900f), vw, vh))
        assertTrue(!WebCrop.insideViewport(WebCrop.CssBox(-30f, 0f, 200f, 200f), vw, vh))
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
