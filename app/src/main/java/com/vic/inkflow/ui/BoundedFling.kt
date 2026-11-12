package com.vic.inkflow.ui

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope

/**
 * 慣性的**距離上限**：一次 flick 最多前進 [maxDistancePx]（呼叫時現讀，通常＝三頁）。
 *
 * 為什麼要有上限：放手慣性本身沒問題，問題是它的尺度。一份課本動輒上千頁，
 * 不夾的話一次快甩就能飛掉十幾頁，使用者根本不知道自己落在哪。
 * 手勢本身的位移有 96px/事件封頂，**唯一能一次移動超過一頁的寫者就是慣性**
 * （見 docs/specs/touch.md）。
 *
 * 做法是夾住 `scrollBy` 的累積距離，不是夾初速：初速→距離要靠 decay spec 反推，
 * 換一個 `FlingBehavior` 就失效；夾距離則與衰減曲線無關，任何 delegate 都成立。
 * 上限用完就回 0（紙停住），不會超射也不會回彈。
 *
 * 上限的單位是「頁」不是「視窗」：舊制用視窗高（2136px），而一頁在低縮放下
 * 不到 1826px，於是預算剛好一頁 → 甩一下走完就停住，體感是「滑一頁後慣性消失」。
 */
class BoundedFlingBehavior(
    private val delegate: FlingBehavior,
    private val maxDistancePx: () -> Float,
) : FlingBehavior {

    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        val outer = this
        var budget = maxDistancePx().coerceAtLeast(0f)
        val limited = object : ScrollScope {
            override fun scrollBy(pixels: Float): Float {
                if (budget <= 0f) return 0f
                val used = outer.scrollBy(pixels.coerceIn(-budget, budget))
                budget -= kotlin.math.abs(used)
                return used
            }
        }
        return with(delegate) { limited.performFling(initialVelocity) }
    }
}
