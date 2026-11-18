package com.vic.inkflow.ui

import androidx.compose.ui.geometry.Offset
import com.vic.inkflow.util.StrokePoint

// ── 連貫畫布 helpers ──────────────────────────────────────────────────────
// 全文件 model 空間統一（見 EditorViewModel.MODEL_W/H），各頁同尺寸，
// 跨頁 = 同一座標系往上下頁平移 modelHeight（筆）或整顆換頁（圖/字/套索）。

/**
 * 拖曳邊緣自動捲（**唯一一份**）：座標一律用「視窗座標」，手指進入上下邊緣感應區就捲，
 * 越靠邊越快，離開感應區立刻停。
 *
 * 為什麼不再用「紙界」判定：紙在 1x 幾乎貼滿視窗，手指能衝出紙界的距離只有幾 px，
 * 舊公式（0.05 增益、8px 上限）等於幾乎不捲；縮小後紙變小，同一個手指位置又會在
 * 「有反應／沒反應」之間跳——這正是拖文字、拖圖片、拖圈選手感不一致的來源。
 *
 * 回傳值是**每一幀**的期望捲動量（px，正=往後頁）。呼叫端跑幀迴圈持續送，
 * 手指停在邊緣不動也照捲（寫在 pointer-move 裡就停了＝手指不動不回調，常見 bug）。
 */
fun dragEdgeScrollDy(pointerViewportY: Float, viewportHeight: Float): Float {
    if (viewportHeight <= 0f) return 0f
    val zone = (viewportHeight * EDGE_SCROLL_ZONE_FRACTION)
        .coerceAtLeast(EDGE_SCROLL_MIN_ZONE_PX)
    return when {
        pointerViewportY < zone -> -edgeScrollSpeed(zone - pointerViewportY, zone)
        pointerViewportY > viewportHeight - zone ->
            edgeScrollSpeed(pointerViewportY - (viewportHeight - zone), zone)
        else -> 0f
    }
}

/** 越靠邊越快（t^1.5）：剛進感應區就有感，貼邊才全速。 */
private fun edgeScrollSpeed(depth: Float, zone: Float): Float {
    if (depth <= 0f) return 0f
    val t = (depth / zone).coerceAtMost(1f)
    return EDGE_SCROLL_MAX_PX_PER_FRAME * t * kotlin.math.sqrt(t)
}

/** 幀迴圈間隔：手指停在邊緣不動也要繼續捲（約 60Hz）。 */
const val EDGE_SCROLL_TICK_MS = 16L

/** 感應區高度＝視窗高度的這個比例（上下各一份）。 */
const val EDGE_SCROLL_ZONE_FRACTION = 0.16f

/** 視窗太矮時的比例下限，保證小視窗也有可用的感應區。 */
const val EDGE_SCROLL_MIN_ZONE_PX = 96f

/** 貼邊全速：每幀最多捲多少 px（60fps 約 0.9 頁/秒）。 */
const val EDGE_SCROLL_MAX_PX_PER_FRAME = 32f

/**
 * model-space Y 越界換頁：逐頁繞回，回傳 (目標頁, 繞回後Y)。未越界回傳 null。
 * 首/末頁撞牆（無處可去）也回傳 null，呼叫方走舊單頁提交。
 */
fun wrapCrossPageY(
    modelY: Float,
    modelH: Float,
    srcPage: Int,
    pageCount: Int
): Pair<Int, Float>? {
    if (modelH <= 0f || pageCount <= 0) return null
    var tp = srcPage
    var y = modelY
    while (y < 0f && tp > 0) { y += modelH; tp-- }
    while (y > modelH && tp < pageCount - 1) { y -= modelH; tp++ }
    if (tp == srcPage) return null
    return tp to y
}

/**
 * 連貫寫筆分段：canvas-space 整筆按「紙高+頁間隙」步長切分到各頁。
 * 算法唯一真相在 [DocLayout.splitByPage]（手勢/墨水共用，單測覆蓋）；這裡只做點型別轉接。
 * 回傳 (page, 該頁 canvas 座標點列)；首/末頁外溢出併入邊界頁並鉗制到纸邊。
 */
fun splitStrokeByPage(
    pts: List<StrokePoint>,
    canvasH: Float,
    gapPx: Float,
    srcPage: Int,
    pageCount: Int
): List<Pair<Int, List<StrokePoint>>> =
    com.vic.inkflow.util.DocLayout.splitByPage(
        items = pts,
        yOf = { it.y },
        canvasH = canvasH,
        gapPx = gapPx,
        srcPage = srcPage,
        pageCount = pageCount,
        local = { p, ly -> p.copy(y = ly) }
    )
