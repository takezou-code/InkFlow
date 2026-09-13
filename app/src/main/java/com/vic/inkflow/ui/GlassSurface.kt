package com.vic.inkflow.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.DialogProperties
import com.styropyr0.prismal.drawPrismalGlass
import com.styropyr0.prismal.depth.PrismalDepthInset
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
// S3：haze 沒有 vibrancy，只能靠「少悶」保鮮豔——打底＋tint 都減淡，讓背底顏色透出來。
// （faux 無 blur，維持原濃度保文字可讀，不共用這組）
fun glassStyle(isDark: Boolean): HazeStyle = HazeStyle(
    backgroundColor = if (isDark) Color.Black.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.08f),
    tints = listOf(
        HazeTint(if (isDark) Color(0x4D0F172A) else Color(0x30FFFFFF))
    ),
    blurRadius = 16.dp,
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
        // S3 高級感配方（對標 iOS Liquid Glass）：
        // - blur 8dp（官方 base 值；3dp 太透像塑膠片，frosted 是厚重感來源）
        // - 折射收窄收斂（12/26：邊帶銳、中央平，透鏡感集中在邊緣才像真玻璃）
        // - CA 0.1（S2 教訓：常駐色散顯假，只留邊緣一絲）
        // - vibrancy 由 applyPrismalGlassEffects 預設開啟（useVibrancy=true）：背底飽和提升，
        //   iOS 招牌「pumped」色感就靠它
        // - specular/depthShadow 走庫預設（真 Blinn-Phong 高光＋深度陰影，不再只靠手畫漸層）
        // - depthInset 內陰影：玻璃厚度感，沒有它邊緣就是一張皮
        effects = {
            applyPrismalGlassEffects(
                density = density,
                adaptiveLuminance = false,
                luminance = 0.5f,
                blurRadiusPx = with(density) { 8.dp.toPx() },
                refractionHeightPx = with(density) { 12.dp.toPx() },
                refractionAmountPx = with(density) { 26.dp.toPx() },
                chromaticAberration = 0.1f
            )
        },
        depthInset = { PrismalDepthInset(radius = 8.dp) },
        // 表面罩紗：iOS 表面只有約 0.08 白，之前 Compose dressing 疊 0.38 直接洗掉折射。
        // 這裡只留一層薄紗，立體打光交給 shader 高光＋減薄後的 dressing。
        onDrawSurface = { drawRect(Color.White.copy(alpha = 0.06f)) }
    )
    // 採樣空窗打底：折射還沒抓到 backdrop 那幀，看到深灰而不是純黑洞。
    // 減淡（暗 25%→18%）：平時這層會悶住折射，是廉價感共犯之一。
    m = m.background(
        color = if (isDark) Color(0x2E0F172A) else Color(0x14FFFFFF),
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
 * 共用打光 v3：prismal 路徑已有真高光/真陰影（shader 算的），這裡只補「妝」不搶戲。
 * v2 的 0.38 白罩＋斜射是廉價感主因（直接蓋掉折射）：全部減到點到為止。
 * haze/faux 路徑沒有 shader 高光，靠這層薄妝＋rim 維持玻璃讀感。
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
        // 頂部 Sheen v3：只留髮絲亮緣＋極淡罩紗（之前 0.38 直接洗掉折射）。
        // 底部內陰影保留做厚度。
        .background(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.White.copy(alpha = if (isDark) 0.12f else 0.14f),
                    0.06f to Color.White.copy(alpha = if (isDark) 0.03f else 0.04f),
                    0.35f to Color.Transparent,
                    0.8f to Color.Transparent,
                    1f to Color.Black.copy(alpha = if (isDark) 0.12f else 0.07f)
                )
            ),
            shape = shape
        )
    if (specular) {
        // iOS 式單向光 rim：頂緣受光亮、兩側速衰、底緣近乎隱形只留一絲環境光。
        // 整圈等亮＝塑膠感來源；shader 高光已罩全邊，這根只負責「頂光」。
        val rimTop = if (isDark) Color.White.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.65f)
        m = m.border(
            border = BorderStroke(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to rimTop,
                        0.25f to Color.White.copy(alpha = 0.05f),
                        0.7f to Color.White.copy(alpha = 0.02f),
                        1f to Color.White.copy(alpha = 0.07f)
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

/**
 * 無鉻玻璃對話框：BasicAlertDialog（純視窗＋遮罩，零 M3 Surface）＋ Box ＋ fauxGlassPanel。
 *
 * M3 AlertDialog 會在 modifier 外框之內再套一層 tonal Surface（內縮約 24dp），
 * 玻璃上就是一道直角內框——跟之前氣泡的白帶同一類 M3 chrome（Bisect 定案：對話框
 * subtree 零 M3 Surface/TextButton）。要開對話框一律走這裡，不要直接用 M3 AlertDialog。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassDialog(
    onDismissRequest: () -> Unit,
    isDark: Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f,
    title: @Composable () -> Unit,
    text: (@Composable () -> Unit)? = null,
    confirmText: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    confirmColor: Color = Color.Unspecified,
    dismissText: String? = "取消",
    onDismissClick: () -> Unit = onDismissRequest,
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false)
) {
    GlassDialogFrame(
        onDismissRequest = onDismissRequest,
        isDark = isDark,
        title = title,
        text = text,
        properties = properties
    ) {
        if (dismissText != null) {
            GlassTextButton(text = dismissText, onClick = onDismissClick)
            Spacer(Modifier.width(8.dp))
        }
        GlassTextButton(
            text = confirmText,
            onClick = onConfirm,
            enabled = confirmEnabled,
            color = if (confirmColor == Color.Unspecified) MaterialTheme.colorScheme.primary else confirmColor
        )
    }
}

/** 按鈕列自訂版（text 裡有選項清單那種）：只借視窗＋玻璃框，按鈕自己排。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassDialogCustom(
    onDismissRequest: () -> Unit,
    isDark: Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f,
    title: @Composable () -> Unit,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false),
    buttons: @Composable RowScope.() -> Unit
) {
    GlassDialogFrame(
        onDismissRequest = onDismissRequest,
        isDark = isDark,
        title = title,
        text = text,
        properties = properties,
        buttons = buttons
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlassDialogFrame(
    onDismissRequest: () -> Unit,
    isDark: Boolean,
    title: @Composable () -> Unit,
    text: (@Composable () -> Unit)?,
    properties: DialogProperties,
    buttons: @Composable RowScope.() -> Unit
) {
    BasicAlertDialog(onDismissRequest = onDismissRequest, properties = properties) {
        Column(
            modifier = Modifier
                .widthIn(min = 280.dp, max = 560.dp)
                .fauxGlassPanel(isDark, ShapeLg)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // M3 AlertDialog 同款字階：標題 headlineSmall/onSurface、內文 bodyMedium/onSurfaceVariant；
            // 呼叫端有寫 style/color 的照樣覆蓋，只影響沒寫的 Text。
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                LocalTextStyle provides MaterialTheme.typography.headlineSmall
            ) { title() }
            if (text != null) {
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                    LocalTextStyle provides MaterialTheme.typography.bodyMedium
                ) { text() }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                content = buttons
            )
        }
    }
}

/** 對話框按鈕：素字＋無漣漪点击，零 M3 chrome。長清單選項用 alignStart 撐滿整行。 */
@Composable
fun GlassTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
    alignStart: Boolean = false
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = if (alignStart) Alignment.CenterStart else Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = color.copy(alpha = if (enabled) 1f else 0.38f)
        )
    }
}
