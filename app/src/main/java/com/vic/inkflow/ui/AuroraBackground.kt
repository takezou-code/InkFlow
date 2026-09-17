package com.vic.inkflow.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 泡泡規格：位置是時間 t（秒）的純函數，不存任何可變狀態。
 * x 走正弦漂移，y 等速上升到底部回繞（回繞那一下跟舊版回收同效果）。
 */
private data class OrbSpec(
    val xAmp: Float,
    val xPeriod: Float,
    val xPhase: Float,
    val yRate: Float,
    val yOffset: Float,
    val radiusFrac: Float,
    val pulseSpeed: Float,
    val pulsePhase: Float,
    val baseColor: Color
) {
    fun currentX(t: Float, w: Float): Float =
        (0.5f + xAmp * sin(6.2832f * t / xPeriod + xPhase)) * w

    fun currentY(t: Float, h: Float): Float {
        // 回收區收緊：之前上下各 0.15  invisible，一次少兩成泡泡在螢幕外
        val span = 1.1f
        val raw = (t * yRate + yOffset) % span
        return (1.05f - raw) * h
    }

    fun currentRadius(t: Float, w: Float, h: Float): Float =
        min(w, h) * radiusFrac * (1f + 0.18f * sin(t * pulseSpeed + pulsePhase))
}

enum class BubbleRegion {
    Full,
    LowerHalf,
    UpperHalf,
    Center,
    Custom
}

@Composable
fun AuroraBackground(
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier,
    orbCount: Int = 5,
    bubbleCount: Int? = null,
    bubbleRegion: BubbleRegion = BubbleRegion.Full,
) {
    val effectiveCount = bubbleCount ?: orbCount
    // 高級感配方：小而多、柔而慢。alpha 由繪製 stops 控制，這裡存純色。
    // S1 預飽和：haze/RenderEffect 鏈塞不進 saturation 節點，玻璃會吃掉彩度，
    // 所以源頭直接給更鮮的色，透出來剛好（零成本替代 saturation boost 1.5x）。
    val palette = if (isDarkTheme) {
        listOf(
            Color(0xFF7C6CFF),
            Color(0xFFA855F7),
            Color(0xFF22D3EE),
            Color(0xFF6366F1),
            Color(0xFFEC6FF7),
            Color(0xFF3B9DFF)
        )
    } else {
        listOf(
            Color(0xFF4F46E5),
            Color(0xFF7C3AED),
            Color(0xFF0E7490),
            Color(0xFF9333EA),
            Color(0xFF2563EB),
            Color(0xFF38BDF8)
        )
    }

    // 全屏分佈、小半徑、慢速：避免探照燈式大光斑
    val yBase = 0.12f
    val yRange = 0.80f
    val radiusMin = 0.045f
    val radiusRange = 0.075f

    // 規格只算一次：普通 List，不是 State，永不觸發重組
    val orbs = remember(effectiveCount, isDarkTheme) {
        List(effectiveCount) { i ->
            val rnd = Random(0x5A5A5A5AL + i)
            OrbSpec(
                xAmp = 0.10f + rnd.nextFloat() * 0.28f,
                xPeriod = 26f + rnd.nextFloat() * 34f,
                xPhase = rnd.nextFloat() * 6.28f,
                yRate = (0.012f + rnd.nextFloat() * 0.016f) * (1.1f / 1.3f),
                yOffset = rnd.nextFloat() * 1.3f,
                radiusFrac = radiusMin + rnd.nextFloat() * radiusRange,
                pulseSpeed = 0.4f + rnd.nextFloat() * 0.9f,
                pulsePhase = rnd.nextFloat() * 6.28f,
                baseColor = palette[rnd.nextInt(palette.size)]
            )
        }
    }

    // 45 幀上限：delay 驅動（不對齊 vsync、不喚醒每一幀，最省電），
    // 泡泡慢動作 45fps 綽綽有餘，GPU 少畫七成。t 取真實秒數，速度不變。
    // 噪點圖已刪除（當初給退役折射做牙齒的；泡泡要清晰，沙子不留）。

    val tick = remember { mutableLongStateOf(0L) }
    val t0 = remember { System.nanoTime() }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(22)
            tick.longValue += 1
        }
    }

    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val h = constraints.maxHeight.toFloat().coerceAtLeast(1f)

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 離屏層只在暗色保留（Plus 已拿掉，亮色不需要多一層 FBO）
                    if (isDarkTheme) {
                        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                        alpha = 0.99f
                    }
                }
        ) {
            // 讀 tick 只排重繪不重組；t 取真實秒數（tick 降頻後位置不受影響）
            tick.longValue
            val t = (System.nanoTime() - t0) / 1_000_000_000f

            // 深空底：縱向微漸層，避免純平一塊死黑
            if (isDarkTheme) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF05070F),
                            Color(0xFF0B1130),
                            Color(0xFF060913)
                        )
                    )
                )
            } else {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFF7F9FD),
                            Color(0xFFE9EEF8),
                            Color(0xFFF7F9FD)
                        )
                    )
                )
            }

            // 噪點平鋪已刪除（泡泡要清晰）。

            // n ≤ 12，這一個小排序是每幀唯一的配置，可接受
            val sortedOrbs = orbs.sortedByDescending { it.currentY(t, h) }

            // 暗色 Plus 已拿掉：SrcOver 走硬體快路，視覺差異極小
            val orbBlend = BlendMode.SrcOver
            // 柔光斑改硬芯：中心實色撐到 0.7 再衰減，泡泡清晰飽滿
            val coreAlpha = if (isDarkTheme) 0.62f else 0.45f
            val midAlpha = if (isDarkTheme) 0.30f else 0.20f
            sortedOrbs.forEach { bubble ->
                val radius = bubble.currentRadius(t, w, h)
                val centerX = bubble.currentX(t, w)
                val centerY = bubble.currentY(t, h)
                val orbCenter = Offset(centerX, centerY)

                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to bubble.baseColor.copy(alpha = coreAlpha),
                            0.7f to bubble.baseColor.copy(alpha = coreAlpha),
                            1f to Color.Transparent
                        ),
                        center = orbCenter,
                        radius = radius
                    ),
                    radius = radius,
                    center = orbCenter,
                    blendMode = orbBlend
                )

                // 極淡外暈，只做氣氛不做主角（減半，不搶實芯）
                val haloRadius = radius * 1.6f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            bubble.baseColor.copy(alpha = midAlpha * 0.35f),
                            Color.Transparent
                        ),
                        center = orbCenter,
                        radius = haloRadius
                    ),
                    center = orbCenter,
                    radius = haloRadius,
                    blendMode = orbBlend
                )
            }

            // 暗角：四周壓暗，畫面才有舞台感
            val vignetteRadius = max(w, h) * 0.72f
            val vignetteEdge = if (isDarkTheme) Color.Black.copy(alpha = 0.38f)
                else Color(0xFF334155).copy(alpha = 0.12f)
            drawRect(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.55f to Color.Transparent,
                        1f to vignetteEdge
                    ),
                    center = Offset(w / 2f, h / 2f),
                    radius = vignetteRadius
                )
            )
        }
    }
}
