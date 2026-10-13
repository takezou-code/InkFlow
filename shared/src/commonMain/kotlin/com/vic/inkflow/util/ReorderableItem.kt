package com.vic.inkflow.util

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex

@Composable
fun Modifier.reorderableItem(
    state: ReorderableLazyGridState,
    index: Int
): Modifier {
    val isDragging = state.draggingItemIndex == index
    // 拿起動畫：快速到位、不帶過衝。舊碼 MediumBouncy 會先衝過頭再回來，
    // 看起來像卡往上彈一下＝用户說的「飄」；stiffness 跟選中放大同一組。
    val lift by animateFloatAsState(
        targetValue = if (isDragging) 1.05f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "ReorderLift"
    )
    return this
        .zIndex(if (isDragging) 1f else 0f)
        .graphicsLayer {
            translationX = if (isDragging) state.dragOffset.x else 0f
            translationY = if (isDragging) state.dragOffset.y else 0f
            scaleX = lift
            scaleY = lift
            shadowElevation = if (isDragging) 8f else 0f
            alpha = if (isDragging) 0.9f else 1f
        }
}
