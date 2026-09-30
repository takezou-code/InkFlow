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
/** 真玻璃 veil（壓在背景上，折射＋模糊仍透得出）。濃度須夠高才保內容對比：
 *  iOS barTintColor 混灰後接近半透明不透明，薄 veil 壓在近黑底會變深灰、深字看不見。 */
val GlassVeilLight = Color(0x8CFFFFFF) // 55% 白
val GlassVeilDark = Color(0x8C0F172A) // 55% 藏青
/** faux 玻璃底（無 blur，靠不透明度保可讀，略濃於真玻璃）。 */
val GlassTintLight = Color(0x4DFFFFFF) // 30%
val GlassTintDark = Color(0x8C0F172A) // 55%
/** 壓在淺玻璃上的內容色（圖標/字）：iOS 亮欄配深內容。 */
val PaperInkColor = Color(0xFF1E293B)
