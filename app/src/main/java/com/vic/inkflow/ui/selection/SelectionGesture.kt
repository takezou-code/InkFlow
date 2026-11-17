package com.vic.inkflow.ui.selection

import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.awaitDragOrCancellation
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.unit.IntSize
import com.vic.inkflow.ui.edgeAutoScrollDy
import com.vic.inkflow.ui.strokeSelectionHandleCenter
import com.vic.inkflow.ui.strokeSelectionResizeAnchor
import com.vic.inkflow.util.DocTransform
import kotlin.math.hypot

/**
 * 圈選手勢的入口：只負責「手指在做什麼」，不碰 DB、不碰渲染。
 *
 * 這個檔案從 `InkCanvas` 的巨大 `awaitEachGesture` 抽出來。手勢只發意圖：
 * 開始移動、移到哪裡（絕對）、開始縮放、縮到多少（絕對）、提交、清除。
 * 手勢層不保存任何選取狀態，狀態只有 [SelectionState] 一份。
 */
internal class SelectionGestureHost(
    val state: () -> SelectionState,
    val canvasSize: () -> IntSize,
    val page: () -> Int,
    val pageCount: () -> Int,
    val modelWidth: () -> Float,
    val modelHeight: () -> Float,
    val pinchActive: () -> Boolean,
    val onEdgeAutoScroll: (Float) -> Unit,
    val setPageLock: (Boolean) -> Unit,
    val setDragPreviewActive: (Boolean) -> Unit,
    /** 回報目前按住的把手中心（null＝放開），供框把手放大回饋。 */
    val setPressedHandle: (Offset?) -> Unit,
    val handleHitRadiusPx: Float,
    val beginMove: () -> Unit,
    val moveTo: (Offset) -> Unit,
    val commitMove: (maxPageIndex: Int) -> Unit,
    val beginResize: (Offset) -> Unit,
    val resizeTo: (Float) -> Unit,
    val commitResize: () -> Unit,
    val commitRect: (Offset, Offset) -> Unit,
    val clear: () -> Unit
)

/**
 * 在 LASSO 工具下處理按下。
 *
 * @return true = 這一指已被圈選吃掉，呼叫端必須 return；false = 讓自由圈選／其他工具接手。
 */
internal suspend fun AwaitPointerEventScope.handleSelectionDown(
    down: PointerInputChange,
    startOffset: Offset,
    host: SelectionGestureHost,
    rectangular: Boolean,
    onRectPreview: (Offset?, Offset?) -> Unit
): Boolean {
    val st = host.state()
    val page = host.page()
    val cs = host.canvasSize()
    val sx = DocTransform.scaleX(cs.width.toFloat(), host.modelWidth())
    val sy = DocTransform.scaleY(cs.height.toFloat(), host.modelHeight())

    // 命中判定還在背景跑：吞掉這一指，避免「判定中又圈一個」造成兩個結果互蓋。
    if (st.phase == SelectionPhase.RESOLVING) {
        down.consume()
        return true
    }

    val frameRect = selectionFrameRectOnPage(st, page, sx, sy)
    if (frameRect != null) {
        // 1) 四角把手 → 縮放（等比，錨點是對角）。
        val handle = hitSelectionHandle(startOffset, frameRect, host.handleHitRadiusPx)
        if (handle != null) {
            down.consume()
            val anchorCanvas = strokeSelectionResizeAnchor(frameRect, handle)
            val initialHandle = strokeSelectionHandleCenter(frameRect, handle)
            val baseDistance = hypot(
                (initialHandle.x - anchorCanvas.x).toDouble(),
                (initialHandle.y - anchorCanvas.y).toDouble()
            ).toFloat().coerceAtLeast(1f)
            host.beginResize(anchorCanvas)
            host.setPressedHandle(initialHandle)
            try {
                var drag = awaitDragOrCancellation(down.id)
                while (drag != null && drag.pressed) {
                    if (host.pinchActive()) break
                    val currentDistance = hypot(
                        (drag.position.x - anchorCanvas.x).toDouble(),
                        (drag.position.y - anchorCanvas.y).toDouble()
                    ).toFloat()
                    host.resizeTo(currentDistance / baseDistance)
                    drag.consume()
                    drag = awaitDragOrCancellation(drag.id)
                }
            } finally {
                host.commitResize()
                host.setPressedHandle(null)
            }
            return true
        }

        // 2) 框內 → 移動。絕對式：每一幀都送「從起點到現在的位移」，
        //    事件合併、掉幀、畫布尺寸中途改變都不會漂；放手只提交一次。
        if (frameRect.contains(startOffset)) {
            down.consume()
            host.beginMove()
            host.setPageLock(true)
            host.setDragPreviewActive(true)
            try {
                var drag = awaitDragOrCancellation(down.id)
                while (drag != null && drag.pressed) {
                    if (host.pinchActive()) break
                    val autoDy = edgeAutoScrollDy(drag.position.y, cs.height.toFloat())
                    if (autoDy != 0f) host.onEdgeAutoScroll(autoDy)
                    host.moveTo(drag.position - startOffset)
                    drag.consume()
                    drag = awaitDragOrCancellation(drag.id)
                }
            } finally {
                host.commitMove((host.pageCount() - 1).coerceAtLeast(0))
                host.setPageLock(false)
                host.setDragPreviewActive(false)
            }
            return true
        }

        // 3) 框外 → 清除選取（與舊行為一致，但只有「有東西」時才吃這一指）。
        down.consume()
        host.clear()
        return true
    }

    if (st.hasItems || st.hasRegion) {
        down.consume()
        host.clear()
        return true
    }

    if (!rectangular) return false

    // 4) 矩形圈選：預覽交給呼叫端畫，放手才建區域。
    host.setDragPreviewActive(false)
    down.consume()
    onRectPreview(startOffset, startOffset)
    var end = startOffset
    try {
        var drag = awaitDragOrCancellation(down.id)
        while (drag != null && drag.pressed) {
            if (host.pinchActive()) return true
            end = drag.position
            onRectPreview(startOffset, end)
            drag.consume()
            drag = awaitDragOrCancellation(drag.id)
        }
    } finally {
        onRectPreview(null, null)
    }
    if (end != startOffset) host.commitRect(startOffset, end)
    return true
}
