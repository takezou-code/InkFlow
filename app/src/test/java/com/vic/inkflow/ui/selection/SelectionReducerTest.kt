package com.vic.inkflow.ui.selection

import androidx.compose.ui.geometry.Offset
import com.vic.inkflow.data.Point
import com.vic.inkflow.data.Stroke
import com.vic.inkflow.data.StrokeWithSamples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 狀態機（純 reducer）：平移／縮放都是**絕對值**，不是增量。
 *
 * 「拖曳不跟手」以前的根因之一就是每幀把 delta 累加，事件合併或掉幀就漂；
 * 這裡直接釘住「連送兩次絕對值，結果等於最後一次」。
 */
class SelectionReducerTest {

    private fun stroke(id: String, page: Int = 0) = StrokeWithSamples(
        stroke = Stroke(
            id = id, documentUri = "u", pageIndex = page, color = 0, strokeWidth = 2f,
            boundsLeft = 100f, boundsTop = 100f, boundsRight = 200f, boundsBottom = 200f
        ),
        points = listOf(Point(strokeId = id, x = 150f, y = 150f))
    )

    private fun readyState(items: SelectionItems): SelectionState = SelectionState(
        phase = SelectionPhase.READY,
        region = SelectionRegion(
            polygonsByPage = mapOf(0 to listOf(
                listOf(Offset(90f, 90f), Offset(210f, 90f), Offset(210f, 210f))
            )),
            srcPage = 0
        ),
        items = items,
        frame = SelectionFrame(0, androidx.compose.ui.geometry.Rect(100f, 100f, 200f, 200f))
    )

    @Test
    fun translateIsAbsoluteNotCumulative() {
        val sel = stroke("a")
        var state = SelectionReducer.reduce(
            readyState(SelectionItems(strokes = listOf(sel))),
            SelectionIntent.BeginTransform(anchor = Offset(150f, 150f))
        )
        assertEquals(SelectionPhase.TRANSFORMING, state.phase)

        state = SelectionReducer.reduce(state, SelectionIntent.Translate(Offset(10f, 0f)))
        state = SelectionReducer.reduce(state, SelectionIntent.Translate(Offset(10f, 0f)))
        state = SelectionReducer.reduce(state, SelectionIntent.Translate(Offset(25f, -5f)))

        // 絕對位移：最後一次蓋掉前面，不是 10+10+25。
        assertEquals(25f, state.transform.translation.x, 0.001f)
        assertEquals(-5f, state.transform.translation.y, 0.001f)
    }

    @Test
    fun scaleIsAbsoluteAndClamped() {
        val sel = stroke("a")
        var state = SelectionReducer.reduce(
            readyState(SelectionItems(strokes = listOf(sel))),
            SelectionIntent.BeginTransform(anchor = Offset(100f, 100f))
        )
        state = SelectionReducer.reduce(state, SelectionIntent.Scale(1.5f))
        state = SelectionReducer.reduce(state, SelectionIntent.Scale(0.8f))
        assertEquals(0.8f, state.transform.scale, 0.001f)

        state = SelectionReducer.reduce(state, SelectionIntent.Scale(0.01f))
        assertEquals(0.2f, state.transform.scale, 0.001f)
    }

    @Test
    fun translateIgnoredWhenNotTransforming() {
        val state = readyState(SelectionItems(strokes = listOf(stroke("a"))))
        val after = SelectionReducer.reduce(state, SelectionIntent.Translate(Offset(50f, 50f)))
        assertTrue(after.transform.isIdentity)
        assertEquals(SelectionPhase.READY, after.phase)
    }

    @Test
    fun beginTransformRequiresItems() {
        val state = SelectionState(
            phase = SelectionPhase.READY,
            region = SelectionRegion(emptyMap(), 0)
        )
        val after = SelectionReducer.reduce(state, SelectionIntent.BeginTransform(Offset.Zero))
        assertEquals(SelectionPhase.READY, after.phase)
    }

    @Test
    fun staleResolveIsDropped() {
        val fresh = stroke("fresh")
        val state = SelectionState(phase = SelectionPhase.READY, revision = 7L)
        val after = SelectionReducer.reduce(
            state,
            SelectionIntent.Resolved(
                revision = 6L,
                region = SelectionRegion(emptyMap(), 0),
                items = SelectionItems(strokes = listOf(fresh)),
                frame = null
            )
        )
        assertTrue(after.items.isEmpty)
        assertEquals(7L, after.revision)
    }

    @Test
    fun newerResolveWins() {
        val fresh = stroke("fresh")
        val region = SelectionRegion(
            polygonsByPage = mapOf(0 to listOf(
                listOf(Offset(0f, 0f), Offset(10f, 0f), Offset(10f, 10f))
            )),
            srcPage = 0
        )
        val after = SelectionReducer.reduce(
            SelectionState(phase = SelectionPhase.RESOLVING, revision = 3L),
            SelectionIntent.Resolved(
                revision = 4L,
                region = region,
                items = SelectionItems(strokes = listOf(fresh)),
                frame = SelectionFrame(0, androidx.compose.ui.geometry.Rect(0f, 0f, 10f, 10f))
            )
        )
        assertEquals(SelectionPhase.READY, after.phase)
        assertEquals(4L, after.revision)
        assertEquals(1, after.items.strokes.size)
        assertEquals(region, after.region)
    }

    @Test
    fun settledKeepsRevisionAndResetsTransform() {
        var state = readyState(SelectionItems(strokes = listOf(stroke("a"))))
        state = SelectionReducer.reduce(state, SelectionIntent.BeginTransform(Offset(150f, 150f)))
        state = SelectionReducer.reduce(state, SelectionIntent.Translate(Offset(30f, 30f)))
        state = SelectionReducer.reduce(state, SelectionIntent.Scale(1.4f))

        val after = SelectionReducer.reduce(
            state,
            SelectionIntent.Settled(
                items = SelectionItems(strokes = listOf(stroke("b"))),
                region = null,
                frame = SelectionFrame(0, androidx.compose.ui.geometry.Rect(1f, 2f, 3f, 4f))
            )
        )
        assertEquals(SelectionPhase.READY, after.phase)
        assertTrue(after.transform.isIdentity)
        assertEquals("b", after.items.strokes.first().stroke.id)
        assertNull(after.region)
    }

    @Test
    fun clearRemovesRegionAndItemsButKeepsRevision() {
        var state = readyState(SelectionItems(strokes = listOf(stroke("a"))))
        state = SelectionReducer.reduce(state, SelectionIntent.SetCommitPreview(listOf(stroke("a"))))
        val after = SelectionReducer.reduce(state, SelectionIntent.Clear)

        assertTrue(after.items.isEmpty)
        assertNull(after.region)
        assertNull(after.frame)
        assertNull(after.commitPreview)
        assertTrue(after.transform.isIdentity)
        assertEquals(state.revision, after.revision)
    }

    @Test
    fun probingAppliesRegionImmediatelyAndDropsOldSelection() {
        val old = stroke("old")
        val state = readyState(SelectionItems(strokes = listOf(old)))
            .copy(commitPreview = listOf(old), revision = 2L)
        val region = SelectionRegion(
            polygonsByPage = mapOf(0 to listOf(
                listOf(Offset(0f, 0f), Offset(50f, 0f), Offset(50f, 50f))
            )),
            srcPage = 0
        )

        val after = SelectionReducer.reduce(state, SelectionIntent.Probing(revision = 3L, region = region))

        assertEquals(SelectionPhase.RESOLVING, after.phase)
        assertEquals(3L, after.revision)
        // 圈選區域當下就能被提取／AI 取用。
        assertEquals(region, after.region)
        // 舊選取立即作廢：判定中不能還顯示上一圈的內容。
        assertTrue(after.items.isEmpty)
        assertNull(after.commitPreview)
        // 判定中先顯示圈出的區域（空白圈選也一樣）。
        assertEquals(0, after.frame?.page)
    }
}
