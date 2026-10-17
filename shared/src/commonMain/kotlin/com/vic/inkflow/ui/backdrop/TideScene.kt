package com.vic.inkflow.ui.backdrop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.sin

// ── 汐：多層正弦水體＋浮光 ───────────────────────────────────

object TideScene : BackdropScene {
    override val id = "TIDE"
    override val label = "汐"

    /**
     * 浮光：共用粒子（固定 seed → 每次啟動同一片天）。
     * speed 0.015..0.045 對應舊版的 0.03×sp（sp 0.5..1.5）；亮度自己用 twinkle 算，不看 alpha。
     */
    private val sparks = driftParticles(
        count = 26,
        seed = 0x71DEL,
        speed = 0.015f..0.045f,
        alpha = 1f..1f,
        swayAmp = 0f..0f,
        swaySpeed = 0f..0f
    )

    override fun DrawScope.render(t: Float, w: Float, h: Float, dark: Boolean) {
        skyVertical(
            dark = dark,
            darkColors = listOf(Color(0xFF020B18), Color(0xFF062033), Color(0xFF0A3A52)),
            lightColors = listOf(Color(0xFFE4F4FB), Color(0xFFB5DCF0), Color(0xFF7FB8DC))
        )
        // 浮光：緩慢上升回繞＋閃爍
        sparks.forEach { p ->
            val sp = p.speed / 0.03f
            val tw = 0.4f + 0.6f * (0.5f + 0.5f * sin(t * sp + p.swayPhase))
            drawCircle(
                color = Color(0xFF9BE8FF).copy(alpha = 0.5f * tw),
                radius = 2.5f,
                center = Offset(p.baseX * w, p.yAt(t, h, rise = true))
            )
        }
        // 水體：5 層，前快後慢
        for (idx in 0 until 5) {
            val f = idx / 4f
            val base = 0.45f + f * 0.38f
            val amp = 0.018f + f * 0.030f
            val speed = 0.55f - f * 0.32f
            val freq = 1.6f + idx * 0.5f
            val path = Path()
            val steps = 60
            for (s in 0..steps) {
                val x = w * s / steps
                val y = h * base + h * amp * sin(x / w * 6.2832f * freq + t * speed * 2f + idx * 2.1f)
                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.lineTo(w, h)
            path.lineTo(0f, h)
            path.close()
            drawPath(
                path = path,
                color = (if (dark) Color(0xFF0E5A7A) else Color(0xFF3E96C4)).copy(alpha = 0.28f + f * 0.5f)
            )
        }
    }
}
