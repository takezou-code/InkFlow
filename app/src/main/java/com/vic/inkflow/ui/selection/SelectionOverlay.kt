package com.vic.inkflow.ui.selection

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.vic.inkflow.ui.StrokeSelectionHandle
import com.vic.inkflow.ui.drawLassoSelectionFrame
import com.vic.inkflow.ui.nearestHandleIndex
import com.vic.inkflow.ui.strokeSelectionHandleCenters
import com.vic.inkflow.ui.theme.Handles
import com.vic.inkflow.util.StrokeTransformUtils

/**
 * 圈選的唯一渲染入口。
 *
 * 手勢與繪製都從這裡拿同一個矩形——不再各自用不同的多邊形／bounds 重新算，
 * 這是「框和內容對不上」的結構性解法。
 */

/** 本頁顯示框換算到 canvas px（含拖曳／縮放草稿）。null = 本頁沒有框。 */
internal fun selectionFrameRectOnPage(
    state: SelectionState,
    page: Int,
    sx: Float,
    sy: Float
): Rect? {
    val bounds = state.frameFor(page) ?: return null
    val t = state.transform
    val anchor = t.anchor ?: bounds.center
    val scale = StrokeTransformUtils.clampUniformScale(t.scale)
    val left = anchor.x + (bounds.left - anchor.x) * scale + t.translation.x
    val top = anchor.y + (bounds.top - anchor.y) * scale + t.translation.y
    val right = anchor.x + (bounds.right - anchor.x) * scale + t.translation.x
    val bottom = anchor.y + (bounds.bottom - anchor.y) * scale + t.translation.y
    val rect = Rect(
        left = left * sx,
        top = top * sy,
        right = right * sx,
        bottom = bottom * sy
    )
    return rect.takeIf { !it.isEmpty && it.width > 0f && it.height > 0f }
}

/** 命中哪顆縮放把手（最近優先，重疊時不會固定抓錯）。 */
internal fun hitSelectionHandle(
    point: Offset,
    frameRect: Rect,
    hitRadiusPx: Float
): StrokeSelectionHandle? {
    val idx = nearestHandleIndex(
        point = point,
        candidates = strokeSelectionHandleCenters(frameRect),
        hitRadiusPx = hitRadiusPx,
        preferLastTies = true
    )
    return StrokeSelectionHandle.entries.getOrNull(idx)
}

/** 畫本頁的選取框（含把手）。回傳實際畫出的矩形，供命中測試共用。 */
internal fun DrawScope.drawSelectionFrameOnPage(
    state: SelectionState,
    page: Int,
    sx: Float,
    sy: Float,
    accent: Color,
    showHandles: Boolean,
    dashPhase: Float,
    animateDash: Boolean,
    visualRadiusPx: Float,
    haloRadiusPx: Float,
    pressedHandleCenter: Offset?
): Rect? {
    val rect = selectionFrameRectOnPage(state, page, sx, sy) ?: return null
    drawLassoSelectionFrame(
        selectionRect = rect,
        accent = accent,
        showHandles = showHandles,
        dashPhase = dashPhase,
        animateDash = animateDash,
        visualRadiusPx = visualRadiusPx,
        haloRadiusPx = haloRadiusPx,
        pressedHandleCenter = pressedHandleCenter,
        strokeScale = if (pressedHandleCenter != null) Handles.PressedStrokeScale else 1f
    )
    return rect
}
