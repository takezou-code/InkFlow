package com.vic.inkflow.data

import com.vic.inkflow.data.Point
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 點位打包：一筆筆跡的所有點壓成**一個 BLOB**（每點 3 個 little-endian float32：x, y, width）。
 *
 * 為什麼要這支（2026-11-15 M1）：
 * 舊 schema 是「一點一 row」，讀一頁要靠 Room 的 `@Relation` 把 27k 列拉成
 * `PointEntity`——每一列都會在 `CursorWindow.nativeGetString` 配置一個 strokeId
 * String。這份文件 140 萬點，捲動時並發幾十次整頁重讀，瞬間就把 512MB heap 打穿。
 * 15 次以上閃退的堆棧全部是同一行：
 * `StrokeDao_Impl.__fetchRelationshippointsAscomVicInkflowDataPointEntity`。
 *
 * 改成 BLOB 後：一頁的點位只走 **每筆一列**（通常幾百列），沒有 per-point String、
 * 沒有 per-point 物件裝箱、CursorWindow 壓力降兩個數量級；一頁 27k 點＝324KB。
 *
 * 格式刻意用固定 12 bytes/點（不存 id）：
 * - `id` 在渲染／命中／匯出都不被讀（全庫掃過，見 pitfalls 同日條目）。
 * - 解包時以陣列索引當 `id`（0..n-1），保留「順序即筆劃順序」的語意。
 * - 版本欄位 `count` 存在同一列，長度不符時以 `data.size / 12` 為準（容錯，不拋）。
 */
internal object StrokeSamplesCodec {

    /** x, y, width 各 4 bytes。 */
    const val BYTES_PER_POINT: Int = 12

    /** model 點 → BLOB。呼叫端保證 [points] 屬於同一筆（同一個 strokeId）。 */
    fun pack(points: List<Point>): ByteArray {
        if (points.isEmpty()) return EMPTY
        val buf = ByteBuffer.allocate(points.size * BYTES_PER_POINT).order(ByteOrder.LITTLE_ENDIAN)
        for (p in points) {
            buf.putFloat(p.x)
            buf.putFloat(p.y)
            buf.putFloat(p.width)
        }
        return buf.array()
    }

    /**
     * BLOB → model 點。[strokeId] 由呼叫端提供（BLOB 裡不存，省 4 bytes/點 的長度前綴與重複字串）。
     *
     * 容錯：`data` 長度不是 12 的倍數時，只解到最後一個完整點——寧可少畫一點，
     * 也不要在開檔路徑拋例外（這條路徑過去就是閃退的來源）。
     */
    fun unpack(strokeId: String, data: ByteArray?): List<Point> {
        if (data == null || data.size < BYTES_PER_POINT) return emptyList()
        val count = data.size / BYTES_PER_POINT
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<Point>(count)
        for (i in 0 until count) {
            out.add(
                Point(
                    id = i.toLong(),
                    strokeId = strokeId,
                    x = buf.getFloat(),
                    y = buf.getFloat(),
                    width = buf.getFloat()
                )
            )
        }
        return out
    }

    private val EMPTY = ByteArray(0)
}
