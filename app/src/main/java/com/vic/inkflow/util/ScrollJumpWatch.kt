package com.vic.inkflow.util

/**
 * 跳頁稽核：把主列表的「內容座標」相鄰取樣拿來比，抓一次取樣內跳掉大半頁的事件。
 *
 * 為什麼需要它：手勢本身的位移是**有上限**的（狀態機每事件封頂 96px），所以「忽然跳一頁」
 * 不可能是單指平移算出來的；嫌疑只剩放手慣性、程式化捲動（`requestScrollToItem` /
 * `animateScrollToItem`）、以及版式高度改變。與其猜，不如在裝置上留下時間戳與前後座標，
 * 跟同一時間的 `InkFlowScroll` 寫入者日誌對照。
 *
 * 純 Kotlin（不碰 Compose / Android），可單測。取樣單位：
 * `y = firstVisibleItemIndex * itemFullPx + firstVisibleItemScrollOffset`。
 * `itemFullPx` 每次都比對——頁高變了本身就是會讓畫面「跳」的原因，要單獨回報，
 * 而且不能把「頁高變造成的座標差」誤報成跳頁。
 *
 * @param jumpFraction 判定門檻＝單頁高的幾倍（預設 0.5：正常滑動一次取樣遠小於半頁）。
 * @param windowMs 兩次取樣間隔超過這個時間就不判（中途可能發生換頁、旋轉、渲染），只更新基準。
 */
class ScrollJumpWatch(
    private val jumpFraction: Float = 0.5f,
    private val windowMs: Long = 400L,
) {
    private var lastT = 0L
    private var lastY = Float.NaN
    private var lastFull = Float.NaN

    /** @return 要落 log 的一行摘要；沒有異常回 null。 */
    fun sample(now: Long, first: Int, offset: Int, itemFullPx: Float): String? {
        if (itemFullPx <= 0f || !itemFullPx.isFinite()) return null
        val y = first * itemFullPx + offset
        val prevY = lastY
        val prevT = lastT
        val prevFull = lastFull
        lastY = y
        lastT = now
        lastFull = itemFullPx
        if (prevY.isNaN() || prevFull.isNaN()) return null
        // 頁高變：本身就會讓內容座標整體位移（像是跳頁），但成因完全不同，單獨標。
        if (kotlin.math.abs(itemFullPx - prevFull) > 0.5f) {
            return "LAYOUT full=${prevFull.toInt()}->${itemFullPx.toInt()} y=${prevY.toInt()}->${y.toInt()}"
        }
        val dt = now - prevT
        if (dt <= 0L || dt > windowMs) return null
        val dz = y - prevY
        if (kotlin.math.abs(dz) < itemFullPx * jumpFraction) return null
        return "JUMP dz=${dz.toInt()} dt=$dt y=${prevY.toInt()}->${y.toInt()} full=${itemFullPx.toInt()}"
    }

    /** 換頁／換視窗後重新建立基準，避免拿舊文件的座標比新文件。 */
    fun reset() {
        lastT = 0L
        lastY = Float.NaN
        lastFull = Float.NaN
    }
}
