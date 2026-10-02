package com.vic.inkflow.ui

/**
 * 側欄階段。
 *
 * 語意重點：**GRID 不是「第三種寬度」，它是覆蓋在紙上的頁面管理浮層。**
 *
 * 舊碼把它塞進寬度動畫（`targetWidth = totalWidth`），造成兩個結構性問題：
 *  1. 從 128dp 一路掃到約 800dp（6.7 倍距離），spring 在這種距離下只會黏爛收斂。
 *  2. 動畫期間側欄內容每幀重新量測（`fixedW` 跟著 `currentWidthDp` 走），
 *     而內容是一整排 `PageThumbnail` → 每幀重排幾十張縮圖。
 *
 * 拆開之後：寬度軸只負責 56dp↔128dp，GRID 走獨立的疊層進度軸。
 */
enum class SidebarStage(val progress: Float) {
    /** 收合：只有一條玻璃脊（頁碼丸／展開／新增）。 */
    RAIL(0f),

    /** 展開：縮圖列表 ＋ 24dp 拖曳條。 */
    PANEL(1f),

    /** 頁面網格浮層（7 欄），覆蓋在紙之上。 */
    GRID(2f);

    companion object {
        /** 收合 → 展開 → 網格的固定順序，吸附與推進都依這裡。 */
        val ordered: List<SidebarStage> = listOf(RAIL, PANEL, GRID)

        /** 連續進度 → 離散階段（四捨五入到最近一階）。 */
        fun fromProgress(progress: Float): SidebarStage =
            ordered[((progress + 0.5f).toInt()).coerceIn(0, ordered.lastIndex)]
    }
}

/** 側欄可接受的輸入。各階段的有效組合由 [SidebarStageMachine.allowedInputs] 定義。 */
/** 側欄可接受的輸入。各階段的有效組合由 [SidebarStageMachine.allowedInputs] 定義。 */
enum class SidebarInput {
    /** 收合態那顆展開鈕（或玻璃脊空白處）。 */
    TAP_EXPAND,

    /** 收合鈕：從網格／展開直接回收合。 */
    TAP_COLLAPSE,

    /** 展開態的 24dp 拖曳條：橫拖調寬、直拖捲主列表。 */
    DRAG_WIDTH,

    /** 網格態右緣的浮動把手：橫拖可連續退回。 */
    EDGE_HANDLE_DRAG,

    /** 系統返回鍵。 */
    SYSTEM_BACK,

    /** 拖曳條直拖：捲動主列表。 */
    VERTICAL_SCROLL,

    /** 網格長按拖曳排序。 */
    REORDER
}

/**
 * 側欄階段的單一真相源。
 *
 * 這裡只放「規則」，不放 Compose。目的是讓「底層怎麼運作」可以用單元測試釘死，
 * 而不是靠讀 UI 程式碼推導——舊碼的階段邏輯散落在 `EditorScreen` 的拖曳迴圈裡，
 * 導致第 3 階變成單向門（返回鈕只能回第 2 階，而拖曳條在第 3 階根本不畫）。
 */
object SidebarStageMachine {

    /** 放開時超過這個速度（px/s）就直接跳一階，不看目前停在哪。 */
    const val SNAP_VELOCITY_PX_PER_SEC = 600f

    /** 低於這個進度就算「完全收起」，浮層必須放行觸控。 */
    const val HIT_TEST_EPSILON = 0.001f

    /**
     * 每個階段的有效輸入。這張表就是側欄的行為契約：
     * 任何階段都至少有一條回到 [SidebarStage.RAIL] 的路徑。
     */
    private val inputsByStage: Map<SidebarStage, Set<SidebarInput>> = mapOf(
        SidebarStage.RAIL to setOf(
            SidebarInput.TAP_EXPAND
        ),
        SidebarStage.PANEL to setOf(
            SidebarInput.TAP_EXPAND,
            SidebarInput.TAP_COLLAPSE,
            SidebarInput.DRAG_WIDTH,
            SidebarInput.VERTICAL_SCROLL,
            SidebarInput.SYSTEM_BACK
        ),
        SidebarStage.GRID to setOf(
            SidebarInput.TAP_COLLAPSE,
            SidebarInput.EDGE_HANDLE_DRAG,
            SidebarInput.SYSTEM_BACK,
            SidebarInput.REORDER
        )
    )

    fun allowedInputs(stage: SidebarStage): Set<SidebarInput> = inputsByStage.getValue(stage)

    fun supports(stage: SidebarStage, input: SidebarInput): Boolean =
        input in inputsByStage.getValue(stage)

    /**
     * 返回鈕：逐階往下退。
     * 回 [SidebarStage.RAIL]（已無可退）時回 null，交給上層離開編輯器。
     */
    fun backTarget(stage: SidebarStage): SidebarStage? = when (stage) {
        SidebarStage.GRID -> SidebarStage.PANEL
        SidebarStage.PANEL -> SidebarStage.RAIL
        SidebarStage.RAIL -> null
    }

    /** 收合鈕：一鍵直接回收合態，不繞道第 2 階。 */
    fun collapseTarget(stage: SidebarStage): SidebarStage? = when (stage) {
        SidebarStage.GRID -> SidebarStage.RAIL
        SidebarStage.PANEL -> SidebarStage.RAIL
        SidebarStage.RAIL -> null
    }

    /** 展開鈕：逐階往上推；已在最外層回 null。 */
    fun expandTarget(stage: SidebarStage): SidebarStage? = when (stage) {
        SidebarStage.RAIL -> SidebarStage.PANEL
        SidebarStage.PANEL -> SidebarStage.GRID
        SidebarStage.GRID -> null
    }

    /** 系統返回鍵走 [backTarget]。 */
    fun onSystemBack(stage: SidebarStage): SidebarStage? = backTarget(stage)

    /**
     * 拖曳期間的連續進度。[dxPx] 為正＝往更展開的方向（右）。
     * [spanPx] 是「拖過多少 px 等於一階」，由呼叫端依實際量測寬度換算。
     */
    fun dragProgress(startProgress: Float, dxPx: Float, spanPx: Float): Float {
        if (spanPx <= 0f) return startProgress
        val delta = dxPx / spanPx
        return (startProgress + delta).coerceIn(
            SidebarStage.RAIL.progress,
            SidebarStage.GRID.progress
        )
    }

    /**
     * 放開時的吸附：速度夠就往該方向跳一階，否則停最近的一階。
     * 兩端都會被夾住，不會溢出。
     */
    fun snap(releaseProgress: Float, velocityPxPerSec: Float): SidebarStage {
        val from = SidebarStage.fromProgress(releaseProgress)
        val direction = when {
            velocityPxPerSec > SNAP_VELOCITY_PX_PER_SEC -> 1
            velocityPxPerSec < -SNAP_VELOCITY_PX_PER_SEC -> -1
            else -> 0
        }
        val index = SidebarStage.ordered.indexOf(from) + direction
        return SidebarStage.ordered[index.coerceIn(0, SidebarStage.ordered.lastIndex)]
    }

    /**
     * 浮層是否仍參與 hit test。
     *
     * `alpha = 0` 不會自動停用 hit test，滑出畫面的卡片照樣會吃掉底下觸控
     * （AI 抽屜踩過一次，整條頁碼欄死掉）。所以只要進度歸零就必須放行。
     */
    fun overlayHitsTest(progress: Float): Boolean = progress > HIT_TEST_EPSILON

    /**
     * 收合態本身是否攔截觸控。
     *
     * GRID 開啟時必須讓路，否則浮層的邊緣把手會被壓在收合脊底下、永遠按不到。
     */
    fun railHitsTest(stage: SidebarStage): Boolean = stage != SidebarStage.GRID
}