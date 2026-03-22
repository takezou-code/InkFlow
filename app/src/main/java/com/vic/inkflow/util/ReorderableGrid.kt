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

    internal var initialItemIndex: Int? = null
    internal var currentItemIndex: Int? = null

    var dragOffset by mutableStateOf(Offset.Zero)
        private set

    var isDragging by mutableStateOf(false)
        private set

    fun onDragStart(offset: Offset) {
        gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            offset.y >= item.offset.y && offset.y <= item.offset.y + item.size.height &&
            offset.x >= item.offset.x && offset.x <= item.offset.x + item.size.width
        }?.let {
            draggingItemIndex = it.index
            initialItemIndex = it.index
            currentItemIndex = it.index
            isDragging = true
        }
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
            onMove(draggingIndex, targetItem.index)
            currentItemIndex = targetItem.index
            // Offset visual translation by the grid layout shift
            dragOffset -= IntOffset(
                x = targetItem.offset.x - draggingItem.offset.x,
                y = targetItem.offset.y - draggingItem.offset.y
            )
        }
    }

    fun onDragInterrupted() {
        if (initialItemIndex != null && currentItemIndex != null && initialItemIndex != currentItemIndex) {
            onDragEnd(initialItemIndex!!, currentItemIndex!!)
        }
        draggingItemIndex = null
        initialItemIndex = null
        currentItemIndex = null
        dragOffset = Offset.Zero
        isDragging = false
    }

    private fun IntOffset(x: Int, y: Int) = Offset(x.toFloat(), y.toFloat())
}

fun Modifier.reorderable(state: ReorderableLazyGridState, enabled: Boolean = true): Modifier = this.pointerInput(enabled) {
    if (!enabled) return@pointerInput
    detectDragGesturesAfterLongPress(
        onDragStart = { offset ->
            state.onDragStart(offset)
        },
        onDrag = { change, dragAmount ->
            change.consume()
            state.onDrag(dragAmount)
        },
        onDragEnd = {
            state.onDragInterrupted()
        },
        onDragCancel = {
            state.onDragInterrupted()
        }
    )
}
