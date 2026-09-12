package com.vic.inkflow.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import com.styropyr0.prismal.drawPrismalGlass
import com.styropyr0.prismal.effects.applyPrismalGlassEffects
import com.styropyr0.prismal.prismalGlassEffects
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.vic.inkflow.ui.theme.ShapeLg
import com.vic.inkflow.ui.theme.ShapeMd
import com.vic.inkflow.ui.theme.ShapeSm
import com.vic.inkflow.ui.theme.ShapeXl
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Liquid-glass material: real-time backdrop blur (haze/RenderEffect) +
 * translucent tint + a specular top-edge highlight that mimics the lit rim
 * of curved glass.
 */

/** Heavy liquid glass — deeper tint + stronger blur so panels stand out. */
private val GlassTintLight = Color(0x59FFFFFF) // 35%:更透，讓模糊背板自己說話
private val GlassTintDark = Color(0x800F172A) // 50% dark liquid

@Composable
fun rememberHazeState(): HazeState = remember { HazeState() }

// backgroundColor 必須 Transparent，實色會直接蓋掉 blur
fun glassStyle(isDark: Boolean): HazeStyle = HazeStyle(
    backgroundColor = Color.Transparent,
    tints = listOf(HazeTint(if (isDark) GlassTintDark else GlassTintLight)),
    blurRadius = 14.dp,
    noiseFactor = 0.02f
)

/**
 * 重點面板用真折射：有 backdrop 就走 Prismal AGSL，沒有就退回 haze 模糊
 *（小元件不傳 backdrop，維持糊、省電也不吵）。
 */
@Composable
fun Modifier.smartGlass(
    state: HazeState,
    isDark: Boolean,
    shape: Shape = ShapeLg,
    specular: Boolean = true,
    prismal: com.styropyr0.prismal.PrismalBackdrop? = null
): Modifier {
    if (prismal == null) return glassPanel(state, isDark, shape, specular)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val prismalShape = when (shape) {
        CircleShape -> com.styropyr0.prismal.shapes.PrismalCapsule()
        ShapeSm -> com.styropyr0.prismal.shapes.PrismalRoundedRectangle(12.dp)
        ShapeMd -> com.styropyr0.prismal.shapes.PrismalRoundedRectangle(16.dp)
        ShapeXl -> com.styropyr0.prismal.shapes.PrismalRoundedRectangle(32.dp)
        else -> com.styropyr0.prismal.shapes.PrismalRoundedRectangle(24.dp)
    }
    var m: Modifier = drawPrismalGlass(
        backdrop = prismal,
        shape = { prismalShape },
        // 顯式配方：折射調強、色散 0.15、模糊調低（去奶味，折射才讀得出來）
        effects = {
            applyPrismalGlassEffects(
                density = density,
                adaptiveLuminance = false,
                luminance = 0.5f,
                blurRadiusPx = with(density) { 6.dp.toPx() },
                refractionHeightPx = with(density) { 14.dp.toPx() },
                refractionAmountPx = with(density) { 28.dp.toPx() },
                chromaticAberration = 0.15f
            )
        }
    )
    // haze 的頂光/邊框留著疊：折射管彎光，這層管打光，兩層加起來才像。
    // 頂光砍半（之前是給糊底的，蓋在折射上只會糊掉細節）。
    m = m
        .background(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.White.copy(alpha = if (isDark) 0.05f else 0.07f),
                    0.3f to Color.Transparent,
                    1f to Color.Black.copy(alpha = if (isDark) 0.08f else 0.05f)
                )
            ),
            shape = shape
        )
    if (specular) {
        val rimTop = if (isDark) Color.White.copy(alpha = 0.70f) else Color.White.copy(alpha = 0.95f)
        val rimBottom = if (isDark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.18f)
        m = m.border(
            border = BorderStroke(
                width = 1.dp,
                brush = Brush.verticalGradient(listOf(rimTop, rimBottom))
            ),
            shape = shape
        )
    }
    // 出現動畫：scale 0.96→1 + 淡入（掛載播一次，圖層階段讀，不重組）
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val appearScale by animateFloatAsState(
        targetValue = if (shown) 1f else 0.96f,
        animationSpec = spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
        ),
        label = "GlassAppearScale"
    )
    val appearAlpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(300),
        label = "GlassAppearAlpha"
    )
    return m.graphicsLayer {
        scaleX = appearScale
        scaleY = appearScale
        alpha = appearAlpha
    }
}

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
        // 頂部 Sheen 反光：玻璃感的關鍵，壓在 blur 上、內容下；
        // 顶部收窄提亮（切割玻璃邊），底部加一點內陰影做出厚度
        .background(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.White.copy(alpha = if (isDark) 0.18f else 0.26f),
                    0.18f to Color.White.copy(alpha = if (isDark) 0.06f else 0.08f),
                    0.45f to Color.Transparent,
                    0.8f to Color.Transparent,
                    1f to Color.Black.copy(alpha = if (isDark) 0.12f else 0.07f)
                )
            ),
            shape = shape
        )
        // 斜射高光：假裝左上有光源，曲面感就靠這一道
        .background(
            brush = Brush.linearGradient(
                colorStops = arrayOf(
                    0f to Color.White.copy(alpha = if (isDark) 0.07f else 0.10f),
                    0.4f to Color.Transparent
                ),
                start = androidx.compose.ui.geometry.Offset.Zero,
                end = androidx.compose.ui.geometry.Offset(10000f, 10000f)
            ),
            shape = shape,
            alpha = 1f
        )
    if (specular) {
        // 亮邊框：頂近實白、底淡出；亮色再加一道外圈暗髮絲，亮底上才跳得出來
        val rimTop = if (isDark) Color.White.copy(alpha = 0.80f) else Color.White
        val rimBottom = if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.22f)
        m = m.border(
            border = BorderStroke(
                width = 1.dp,
                brush = Brush.verticalGradient(listOf(rimTop, rimBottom))
            ),
            shape = shape
        )
        if (!isDark) {
            m = m.border(
                border = BorderStroke(
                    width = 0.5.dp,
                    color = Color(0xFF0F172A).copy(alpha = 0.10f)
                ),
                shape = shape
            )
        }
    }
    return m
}
