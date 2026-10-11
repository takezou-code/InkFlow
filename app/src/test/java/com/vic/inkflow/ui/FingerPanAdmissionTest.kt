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

    // ---- 紙邊界算式（放大時推到底推不動的修法所在）------------------------
    //
    // 這些不是「多寫的測試」，是補上一個兩輪沒修好的 bug 的回歸網。紙邊界算式原本
    // 內聯在 Workspace 的 isBlankX 裡，只能靠實機手勢碰，於是漏掉 hScroll 那一項
    // 的錯誤重現了兩次。抽出成純函式後才釘得住。

    @Test
    fun atFullScalePaperIsCentredAndPanShiftsIt() {
        // 視窗 1000、z=1：L = 1000（視窗同寬）、置中偏移 = 0、紙占寬 = 1000
        // → 紙滿版，(0, 1000)。pan=0 時紙外沒有活區。
        val (l0, r0) = paperEdgesInViewport(1000f, 1f, hScrollPx = 0f, panX = 0f)
        assertEquals(0f, l0, 0.01f)
        assertEquals(1000f, r0, 0.01f)

        // pan=+200 → 整張紙右移 200，左邊露出 200px 活區
        val (l1, _) = paperEdgesInViewport(1000f, 1f, hScrollPx = 0f, panX = 200f)
        assertEquals(200f, l1, 0.01f)
    }

    @Test
    fun belowFullScalePaperIsNarrowerAndCentred() {
        // z=0.5：紙占寬 = 1000 × 0.5 = 500，置中 → (250, 750)。左右各有 250px 活區。
        val (l, r) = paperEdgesInViewport(1000f, 0.5f, hScrollPx = 0f, panX = 0f)
        assertEquals(250f, l, 0.01f)
        assertEquals(750f, r, 0.01f)
    }

    @Test
    fun whenZoomedPaperSpansWholeScrollingList() {
        // z=2：L = 2000 > 視窗。置中偏移 = (1000−2000)/2 = −500。
        // 紙占寬 = L × 1 = 2000（與列表同寬），列表內置中偏移為 0 → 紙邊 = −500..1500。
        val (l, r) = paperEdgesInViewport(1000f, 2f, hScrollPx = 0f, panX = 0f)
        assertEquals(-500f, l, 0.01f)
        assertEquals(1500f, r, 0.01f)
    }

    @Test
    fun zoomedEdgesFollowHScrollNotJustPan() {
        // ⚠️ 這條是修好那個 bug 的關鍵，也是舊測試做不到的。
        // 放大時 applyHorizontalDelta 把位移寫進 hScrollState，不是 panOffsetX。
        // 舊版算式只算 pan → hScroll 推了 400 而邊界紋絲不動 → 永遠判定不出紙外
        // → 單指推不動。
        //
        // 這裡固定 pan=0，只動 hScroll：邊界必須跟著動。
        val (l0, _) = paperEdgesInViewport(1000f, 2f, hScrollPx = 0f, panX = 0f)
        val (l1, r1) = paperEdgesInViewport(1000f, 2f, hScrollPx = 400f, panX = 0f)
        assertEquals(-900f, l1, 0.01f)      // −500 − 400
        assertEquals(1100f, r1, 0.01f)      // 1500 − 400
        assertTrue(
            "hScroll 必須影響紙邊界，否則放大後單指推不動",
            l1 < l0
        )
    }

    @Test
    fun hScrollAndPanAreIndependentAndOpposed() {
        // 兩項都必須算，而且**方向相反**，這是最容易寫錯的地方：
        //   內容左緣 = 置中偏移 − hScroll + pan
        // hScroll 是原生捲動偏移（內容往左移），pan 是內層 Column 的 .offset（整列往右移）。
        //
        // 誰把其中一項的正負號寫反，這裡就紅 —— 而寫反的後果是放大後紙邊界算錯、
        // 單指推不動，而且症狀是「感覺怪怪的」而不是任何明確錯誤，極難靠實機發現。
        val (l0, _) = paperEdgesInViewport(1000f, 2f, hScrollPx = 0f, panX = 0f)
        assertEquals(-500f, l0, 0.01f)

        // 只有 hScroll=200 → 左移 200
        val (ls, _) = paperEdgesInViewport(1000f, 2f, hScrollPx = 200f, panX = 0f)
        assertEquals(l0 - 200f, ls, 0.01f)

        // 只有 pan=200 → 右移 200
        val (lp, _) = paperEdgesInViewport(1000f, 2f, hScrollPx = 0f, panX = 200f)
        assertEquals(l0 + 200f, lp, 0.01f)

        // 兩項同時：相減而不是相加（−200 + 200 = 淨 0）
        val (lb, _) = paperEdgesInViewport(1000f, 2f, hScrollPx = 200f, panX = 200f)
        assertEquals(l0, lb, 0.01f)
    }

    @Test
    fun fullOutLeavesReachableBlankAreaToPanBack() {
        // 這是「推到底卡死」這個 bug 的核心不變式：推到極限之後，畫面上**必須還有**
        // 活區存在，否則單指落在紙上就再也拉不回來。
        //
        // clampPan 的極限 = paperW × 0.75（PaperWorkspace 的 KEEP 規則，至少留 1/4 紙在區內），
        // 所以推到極限時左邊至少露出 25% 視窗。
        val viewport = 1000f
        val paperW = viewport - 80f          // paperWpxForPan：視窗 − 40dp×2（密度 1）
        val maxPan = paperW * 0.75f
        val (l, _) = paperEdgesInViewport(viewport, 1f, hScrollPx = 0f, panX = maxPan)
        assertTrue(
            "推到極限後左邊必須還有活區（$l）",
            l > 0f
        )
        // 具體數字：paperW=920、極限 pan=690 → 紙左緣 = 690 > 0 ✅
        assertEquals(690f, l, 0.01f)
    }

    @Test
    fun panLimitAndBlankAreaStayConsistentAtEveryZoom() {
        // 鉗制極限（clampPan 用 paperWpxForPan = 視窗×z − 80）與命中區（isBlankX 用
        // slot = 視窗×z）刻意差那 80px——那是紙左右的留白。這裡釘住兩者的關係：
        // 推到極限時活區必須 **大於** 極限本身留出的量，也就是使用者永遠拉得回來。
        val viewport = 1000f
        for (zoom in listOf(0.4f, 0.5f, 0.8f, 1f)) {
            val paperW = (viewport * zoom - 80f).coerceAtLeast(0f)
            val maxPan = paperW * 0.75f
            val (l, _) = paperEdgesInViewport(viewport, zoom, hScrollPx = 0f, panX = maxPan)
            assertTrue(
                "z=$zoom 推到極限（pan=$maxPan）後左邊必須還有活區",
                l > 0f
            )
            // 活區 = 紙左緣，必須不超過視窗寬（紙不會被推出畫面外到不可見）
            assertTrue("z=$zoom 紙左緣不該超出視窗", l < viewport)
        }
    }

    @Test
    fun zoomBelowOneStillTracksPanTheSameWay() {
        // z < 1 與 z == 1 共用同一條 pan 路徑（clampPan / 紙外活區），
        // 行為必須一致：pan 每加 1，紙邊界就左移 1。不能因為縮小就換語意。
        val zoom = 0.6f
        val (l0, r0) = paperEdgesInViewport(1000f, zoom, hScrollPx = 0f, panX = 0f)
        val (l1, r1) = paperEdgesInViewport(1000f, zoom, hScrollPx = 0f, panX = 37f)
        assertEquals(l0 + 37f, l1, 0.01f)
        assertEquals(r0 + 37f, r1, 0.01f)
    }
}
