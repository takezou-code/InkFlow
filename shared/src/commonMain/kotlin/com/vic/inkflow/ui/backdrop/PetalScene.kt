package com.vic.inkflow.ui.backdrop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import kotlin.math.min

// ── 落櫻：花瓣旋轉飄落＋暮色暖暈 ─────────────────────────────

object PetalScene : BackdropScene {
    override val id = "PETAL"
    override val label = "落櫻"

    /** 花瓣：共用飄落粒子（固定 seed）。比雪慢、比雪少，每一片都要看得出來是花瓣。 */
    private val petals = driftParticles(
        count = 20,
        seed = 0x9E7A15L,
        speed = 0.030f..0.070f,
        alpha = 0.55f..1f,
        swayAmp = 0.012f..0.048f,
        swaySpeed = 0.20f..0.55f
    )

    override fun DrawScope.render(t: Float, w: Float, h: Float, dark: Boolean) {
        skyVertical(
            dark = dark,
            darkColors = listOf(Color(0xFF170B1E), Color(0xFF2B1330), Color(0xFF3E1D36)),
            lightColors = listOf(Color(0xFFFFF8FB), Color(0xFFFFE6F1), Color(0xFFF8D2E3))
        )
        // 暮色暖暈：偏右下，讓花瓣有逆光的邊（純色背景上花瓣像貼紙）
        softGlow(
            center = Offset(w * 0.78f, h * 0.72f),
            radius = minOf(w, h) * 0.55f,
            color = if (dark) Color(0xFFFFB37A).copy(alpha = 0.14f) else Color(0xFFFFD9A8).copy(alpha = 0.45f)
        )
        val short = minOf(w, h)
        petals.forEach { p ->
            val c = Offset(p.xAt(t, w), p.yAt(t, h))
            val r = short * (0.0018f + p.depth * 0.0032f)
            // 花瓣＝壓扁的橢圓（1.6 : 0.9），不是圓點；旋轉讓它看起來在翻飛
            val rx = r * 1.6f
            val ry = r * 0.9f
            val color = if (dark) {
                lerp(Color(0xFFA63A6E), Color(0xFFFFD9EC), p.depth)
            } else {
                lerp(Color(0xFFE9A8C6), Color(0xFFC2417A), p.depth)
            }
            val spin = t * (14f + p.swaySpeed * 46f) + p.swayPhase * 57f
            rotate(degrees = spin, pivot = c) {
                drawOval(
                    color = color.copy(alpha = p.alpha),
                    topLeft = Offset(c.x - rx, c.y - ry),
                    size = Size(rx * 2f, ry * 2f)
                )
            }
        }
    }
}
