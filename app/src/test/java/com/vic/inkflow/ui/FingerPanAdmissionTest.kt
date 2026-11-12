package com.vic.inkflow.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 紙面單指平移准入門 + 水平軸分流目標的回歸網（touch.md:16「新增手勢分支先補純函數例」）。
 *
 * 背景一（准入）：觸控筆模式下 InkCanvas 對任何 PointerType.Touch 直接 return 不 consume，
 * 紙上單指就沒人接手——而 LazyColumn userScrollEnabled=false、horizontalScroll enabled=false、
 * 無 nestedScroll，事件直接沉進 Void（手指在觸控筆模式下摸紙面滑不動的根因）。
 * 這道門把紙上單指交回樞紐；狀態機本身不該、也沒有自己決定（見 GestureStateMachineTest
 * 的 singleOnPaperNeverLocks）。
 *
 * 背景二（分流）：樞紐是水平軸唯一寫者，水平位移依縮放丟去不同地方。
 * 分流錯了會變成兩個寫者搶同一根手指（約 2 倍速、撞邊界卡住）。
 *
 * 背景三（IMAGE 例外）：圖片工具是唯一不在紙上畫東西的工具，所以手指模式下
 * 「圖片工具 + 單指在空白紙上」要能捲頁；而單指在圖片本體／把手上仍歸 InkCanvas
 * 移動/縮放/旋轉（靠 InkCanvas 端 activeTool != Tool.IMAGE 不預先 consume 達成）。
 * 這個例外不可外溢到其他工具——那會讓手指在畫線時變成捲頁。
 */
class FingerPanAdmissionTest {

    // ---- 準入門 ----

    @Test
    fun stylusOnlyAllowsPaperPan() {
        assertTrue(fingerPanOnPaperAllowed(InputMode.STYLUS_ONLY, Tool.PEN))
    }

    @Test
    fun freeModeKeepsPaperForInk() {
        // 手指模式紙上單指是墨水的（InkCanvas consume 後畫線），門必須關著。
        assertFalse(fingerPanOnPaperAllowed(InputMode.FREE, Tool.PEN))
    }

    @Test
    fun palmRejectionAlsoAllowsPaperPan() {
        // PALM_REJECTION 目前已併入 STYLUS_ONLY（VM init 收斂），但 enum 還在。
        // 若日後復活，語意應與觸控筆模式一致：手指不畫畫，紙上單指歸樞紐。
        assertTrue(fingerPanOnPaperAllowed(InputMode.PALM_REJECTION, Tool.PEN))
    }

    // ---- IMAGE 工具例外（手指模式唯一例外）----

    @Test
    fun freeModeAllowsPaperPanInImageTool() {
        // 圖片工具不在紙上畫東西 → 空白紙上的單指要能捲頁。
        assertTrue(fingerPanOnPaperAllowed(InputMode.FREE, Tool.IMAGE))
    }

    @Test
    fun imageExceptionIsTheOnlyToolException() {
        // 這個例外絕對不能外溢：其他每一個工具在手指模式下都必須門關著，
        // 否則畫線/橡皮/套索會變成捲頁。
        listOf(
            Tool.PEN, Tool.HIGHLIGHTER, Tool.ERASER, Tool.LASSO,
            Tool.SHAPE, Tool.TEXT
        ).forEach { tool ->
            assertFalse(
                "$tool 在手指模式下不該開門",
                fingerPanOnPaperAllowed(InputMode.FREE, tool)
            )
        }
    }

    @Test
    fun stylusModeAllowsPaperPanInEveryTool() {
        // 觸控筆模式下 InkCanvas 對手指一律放行不 consume → 所有工具都要開門，
        // 否則某些工具下手指會變成完全沒反應。
        listOf(
            Tool.PEN, Tool.HIGHLIGHTER, Tool.ERASER, Tool.LASSO,
            Tool.SHAPE, Tool.TEXT, Tool.IMAGE
        ).forEach { tool ->
            assertTrue(
                "$tool 在觸控筆模式下應開門",
                fingerPanOnPaperAllowed(InputMode.STYLUS_ONLY, tool)
            )
        }
    }

    // ---- 水平位移：單一數值 + 連續夾鉗 ------------------------------------
    //
    // 這是兩個修過好幾輪的 bug 的回歸網：
    //   1. 100% 推到一邊再放大 → 紙卡在一邊拉不回
    //   2. 放大後拖到邊界 → 會彈／卡住、角落看不到
    // 共同根因是「兩個水平數值 + 兩把不同鉗制」（panOffsetX 與 hScrollState 不換算）。
    // 現在只有一個數值、一條連續夾鉗，所以測試直接釘「範圍」而不是「哪個 state 被寫」。

    private val viewport = 1000f
    private val sidePad = 80f   // 40dp 合計（密度 1）

    @Test
    fun panRangeAtHundredPercentKeepsTheOldPushLimit() {
        // z=1：紙寬 = 1000 − 80 = 920，slack = 0.75 × 920 = 690。
        // 這是改動前 100% 的手感，必須一模一樣。
        val r = panRangePx(viewport, 1f, sidePad)
        assertEquals(690f, r.endInclusive, 0.01f)
        assertEquals(-690f, r.start, 0.01f)
    }

    @Test
    fun panRangeIsContinuousAcrossHundredPercent() {
        // 舊版：z=1.00 可動 ±690，z=1.01 只剩 32px（hScroll 上限 = 視窗×(z−1)），
        // 75 倍突變。現在 z 前後是同一條曲線，而且範圍不會塌掉。
        val at = panRangePx(viewport, 1f, sidePad)
        val below = panRangePx(viewport, 0.999f, sidePad)
        val above = panRangePx(viewport, 1.001f, sidePad)
        assertEquals(at.endInclusive, below.endInclusive, 1f)
        assertEquals(at.endInclusive, above.endInclusive, 1f)
        assertTrue("z=1.001 的範圍 ${above.endInclusive} 不該塌掉", above.endInclusive > 600f)
    }

    @Test
    fun sameFingerDeltaMovesTheSamePixelsAtEveryZoomNearHundredPercent() {
        // 同一段手指位移在 0.98 / 1.0 / 1.02 必須移動同樣的像素
        //（舊版放大後換成捲動，會被 [0, maxValue] 夾住而卡死）。
        for (z in listOf(0.98f, 1f, 1.02f)) {
            val r = panRangePx(viewport, z, sidePad)
            val moved = (0f + 40f).coerceIn(r.start, r.endInclusive)
            assertEquals("z=$z 的位移被夾掉了", 40f, moved, 0.01f)
        }
    }

    @Test
    fun panRangeIsSymmetricSoThePaperCannotStickToASide() {
        // 對稱是「推到一邊再放大不會卡住」的數學前提：
        // 殘留值在任何 z 都還在範圍內，不會被單邊夾死。
        for (z in listOf(0.4f, 0.8f, 1f, 1.02f, 2f, 4f)) {
            val r = panRangePx(viewport, z, sidePad)
            assertEquals("z=$z 範圍必須對稱", -r.endInclusive, r.start, 0.01f)
        }
    }

    @Test
    fun zoomedRangeCoversTheWholePage() {
        // 放大後至少能從紙左緣捲到紙右緣，否則邊角永遠看不到。
        val z = 2f
        val paperW = viewport * z - sidePad
        val traverse = (paperW - viewport) / 2f
        val r = panRangePx(viewport, z, sidePad)
        assertTrue(
            "範圍 ${r.endInclusive} 未覆蓋整頁（需 >= $traverse）",
            r.endInclusive >= traverse
        )
        // pan 正 = 內容往右：紙左緣貼齊視窗左邊要 +traverse，右緣貼齊右邊要 −traverse。
        val (left, _) = paperEdgesInViewport(viewport, z, sidePad, traverse)
        assertEquals("紙左緣應能貼齊視窗左邊", 0f, left, 0.01f)
        val (_, right) = paperEdgesInViewport(viewport, z, sidePad, -traverse)
        assertEquals("紙右緣應能貼齊視窗右邊", viewport, right, 0.01f)
    }

    @Test
    fun fullOutStillLeavesReachableBlankArea() {
        // 推到極限後畫面上還必須有活區，否則落在紙上就再也拉不回。
        val r = panRangePx(viewport, 1f, sidePad)
        val (left, _) = paperEdgesInViewport(viewport, 1f, sidePad, r.endInclusive)
        assertTrue("推到極限後左邊必須還有活區（$left）", left > 0f)
        // 紙寬 920、置中左緣 40、再推 690 → 730；右邊仍留 25% 紙（270px）看得見。
        assertEquals(730f, left, 0.01f)
    }

    @Test
    fun paperEdgesAreCentrePlusPanOnly() {
        // 紙邊界＝置中＋pan，1:1 跟手（不再有 hScroll/pan 兩個數值相加）。
        val (l0, r0) = paperEdgesInViewport(viewport, 1f, sidePad, 0f)
        assertEquals(40f, l0, 0.01f)
        assertEquals(960f, r0, 0.01f)
        val (l1, r1) = paperEdgesInViewport(viewport, 1f, sidePad, 37f)
        assertEquals(l0 + 37f, l1, 0.01f)
        assertEquals(r0 + 37f, r1, 0.01f)
    }

    @Test
    fun belowFullScalePaperIsNarrowerAndCentred() {
        // z=0.5：紙寬 = 500 − 80 = 420，置中 → 290..710。
        val (l, r) = paperEdgesInViewport(viewport, 0.5f, sidePad, 0f)
        assertEquals(290f, l, 0.01f)
        assertEquals(710f, r, 0.01f)
    }

    // ---- 捏合水平錨定 -----------------------------------------------------

    /** 螢幕 x 底下的紙面座標（0=紙左緣、1=紙右緣）。 */
    private fun paperFractionAt(x: Float, z: Float, panX: Float): Float {
        val paperW = viewport * z - sidePad
        val left = (viewport - paperW) / 2f + panX
        return (x - left) / paperW
    }

    @Test
    fun zoomKeepsThePaperPointUnderTheFingers() {
        // 捏合時 fx 底下的紙面點必須前後不動，否則紙會在指下漂走。
        for ((zOld, zNew) in listOf(
            1f to 2f, 2f to 1f, 1f to 0.5f, 0.5f to 1f, 1f to 1.02f
        )) {
            for (fx in listOf(0f, 250f, 500f, 800f, 1000f)) {
                val newPan = panAfterZoom(0f, zOld, zNew, fx, viewport, sidePad)
                assertEquals(
                    "z $zOld→$zNew fx=$fx",
                    paperFractionAt(fx, zOld, 0f),
                    paperFractionAt(fx, zNew, newPan),
                    1e-4f
                )
            }
        }
    }

    @Test
    fun zoomAnchorAlsoWorksWithAnExistingPan() {
        val oldPan = 200f
        val newPan = panAfterZoom(oldPan, 1f, 2f, 300f, viewport, sidePad)
        assertEquals(
            paperFractionAt(300f, 1f, oldPan),
            paperFractionAt(300f, 2f, newPan),
            1e-4f
        )
    }
}
