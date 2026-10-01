package com.vic.inkflow.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vic.inkflow.ui.theme.GlassVeilDark
import com.vic.inkflow.ui.theme.GlassVeilLight
import com.vic.inkflow.ui.theme.ShapeLg
import com.vic.inkflow.ui.theme.ShapeMd
import com.vic.inkflow.ui.theme.ShapeSm
import com.vic.inkflow.ui.theme.ShapeXl
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.glass.ChromaticAberrationMode
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.SurfaceProfile
import dev.chrisbanes.haze.glass.hazeGlass
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
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

/** 玻璃色票唯一來源（theme/Color.kt）：faux 半透明底（無 blur，靠它保可讀）。 */
@Composable
fun rememberHazeState(): HazeState = remember { HazeState() }

/** 內容色：壓在玻璃上的圖標/字該用哪個色，由玻璃這裡單一宣告，呼叫端禁寫死。 */
fun glassContentColor(isDark: Boolean): Color =
    if (isDark) Color.White else com.vic.inkflow.ui.theme.PaperInkColor

/**
 * 靜模式旗標，全 App 唯一來源（AppNav 提供）。開＝靜時：
 *  - 玻璃走 [hazeBlur]（保留 veil ＋ rim ＋ 模糊，只拿掉真折射與色散）
 *  - 背景凍結在首幀
 *  - 幀率降 60、自動備份暫停
 * 呼叫端禁自己再存一份。
 */
val LocalQuietMode = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * 真折射玻璃配方（唯一）。
 *
 * 原則：**以 [GlassStyle.clear] 為基準，只做刻意的覆寫**，不要逐項自創數值。
 * 官方 clearDark 的實測值是 specularExponent=16f、whitePoint=-0.18f、ambientResponse=0.22f。
 * 過去我們把 specularExponent 寫成 1.6f（官方 10 倍低）＋ specularIntensity 0.9，
 * 等於把高光從「邊緣一條集中亮線」變成「整片均勻平塗」，那層亮光會把深色 veil 抵銷，
 * 於是黑玻璃與折射都看不見（症狀：黑玻璃消失、只剩一片糊）。
 *
 * 刻意覆寫只有四項（其餘全部沿用 clear／clearDark 官方值）：
 *  1. blurRadius 7dp ＋ 折射加強（見下方「模糊 vs 折射的取捨」）
 *  2. chromaticAberrationStrength 0.3f（clear 只有 0.04f，等於沒色散）
 *  3. tint 用 App 的 veil 色票（唯一色票來源）
 *  4. shape 跟呼叫端走
 *
 * 【模糊 vs 折射的取捨】模糊是全片平均，折射是位移採樣。當 blurRadius > 位移量，
 * 位移差異被平均掉 → 折射看不見。所以要「折射明顯」就必須模糊夠淺，
 * 通透感改由 refractionStrength／displacement／detailIntensity 拿。
 */
@OptIn(ExperimentalHazeApi::class)
fun glassStyle(isDark: Boolean, shape: Shape = ShapeLg): GlassStyle =
    GlassStyle.clear.then {
        backgroundColor(Color.Transparent)
        tint(if (isDark) com.vic.inkflow.ui.theme.GlassVeilDark else com.vic.inkflow.ui.theme.GlassVeilLight)
        optics(
            // 模糊保持「淺」（官方 clear 是 1.25dp）。原因：模糊半徑一旦大於折射位移量，
            // 位移產生的細節差異會被平均掉，折射就看不見了（模糊會把折射「吃掉」）。
            // 所以模糊收淺，改用「折射本身加強」來拿通透感。
            blurRadius = 7.dp,
            // 折射加強補償模糊減少：位移與細節拉高，邊緣彎曲更明顯。
            refractionStrength = 1f,
            refractionDisplacement = 56.dp,
            refractionHeightFraction = 0.35f,
            refractionDetailIntensity = 1f,
        )
        // 光譜色散：clear 只有 0.04f（≈關閉）。Simple 模式每幀一層，Full 太貴。
        chromaticAberrationMode(ChromaticAberrationMode.Simple)
        chromaticAberrationStrength(0.3f)
        shape((shape as? RoundedCornerShape) ?: RoundedCornerShape(24.dp))
    }

/**
 * 全統一入口（真折射）。所有玻璃容器都走這一支：
 *  - 同窗面板與對話框/下拉（跨視窗）都用 [HazeInput.Sources]：可攜路徑，真的去 source 採樣。
 *  - 內容色由 [glassContentColor] 單一宣告，呼叫端禁自訂圖標色。
 *  - 靜模式（[LocalQuietMode]）只關掉「貴的」：真折射＋色散。模糊＋veil＋rim 留著，
 *    黑玻璃在兩種模式都正常渲染（靜模式走 [hazeBlur]，不是沒模糊的 faux）。
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun Modifier.glassPanel(
    state: HazeState,
    isDark: Boolean,
    shape: Shape = ShapeLg,
    specular: Boolean = true,
    input: HazeInput = HazeInput.Sources(state)
): Modifier {
    val quiet = LocalQuietMode.current
    val base = this.clip(shape)
    // 靜模式：同樣的 veil 與 rim，只把折射/色散拿掉，模糊留著
    if (quiet) {
        return base
            .hazeBlur(
                input = input,
                style = HazeBlurStyle {
                    backgroundColor(Color.Transparent)
                    colorEffects(listOf(HazeColorEffect.tint(if (isDark) GlassVeilDark else GlassVeilLight)))
                    blurRadius(24.dp)
                    noiseFactor(0.02f)
                }
            )
            .glassDressing(isDark, shape, specular)
    }
    return base
        .hazeGlass(input = input, style = glassStyle(isDark, shape))
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
 * 啫喱按壓：縮放 + 提亮 + 掃光（按下去一道光從左上掃到右下，靜止時零成本）
 * ＋輕觸感（流光限定，靜模式不震；滑動拖曳不震，只認按下那一下）。
 */
@Composable
fun Modifier.pressableGlass(
    interactionSource: MutableInteractionSource,
    shape: Shape = ShapeLg,
    pressedScale: Float = 0.96f
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val quiet = LocalQuietMode.current
    // 只在「由未按→按」那一刻震一次；放開不震，長按滑動不重複
    androidx.compose.runtime.LaunchedEffect(pressed, quiet) {
        // TextHandleMove 映射到平台輕點擊，比 LongPress 輕、比無回饋有感
        if (pressed && !quiet) {
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        }
    }
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
    // 掃光：只在按壓期間播一次（0→1 去，1→0 回），靜止時 alpha=0 不多一層開銷
    val sweep by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(if (pressed) 450 else 200),
        label = "PressSweep"
    )
    // 按壓 rim 閃一下：玻璃被壓時邊緣亮起來（凝膠感）
    val rimFlash by animateFloatAsState(
        targetValue = if (pressed) 0.55f else 0f,
        animationSpec = tween(if (pressed) 90 else 260),
        label = "PressRim"
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .background(Color.White.copy(alpha = glow), shape)
        .border(BorderStroke(1.dp, Color.White.copy(alpha = rimFlash)), shape)
        .sweepLayer(sweep, shape)
}

/**
 * 純掃光疊層：給 M3 Card/Surface 用（點擊走它們自己的 onClick，只借按壓源畫掃光）。
 * 一般可壓玻璃走 [glassClickable]，不要拆開調這裡。
 */
@Composable
fun Modifier.glassSweep(
    source: MutableInteractionSource,
    shape: Shape = ShapeLg
): Modifier {
    val pressed by source.collectIsPressedAsState()
    val sweep by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(if (pressed) 450 else 200),
        label = "GlassSweep"
    )
    return this.sweepLayer(sweep, shape)
}

/** 掃光實繪：靜止時 alpha=0 不多一層開銷。 */
private fun Modifier.sweepLayer(sweep: Float, shape: Shape): Modifier = this.then(
    if (sweep > 0.01f) Modifier.background(
        brush = Brush.linearGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                ((0.05f + 0.55f * sweep).coerceIn(0f, 1f)) to Color.Transparent,
                ((0.30f + 0.55f * sweep).coerceIn(0f, 1f)) to Color.White.copy(alpha = 0.30f * sweep),
                ((0.60f + 0.55f * sweep).coerceIn(0f, 1f)) to Color.Transparent,
                1f to Color.Transparent
            )
        ),
        shape = shape
    ) else Modifier
)

/**
 * 按壓統一入口：掃光＋縮放＋觸感＋無漣漪點擊一次包好。
 * 以後新增可壓玻璃一律走這裡，不各自拼 source/clickable。
 */
@Composable
fun Modifier.glassClickable(
    onClick: () -> Unit,
    shape: Shape = ShapeLg,
    enabled: Boolean = true,
): Modifier {
    val source = remember { MutableInteractionSource() }
    return this
        .pressableGlass(source, shape)
        .clickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )
}

/**
 * 卡片類容器的玻璃底：有 hazeState 走真折射玻璃，無就 faux（省 blur，給大量重複卡片用）。
 * 分支單一在這裡，呼叫端不要自己判斷。
 */
@Composable
fun Modifier.glassCardSurface(
    isDark: Boolean,
    shape: Shape = ShapeLg,
    hazeState: HazeState? = null
): Modifier = if (hazeState != null) this.glassPanel(hazeState, isDark, shape)
    else this.fauxGlassPanel(isDark, shape)

/** 玻璃下拉（唯一入口）：壳自帶理璃卡，列走 [GlassMenuItem]，零 M3 chrome。 */
@Composable
fun GlassMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    shape: Shape = ShapeMd,
    isDark: Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f,
    hazeState: HazeState? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        containerColor = Color.Transparent,
        shadowElevation = 0.dp,
        shape = shape,
        modifier = Modifier.glassCardSurface(isDark, shape, hazeState)
    ) { content() }
}

/** 下拉列：圖標＋文字，統一理璃按壓（掃光＋觸感）。 */
@Composable
fun ColumnScope.GlassMenuItem(
    text: String,
    icon: ImageVector? = null,
    isDark: Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassClickable(onClick = onClick, shape = ShapeMd)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val ink = glassContentColor(isDark)
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = ink)
    }
}

@Composable
fun Modifier.fauxGlassPanel(
    isDark: Boolean,
    shape: Shape = ShapeLg,
    specular: Boolean = true
): Modifier {
    return this
        .clip(shape)
        .background(
            color = if (isDark) com.vic.inkflow.ui.theme.GlassTintDark else com.vic.inkflow.ui.theme.GlassTintLight,
            shape = shape
        )
        .glassDressing(isDark, shape, specular)
}

/**
 * 共用打光：薄 Sheen ＋ 頂光 rim，全 App 唯一玻璃妝。
 * faux 路徑沒有 shader 高光，靠這層薄妝＋rim 維持玻璃讀感。
 *
 * 注意：這裡刻意不用 Modifier.shadow()——在小米平板 GPU 上，shadow 層會在文字後
 * 留下白色行框殘影（9 輪診斷版實證：有 shadow 全有框、拿掉全沒框，描邊/亮面/模糊皆無罪）。
 * 深度改由 rim＋底內陰影撐，不要加 shadow 回來。
 *
 * 待機不播 sheen：每個玻璃件各掛一個無限動畫會強制整窗每秒重繪 60 次，實測 fps 從 60
 * 掉到 30–42。要動感用按壓那一下（[glassClickable] 的掃光），不要加回待機動畫。
 * rim 要細（0.75dp 且低透明）：粗 rim 會有廉價感，邊緣的光影交給 haze 的 specular。
 */
@Composable
private fun Modifier.glassDressing(
    isDark: Boolean,
    shape: Shape,
    specular: Boolean
): Modifier {
    var m = this
        // 頂部 Sheen v4：hazeGlass 自帶高光，妝只留髮絲亮緣＋極淡罩紗。
        // 底部內陰影保留做厚度（減淡）。
        .background(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.White.copy(alpha = if (isDark) 0.07f else 0.08f),
                    0.06f to Color.White.copy(alpha = if (isDark) 0.02f else 0.02f),
                    0.35f to Color.Transparent,
                    0.8f to Color.Transparent,
                    1f to Color.Black.copy(alpha = if (isDark) 0.08f else 0.05f)
                )
            ),
            shape = shape
        )
    if (specular) {
        // rim 只留「頂緣一道細光」：定邊靠它，但其餘三邊幾乎不畫。
        // 粗 rim＋滿版白線＝廉價感（那是 2020 年的 filter UI，不是玻璃）。
        // 真正的「跟光影反應」交給 haze 的 specular（依 lightPosition 算，會跟形狀與光源跑），
        // 這裡只補一層幾乎看不見的底緣暗線把形狀收乾淨，不要跟它搶。
        m = m.border(
            border = BorderStroke(
                width = 0.75.dp,
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.White.copy(alpha = 0.34f),
                        0.18f to Color.White.copy(alpha = 0.06f),
                        0.72f to Color.White.copy(alpha = 0.02f),
                        1f to Color.White.copy(alpha = 0.05f)
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
 * 全統一對話框殼（單一入口）：ui.window.Dialog（純視窗＋系統遮罩，零 M3）
 * ＋ 單一玻璃卡 ＋ GlassTextButton。
 *
 * 背底＝系統調光（跟以前 M3 框一模一樣：全屏稍微變暗＋框浮起），不另加模糊層。
 *
 * 殼內鐵律：單一背景層（卡是唯一底）、零 M3 widget（禁 Surface/TextButton/FilterChip）、
 * 禁第二層底（分區用分隔線＋字階，不疊 Surface）。要開對話框一律走這裡。
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
    // 穿窗真玻璃：傳入背後螢幕的 HazeState 即升 glassPanelDialog，不傳沿用 faux
    hazeState: HazeState? = null,
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false)
) {
    GlassDialogFrame(
        onDismissRequest = onDismissRequest,
        isDark = isDark,
        hazeState = hazeState,
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

/** 按鈕列自訂版（選項清單／輸入框那種）：只借視窗＋模糊背底＋玻璃卡，按鈕自己排。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassDialogCustom(
    onDismissRequest: () -> Unit,
    isDark: Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f,
    title: @Composable () -> Unit,
    text: (@Composable () -> Unit)? = null,
    // 穿窗真玻璃：傳入背後螢幕的 HazeState 即升 glassPanelDialog，不傳沿用 faux
    hazeState: HazeState? = null,
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false),
    buttons: @Composable RowScope.() -> Unit
) {
    GlassDialogFrame(
        onDismissRequest = onDismissRequest,
        isDark = isDark,
        hazeState = hazeState,
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
    hazeState: HazeState? = null,
    title: @Composable () -> Unit,
    text: (@Composable () -> Unit)?,
    properties: DialogProperties,
    buttons: @Composable RowScope.() -> Unit
) {
    // 注意：不用 M3 BasicAlertDialog——它在內容外再套 sizeIn(280..560dp) 的 Box，
    // 全屏層會被箍成 560dp 寬的豎帶（背景腰帶 bug）。ui.window.Dialog 是純視窗，無箍。
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics { paneTitle = "對話框" },
            contentAlignment = Alignment.Center
        ) {
            // 背底＝系統調光＋這層補暗（單一數字調深淺）：全屏稍微變暗＋框浮起。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
            )
            // 卡片自播 scale＋淡入（外層 AnimatedDialog 只剩純淡入，背底不再跟著縮放跳）。
            var cardShown by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { cardShown = true }
            val cardScale by animateFloatAsState(
                targetValue = if (cardShown) 1f else 0.94f,
                animationSpec = tween(220),
                label = "DialogCardScale"
            )
            val cardAlpha by animateFloatAsState(
                targetValue = if (cardShown) 1f else 0f,
                animationSpec = tween(180),
                label = "DialogCardAlpha"
            )
            // 全殼唯一背景層：有 state 穿窗真玻璃，無 state 沿用 faux
            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .widthIn(min = 280.dp, max = 560.dp)
                    .then(
                        if (hazeState != null) Modifier.glassPanel(hazeState, isDark, ShapeLg, input = HazeInput.Sources(hazeState))
                        else Modifier.fauxGlassPanel(isDark, ShapeLg)
                    )
                    .padding(24.dp)
                    .graphicsLayer {
                        scaleX = cardScale
                        scaleY = cardScale
                        alpha = cardAlpha
                    },
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
}

/** 分段選擇條：單顆玻璃槽＋選中藥丸＋按壓掃光，設定頁那種選項列走這裡，不單擺 chips。 */
@Composable
fun GlassSegmentedBar(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    isDark: Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .fauxGlassPanel(isDark, CircleShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEachIndexed { idx, label ->
            val selected = idx == selectedIndex
            val pillAlpha by animateFloatAsState(
                targetValue = if (selected) 1f else 0f,
                animationSpec = tween(180),
                label = "SegmentPill"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .glassClickable(onClick = { onSelect(idx) }, shape = CircleShape)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f * pillAlpha),
                        shape = CircleShape
                    )
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 對話框選項丸（取代 M3 FilterChip）：選中靛底＋白字，未選中半透明＋主色字，零 M3 chrome。 */
@Composable
fun GlassOptionChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDark: Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .glassClickable(onClick = onClick, shape = CircleShape)
            .background(
                color = if (selected) MaterialTheme.colorScheme.primary
                else Color.White.copy(alpha = if (isDark) 0.10f else 0.55f),
                shape = CircleShape
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Color.White
            else MaterialTheme.colorScheme.primary
        )
    }
}

/** 對話框按鈕：素字＋無漣漪点击，零 M3 chrome。長清單選項用 alignStart 撐滿整行。 */@Composable
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
            .glassClickable(onClick = onClick, shape = CircleShape, enabled = enabled)
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
