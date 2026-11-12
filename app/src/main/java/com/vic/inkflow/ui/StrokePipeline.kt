package com.vic.inkflow.ui

import com.vic.inkflow.data.StrokeWithSamples
import com.vic.inkflow.util.DocTransform
import com.vic.inkflow.util.StrokePoint

/**
 * 一頁筆跡的組成：DB 已落庫的列 + 尚未落庫的樂觀列，與「這次合併解掉了哪些樂觀列」。
 *
 * [pendingIds] 不是裝飾：畫布要靠它把「還在路上的墨」當向量層畫（不進封頂位圖快取），
 * 否則提筆後那幾幀會先糊掉再跳回清晰，看起來就是「閃一下」。
 */
internal data class PendingPageMerge(
    val strokes: List<StrokeWithSamples>,
    val pendingIds: Set<String>,
    val resolvedIds: Set<String>
)

/**
 * 合併一頁的「DB 列 + 樂觀列」。
 *
 * 三條規則，任一漏掉都會出人命：
 *  1. DB 已有的 id 就是已落庫 → 從樂觀層退場（[PendingPageMerge.resolvedIds] 讓呼叫端清 map），
 *     否則同一筆會被畫兩次（高亮筆＝alpha 疊兩層，提筆瞬間明顯偏暗）。
 *  2. 樂觀列只屬於自己的頁（`stroke.pageIndex == page`）；否則會出現在隔壁紙上。
 *  3. 樂觀列接在 DB 列之後。`getStrokesForPage` 沒有 ORDER BY，Room 走 rowid＝插入序，
 *     所以「新的在後」對 DB 列成立；樂觀列本來就是最後寫的，接尾即維持 z 序。
 *
 * hiddenIds（樂觀隱藏／undo 墓碑）對兩邊一視同仁，否則刪掉的樂觀筆會留在畫面上。
 *
 * 純函數：不碰 State、不碰 DB，時序完全由呼叫端決定（所以可以單測）。
 */
internal fun mergePageStrokes(
    dbStrokes: List<StrokeWithSamples>,
    pending: Map<String, StrokeWithSamples>,
    page: Int,
    hiddenIds: Set<String>
): PendingPageMerge {
    val dbIds = HashSet<String>(dbStrokes.size * 2)
    for (swp in dbStrokes) dbIds.add(swp.stroke.id)

    val out = ArrayList<StrokeWithSamples>(dbStrokes.size + 8)
    if (hiddenIds.isEmpty()) {
        out.addAll(dbStrokes)
    } else {
        for (swp in dbStrokes) if (swp.stroke.id !in hiddenIds) out.add(swp)
    }

    val resolved = if (pending.isEmpty()) emptySet() else pending.keys.intersect(dbIds)
    val pendingIds = HashSet<String>(pending.size)
    if (pending.isNotEmpty()) {
        // Map.plus 產生的是 LinkedHashMap：插入序即落筆序，這裡照序接尾。
        for ((id, swp) in pending) {
            if (id in dbIds) continue
            if (swp.stroke.pageIndex != page) continue
            if (hiddenIds.isNotEmpty() && id in hiddenIds) continue
            out.add(swp)
            pendingIds.add(id)
        }
    }
    return PendingPageMerge(out, pendingIds, resolved)
}

/** 正規化結果：點列 + 寬度倍率（存進 Stroke.strokeWidth 用，跟點同一套框架）。 */
internal data class NormalizedStroke(
    val points: List<StrokePoint>,
    val widthScale: Float
)

/**
 * 畫布像素點列 → model 空間（提筆入庫前的最後一步）。
 *
 * **[frameW]/[frameH] 必須是「這些點被收下來時」的畫布尺寸，不是提筆當下的**：
 * 手勢途中若 relayout（視窗/紙面尺寸變），舊點屬舊框架，用新框架正規化＝整筆等比偏移
 * （預覽還在筆尖，所以肉眼只看到「一放手就歪」）。寬度同理吃同一個框架，
 * 座標與線寬才不會分屬兩個空間。
 *
 * 純函數，跟 [mergePageStrokes] 一樣可單測：同一個實體落點在兩個不同框架下量到
 * （點 ×k、框架 ×k）必須得到完全一樣的 model 座標。
 */
internal fun normalizeStrokePoints(
    points: List<StrokePoint>,
    frameW: Float,
    frameH: Float,
    modelW: Float,
    modelH: Float,
    docZoom: Float
): NormalizedStroke {
    val scaleX = DocTransform.invScaleXUnchecked(frameW, modelW)
    val scaleY = DocTransform.invScaleYUnchecked(frameH, modelH)
    // 寬度用 zoom=1 基準歸一（× docZoom 還原）：座標用實際框架映射，
    // 新墨在任何縮放下存入同樣的 model 寬，放大等比變粗（與預覽同管線）。
    val widthScaleX = scaleX * docZoom.coerceAtLeast(0.1f)
    val normalized = points.map { StrokePoint(it.x * scaleX, it.y * scaleY, it.width * widthScaleX) }
    return NormalizedStroke(normalized, widthScaleX)
}
