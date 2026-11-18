package com.vic.inkflow.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 拖曳邊緣自捲是全手勢共用的唯一一份公式（viewport 座標）。
 *
 * 這裡釘住三件事，避免以後又有人「順手」改回紙界判定：
 *  1. 只有進入上下感應區才捲，畫面中央永遠 0（不能亂飄）。
 *  2. 越靠邊越快、方向正確（上緣負、下緣正）。
 *  3. 貼邊封頂；視窗尺寸異常時安全回 0。
 */
class DragEdgeScrollTest {

    private val viewportH = 2000f
    private val zone = viewportH * EDGE_SCROLL_ZONE_FRACTION

    @Test
    fun `middle of viewport never scrolls`() {
        assertEquals(0f, dragEdgeScrollDy(viewportH / 2f, viewportH), 0f)
        assertEquals(0f, dragEdgeScrollDy(zone + 1f, viewportH), 0f)
        assertEquals(0f, dragEdgeScrollDy(viewportH - zone - 1f, viewportH), 0f)
    }

    @Test
    fun `top edge scrolls up and bottom edge scrolls down`() {
        assertTrue(dragEdgeScrollDy(1f, viewportH) < 0f)
        assertTrue(dragEdgeScrollDy(viewportH - 1f, viewportH) > 0f)
    }

    @Test
    fun `closer to the edge scrolls faster`() {
        val justInside = abs(dragEdgeScrollDy(zone * 0.9f, viewportH))
        val nearEdge = abs(dragEdgeScrollDy(zone * 0.1f, viewportH))
        assertTrue("越靠邊要越快（$nearEdge vs $justInside）", nearEdge > justInside)
        // 同一深度上下對稱
        assertEquals(
            abs(dragEdgeScrollDy(zone * 0.25f, viewportH)),
            abs(dragEdgeScrollDy(viewportH - zone * 0.25f, viewportH)),
            0.001f
        )
    }

    @Test
    fun `very edge is capped at max speed`() {
        assertEquals(-EDGE_SCROLL_MAX_PX_PER_FRAME, dragEdgeScrollDy(-500f, viewportH), 0f)
        assertEquals(EDGE_SCROLL_MAX_PX_PER_FRAME, dragEdgeScrollDy(viewportH + 500f, viewportH), 0f)
    }

    @Test
    fun `tiny or invalid viewport is safe`() {
        assertEquals(0f, dragEdgeScrollDy(0f, 0f), 0f)
        assertEquals(0f, dragEdgeScrollDy(0f, -10f), 0f)
        // 小視窗用最小感應區，畫面中央仍不動
        assertEquals(0f, dragEdgeScrollDy(200f, 400f), 0f)
        assertTrue(dragEdgeScrollDy(399f, 400f) > 0f)
    }
}
