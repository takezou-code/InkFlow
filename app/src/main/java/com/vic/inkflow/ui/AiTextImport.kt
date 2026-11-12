package com.vic.inkflow.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import com.vic.inkflow.data.whiteRatioOfPixels

/**
 * Android 端的字型／點陣量測 shim。
 *
 * B3 之後，AI 匯入的切塊、分頁、版面量測本體都在 `:shared`
 * （`com.vic.inkflow.data.AiBlocks` / `AiTextLayout`），桌面與平板共用同一份。
 * 這裡只留兩個**只有 Android 做得到**的量測：
 *
 *  - `Paint.fontMetrics`（真實字高）——桌面沒有這支，它用自己的係數模型；
 *  - `Bitmap.getPixel`（紙白比例）——共用層只吃 `IntArray`。
 *
 * 別把分頁邏輯搬回來：兩端各寫一次，就是「同一段文字在兩台裝置折在不同地方」的來源。
 */

/** 裝置量測（DEFAULT_BOLD）：回傳 `[ascent, descent, leading]`。 */
internal fun androidFontMetrics(fontSize: Float): FloatArray {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = fontSize
    }
    val fm = paint.fontMetrics
    return floatArrayOf(fm.ascent, fm.descent, fm.leading)
}

/**
 * 字 metrics（與 InkCanvas/PageWorkspace 同公式：首行 baseline＋fontMetrics 行高；
 * 已對實現驗過）。回傳 (行高, descent)。
 */
fun textMetricsOf(fontSize: Float): Pair<Float, Float> {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = fontSize }
    val fm = p.fontMetrics
    return (-fm.ascent + fm.descent + fm.leading) to fm.descent
}

/**
 * 文字流重排用的垂直度量（行高、首行 ascent）——**與畫布同一顆字體**（DEFAULT_BOLD）。
 *
 * 為什麼要在 app 這一側量、再注入 `:shared`：`Paint` 只有 Android 有，而重排必須用
 * **畫出來的那組數字**算，否則推開的距離會跟實際行高差幾 px，一格一格累積成疊字。
 * 字體一定要與 `InkCanvas.textPaint` 相同：換成 DEFAULT 會讓行高不同。
 */
fun androidFlowLineMetrics(fontSize: Float): com.vic.inkflow.data.TextFlowLineMetrics {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = fontSize
    }
    val fm = p.fontMetrics
    return com.vic.inkflow.data.TextFlowLineMetrics(
        lineHeight = -fm.ascent + fm.descent + fm.leading,
        ascent = -fm.ascent
    )
}

/** M6：點陣紙白比例（取樣步長 stridePx；大點陣圖 IO 執行緒呼叫）。 */
fun whiteRatioOfBitmap(
    bmp: Bitmap,
    stridePx: Int = 8,
    luminanceThreshold: Int = 200
): Float {
    val w = bmp.width
    val h = bmp.height
    if (w <= 0 || h <= 0) return 0f
    val xs = (0 until w step stridePx).toList()
    if (xs.isEmpty()) return 0f
    val rows = (h + stridePx - 1) / stridePx
    val buf = IntArray(xs.size * rows)
    var k = 0
    var y = 0
    while (y < h) {
        for (x in xs) buf[k++] = bmp.getPixel(x, y)
        y += stridePx
    }
    return whiteRatioOfPixels(buf.copyOf(k), luminanceThreshold)
}
