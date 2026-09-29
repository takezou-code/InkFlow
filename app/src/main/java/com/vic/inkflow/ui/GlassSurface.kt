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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vic.inkflow.ui.theme.ShapeLg
import com.vic.inkflow.ui.theme.ShapeMd
import com.vic.inkflow.ui.theme.ShapeSm
import com.vic.inkflow.ui.theme.ShapeXl
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.SurfaceProfile
import dev.chrisbanes.haze.glass.hazeGlass
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
/** 真折射玻璃：大火晶透（頂光＋凸緣＋淡 tint＋滿折射細節），靜模式走 faux。 */
@OptIn(ExperimentalHazeApi::class)
fun glassStyle(isDark: Boolean, shape: Shape = ShapeLg): GlassStyle =
    GlassStyle.clear.then {
        backgroundColor(Color.Transparent)
        // 本體放透：tint 靛 0.14
        tint(if (isDark) Color(0xFF1E1B4B).copy(alpha = 0.14f) else Color.White.copy(alpha = 0.10f))
        ambientResponse(0f)
        optics(
            blurRadius = 16.dp,
            refractionStrength = 1.0f,
            refractionHeightFraction = 0.25f,
            depth = 0.15f,
            refractionDetailIntensity = 1f
        )
        // 晶透四件：頂光＋凸緣＋高光＋色散；whitePoint 轉負抵消 clear 底提亮
        lightPosition(Alignment.TopCenter)
        surfaceProfile(SurfaceProfile.Lip)
        specularIntensity(0.8f)
        whitePoint(-0.15f)
        chromaticAberrationStrength(0.25f)
        shape((shape as? RoundedCornerShape) ?: RoundedCornerShape(24.dp))
    }

/**
 * 全統一入口（Prismal 真折射已退役）：等同 [glassPanel]。
 * 保留此別名是為了呼叫端收斂期少改一行；新 code 直接用 [glassPanel]。
 */
fun Modifier.smartGlass(
    state: HazeState,
    isDark: Boolean,
    shape: Shape = ShapeLg,
    specular: Boolean = true
): Modifier = glassPanel(state, isDark, shape, specular)

/**
 * Turn any surface into liquid glass. Attach [com.vic.inkflow.ui.hazeSource]
 * (Modifier.hazeSource) to the scene content BEHIND these panels first.
 */
@OptIn(ExperimentalHazeApi::class)
fun Modifier.glassPanel(
    state: HazeState,
    isDark: Boolean,
    shape: Shape = ShapeLg,
    specular: Boolean = true
): Modifier {
    return this
        .clip(shape)
        .hazeGlass(input = HazeInput.Backdrop(state), style = glassStyle(isDark, shape))
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
 * 啫喱按壓：縮放 + 提亮 + 掃光（按下去一道光從左上掃到右下，靜止時零成本）。
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
    // 掃光：只在按壓期間播一次（0→1 去，1→0 回），靜止時 alpha=0 不多一層開銷
    val sweep by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(if (pressed) 450 else 200),
        label = "PressSweep"
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .background(Color.White.copy(alpha = glow), shape)
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
 * 按壓統一入口：掃光＋縮放＋無漣漪點擊一次包好。
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
 * 穿窗版：給對話框卡／下拉選單用（跟視窗同一 HazeState，Sources 穿過去；
 * Backdrop 過不了視窗邊界，這裡必須用 Sources）。
 */
@OptIn(ExperimentalHazeApi::class)
fun Modifier.glassPanelDialog(
    state: HazeState,
    isDark: Boolean,
    shape: Shape = ShapeLg
): Modifier {
    return this
        .clip(shape)
        .hazeGlass(input = HazeInput.Sources(state), style = glassStyle(isDark, shape))
        .glassDressing(isDark, shape, true)
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
 * 共用打光：薄 Sheen＋頂光 rim＋亮邊，全 App 唯一玻璃妝。
 * haze/faux 路徑沒有 shader 高光，靠這層薄妝＋rim 維持玻璃讀感。
 *
 * 注意：這裡刻意不用 Modifier.shadow()——在小米平板 GPU 上，shadow 層會在文字後
 * 留下白色行框殘影（9 輪診斷版實證：有 shadow 全有框、拿掉全沒框，描邊/亮面/模糊皆無罪）。
 * 深度改由 rim＋底內陰影撐，不要加 shadow 回來。
 * 壓在底（blur 或半透明色）上、內容下。
 */
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
        // 大火 rim：頂緣高光拉滿，跟 haze 頂光同方向疊
        val rimTop = if (isDark) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.95f)
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
                        if (hazeState != null) Modifier.glassPanelDialog(hazeState, isDark, ShapeLg)
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
