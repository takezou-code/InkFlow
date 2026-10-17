package com.vic.inkflow.ui.backdrop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.sin

// ── 沙丘：層疊正弦 ridge＋月暈 ───────────────────────────────

object DuneScene : BackdropScene {
    override val id = "DUNE"
    override val label = "沙丘"

    override fun DrawScope.render(t: Float, w: Float, h: Float, dark: Boolean) {
        skyVertical(
            dark = dark,
            darkColors = listOf(Color(0xFF060414), Color(0xFF141038), Color(0xFF241B4D)),
            lightColors = listOf(Color(0xFFF1EDFB), Color(0xFFD9CFF3), Color(0xFFB7A6E4))
        )
        // 月＋暈
        val moonC = Offset(w * 0.78f, h * 0.20f)
        val moonR = minOf(w, h) * 0.07f
        softGlow(moonC, moonR * 3.2f, Color(0xFFEDE9FE).copy(alpha = 0.5f))
        drawCircle(color = Color(0xFFEDE9FE), radius = moonR, center = moonC)
        // ridge：4 層，後慢前快，填色前深
        val layers = listOf(
            Triple(0.55f, 0.030f, 0.10f),
            Triple(0.64f, 0.042f, 0.16f),
            Triple(0.73f, 0.055f, 0.24f),
            Triple(0.83f, 0.070f, 0.34f)
        )
        layers.forEachIndexed { idx, (base, amp, speed) ->
            val topPath = Path()
            val steps = 48
            for (s in 0..steps) {
                val x = w * s / steps
                val y = h * base + h * amp * sin(x / w * 6.2832f * (1.2f + idx * 0.35f) + t * speed + idx * 1.7f)
                if (s == 0) topPath.moveTo(x, y) else topPath.lineTo(x, y)
            }
            // 填色用閉合路徑，描邊只走頂緣（整圈描會描出底邊側邊，很框）
            val fillPath = Path().apply {
                addPath(topPath)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            val shade = idx / (layers.size - 1).toFloat()
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = if (dark) listOf(
                        Color(0xFF2E2360).copy(alpha = 0.75f + 0.25f * shade),
                        Color(0xFF120C2E)
                    ) else listOf(
                        Color(0xFF8F7BD8).copy(alpha = 0.65f + 0.35f * shade),
                        Color(0xFF5B4BA8)
                    )
                )
            )
            // ridge 受光緣：只描頂，只提一點
            drawPath(
                path = topPath,
                color = Color.White.copy(alpha = if (dark) 0.08f else 0.16f),
                style = Stroke(width = 1.5f)
            )
        }
    }
}
