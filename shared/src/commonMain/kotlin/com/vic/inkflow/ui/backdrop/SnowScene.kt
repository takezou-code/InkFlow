package com.vic.inkflow.ui.backdrop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

// ── 初雪：三層雪粒緩落＋冷月暈 ───────────────────────────────

object SnowScene : BackdropScene {
    override val id = "SNOW"
    override val label = "初雪"

    private const val DEPTH_THIN = 0.0012f
    private const val DEPTH_THICK = 0.0028f

    /** 雪粒：共用粒子（固定 seed）。半徑由 depth 插值——遠小近大，一層名單編碼三層景深。 */
    private val flakes = driftParticles(
        count = 46,
        seed = 0x5D0F1AL,
        speed = 0.018f..0.063f,
        alpha = 0.30f..0.85f,
        swayAmp = 0.004f..0.022f
    )

    override fun DrawScope.render(t: Float, w: Float, h: Float, dark: Boolean) {
        skyVertical(
            dark = dark,
            darkColors = listOf(Color(0xFF060A18), Color(0xFF111D38), Color(0xFF1D2E52)),
            lightColors = listOf(Color(0xFFFCFDFF), Color(0xFFE9F1FB), Color(0xFFD2E2F6))
        )
        // 月暈：光源偏左上，給雪粒一個來處（不做實心月，免搶玻璃上的字）
        val glowR = minOf(w, h) * 0.34f
        softGlow(
            center = Offset(w * 0.24f, h * 0.18f),
            radius = glowR,
            color = if (dark) Color(0xFF9FC4FF).copy(alpha = 0.16f) else Color.White.copy(alpha = 0.85f)
        )
        // 地平積雪冷光：底部一抹，雪有落點（沒有的話雪像飄在虛空）
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Transparent,
                    if (dark) Color(0xFF2B3F6B).copy(alpha = 0.55f) else Color(0xFFBBD2EE).copy(alpha = 0.5f)
                )
            )
        )
        val flakeColor = if (dark) Color(0xFFEAF2FF) else Color(0xFF9CB6D6)
        val alphaScale = if (dark) 1f else 0.85f
        val short = minOf(w, h)
        flakes.forEach { f ->
            val xx = f.xAt(t, w)
            val yy = f.yAt(t, h)
            val r = short * (DEPTH_THIN + f.depth * DEPTH_THICK)
            val a = f.alpha * alphaScale
            // 近處大顆才加柔暈，遠的一律素點（否則整片糊成霧）
            if (r > short * 0.0022f) {
                drawCircle(color = flakeColor.copy(alpha = a * 0.25f), radius = r * 2.6f, center = Offset(xx, yy))
            }
            drawCircle(color = flakeColor.copy(alpha = a), radius = r, center = Offset(xx, yy))
        }
    }
}
