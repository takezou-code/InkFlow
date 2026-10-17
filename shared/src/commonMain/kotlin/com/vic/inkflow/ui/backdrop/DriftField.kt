package com.vic.inkflow.ui.backdrop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.sin
import kotlin.random.Random

/** 回繞跨度：上下各留一點回收區，粒子回繞那一下不刺眼。 */
private const val DRIFT_SPAN = 1.15f

/**
 * 會飄的粒子（雪／花瓣／浮光／火星）：位置是 t（秒）的純函數，跟泡泡同一套精神。
 * 名單建一次就永不重建（見 [driftParticles]），每幀只是代入 t 算座標，不配置大物件。
 *
 * @param depth 0＝最遠、1＝最近。呼叫端拿它插值尺寸／亮度（遠小慢淡、近大快亮）。
 */
data class DriftParticle(
    val baseX: Float,
    val baseY: Float,
    val depth: Float,
    val speed: Float,
    val alpha: Float,
    val swayAmp: Float,
    val swaySpeed: Float,
    val swayPhase: Float
) {
    fun xAt(t: Float, w: Float): Float = (baseX + sin(t * swaySpeed + swayPhase) * swayAmp) * w

    /** @param rise true＝上升（浮光／火星），false＝飄落（雪／花瓣）。 */
    fun yAt(t: Float, h: Float, rise: Boolean = false): Float {
        val raw = (baseY + t * speed) % DRIFT_SPAN
        return (if (rise) 1.05f - raw else raw) * h
    }
}

/**
 * 固定 seed 產生粒子名單：**同一個 seed 每次啟動都一模一樣**（可重現、可回歸比對）。
 *
 * @param speed 每秒幾分之幾屏高（近的那端＝speed.end）。
 */
fun driftParticles(
    count: Int,
    seed: Long,
    speed: ClosedFloatingPointRange<Float>,
    alpha: ClosedFloatingPointRange<Float>,
    swayAmp: ClosedFloatingPointRange<Float>,
    swaySpeed: ClosedFloatingPointRange<Float> = 0.25f..1f
): List<DriftParticle> {
    val rnd = Random(seed)
    return List(count) {
        val depth = rnd.nextFloat()
        DriftParticle(
            baseX = rnd.nextFloat(),
            baseY = rnd.nextFloat(),
            depth = depth,
            speed = speed.start + depth * (speed.endInclusive - speed.start),
            alpha = alpha.start + rnd.nextFloat() * (alpha.endInclusive - alpha.start),
            swayAmp = swayAmp.start + rnd.nextFloat() * (swayAmp.endInclusive - swayAmp.start),
            swaySpeed = swaySpeed.start + rnd.nextFloat() * (swaySpeed.endInclusive - swaySpeed.start),
            swayPhase = rnd.nextFloat() * 6.28f
        )
    }
}

/** 天空底：亮暗兩套色票的縱向漸層。省掉每個場景各寫一次 drawRect＋Brush。 */
fun DrawScope.skyVertical(dark: Boolean, darkColors: List<Color>, lightColors: List<Color>) {
    drawRect(brush = Brush.verticalGradient(colors = if (dark) darkColors else lightColors))
}

/** 圓形柔光（月暈／日暈／光源）：中心 alpha 到邊緣歸零。 */
fun DrawScope.softGlow(center: Offset, radius: Float, color: Color) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color, Color.Transparent),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )
}
