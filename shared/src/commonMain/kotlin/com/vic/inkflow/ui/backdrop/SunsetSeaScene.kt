package com.vic.inkflow.ui.backdrop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

// ── 日落海：海平線＋太陽柱波光 ───────────────────────────────

object SunsetSeaScene : BackdropScene {
    override val id = "SUNSETSEA"
    override val label = "日落海"

    /** 海平線的比例：天空 0～[HORIZON]，海面 [HORIZON]～1。唯一真相，別在別處再寫 0.61。 */
    private const val HORIZON = 0.61f

    /** 水面碎光：定點閃爍粒子，y 再映射到海面區間。 */
    private val glints = twinkles(count = 44, seed = 0x51E7A11L, alpha = 0.3f..0.9f, speed = 0.9f..2.8f)

    override fun DrawScope.render(t: Float, w: Float, h: Float, dark: Boolean) {
        val hz = h * HORIZON
        skyVertical(
            dark = dark,
            darkColors = listOf(Color(0xFF1B0E24), Color(0xFF4A1533), Color(0xFFB0541F)),
            lightColors = listOf(Color(0xFFFFF7EE), Color(0xFFFFD8B2), Color(0xFFFFAE79))
        )
        // 太陽：坐在海平線上，暈開一大片（暈比本體重要，本體只是一個亮點）
        val sunC = Offset(w * 0.5f, hz)
        val sunR = minOf(w, h) * 0.070f
        softGlow(
            center = sunC,
            radius = sunR * 6.5f,
            color = if (dark) Color(0xFFFFC178).copy(alpha = 0.30f) else Color(0xFFFFF0C8).copy(alpha = 0.75f)
        )
        drawCircle(
            color = if (dark) Color(0xFFFFD9A0) else Color(0xFFFFF3D6),
            radius = sunR,
            center = sunC
        )
        drawSea(t, w, h, hz, sunR, dark)
    }

    private fun DrawScope.drawSea(t: Float, w: Float, h: Float, hz: Float, sunR: Float, dark: Boolean) {
        val seaH = h - hz
        // 海面：靠岸暖、往深處冷（日落的方向感全在這條漸層）
        drawRect(
            brush = Brush.verticalGradient(
                colors = if (dark) listOf(Color(0xFF54241C), Color(0xFF1E1018), Color(0xFF0A0509))
                else listOf(Color(0xFFFFD3A6), Color(0xFF9FBED6), Color(0xFF6E90AE))
            ),
            topLeft = Offset(0f, hz),
            size = Size(w, seaH)
        )
        // 海平線：一條細亮線（沒有它，海天糊成一片）
        drawLine(
            color = (if (dark) Color(0xFFFFE0B0) else Color.White).copy(alpha = 0.55f),
            start = Offset(0f, hz),
            end = Offset(w, hz),
            strokeWidth = 2f
        )
        // 太陽柱：柱底先鋪一層柔光把它黏成一片，再疊細碎光——
        // 只有碎光的話會變成一排階梯（實測第一版就是這樣）。
        clipRect(left = 0f, top = hz, right = w, bottom = h) {
            softGlow(
                center = Offset(w * 0.5f, hz),
                radius = seaH * 0.95f,
                color = (if (dark) Color(0xFFFF9E4A) else Color(0xFFFFE0A8))
                    .copy(alpha = if (dark) 0.26f else 0.50f)
            )
        }
        val bands = 30
        for (i in 0 until bands) {
            val f = i / (bands - 1).toFloat()
            val yy = hz + seaH * (0.02f + f * 0.94f)
            val shimmer = 0.4f + 0.6f * (0.5f + 0.5f * sin(t * 1.7f + i * 0.7f))
            // 兩個不同週期的擾動：單一正弦會讓整排一起呼吸，看起來像節拍器
            val wobble = 0.7f + 0.3f * sin(i * 4.7f + t * 0.6f)
            val halfW = w * (0.012f + 0.060f * f) * shimmer * wobble
            val jitter = sin(i * 2.3f) * w * 0.010f
            drawLine(
                color = (if (dark) Color(0xFFFFD9A0) else Color(0xFFFFF6DE))
                    .copy(alpha = (0.52f - f * 0.38f) * (0.45f + 0.55f * shimmer)),
                start = Offset(w * 0.5f + jitter - halfW, yy),
                end = Offset(w * 0.5f + jitter + halfW, yy),
                strokeWidth = 1.5f + sunR * 0.05f
            )
        }
        // 側面浪：低振幅正弦帶，只為「這是一片海」不是「一條光柱」
        for (idx in 0 until 5) {
            val f = idx / 4f
            val base = 0.68f + f * 0.30f
            val amp = 0.006f + f * 0.012f
            val y0 = h * base
            val alpha = 0.10f + f * 0.14f
            val segs = 40
            var prev = Offset(0f, y0 + h * amp * sin(t * 0.5f + idx))
            for (s in 1..segs) {
                val x = w * s / segs
                val y = y0 + h * amp * sin(x / w * 6.2832f * (1.1f + idx * 0.3f) + t * 0.5f + idx)
                drawLine(
                    color = (if (dark) Color(0xFFC98B6A) else Color.White).copy(alpha = alpha),
                    start = prev,
                    end = Offset(x, y),
                    strokeWidth = 1.5f + idx
                )
                prev = Offset(x, y)
            }
        }
        // 水面碎光：遠離太陽柱的也有一點，才不會只有中間亮
        glints.forEach { g ->
            val yy = hz + seaH * (0.04f + g.y * 0.92f)
            val xx = w * (0.06f + g.x * 0.88f)
            // 柱內更亮：以太陽柱中心距離衰減
            val centerness = 1f - (abs(xx - w * 0.5f) / (w * 0.5f)).coerceIn(0f, 1f)
            drawCircle(
                color = (if (dark) Color(0xFFFFD9A0) else Color(0xFFFFF6DE))
                    .copy(alpha = g.alphaAt(t) * (0.20f + 0.55f * centerness)),
                radius = minOf(w, h) * (0.0008f + g.depth * 0.0014f),
                center = Offset(xx, yy)
            )
        }
    }
}
