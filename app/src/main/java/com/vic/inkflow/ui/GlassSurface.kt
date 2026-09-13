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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
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
private val GlassTintLight = Color(0x40FFFFFF) // 25%:往 iOS 透亮派靠，折射扛存在感
private val GlassTintDark = Color(0x660F172A) // 40%:haze/faux 共用同一罐，不再分家

@Composable
fun rememberHazeState(): HazeState = remember { HazeState() }

// backgroundColor 半透明打底：採樣空窗那幀看到的是深灰而不是純黑洞；
// 平時只是多一層薄紗（實色才會蓋掉 blur，半透明不會）。
fun glassStyle(isDark: Boolean): HazeStyle = HazeStyle(
    backgroundColor = if (isDark) Color.Black.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.15f),
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
        // S2 校準：blur 降到 3dp（折射扛大樑）、靜止 CA 收到 0.05（常駐色散顯假）、
        // 折射量往 dramatic 推（直線掰彎才讀得出來是真玻璃）
        effects = {
            applyPrismalGlassEffects(
                density = density,
                adaptiveLuminance = false,
                luminance = 0.5f,
                blurRadiusPx = with(density) { 3.dp.toPx() },
                refractionHeightPx = with(density) { 16.dp.toPx() },
                refractionAmountPx = with(density) { 34.dp.toPx() },
                chromaticAberration = 0.05f
            )
        }
    )
    // 採樣空窗打底：折射還沒抓到 backdrop 那幀，看到深灰而不是純黑洞
    m = m.background(
        color = if (isDark) Color(0x400F172A) else Color(0x26FFFFFF),
        shape = shape
    )
    // 打光走共用 dressing：跟 haze/faux 同款 Sheen + 亮邊，全 App 只剩一種玻璃語言，
    // 差別只剩底是折射 / 模糊 / 半透明（效能分級），表面看起來一致。
    m = m.glassDressing(isDark, shape, specular)
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
    return this
        .clip(shape)
        .hazeEffect(state, style = glassStyle(isDark))
        .glassDressing(isDark, shape, specular)
}

/**
 * 氣泡專用毛玻璃：高遮蓋打底（亮 85% 白 / 暗 90% 藏青）+ 同套高光亮邊。
 * 給壓在紙上的小浮層用——不採樣（紙層當 source 會重採樣凍結），靠遮蓋做出
 * 「浮在紙上」的感覺，而不是透視到底層黑的 X 光片。
 */
fun Modifier.bubbleGlass(
    isDark: Boolean,
    shape: Shape = ShapeLg
): Modifier {
    // 自繪氣泡底：高遮蓋白（紙上浮起感）+ 柔光（無亮帶切層）+ 亮邊 + 白紙專用深髮絲。
    // 注意：不要再包 M3 Surface / 用 TextButton——它們自帶的 chrome 會畫出
    // 一條銳利白帶（Bisect A/B 定案），氣泡 subtree 只用 Box + background + border。
    return this
        .clip(shape)
        .background(
            color = if (isDark) Color(0xE60F172A) else Color(0xE6FFFFFF),
            shape = shape
        )
        // 氣泡專用柔光：頂部只提一點（0.10 且 60% 就淡完），不要亮帶切層；
        // 底部不加陰（小膠囊加了顯髒）；亮邊保留，浮起靠它 + 陰影。
        .background(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.White.copy(alpha = if (isDark) 0.10f else 0.12f),
                    0.6f to Color.Transparent
                )
            ),
            shape = shape
        )
        .border(
            border = BorderStroke(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        if (isDark) Color.White.copy(alpha = 0.80f) else Color.White,
                        if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.22f)
                    )
                )
            ),
            shape = shape
        )
        // 亮 pill 浮在白紙上全靠這根深色髮絲定邊（白邊在白底上隱形）
        .then(
            if (!isDark) Modifier.border(
                border = BorderStroke(
                    width = 0.5.dp,
                    color = Color(0xFF0F172A).copy(alpha = 0.18f)
                ),
                shape = shape
            ) else Modifier
        )
}

/**
 * 對話框內輸入框共用色：跟搜尋丸同一語言（半透明底 + 無框 + 聚焦靛環）。
 */
@Composable
fun glassFieldColors(isDark: Boolean) = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color.White.copy(alpha = if (isDark) 0.16f else 0.70f),
    unfocusedContainerColor = Color.White.copy(alpha = if (isDark) 0.10f else 0.55f),
    disabledContainerColor = Color.White.copy(alpha = if (isDark) 0.10f else 0.55f),
    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
    unfocusedBorderColor = Color.Transparent,
    cursorColor = MaterialTheme.colorScheme.primary
)

/**
 * 啫喱按壓：縮放 + 提亮（gel-press 的 Compose 版前菜；局部鼓起/CA 閃要 shader 接線，後續）。
 */
@Composable
fun Modifier.pressableGlass(
    interactionSource: MutableInteractionSource,
    shape: Shape = ShapeLg,
    pressedScale: Float = 0.96f
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
        ),
        label = "PressScale"
    )
    val glow by animateFloatAsState(
        targetValue = if (pressed) 0.10f else 0f,
        animationSpec = tween(120),
        label = "PressGlow"
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .background(Color.White.copy(alpha = glow), shape)
}

/**
 * 無 blur 的仿玻璃：半透明底 + 同套高光亮邊，背景直接透過去。
 * 給大量重複的卡片用（每卡一個即時模糊是滾動卡頓主因），透光免費。
 */
fun Modifier.fauxGlassPanel(
    isDark: Boolean,
    shape: Shape = ShapeLg,
    specular: Boolean = true
): Modifier {
    return this
        .clip(shape)
        .background(
            color = if (isDark) GlassTintDark else GlassTintLight,
            shape = shape
        )
        .glassDressing(isDark, shape, specular)
}

/**
 * 共用打光 v2：銳頂緣 + 斜射高光 + 雙 rim（頂受光/底環境微光）+ 染色外投影。
 * 壓在底（blur 或半透明色）上、內容下。
 */
private fun Modifier.glassDressing(
    isDark: Boolean,
    shape: Shape,
    specular: Boolean,
    shadowDp: Dp = 6.dp
): Modifier {
    val spot = if (isDark) Color.Black.copy(alpha = 0.45f)
               else Color(0xFF1E1B4B).copy(alpha = 0.28f)
    var m = this
        // 染色外投影：跟著形狀走的深度，取代 M3 灰影
        .shadow(shadowDp, shape, spotColor = spot)
        // 頂部 Sheen v2：收窄提亮成銳帶（iOS 味的關鍵），底部內陰影加深做厚度
        .background(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.White.copy(alpha = if (isDark) 0.30f else 0.38f),
                    0.10f to Color.White.copy(alpha = if (isDark) 0.08f else 0.10f),
                    0.45f to Color.Transparent,
                    0.8f to Color.Transparent,
                    1f to Color.Black.copy(alpha = if (isDark) 0.14f else 0.08f)
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
        // 雙 rim：頂受光亮、底環境微光（iOS 雙邊框），中段安靜
        val rimTop = if (isDark) Color.White.copy(alpha = 0.85f) else Color.White
        val rimMid = Color.White.copy(alpha = 0.06f)
        val rimGlow = if (isDark) Color.White.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.35f)
        m = m.border(
            border = BorderStroke(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to rimTop,
                        0.5f to rimMid,
                        1f to rimGlow
                    )
                )
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
