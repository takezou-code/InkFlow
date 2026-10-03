package com.vic.inkflow.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/**
 * Centralized motion language: one spring personality and a small set of
 * durations so transitions feel like one app instead of assorted snippets.
 */
object Motion {
    const val DURATION_FAST = 180
    const val DURATION_NORMAL = 240
    const val DURATION_SLOW = 320

    /** Gentle, barely-bouncy spring for scale/press feedback. */
    fun <T> pressSpring() = spring<T>(
        dampingRatio = 0.75f,
        stiffness = Spring.StiffnessMediumLow
    )

    /** Smooth settle for layout/position changes. */
    fun <T> settleSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )

    /**
     * 放手吸附的彈簧：帶明顯過衝（「Q彈」）。
     *
     * dampingRatio 是取捨的關鍵：
     *  - < 0.5 會來回擺盪收斂，側欄看起來像在抖
     *  - > 0.7 幾乎沒彈性，等於 [settleSpring]
     * 0.55 剛好是「到位後輕輕帶一下再停住」。
     */
    fun <T> snapSpring() = spring<T>(
        dampingRatio = 0.55f,
        stiffness = Spring.StiffnessMediumLow
    )
}
