package com.vic.inkflow.ui

import androidx.compose.ui.geometry.Offset
import com.vic.inkflow.util.StrokePoint

// ── 連貫畫布 helpers ──────────────────────────────────────────────────────
// 全文件 model 空間統一（見 EditorViewModel.MODEL_W/H），各頁同尺寸，
// 跨頁 = 同一座標系往上下頁平移 modelHeight（筆）或整顆換頁（圖/字/套索）。

/** 邊緣自動捲（拖曳專用極慢速）：手指拖出紙上下界才捲，死區內不動，比例極小好微控。 */
internal fun edgeAutoScrollDy(y: Float, canvasH: Float): Float {
    if (canvasH <= 0f) return 0f
    val overshoot = when {
        y > canvasH -> y - canvasH
        y < 0f -> y // 負值=往上捲
        else -> return 0f
    }
    // 死區：紙界外 8px 內不捲，手指搭邊不飄移
    val dead = 8f
    val eff = when {
        overshoot > dead -> overshoot - dead
        overshoot < -dead -> overshoot + dead
        else -> return 0f
    }
    return (eff * 0.05f).coerceIn(-8f, 8f)
}

/**
 * model-space Y 越界換頁：逐頁繞回，回傳 (目標頁, 繞回後Y)。未越界回傳 null。
 * 首/末頁撞牆（無處可去）也回傳 null，呼叫方走舊單頁提交。
 */
internal fun wrapCrossPageY(
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
internal fun splitStrokeByPage(
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
