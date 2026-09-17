package com.vic.inkflow.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelCopy
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * S1：Gemini 公式像素裁圖 — TeX 拿不到時的第二引擎（所見即所得）。
 * 流程：collect 回傳元素 CSS 框 → 映射 window 像素 → PixelCopy 整窗 → 裁剪 →
 * 深底自動反白 → 墨量自檢 → 存檔。全部失敗一律回 null，上層退回文字路。
 */
object WebCrop {
    private const val TAG = "WebCrop"

    /** CSS px 框（getBoundingClientRect 原值，viewport 相對）。 */
    data class CssBox(val x: Float, val y: Float, val w: Float, val h: Float)

    /** Window 像素裁剪矩（純資料，可單測）。 */
    data class CropPx(val l: Int, val t: Int, val r: Int, val b: Int)

    /** CSS px → device px（WebView.scale 已含 density 與頁面縮放）。 */
    fun cssToDevice(v: Float, scale: Float): Int = (v * scale).roundToInt()

    /**
     * 算出在 window 點陣圖上的裁剪矩形；框無效/完全在窗外/過小/過大回 null。
     * 純函數（可單測），呼叫方再包成 android.graphics.Rect。
     */
    fun cropWindowPx(
        box: CssBox,
        scale: Float,
        locX: Int,
        locY: Int,
        winW: Int,
        winH: Int,
        insetPx: Int = 2
    ): CropPx? {
        if (box.w <= 0f || box.h <= 0f || scale <= 0f) return null
        val l = locX + cssToDevice(box.x, scale) + insetPx
        val t = locY + cssToDevice(box.y, scale) + insetPx
        val r = locX + cssToDevice(box.x + box.w, scale) - insetPx
        val b = locY + cssToDevice(box.y + box.h, scale) - insetPx
        val cl = l.coerceIn(0, winW)
        val ct = t.coerceIn(0, winH)
        val cr = r.coerceIn(0, winW)
        val cb = b.coerceIn(0, winH)
        if (cr <= cl || cb <= ct) return null
        val w = cr - cl
        val h = cb - ct
        // 太小的大概率是 UI 殘件不是公式
        if (w < 24 || h < 24) return null
        // 太大的整頁誤裁（>80% 窗）也不要
        if (w.toLong() * h > winW.toLong() * winH * 8 / 10) return null
        return CropPx(cl, ct, cr, cb)
    }

    /**
     * 算出在 window 點陣圖上的裁剪矩形；框無效/完全在窗外回 null。
     * @param loc WebView 左上角在 window 的 device px（getLocationInWindow）
     */
    fun cropWindowRect(
        box: CssBox,
        scale: Float,
        locX: Int,
        locY: Int,
        winW: Int,
        winH: Int,
        insetPx: Int = 2
    ): Rect? {
        val c = cropWindowPx(box, scale, locX, locY, winW, winH, insetPx) ?: return null
        return Rect(c.l, c.t, c.r, c.b)
    }

    /** 整窗截圖（Main thread 呼叫；PixelCopy API 26+，minSdk 32）。 */
    suspend fun captureWindow(activity: Activity): Bitmap? {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.w(TAG, "captureWindow off-main")
            return null
        }
        return try {
            val decor = activity.window?.decorView ?: return null
            val w = decor.width
            val h = decor.height
            if (w <= 0 || h <= 0) return null
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val done = CompletableDeferred<Int?>()
            PixelCopy.request(
                activity.window,
                bmp,
                { result -> done.complete(result) },
                Handler(Looper.getMainLooper())
            )
            val code = withTimeoutOrNull(5000) { done.await() }
            if (code == PixelCopy.SUCCESS) bmp else {
                bmp.recycle()
                Log.w(TAG, "pixelcopy code=$code")
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "captureWindow failed: $t")
            null
        }
    }

    /** 取樣中位亮度（0黑～255白），步長 stridePx。 */
    fun medianLuminance(bmp: Bitmap, stridePx: Int = 12): Float {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return 255f
        var n = 0
        var sum = 0L
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = bmp.getPixel(x, y)
                sum += (((p shr 16) and 0xff) + ((p shr 8) and 0xff) + (p and 0xff)) / 3
                n++
                x += stridePx
            }
            y += stridePx
        }
        return if (n > 0) sum.toFloat() / n else 255f
    }

    /** 深底（<128）全圖反色→黑字白底；淺底原樣。回傳新圖（輸入由呼叫方 recycle）。 */
    fun normalizeToWhite(bmp: Bitmap): Bitmap {
        if (medianLuminance(bmp) >= 128f) return bmp
        val w = bmp.width
        val h = bmp.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val p = px[i]
            val a = (p ushr 24) and 0xff
            px[i] = (a shl 24) or ((255 - ((p shr 16) and 0xff)) shl 16) or
                ((255 - ((p shr 8) and 0xff)) shl 8) or (255 - (p and 0xff))
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        Log.d(TAG, "inverted dark crop to white")
        return out
    }

    /** 墨量比（非白像素佔比；透明視為空）。 */
    fun inkRatio(bmp: Bitmap, stridePx: Int = 12): Float {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return 0f
        var n = 0
        var ink = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = bmp.getPixel(x, y)
                n++
                val a = (p ushr 24) and 0xff
                if (a >= 128) {
                    val r = (p shr 16) and 0xff
                    val g = (p shr 8) and 0xff
                    val b = p and 0xff
                    if (r < 240 || g < 240 || b < 240) ink++
                }
                x += stridePx
            }
            y += stridePx
        }
        return if (n > 0) ink.toFloat() / n else 0f
    }

    /**
     * 裁一個公式框：整窗截圖 → 裁剪 → 反白 → 自檢 → 存檔。
     * @return RenderedMath（file/px尺寸）或 null（任一步失敗→上層退文字路）
     */
    suspend fun cropFormula(
        activity: Activity,
        webView: WebView,
        box: CssBox,
        tag: String
    ): RenderedMath? {
        if (Looper.myLooper() != Looper.getMainLooper()) return null
        return try {
            @Suppress("DEPRECATION")
            val scale = webView.scale.takeIf { it > 0f }
                ?: webView.resources.displayMetrics.density
            val loc = IntArray(2)
            (webView as View).getLocationInWindow(loc)
            val decor = activity.window?.decorView ?: return null
            val winW = decor.width
            val winH = decor.height
            val rect = cropWindowRect(box, scale, loc[0], loc[1], winW, winH)
            if (rect == null) {
                Log.w(TAG, "crop skipped box=$box scale=$scale win=${winW}x$winH")
                return null
            }
            val full = captureWindow(activity) ?: return null
            var bmp = Bitmap.createBitmap(full, rect.left, rect.top, rect.width(), rect.height())
            full.recycle()
            bmp = normalizeToWhite(bmp)
            val ink = inkRatio(bmp)
            Log.d(TAG, "crop $tag ${rect.width()}x${rect.height()} ink=${"%.3f".format(ink)}")
            if (ink < 0.0005f) {
                bmp.recycle()
                Log.w(TAG, "crop blank, fallback")
                return null
            }
            val f = File(activity.filesDir, "crop_${System.currentTimeMillis()}_$tag.png")
            FileOutputStream(f).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val rm = RenderedMath(f, bmp.width, bmp.height)
            bmp.recycle()
            rm
        } catch (t: Throwable) {
            Log.w(TAG, "cropFormula failed: $t")
            null
        }
    }
}
