package com.vic.inkflow.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * 主題重點色（accent）的唯一定義。
 *
 * 規則：**整個 App 只有一個「重點色」變數**。使用者選了什麼，就由
 * [inkColorScheme] 推導出 `primary`／`secondary`／容器色／`surfaceTint`，
 * UI 一律讀 `MaterialTheme.colorScheme.primary`——不准在任何檔案另寫死靛紫。
 *
 * 為什麼不是「存一份顏色然後各處自己用」：那正是舊版的情況（`BrandIndigo` 被
 * 畫布選取框、文字游標、玻璃選中藥丸各自 import），改色一定漏。現在：
 *  - 可組合函式／繪圖：讀 `colorScheme.primary`（Canvas 內先在外層 hoist）；
 *  - 玻璃選中藥丸：`accentPillTop/Bottom(primary)` 現場推導，不再用固定薰衣草常數；
 *  - 色系容器／反轉主色：`inkColorScheme` 一起算，不會留舊紫。
 *
 * 背景極光（`AuroraBackground`）刻意不受這裡影響：背景是獨立色系，
 * 使用者可以「墨綠按鈕＋藍紫背景」或反過來。詳見 AccentPresets 的註解。
 */
val DefaultAccentColor: Color = BrandIndigo

/** 設定頁的主題色選項。label 是唯一顯示名稱；要加色只改這張表。 */
data class AccentPreset(val label: String, val color: Color)

val AccentPresets: List<AccentPreset> = listOf(
    AccentPreset("靛紫", BrandIndigo),
    AccentPreset("夜藍", Color(0xFF3B82F6)),
    AccentPreset("湖水青", Color(0xFF0891B2)),
    AccentPreset("森綠", Color(0xFF10B981)),
    AccentPreset("暖金", Color(0xFFF59E0B)),
    AccentPreset("珊瑚", Color(0xFFEF4444)),
    AccentPreset("玫紅", Color(0xFFEC4899)),
    AccentPreset("石墨", Color(0xFF64748B))
)

/**
 * 線性混色：`amount` = 要混入 [other] 的比例（0＝自己、1＝完全是 other）。
 * 只在 alpha 相同的色之間用；重點色都是不透明的。
 */
fun Color.blendWith(other: Color, amount: Float): Color {
    val t = amount.coerceIn(0f, 1f)
    return Color(
        red = red + (other.red - red) * t,
        green = green + (other.green - green) * t,
        blue = blue + (other.blue - blue) * t,
        alpha = alpha + (other.alpha - alpha) * t
    )
}

/** 玻璃選中藥丸的頂端（珍珠色）：重點色只留一點點染色，不能變成螢光色塊。 */
fun accentPillTop(accent: Color): Color = accent.blendWith(Color.White, 0.93f)

/** 玻璃選中藥丸的底部（比頂端深一階，做出厚度）。 */
fun accentPillBottom(accent: Color): Color = accent.blendWith(Color.White, 0.78f)

/**
 * 由單一重點色推導整份 color scheme。
 *
 * 預設色（[BrandIndigo]）直接回傳原本的 `InkDarkScheme`／`InkLightScheme`，
 * 確保「沒改過」的畫面與改版前逐像素一致。
 *
 * 其餘顏色只覆寫「跟重點色有關」的角色；背景／紙張／玻璃色票／錯誤色不動，
 * 所以換色不會把整個 App 變成單色調。
 */
fun inkColorScheme(dark: Boolean, accent: Color): ColorScheme {
    val base = if (dark) InkDarkScheme else InkLightScheme
    if (accent == BrandIndigo) return base

    // 重點色上的字：亮底配深墨、暗底配白。閾值 0.5 大約是 M3 對比翻轉的位置。
    val onAccent = if (accent.luminance() > 0.5f) Color(0xFF1B1235) else Color.White
    // 容器色：亮色模式往白淡、深色模式往表面暗，兩者都保留一點色偏（看得出是同一個 hue）。
    val container = if (dark) accent.blendWith(base.surface, 0.78f)
    else accent.blendWith(Color.White, 0.90f)
    val onContainer = if (dark) accent.blendWith(Color.White, 0.55f)
    else accent.blendWith(Color.Black, 0.32f)

    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = container,
        onPrimaryContainer = onContainer,
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = container,
        onSecondaryContainer = onContainer,
        surfaceTint = accent,
        // 反轉主色用在深色 snackbar/反轉表面上，需要比原色亮一階
        inversePrimary = accent.blendWith(Color.White, 0.35f)
    )
}
