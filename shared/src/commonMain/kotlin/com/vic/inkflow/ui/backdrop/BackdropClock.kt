package com.vic.inkflow.ui.backdrop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import kotlin.time.TimeSource

/**
 * 所有動態背景唯一的時鐘：delay(22) 驅動的 frame counter ＋ 建立時的奈秒起點。
 *
 * 為什麼是 delay 而不是 withFrameNanos：不對齊 vsync、不喚醒每一幀，最省電；
 * 泡泡那種慢動作 45fps 綽綽有餘，GPU 少畫七成。時間一律取 [seconds]（真實秒數），
 * 所以降頻不影響位置與速度——[frame] 只負責「有動就重畫」。
 *
 * 用法：`Canvas { clock.frame.longValue; val t = clock.seconds(); ... }`
 */
class BackdropClock internal constructor() {
    private val state = mutableLongStateOf(0L)

    /** 排重繪用：Canvas 內讀一次（讀的是 snapshot state）就會在每次 tick 重畫。 */
    val frame: Long get() = state.longValue

    // `TimeSource.Monotonic` rather than `System.nanoTime()`: the latter is a
    // JVM API and this module now compiles for iOS too, where there is no
    // `java.lang.System`. A monotonic source is also the right clock for an
    // animation: it is unaffected by wall-clock adjustments.
    private val startMark = TimeSource.Monotonic.markNow()

    fun seconds(): Float = startMark.elapsedNow().inWholeMilliseconds / 1000f

    internal fun advance() {
        state.longValue += 1
    }
}

/**
 * @param static 靜模式：不啟動迴圈 → frame 永遠 0 → 背景凍結在首幀。
 *   切回流光時 LaunchedEffect 重啟，時間取真實秒數，所以是「續行」不是「跳回 0」。
 */
@Composable
fun rememberBackdropClock(static: Boolean = false): BackdropClock {
    val clock = remember { BackdropClock() }
    LaunchedEffect(static) {
        if (!static) {
            while (true) {
                delay(22)
                clock.advance()
            }
        }
    }
    return clock
}
