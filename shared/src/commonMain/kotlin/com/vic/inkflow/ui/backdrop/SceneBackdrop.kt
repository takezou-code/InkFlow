package com.vic.inkflow.ui.backdrop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 特效場景的唯一宿主：時鐘、尺寸、靜模式全部在這裡，場景自己只負責畫。
 * 場景端因此拿不到 tick/state，也寫不出「偷偷存可變狀態」的實作。
 */
@Composable
fun SceneBackdrop(
    scene: BackdropScene,
    static: Boolean,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    val clock = rememberBackdropClock(static)
    Canvas(modifier = modifier.fillMaxSize()) {
        // 讀 frame 只排重繪不重組；時間取真實秒數（降頻後位置與速度不變）
        clock.frame
        val t = clock.seconds()
        with(scene) {
            render(
                t = t,
                w = size.width.coerceAtLeast(1f),
                h = size.height.coerceAtLeast(1f),
                dark = isDarkTheme
            )
        }
    }
}
