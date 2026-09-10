package com.vic.inkflow.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
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
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private data class BubbleState(
    var x: Float,
    var y: Float,
    var radiusFrac: Float,
    var velocityX: Float,
    var velocityY: Float,
    var wobblePhase: Float,
    val wobbleSpeed: Float,
    val wobbleAmount: Float,
    val baseColor: Color,
    val glowIntensity: Float,
    val riseSpeed: Float,
    val wobblePhaseOffset: Float
) {
    fun currentX(w: Float): Float = (x + sin(wobblePhase) * wobbleAmount * 0.5f) * w
    fun currentY(h: Float): Float = y * h
    fun currentRadius(w: Float, h: Float): Float = min(w, h) * radiusFrac * (1f + 0.18f * sin(wobblePhase))
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
    bubbleRegion: BubbleRegion = BubbleRegion.Full
) {
    val effectiveCount = bubbleCount ?: orbCount
    // 高級感配方：小而多、柔而慢。alpha 由繪製 stops 控制，這裡存純色。
    val palette = if (isDarkTheme) {
        listOf(
            Color(0xFF7C83FF),
            Color(0xFFA78BFA),
            Color(0xFF67E8F9),
            Color(0xFF818CF8),
            Color(0xFFE879F9),
            Color(0xFF60A5FA)
        )
    } else {
        listOf(
            Color(0xFF6366F1),
            Color(0xFF8B5CF6),
            Color(0xFF0891B2),
            Color(0xFFA78BFA),
            Color(0xFF818CF8),
            Color(0xFFC7D2FE)
        )
    }

    // 全屏分佈、小半徑、慢速：避免探照燈式大光斑
    val yBase = 0.12f
    val yRange = 0.80f
    val radiusMin = 0.045f
    val radiusRange = 0.075f

    val bubbleStates = remember(effectiveCount, isDarkTheme) {
        mutableStateOf(
            List(effectiveCount) { i ->
                val rnd = Random(0x5A5A5A5AL + i)
                BubbleState(
                    x = rnd.nextFloat() * 0.8f + 0.1f,
                    y = yBase + rnd.nextFloat() * yRange,
                    radiusFrac = radiusMin + rnd.nextFloat() * radiusRange,
                    velocityX = (rnd.nextFloat() - 0.5f) * 0.035f,
                    velocityY = -(0.018f + rnd.nextFloat() * 0.028f),
                    wobblePhase = rnd.nextFloat() * 6.28f,
                    wobbleSpeed = 0.4f + rnd.nextFloat() * 0.9f,
                    wobbleAmount = 0.012f + rnd.nextFloat() * 0.016f,
                    baseColor = palette[rnd.nextInt(palette.size)],
                    glowIntensity = 0.18f + rnd.nextFloat() * 0.22f,
                    riseSpeed = 0.012f + rnd.nextFloat() * 0.016f,
                    wobblePhaseOffset = rnd.nextFloat() * 6.28f
                )
            }
        )
    }

    var time by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var lastFrameNanos = 0L
        while (true) {
            withFrameNanos { now ->
                if (lastFrameNanos == 0L) lastFrameNanos = now
                val dt = ((now - lastFrameNanos) / 1_000_000_000f).coerceAtMost(0.05f)
                lastFrameNanos = now
                time += dt

                bubbleStates.value = bubbleStates.value.map { bubble ->
                    val wobbleX = cos(time * bubble.wobbleSpeed * 0.7f + bubble.wobblePhaseOffset) * bubble.wobbleAmount * 0.5f
                    val wobbleY = sin(time * bubble.wobbleSpeed * 1.3f + bubble.wobblePhaseOffset) * bubble.wobbleAmount * 0.3f

                    var newX = bubble.x + bubble.velocityX * dt + wobbleX * dt * 2f
                    var newY = bubble.y + bubble.velocityY * dt + wobbleY * dt
                    var velX = bubble.velocityX
                    var velY = bubble.velocityY

                    if (newX < 0.06f || newX > 0.94f) {
                        velX = -velX * 0.7f
                        newX = newX.coerceIn(0.06f, 0.94f)
                    }
                    // 頂部回收到底部：速度一起重置為上升，否則泡泡會卡在螢幕外永遠消失
                    if (newY < 0.08f || newY > 1.3f) {
                        newX = 0.08f + Random.nextFloat() * 0.84f
                        newY = 1.12f + Random.nextFloat() * 0.1f
                        velX = (Random.nextFloat() - 0.5f) * 0.035f
                        velY = -(0.018f + Random.nextFloat() * 0.028f)
                    }

                    bubble.copy(
                        x = newX,
                        y = newY,
                        velocityX = velX,
                        velocityY = velY,
                        wobblePhase = bubble.wobblePhase + bubble.wobbleSpeed * dt
                    )
                }
            }
        }
    }

    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val h = constraints.maxHeight.toFloat().coerceAtLeast(1f)

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                    alpha = 0.99f
                }
        ) {
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

            val sortedBubbles = bubbleStates.value.sortedByDescending { it.y }

            val orbBlend = if (isDarkTheme) BlendMode.Plus else BlendMode.SrcOver
            // 柔光斑：三段衰減，中心不再 1.0 全亮
            val coreAlpha = if (isDarkTheme) 0.50f else 0.34f
            val midAlpha = if (isDarkTheme) 0.22f else 0.14f
            sortedBubbles.forEach { bubble ->
                val radius = bubble.currentRadius(w, h)
                val centerX = bubble.currentX(w)
                val centerY = bubble.currentY(h)
                val orbCenter = Offset(centerX, centerY)

                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to bubble.baseColor.copy(alpha = coreAlpha),
                            0.55f to bubble.baseColor.copy(alpha = midAlpha),
                            1f to Color.Transparent
                        ),
                        center = orbCenter,
                        radius = radius
                    ),
                    radius = radius,
                    center = orbCenter,
                    blendMode = orbBlend
                )

                // 極淡外暈，只做氣氛不做主角
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
