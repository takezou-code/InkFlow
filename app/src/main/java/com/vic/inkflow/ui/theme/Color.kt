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
val GlassVeilDark = Color(0xB30F172A) // 70% 近黑藏青（黑玻璃）
/** faux 玻璃底（無 blur，濃度略高於真玻璃保可讀）。 */
val GlassTintLight = Color(0x4DFFFFFF) // 30%
val GlassTintDark = Color(0xA60F172A) // 65% 近黑藏青
/** 壓在淺玻璃上的內容色（圖標/字）：iOS 亮欄配深內容。 */
val PaperInkColor = Color(0xFF1E293B)
