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
}
