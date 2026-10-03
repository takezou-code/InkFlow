package com.vic.inkflow.util

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex

fun Modifier.reorderableItem(
    state: ReorderableLazyGridState,
    index: Int
): Modifier {
    val isDragging = state.draggingItemIndex == index
    return this
        .zIndex(if (isDragging) 1f else 0f)
        .graphicsLayer {
            if (isDragging) {
                translationX = state.dragOffset.x
                translationY = state.dragOffset.y
                scaleX = 1.05f
                scaleY = 1.05f
                shadowElevation = 8f
                alpha = 0.9f
            }
        }
}
