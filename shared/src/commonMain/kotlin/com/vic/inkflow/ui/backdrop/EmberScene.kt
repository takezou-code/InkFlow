package com.vic.inkflow.ui.backdrop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import kotlin.math.min

// ── 余燼：上升火星＋地平線暖光 ───────────────────────────────

object EmberScene : BackdropScene {
    override val id = "EMBER"
    override val label = "余燼"

    /** 火星：共用飄落粒子（固定 seed），rise＝true 讓它往上。 */
    private val embers = driftParticles(
        count = 36,
        seed = 0xE83E2A1BL,
        speed = 0.020f..0.055f,
        alpha = 0.35f..0.95f,
        swayAmp = 0.006f..0.022f
    )

    override fun DrawScope.render(t: Float, w: Float, h: Float, dark: Boolean) {
        skyVertical(
            dark = dark,
            darkColors = listOf(Color(0xFF0A0402), Color(0xFF1C0A04), Color(0xFF2E1508)),
            lightColors = listOf(Color(0xFFFFF8F1), Color(0xFFFFE5CF), Color(0xFFF8CCA8))
        )
        // 火源在地平線下方（看不到的地方）：底部一抹暖光，余燼才有來處
        softGlow(
            center = Offset(w * 0.5f, h * 1.04f),
            radius = minOf(w, h) * 0.85f,
            color = if (dark) Color(0xFFFF7A18).copy(alpha = 0.26f) else Color(0xFFFFAE63).copy(alpha = 0.34f)
        )
        val short = minOf(w, h)
        embers.forEach { e ->
            val c = Offset(e.xAt(t, w), e.yAt(t, h, rise = true))
            val r = short * (0.0010f + e.depth * 0.0022f)
            // 遠＝暗橘、近＝亮黃白（亮暗兩套各自插值：亮底上「亮」＝偏深才看得見）
            val color = if (dark) {
                lerp(Color(0xFFC2410C), Color(0xFFFFE9A8), e.depth)
            } else {
                lerp(Color(0xFFD89A6B), Color(0xFF8A3F13), e.depth)
            }
            if (r > short * 0.0020f) {
                softGlow(center = c, radius = r * 4.5f, color = color.copy(alpha = e.alpha * 0.28f))
            }
            drawCircle(color = color.copy(alpha = e.alpha), radius = r, center = c)
        }
    }
}
