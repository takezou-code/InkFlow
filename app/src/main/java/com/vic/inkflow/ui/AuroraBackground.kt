package com.vic.inkflow.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.vic.inkflow.ui.theme.BrandIndigo
import com.vic.inkflow.ui.theme.BrandPurple
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private data class OrbSpec(
    val centerXFrac: Float,
    val centerYFrac: Float,
    val orbitRadiusFrac: Float,
    val angularSpeed: Float,
    val phase: Float,
    val radiusFrac: Float,
    val color: Color
)

@Composable
fun AuroraBackground(
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier,
    orbCount: Int = 5
) {
    val density = LocalDensity.current
    val palette = if (isDarkTheme) {
        listOf(
            BrandIndigo.copy(alpha = 0.22f),
            BrandPurple.copy(alpha = 0.18f),
            Color(0xFF38BDF8).copy(alpha = 0.14f),
            BrandIndigo.copy(alpha = 0.16f),
            Color(0xFFA78BFA).copy(alpha = 0.12f)
        )
    } else {
        listOf(
            BrandIndigo.copy(alpha = 0.10f),
            BrandPurple.copy(alpha = 0.08f),
            Color(0xFF38BDF8).copy(alpha = 0.06f),
            BrandIndigo.copy(alpha = 0.07f),
            Color(0xFFE0E7FF).copy(alpha = 0.9f)
        )
    }

    val orbs = remember(orbCount, isDarkTheme) {
        val rnd = Random(0xA0BEEFL)
        List(orbCount) { i ->
            OrbSpec(
                centerXFrac = rnd.nextFloat() * 0.6f + 0.2f,
                centerYFrac = rnd.nextFloat() * 0.5f + 0.25f,
                orbitRadiusFrac = rnd.nextFloat() * 0.08f + 0.04f,
                angularSpeed = rnd.nextFloat() * 0.35f + 0.15f,
                phase = rnd.nextFloat() * 6.28f,
                radiusFrac = rnd.nextFloat() * 0.18f + 0.14f,
                color = palette[i % palette.size]
            )
        }
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
            }
        }
    }

    val bgColor = if (isDarkTheme) Color(0xFF09090B) else Color(0xFFF8FAFC)

    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val h = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val maxDim = maxOf(w, h)

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                    alpha = 0.99f
                }
        ) {
            drawRect(bgColor)
            orbs.forEach { orb ->
                val angle = time * orb.angularSpeed + orb.phase
                val orbitR = orb.orbitRadiusFrac * maxDim
                val x = orb.centerXFrac * w + cos(angle) * orbitR
                val y = orb.centerYFrac * h + sin(angle) * orbitR * 0.6f
                val radius = orb.radiusFrac * maxDim
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            orb.color,
                            orb.color.copy(alpha = orb.color.alpha * 0.55f),
                            Color.Transparent
                        ),
                        center = Offset(x, y),
                        radius = radius
                    ),
                    radius = radius,
                    center = Offset(x, y),
                    blendMode = BlendMode.Plus
                )
            }
        }
    }
}


