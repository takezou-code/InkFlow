package com.vic.inkflow.ui.theme

import androidx.compose.ui.graphics.Color

val BrandIndigo = Color(0xFF6366F1)
val BrandPurple = Color(0xFF8B5CF6)
val BrandAmber = Color(0xFFF59E0B)

val Slate50 = Color(0xFFF8FAFC)
val Slate100 = Color(0xFFF1F5F9)
val Slate200 = Color(0xFFE2E8F0)
val Slate300 = Color(0xFFCBD5E1)
val Slate400 = Color(0xFF94A3B8)
val Slate500 = Color(0xFF64748B)
val Slate600 = Color(0xFF475569)
val Slate700 = Color(0xFF334155)
val Slate800 = Color(0xFF1E293B)
val Slate900 = Color(0xFF0F172A)

val InkTextStrong = Color(0xFF0F172A)
val InkTextSoft = Color(0xFF64748B)
val InkTextStrongDark = Color(0xFFF8FAFC)
val InkTextSoftDark = Color(0xFF94A3B8)

val WorkspaceDeskLight = Color(0xFFF1F5F9)
val WorkspaceDeskDark = Color(0xFF09090B)
val PaperLight = Color(0xFFFFFFFF)
val PaperDark = Color(0xFF1E293B)
val PaperShadowLight = Color(0x1F0F172A)
val PaperShadowDark = Color(0x40000000)

// ── 玻璃色票（唯一來源，GlassSurface 一律引用這裡，禁在 UI 檔另立） ──
/** 真玻璃 veil（壓在背景上，折射＋模糊仍透得出）。
 *  深色＝黑玻璃：70% 近黑藏青，存在感靠厚度不靠高光（亮色模式維持 55% 白不動）。 */
val GlassVeilLight = Color(0x8CFFFFFF) // 55% 白
// 深色別再壓到 62–70%：壓在暗極光上＝和背景同亮度＝看不出是玻璃（像素實測
// 頂欄 22.9/20.9/37 vs 背景 22.4/19.5/51）。45% 讓背景透出來＝黑玻璃，靠 rim 定邊。
// 深色 veil：白紙上才看得出黑玻璃，濃度不能低（低＝沒黑化）；
// 暗底上靠 rim 定形就好，veil 略低避免跟背景糊成一片。50% 兩邊都成立。
val GlassVeilDark = Color(0x730F172A) // 45%（已驗證有黑玻璃後再調亮一點點）
/** faux 玻璃底（無 blur，濃度略高於真玻璃保可讀）。 */
val GlassTintLight = Color(0x4DFFFFFF) // 30%
val GlassTintDark = Color(0x800F172A) // 50%
/** 壓在淺玻璃上的內容色（圖標/字）：iOS 亮欄配深內容。 */
val PaperInkColor = Color(0xFF1E293B)

/** 選中態藥丸（實體）。半透明在黑玻璃上＝等於沒有，看不出選到哪個，所以用實體。
 *  配色走「珍珠白 → 柔薰衣草」：亮但壓得住，不���那種螢光紫的廉價感。 */
val GlassSelectionTop = Color(0xFFF6F4FF)
val GlassSelectionBottom = Color(0xFFDDD8F7)
/** 選中態的墨色（實體亮底上必須用深色，兩種主題都一樣才讀得清楚）。 */
val GlassSelectionInk = Color(0xFF1B1235)
/** 選中藥丸的內高光（做出珍珠感，代替 shadow——shadow 在這台 GPU 會留白框）。 */
val GlassSelectionSheen = Color(0x59FFFFFF)
