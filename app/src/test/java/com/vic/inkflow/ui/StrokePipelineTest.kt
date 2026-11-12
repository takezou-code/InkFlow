package com.vic.inkflow.ui

import com.vic.inkflow.data.Point
import com.vic.inkflow.data.Stroke
import com.vic.inkflow.data.StrokeWithSamples
import com.vic.inkflow.util.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提筆管線的兩個純函數（回歸網）。
 *
 * 這裡刻意不碰 Compose / Room：兩個 bug 的「數學」與「合併規則」都能在這一層釘死，
 * 剩下的接線（誰把 frame 傳進來、誰訂閱這條流）由 [PendingStrokeOverlayTest] 與編譯把關。
 */
class StrokePipelineTest {

    private fun swp(id: String, page: Int = 0) = StrokeWithSamples(
        Stroke(
            id = id,
            documentUri = "doc",
            pageIndex = page,
            color = 0xFF000000.toInt(),
            strokeWidth = 4f
        ),
        listOf(Point(strokeId = id, x = 1f, y = 2f, width = 4f))
    )

    // ── mergePageStrokes：樂觀層接尾、去重、分頁 ─────────────────────────────

    @Test
    fun `pending strokes are appended after db strokes in insertion order`() {
        val merged = mergePageStrokes(
            dbStrokes = listOf(swp("s1"), swp("s2")),
            pending = linkedMapOf("p1" to swp("p1"), "p2" to swp("p2")),
            page = 0,
            hiddenIds = emptySet()
        )
        // 新的在後＝z 序正確；DB 沒有 ORDER BY，靠插入序，樂觀層本來就是最後寫的。
        assertEquals(listOf("s1", "s2", "p1", "p2"), merged.strokes.map { it.stroke.id })
        assertEquals(setOf("p1", "p2"), merged.pendingIds)
        assertTrue(merged.resolvedIds.isEmpty())
    }

    @Test
    fun `pending stroke of another page never leaks onto this page`() {
        val merged = mergePageStrokes(
            dbStrokes = listOf(swp("s1")),
            pending = linkedMapOf("p1" to swp("p1", page = 1)),
            page = 0,
            hiddenIds = emptySet()
        )
        assertEquals(listOf("s1"), merged.strokes.map { it.stroke.id })
        assertTrue(merged.pendingIds.isEmpty())
    }

    @Test
    fun `pending entry whose db row arrived is not drawn twice and is reported resolved`() {
        // 這是「同一筆被畫兩次」的那一幀：DB 熱流先到、樂觀層還沒退場。
        // 高亮筆 alpha 疊兩層＝提筆瞬間明顯偏暗，所以這裡必須只留 DB 那一份。
        val merged = mergePageStrokes(
            dbStrokes = listOf(swp("p1")),
            pending = linkedMapOf("p1" to swp("p1")),
            page = 0,
            hiddenIds = emptySet()
        )
        assertEquals(listOf("p1"), merged.strokes.map { it.stroke.id })
        assertEquals(setOf("p1"), merged.resolvedIds)
        assertTrue(merged.pendingIds.isEmpty())
    }

    @Test
    fun `hidden pending stroke is neither drawn nor marked pending`() {
        // undo 在落庫前就砍掉這筆：樂觀隱藏集一視同仁。
        val merged = mergePageStrokes(
            dbStrokes = emptyList(),
            pending = linkedMapOf("p1" to swp("p1")),
            page = 0,
            hiddenIds = setOf("p1")
        )
        assertTrue(merged.strokes.isEmpty())
        assertTrue(merged.pendingIds.isEmpty())
    }

    @Test
    fun `hidden db row still resolves its pending twin`() {
        // DB 有了但同時被隱藏：樂觀層也要退場，否則 map 永遠留著一條殭屍。
        val merged = mergePageStrokes(
            dbStrokes = listOf(swp("p1")),
            pending = linkedMapOf("p1" to swp("p1")),
            page = 0,
            hiddenIds = setOf("p1")
        )
        assertTrue(merged.strokes.isEmpty())
        assertEquals(setOf("p1"), merged.resolvedIds)
    }

    // ── normalizeStrokePoints：框架綁定（提筆偏移） ─────────────────────────

    /**
     * 同一個實體落點在兩個不同大小的畫布上量到（點與框架等比縮放），
     * 正規化後必須是同一個 model 座標 —— 這就是「用哪個框架」的判準。
     */
    @Test
    fun `same physical ink in different canvas frames normalizes to same model point`() {
        val modelW = 1000f
        val modelH = 1400f
        val a = normalizeStrokePoints(
            points = listOf(StrokePoint(400f, 600f, 8f), StrokePoint(500f, 800f, 8f)),
            frameW = 2000f, frameH = 2800f,
            modelW = modelW, modelH = modelH, docZoom = 1f
        ).points
        val b = normalizeStrokePoints(
            points = listOf(StrokePoint(600f, 900f, 12f), StrokePoint(750f, 1200f, 12f)),
            frameW = 3000f, frameH = 4200f,
            modelW = modelW, modelH = modelH, docZoom = 1f
        ).points
        assertEquals(a.size, b.size)
        a.indices.forEach { i ->
            assertEquals(a[i].x, b[i].x, 0.001f)
            assertEquals(a[i].y, b[i].y, 0.001f)
            // 寬度吃同一個框架：8px@2000 ≡ 12px@3000，換回 model 就是同一條線。
            assertEquals(a[i].width, b[i].width, 0.001f)
        }
        assertEquals(200f, a[0].x, 0.001f)
        assertEquals(300f, a[0].y, 0.001f)
    }

    /**
     * 反面網：拿「提筆當下（新）」的框架去換算「舊框架收的點」，
     * 整筆會按比例位移 —— 正是使用者看到的「一放手就歪」。這條測試證明
     * 這個判準抓得到那個 bug（若哪天有人把 frame 參數拿掉，上面那條會過、這條的
     * 對照值就會變成真正的入庫值）。
     */
    @Test
    fun `using the wrong frame shifts the whole stroke proportionally`() {
        val modelW = 1000f
        val modelH = 1400f
        // 點在舊框架 2000 寬下量到；提筆時畫布已變成 3000 寬。
        val correct = normalizeStrokePoints(
            points = listOf(StrokePoint(400f, 600f, 8f)),
            frameW = 2000f, frameH = 2800f,
            modelW = modelW, modelH = modelH, docZoom = 1f
        ).points.first()
        val wrong = normalizeStrokePoints(
            points = listOf(StrokePoint(400f, 600f, 8f)),
            frameW = 3000f, frameH = 4200f,
            modelW = modelW, modelH = modelH, docZoom = 1f
        ).points.first()
        // 舊框架 → x=200；用新框架 → x=133.3（少了 1/3）＝整筆往左上縮。
        assertEquals(200f, correct.x, 0.001f)
        assertEquals(400f / 3f, wrong.x, 0.001f)
        assertTrue(correct.x > wrong.x)
    }

    @Test
    fun `width scale follows frame and docZoom`() {
        val n = normalizeStrokePoints(
            points = listOf(StrokePoint(100f, 100f, 8f)),
            frameW = 2000f, frameH = 2800f,
            modelW = 1000f, modelH = 1400f,
            docZoom = 2f
        )
        // 2000px 框架 → invScale 0.5；寬度再 ×docZoom 2 = 1.0（存 zoom=1 基準）。
        assertEquals(1f, n.widthScale, 0.0001f)
        assertEquals(8f, n.points.first().width, 0.0001f)
        assertEquals(50f, n.points.first().x, 0.0001f)
    }
}
