package com.vic.inkflow.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.vic.inkflow.ui.theme.ShapeLg
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Liquid-glass material: real-time backdrop blur (haze/RenderEffect) +
 * translucent tint + a specular top-edge highlight that mimics the lit rim
 * of curved glass.
 */

/** Translucent glass tint per theme — blur supplies legibility underneath. */
private val GlassTintLight = Color(0xC8F6FAFF)
private val GlassTintDark = Color(0xB01E293B)

@Composable
fun rememberHazeState(): HazeState = remember { HazeState() }

fun glassStyle(isDark: Boolean): HazeStyle = HazeStyle(
    backgroundColor = Color.Transparent,
    tints = listOf(HazeTint(if (isDark) GlassTintDark else GlassTintLight)),
    blurRadius = 22.dp,
    noiseFactor = 0.04f
)

/**
 * Turn any surface into liquid glass. Attach [com.vic.inkflow.ui.hazeSource]
 * (Modifier.hazeSource) to the scene content BEHIND these panels first.
 */
fun Modifier.glassPanel(
    state: HazeState,
    isDark: Boolean,
    shape: Shape = ShapeLg,
    specular: Boolean = true
): Modifier {
    var m = this
        .clip(shape)
        .hazeEffect(state, style = glassStyle(isDark))
    if (specular) {
        val rimTop = if (isDark) Color.White.copy(alpha = 0.32f) else Color.White.copy(alpha = 0.65f)
        val rimBottom = if (isDark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.10f)
        m = m.border(
            border = BorderStroke(
                width = 1.dp,
                brush = Brush.verticalGradient(listOf(rimTop, rimBottom))
            ),
            shape = shape
        )
    }
    return m
}
