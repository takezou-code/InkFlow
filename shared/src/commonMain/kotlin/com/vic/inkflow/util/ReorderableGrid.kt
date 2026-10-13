package com.vic.inkflow.util

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope

@Composable
fun rememberReorderableLazyGridState(
    gridState: LazyGridState,
    onMove: (from: Int, to: Int) -> Unit,
    onDragEnd: (from: Int, to: Int) -> Unit
): ReorderableLazyGridState {
    val scope = rememberCoroutineScope()
    val state = remember(gridState) {
        ReorderableLazyGridState(gridState, scope, onMove, onDragEnd)
    }
    return state
}

class ReorderableLazyGridState(
    val gridState: LazyGridState,
    private val scope: CoroutineScope,
    private val onMove: (Int, Int) -> Unit,
    private val onDragEnd: (Int, Int) -> Unit
) {
    var draggingItemIndex by mutableStateOf<Int?>(null)
        private set

    // Drag bookkeeping, used only by this class. Kept internal rather than opened
    // up: making the file cross-module does not mean every member in it has to
    // become public API.
    internal var initialItemIndex: Int? = null
    internal var currentItemIndex: Int? = null
    // 上一幀被拖卡中心（網格座標）：過中心線遲滯用。只在拖動中有效。
    private var prevCenterX = 0f
    private var prevCenterY = 0f

    var dragOffset by mutableStateOf(Offset.Zero)
        private set

    var isDragging by mutableStateOf(false)
        private set

    /**
     * 手指在網格裡的位置（網格座標 px）。給呼叫端的邊緣自動捲用：
     * 官方 Compose 博客同款做法——捲動循環住在呼叫端，這裡只暴露位置。
     */
    var dragPosition by mutableStateOf<Offset?>(null)
        private set

    /** @return 有沒有真的拎起一張卡（點到縫裡不算）。 */
    fun onDragStart(offset: Offset): Boolean {
        gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            offset.y >= item.offset.y && offset.y <= item.offset.y + item.size.height &&
            offset.x >= item.offset.x && offset.x <= item.offset.x + item.size.width
        }?.let {
            draggingItemIndex = it.index
            initialItemIndex = it.index
            currentItemIndex = it.index
            // 起拖中心＝卡中心（手指不一定按在正中，遲滯基準從卡中心起算，
            // 否則第一下就可能誤判已過線）。
            prevCenterX = it.offset.x + it.size.width / 2f
            prevCenterY = it.offset.y + it.size.height / 2f
            dragPosition = offset
            isDragging = true
            return true
        }
        return false
    }

    fun onDrag(dragAmount: Offset) {
        if (!isDragging) return
        dragOffset += dragAmount

        val draggingIndex = currentItemIndex ?: return
        val draggingItem = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == draggingIndex } ?: return

        val centerX = draggingItem.offset.x + dragOffset.x + draggingItem.size.width / 2f
        val centerY = draggingItem.offset.y + dragOffset.y + draggingItem.size.height / 2f

        val targetItem = gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            centerX >= item.offset.x && centerX <= item.offset.x + item.size.width &&
            centerY >= item.offset.y && centerY <= item.offset.y + item.size.height &&
            item.index != draggingIndex
        }

        if (targetItem != null) {
            // 過中心線才換位（遲滯）：只進 bounds 就換，手指停在邊界會來回橫跳、
            // 整排看起來在飄。上一幀中心還在目標中心另一側＝真的穿過去了才算數。
            val targetCx = targetItem.offset.x + targetItem.size.width / 2f
            val targetCy = targetItem.offset.y + targetItem.size.height / 2f
            val crossedX = (prevCenterX - targetCx) * (centerX - targetCx) < 0f
            val crossedY = (prevCenterY - targetCy) * (centerY - targetCy) < 0f
            prevCenterX = centerX
            prevCenterY = centerY
            if (!crossedX && !crossedY) return
            onMove(draggingIndex, targetItem.index)
            currentItemIndex = targetItem.index
            // Offset visual translation by the grid layout shift
            dragOffset -= IntOffset(
                x = targetItem.offset.x - draggingItem.offset.x,
                y = targetItem.offset.y - draggingItem.offset.y
            )
        } else {
            prevCenterX = centerX
            prevCenterY = centerY
        }
    }

    /**
     * @param commit 手勢正常放手才 true：走 onDragEnd 提交。系統取消（來電之類）
     * 傳 false——只復位不提交，舊碼連取消都提交是錯的。
     */
    fun onDragInterrupted(commit: Boolean) {
        if (commit && initialItemIndex != null && currentItemIndex != null && initialItemIndex != currentItemIndex) {
            onDragEnd(initialItemIndex!!, currentItemIndex!!)
        }
        draggingItemIndex = null
        initialItemIndex = null
        currentItemIndex = null
        dragOffset = Offset.Zero
        dragPosition = null
        isDragging = false
    }

    internal fun updateDragPosition(position: Offset) {
        if (isDragging) dragPosition = position
    }

    private fun IntOffset(x: Int, y: Int) = Offset(x.toFloat(), y.toFloat())
}

fun Modifier.reorderable(
    state: ReorderableLazyGridState,
    enabled: Boolean = true,
    /** 真的拎起一張卡時一次（起飛震動放這裡）。 */
    onLift: (() -> Unit)? = null,
    /** 系統取消時一次（呼叫端把預覽順序彈回去）。正常放手不走這裡。 */
    onDragCancel: (() -> Unit)? = null
): Modifier = this.pointerInput(enabled, onLift, onDragCancel) {
    if (!enabled) return@pointerInput
    detectDragGesturesAfterLongPress(
        onDragStart = { offset ->
            if (state.onDragStart(offset)) onLift?.invoke()
        },
        onDrag = { change, dragAmount ->
            change.consume()
            state.updateDragPosition(change.position)
            state.onDrag(dragAmount)
        },
        onDragEnd = {
            state.onDragInterrupted(commit = true)
        },
        onDragCancel = {
            state.onDragInterrupted(commit = false)
            onDragCancel?.invoke()
        }
    )
}
