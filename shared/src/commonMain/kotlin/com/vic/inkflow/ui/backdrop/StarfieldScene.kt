package com.vic.inkflow.ui.backdrop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.min
import kotlin.math.sin

// ── 星夜：銀河斜帶＋滿天星呼吸＋偶發流星 ──────────────────────

object StarfieldScene : BackdropScene {
    override val id = "STARFIELD"
    override val label = "星夜"

    private const val STAR_COUNT = 96
    private const val BRIGHT_COUNT = 7

    /** 一般星：多、小、慢呼吸。 */
    private val stars = twinkles(count = STAR_COUNT, seed = 0x57A2F1EEL, alpha = 0.25f..0.9f, speed = 0.5f..1.9f)

    /** 亮星：少、會暈、呼吸慢（讓天空有層次，不是均勻雜點）。 */
    private val brightStars = twinkles(count = BRIGHT_COUNT, seed = 0xB217E5EEL, alpha = 0.65f..1f, speed = 0.25f..0.8f)

    override fun DrawScope.render(t: Float, w: Float, h: Float, dark: Boolean) {
        skyVertical(
            dark = dark,
            darkColors = listOf(Color(0xFF03040C), Color(0xFF0A1026), Color(0xFF141C3D)),
            lightColors = listOf(Color(0xFFF8FAFF), Color(0xFFE7EDFB), Color(0xFFD0DBF0))
        )
        drawMilkyWay(w, h, dark)
        val starColor = if (dark) Color(0xFFE8EEFF) else Color(0xFF5A6A94)
        val alphaScale = if (dark) 1f else 0.8f
        val short = minOf(w, h)

        stars.forEach { s ->
            val c = Offset(s.x * w, s.y * h)
            drawCircle(
                color = starColor.copy(alpha = s.alphaAt(t) * alphaScale),
                radius = short * (0.0007f + s.depth * 0.0013f),
                center = c
            )
        }
        brightStars.forEach { s ->
            val c = Offset(s.x * w, s.y * h)
            val a = s.alphaAt(t) * alphaScale
            val r = short * (0.0016f + s.depth * 0.0014f)
            // 亮星才給暈：天空的「遠近」靠這個，不是靠大小
            softGlow(center = c, radius = r * 6f, color = starColor.copy(alpha = a * 0.30f))
            drawCircle(color = starColor.copy(alpha = a), radius = r, center = c)
        }
        drawMeteor(t, w, h, starColor)
    }

    /** 銀河：一條斜的柔光帶（不是硬邊條紋——霓城就是死在硬邊）。 */
    private fun DrawScope.drawMilkyWay(w: Float, h: Float, dark: Boolean) {
        val band = if (dark) Color(0xFF8FA6FF).copy(alpha = 0.09f) else Color(0xFF93A6D8).copy(alpha = 0.16f)
        rotate(degrees = -26f, pivot = Offset(w / 2f, h / 2f)) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, band, Color.Transparent),
                    startY = h * 0.28f,
                    endY = h * 0.68f
                ),
                topLeft = Offset(-w * 0.5f, 0f),
                size = Size(w * 2f, h)
            )
        }
    }

    /**
     * 流星：t 的純函數（固定週期 13 秒，前 22% 可見＝約 2.9 秒劃過）。
     * 用 sin(p·π) 淡入淡出，頭尾都不會突然出現／消失。
     */
    private fun DrawScope.drawMeteor(t: Float, w: Float, h: Float, color: Color) {
        val cycle = 13f
        val visible = 0.22f
        val phase = (t % cycle) / cycle
        if (phase > visible) return
        val p = phase / visible

        val sx = w * 0.94f; val sy = h * 0.08f
        val ex = w * 0.46f; val ey = h * 0.60f
        val head = Offset(sx + (ex - sx) * p, sy + (ey - sy) * p)
        val fade = sin(p * 3.1416f)

        // 尾：由方向反推起點，用漸層收尾（純色線會像一根火柴）
        val len = minOf(w, h) * 0.13f
        val dx = ex - sx
        val dy = ey - sy
        val norm = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        val tail = Offset(head.x - dx / norm * len, head.y - dy / norm * len)
        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(Color.Transparent, color.copy(alpha = 0.85f * fade)),
                start = tail,
                end = head
            ),
            start = tail,
            end = head,
            strokeWidth = 2.5f,
            cap = StrokeCap.Round
        )
        drawCircle(color = color.copy(alpha = fade), radius = 2.8f, center = head)
    }
}
