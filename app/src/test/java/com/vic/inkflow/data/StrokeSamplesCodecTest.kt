package com.vic.inkflow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 點位 BLOB 格式的守門測試（v34 / M1）。
 *
 * 這條格式是「DB 裡既有的 140 萬點」與「之後每一筆新墨」的共同契約：
 * 一旦 pack/unpack 不對稱，症狀是開檔後墨消失或位置錯亂，而且只有真機看得到。
 * 所以用單測釘住三件事：順序、欄位精度、長度容錯。
 */
class StrokeSamplesCodecTest {

    private fun pt(i: Int) = Point(
        id = i.toLong(),
        strokeId = "stroke-$i",
        x = 12.5f * i,
        y = -3.25f * i,
        width = 0.125f * i
    )

    @Test
    fun `roundtrip preserves order and all three fields`() {
        val src = (0 until 500).map { pt(it) }
        val bytes = StrokeSamplesCodec.pack(src)
        assertEquals(500 * StrokeSamplesCodec.BYTES_PER_POINT, bytes.size)

        val out = StrokeSamplesCodec.unpack("stroke-x", bytes)
        assertEquals(500, out.size)
        out.forEachIndexed { i, p ->
            assertEquals(src[i].x, p.x, 0f)
            assertEquals(src[i].y, p.y, 0f)
            assertEquals(src[i].width, p.width, 0f)
            // 順序即筆劃順序：解包後 id 是 0..n-1 的索引
            assertEquals(i.toLong(), p.id)
            // strokeId 由呼叫端提供，不在 BLOB 內
            assertEquals("stroke-x", p.strokeId)
        }
    }

    @Test
    fun `empty input produces empty blob and empty list`() {
        val bytes = StrokeSamplesCodec.pack(emptyList())
        assertEquals(0, bytes.size)
        assertTrue(StrokeSamplesCodec.unpack("s", bytes).isEmpty())
        assertTrue(StrokeSamplesCodec.unpack("s", null).isEmpty())
    }

    @Test
    fun `truncated blob decodes only whole points instead of throwing`() {
        val bytes = StrokeSamplesCodec.pack(listOf(pt(0), pt(1)))
        // 砍掉最後 5 bytes：第 2 個點不完整
        val cut = bytes.copyOf(bytes.size - 5)
        val out = StrokeSamplesCodec.unpack("s", cut)
        assertEquals(1, out.size)
        assertEquals(pt(0).x, out[0].x, 0f)
    }

    @Test
    fun `width is preserved separately from x and y`() {
        val p = Point(id = 0, strokeId = "s", x = 1f, y = 2f, width = 3f)
        val out = StrokeSamplesCodec.unpack("s", StrokeSamplesCodec.pack(listOf(p)))
        assertEquals(1f, out[0].x, 0f)
        assertEquals(2f, out[0].y, 0f)
        assertEquals(3f, out[0].width, 0f)
    }
}
