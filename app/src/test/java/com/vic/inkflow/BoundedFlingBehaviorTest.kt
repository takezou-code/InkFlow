package com.vic.inkflow

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import com.vic.inkflow.ui.BoundedFlingBehavior
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 慣性距離上限：delegate 想滑多遠都無所謂，累積位移不准超過預算。
 * 這條釘住「一次 flick ≤ 一個視窗高」的語義（頁高＝兩個視窗高，見 pitfalls）。
 */
class BoundedFlingBehaviorTest {

    private class FakeScope : ScrollScope {
        var total = 0f
        var calls = 0
        override fun scrollBy(pixels: Float): Float {
            calls++
            total += pixels
            return pixels
        }
    }

    private fun delegate(scrollPerFrame: Float, frames: Int) = object : FlingBehavior {
        override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
            repeat(frames) { scrollBy(scrollPerFrame) }
            return 0f
        }
    }

    @Test
    fun totalDistanceIsClampedToBudget() = runBlocking {
        val scope = FakeScope()
        val behavior = BoundedFlingBehavior(delegate(scrollPerFrame = 1_000f, frames = 10)) { 2_136f }
        with(behavior) { scope.performFling(8_000f) }
        assertEquals(2_136f, scope.total, 0.01f)
    }

    @Test
    fun shortFlingIsUntouched() = runBlocking {
        val scope = FakeScope()
        val behavior = BoundedFlingBehavior(delegate(scrollPerFrame = 200f, frames = 3)) { 2_136f }
        with(behavior) { scope.performFling(1_200f) }
        assertEquals(600f, scope.total, 0.01f)
    }

    @Test
    fun directionIsPreserved() = runBlocking {
        val scope = FakeScope()
        val behavior = BoundedFlingBehavior(delegate(scrollPerFrame = -1_000f, frames = 10)) { 2_136f }
        with(behavior) { scope.performFling(-8_000f) }
        assertEquals(-2_136f, scope.total, 0.01f)
    }

    @Test
    fun zeroBudgetMeansNoMovement() = runBlocking {
        val scope = FakeScope()
        val behavior = BoundedFlingBehavior(delegate(scrollPerFrame = 500f, frames = 5)) { 0f }
        with(behavior) { scope.performFling(3_000f) }
        assertEquals(0f, scope.total, 0.01f)
    }
}
