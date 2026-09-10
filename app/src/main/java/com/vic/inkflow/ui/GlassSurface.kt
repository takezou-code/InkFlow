package com.vic.inkflow.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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

/** Heavy liquid glass — deeper tint + stronger blur so panels stand out. */
private val GlassTintLight = Color(0x66FFFFFF) // 40%
private val GlassTintDark = Color(0x800F172A) // 50% dark liquid

@Composable
fun rememberHazeState(): HazeState = remember { HazeState() }

// backgroundColor 必須 Transparent，實色會直接蓋掉 blur
fun glassStyle(isDark: Boolean): HazeStyle = HazeStyle(
    backgroundColor = Color.Transparent,
    tints = listOf(HazeTint(if (isDark) GlassTintDark else GlassTintLight)),
    blurRadius = 30.dp,
    noiseFactor = 0.02f
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
        // 頂部 Sheen 反光：玻璃感的關鍵，壓在 blur 上、內容下
        .background(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.White.copy(alpha = if (isDark) 0.10f else 0.16f),
                    0.35f to Color.White.copy(alpha = 0.02f),
                    1f to Color.Transparent
                )
            ),
            shape = shape
        )
    if (specular) {
        val rimTop = if (isDark) Color.White.copy(alpha = 0.60f) else Color.White.copy(alpha = 0.90f)
        val rimBottom = if (isDark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.15f)
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
