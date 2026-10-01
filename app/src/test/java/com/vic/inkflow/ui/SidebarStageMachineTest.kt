package com.vic.inkflow.ui

import com.vic.inkflow.ui.SidebarInput.EDGE_HANDLE_DRAG
import com.vic.inkflow.ui.SidebarInput.SYSTEM_BACK
import com.vic.inkflow.ui.SidebarInput.TAP_COLLAPSE
import com.vic.inkflow.ui.SidebarInput.TAP_EXPAND
import com.vic.inkflow.ui.SidebarInput.VERTICAL_SCROLL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 側欄階段狀態機的迴歸網。
 *
 * 每個測試都對應一個真實發生過的失敗：
 *  - 「第 3 階展開後沒辦法收合」→ [backTarget]／[collapseTarget] 的逐階段覆蓋
 *  - 「點一下永遠在第 2／3 階之間繞」→ [expandTarget] 單向推進、沒有循環
 *  - 「滑出畫面的東西還在吃觸控」→ [overlayHitsTest]
 *  - 「邊緣把手被收合脊壓住按不到」→ [railHitsTest]
 */
class SidebarStageMachineTest {

    // ── 階段映射 ────────────────────────────────────────────────

    @Test
    fun `進度四捨五入到最近一階`() {
        assertEquals(SidebarStage.RAIL, SidebarStage.fromProgress(0f))
        assertEquals(SidebarStage.RAIL, SidebarStage.fromProgress(0.49f))
        assertEquals(SidebarStage.PANEL, SidebarStage.fromProgress(0.51f))
        assertEquals(SidebarStage.PANEL, SidebarStage.fromProgress(1f))
        assertEquals(SidebarStage.PANEL, SidebarStage.fromProgress(1.49f))
        assertEquals(SidebarStage.GRID, SidebarStage.fromProgress(1.51f))
        assertEquals(SidebarStage.GRID, SidebarStage.fromProgress(2f))
    }

    @Test
    fun `進度超出範圍時夾住不爆`() {
        assertEquals(SidebarStage.RAIL, SidebarStage.fromProgress(-5f))
        assertEquals(SidebarStage.GRID, SidebarStage.fromProgress(99f))
    }

    // ── 收合路徑：每個階段都必須到得了 RAIL ──────────────────────

    @Test
    fun `每個階段都能收合到RAIL`() {
        assertEquals(SidebarStage.RAIL, SidebarStageMachine.collapseTarget(SidebarStage.PANEL))
        assertEquals(SidebarStage.RAIL, SidebarStageMachine.collapseTarget(SidebarStage.GRID))
    }

    @Test
    fun `GRID 的返回鈕回到PANEL 不直接跳RAIL`() {
        // 舊碼只有這一條路，而且 drag strip 在 GRID 不畫 → 第 3 階是單向門。
        // 保留逐階語意，但必須搭配 collapseTarget／邊緣把手才不會卡死。
        assertEquals(SidebarStage.PANEL, SidebarStageMachine.backTarget(SidebarStage.GRID))
        assertEquals(SidebarStage.RAIL, SidebarStageMachine.backTarget(SidebarStage.PANEL))
        assertNull(SidebarStageMachine.backTarget(SidebarStage.RAIL))
    }

    @Test
    fun `系統返回逐階退到RAIL後交還上層`() {
        assertEquals(SidebarStage.PANEL, SidebarStageMachine.onSystemBack(SidebarStage.GRID))
        assertEquals(SidebarStage.RAIL, SidebarStageMachine.onSystemBack(SidebarStage.PANEL))
        assertNull(SidebarStageMachine.onSystemBack(SidebarStage.RAIL))
    }

    @Test
    fun `展開鈕是單向推進不做循環`() {
        // 舊碼是 order[(indexOf + 1) % 3] 的循環：PANEL 點一下進 GRID，
        // GRID 沒有 strip 所以只會卡住。改成逐階推進後不會繞回來。
        assertEquals(SidebarStage.PANEL, SidebarStageMachine.expandTarget(SidebarStage.RAIL))
        assertEquals(SidebarStage.GRID, SidebarStageMachine.expandTarget(SidebarStage.PANEL))
        assertNull(SidebarStageMachine.expandTarget(SidebarStage.GRID))
    }

    // ── 輸入契約 ────────────────────────────────────────────────

    @Test
    fun `各階段的手勢契約`() {
        assertEquals(setOf(TAP_EXPAND), SidebarStageMachine.allowedInputs(SidebarStage.RAIL))

        assertTrue(SidebarStageMachine.supports(SidebarStage.PANEL, VERTICAL_SCROLL))
        assertTrue(SidebarStageMachine.supports(SidebarStage.PANEL, SYSTEM_BACK))

        // GRID 不該有拖曳條（它是浮層），改用邊緣把手 + 返回/收合鈕
        assertFalse(SidebarStageMachine.supports(SidebarStage.GRID, VERTICAL_SCROLL))
        assertTrue(SidebarStageMachine.supports(SidebarStage.GRID, EDGE_HANDLE_DRAG))
        assertTrue(SidebarStageMachine.supports(SidebarStage.GRID, TAP_COLLAPSE))
    }

    @Test
    fun `GRID 開啟時RAIL 必須讓路否則邊緣把手按不到`() {
        assertFalse(SidebarStageMachine.railHitsTest(SidebarStage.GRID))
        assertTrue(SidebarStageMachine.railHitsTest(SidebarStage.RAIL))
        assertTrue(SidebarStageMachine.railHitsTest(SidebarStage.PANEL))
    }

    // ── 拖曳與吸附 ──────────────────────────────────────────────

    @Test
    fun `拖曳進度跟著位移走並夾在兩端`() {
        // dxPx 是像素、spanPx 是「拖過多少 px 算一階」，所以 50px/100px = 半階
        assertEquals(1.5f, SidebarStageMachine.dragProgress(1f, 50f, 100f), 1e-4f)
        assertEquals(0.5f, SidebarStageMachine.dragProgress(1f, -50f, 100f), 1e-4f)
        // 從 RAIL 往左拖不會溢出
        assertEquals(0f, SidebarStageMachine.dragProgress(0f, -300f, 100f), 1e-4f)
        // 從 GRID 往右拖不會溢出
        assertEquals(2f, SidebarStageMachine.dragProgress(2f, 300f, 100f), 1e-4f)
    }

    @Test
    fun `spanPx 非法時不動`() {
        assertEquals(1f, SidebarStageMachine.dragProgress(1f, 500f, 0f), 1e-4f)
        assertEquals(1f, SidebarStageMachine.dragProgress(1f, 500f, -10f), 1e-4f)
    }

    @Test
    fun `慢放開停最近一階`() {
        assertEquals(SidebarStage.PANEL, SidebarStageMachine.snap(0.9f, 0f))
        assertEquals(SidebarStage.PANEL, SidebarStageMachine.snap(1.1f, 0f))
        // 四捨五入的分界在 1.5（round half up）
        assertEquals(SidebarStage.PANEL, SidebarStageMachine.snap(1.49f, 0f))
        assertEquals(SidebarStage.GRID, SidebarStageMachine.snap(1.5f, 0f))
        assertEquals(SidebarStage.RAIL, SidebarStageMachine.snap(0.4f, 0f))
    }

    @Test
    fun `快甩直接跳一階`() {
        val fast = SidebarStageMachine.SNAP_VELOCITY_PX_PER_SEC + 100f
        assertEquals(SidebarStage.GRID, SidebarStageMachine.snap(1.1f, fast))
        assertEquals(SidebarStage.RAIL, SidebarStageMachine.snap(0.9f, -fast))
    }

    @Test
    fun `剛好在門檻上不算快甩`() {
        val exact = SidebarStageMachine.SNAP_VELOCITY_PX_PER_SEC
        assertEquals(SidebarStage.PANEL, SidebarStageMachine.snap(1.1f, exact))
        assertEquals(SidebarStage.PANEL, SidebarStageMachine.snap(0.9f, -exact))
    }

    @Test
    fun `兩端快甩不會溢出`() {
        val fast = SidebarStageMachine.SNAP_VELOCITY_PX_PER_SEC + 100f
        assertEquals(SidebarStage.RAIL, SidebarStageMachine.snap(0f, -fast))
        assertEquals(SidebarStage.GRID, SidebarStageMachine.snap(2f, fast))
    }

    // ── hit test ────────────────────────────────────────────────

    @Test
    fun `浮層完全收起就放行觸控`() {
        assertFalse(SidebarStageMachine.overlayHitsTest(0f))
        assertFalse(
            SidebarStageMachine.overlayHitsTest(SidebarStageMachine.HIT_TEST_EPSILON)
        )
        // 還在滑動中就必須攔，否則會看見紙被底下拖過
        assertTrue(SidebarStageMachine.overlayHitsTest(0.002f))
        assertTrue(SidebarStageMachine.overlayHitsTest(2f))
    }
}