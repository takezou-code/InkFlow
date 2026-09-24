package com.vic.inkflow

import androidx.compose.ui.unit.Density
import com.vic.inkflow.util.PalmRejectionFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PalmAreaTest {

    private val density2x = object : Density {
        override val density = 2f
        override val fontScale = 1f
    }

    @Test
    fun missingDataIsFailOpen() {
        // touchMajor <= 0（裝置不回報）一律當手指，不誤殺。
        assertFalse(PalmRejectionFilter.isPalmByArea(0f, density2x))
        assertFalse(PalmRejectionFilter.isPalmByArea(-1f, density2x))
    }

    @Test
    fun normalizedUnitsFollowRawThreshold() {
        // 小米歸一化檔：1.8 為界。
        assertFalse(PalmRejectionFilter.isPalmByArea(1.6f, density2x))
        assertTrue(PalmRejectionFilter.isPalmByArea(2.0f, density2x))
        assertTrue(PalmRejectionFilter.isPalmByArea(3.8f, density2x))
    }

    @Test
    fun pixelUnitsFollow45dp() {
        // 像素檔（>= 10）：45dp 為界；density=2 時 45dp = 90px。
        assertFalse(PalmRejectionFilter.isPalmByArea(80f, density2x))
        assertTrue(PalmRejectionFilter.isPalmByArea(100f, density2x))
        assertTrue(PalmRejectionFilter.isPalmByArea(400f, density2x))
    }
}
