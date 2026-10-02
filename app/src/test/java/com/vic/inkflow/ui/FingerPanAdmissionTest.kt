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

    // ---- 水平軸分流 ----

    @Test
    fun atFullScaleHorizontalPanPushesPaper() {
        // 100%：紙比視窗窄，水平拖 = 把紙在視窗內推（panOffsetX）。
        assertEquals(
            HorizontalPanTarget.PAN_OFFSET_X,
            horizontalPanTarget(1f)
        )
        assertEquals(
            HorizontalPanTarget.PAN_OFFSET_X,
            horizontalPanTarget(0.4f)
        )
    }

    @Test
    fun whenZoomedHorizontalPanScrollsContent() {
        // 放大：紙比視窗寬，沒有紙外可言，水平拖 = 捲動那張寬紙（hScrollState）。
        assertEquals(
            HorizontalPanTarget.SCROLL_ZOOMED_CONTENT,
            horizontalPanTarget(1.5f)
        )
        // 放大上限（applyZoom 夾在 0.4f..4f）也要走到捲動分支，不能漏。
        assertEquals(
            HorizontalPanTarget.SCROLL_ZOOMED_CONTENT,
            horizontalPanTarget(4f)
        )
    }

    @Test
    fun boundaryIsInclusiveAtExactlyFullScale() {
        // docZoom == 1f 屬 100% 分支：此刻 paper 比視窗窄，push 語意才成立。
        assertEquals(HorizontalPanTarget.PAN_OFFSET_X, horizontalPanTarget(1.0f))
        assertEquals(HorizontalPanTarget.SCROLL_ZOOMED_CONTENT, horizontalPanTarget(1.0001f))
    }
}
