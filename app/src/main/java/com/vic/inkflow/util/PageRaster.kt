package com.vic.inkflow.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * 頁面點陣圖的產生與裁切（P3 從 EditorViewModel 抽出）。
 *
 * 這組 helper 原本是 EditorViewModel 最尾端的 private 方法群。它們的共同點是
 * **完全不依賴 ViewModel 的任何狀態**——只吃 context/uri/頁碼，吐 Bitmap。
 * 放在 ViewModel 裡只是因為它們「剛好在裡面」，不是因為它們屬於那層。
 *
 * 順帶解決一個實際問題：renderPdfPageFromDocumentUri 會 open PdfRenderer，
 * 在 ViewModel 裡看起來像輕量運算，其實是同步的檔案 I/O 加原生資源；
 * 移到這裡之後依賴關係一眼看得出來。
 *
 * 錯誤處理刻意維持原樣（回傳 null / 回傳原圖），這是呼叫端既有契約。
 */
object PageRaster {

    /** 從 content:// / file:// / 裸路徑載入點陣圖。任何失敗回傳 null。 */
    fun loadBitmapFromUri(context: Context, uri: String): Bitmap? {
        return try {
            val parsed = Uri.parse(uri)
            if (parsed.scheme == "content") {
                context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it) }
            } else {
                // file:// 或裸路徑
                val path = if (parsed.scheme == "file") parsed.path ?: uri else uri
                BitmapFactory.decodeFile(path)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 直接從 file:// 的 documentUri 渲染第 [pageIndex] 頁（擷取失敗時的後備路徑）。
     * 非 file:// 或頁碼越界都回傳 null。
     */
    fun renderPdfPageFromDocumentUri(
        documentUri: String,
        pageIndex: Int,
        outWidth: Int,
        outHeight: Int
    ): Bitmap? {
        return try {
            val parsed = Uri.parse(documentUri)
            if (parsed.scheme != "file") return null
            val path = parsed.path ?: return null
            val fd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                val renderer = PdfRenderer(fd)
                try {
                    if (pageIndex < 0 || pageIndex >= renderer.pageCount) return null
                    val bmp = Bitmap.createBitmap(
                        outWidth.coerceAtLeast(1),
                        outHeight.coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888
                    )
                    bmp.eraseColor(Color.WHITE)
                    val page = renderer.openPage(pageIndex)
                    try {
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    } finally {
                        page.close()
                    }
                    bmp
                } finally {
                    renderer.close()
                }
            } finally {
                fd.close()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 裁掉 ARGB 點陣圖的透明邊；不需要裁時回傳同一個 instance。
     *
     * 逐列掃描而非整張取進記憶體：整頁點陣圖一次 getPixels 會配置約 16MB 的
     * IntArray，長文件連續擷取時這是記憶體尖峰的來源。這個做法是 P1「長文件
     * 記憶體有界化」的一部分，別改回整張掃。
     */
    fun trimTransparentEdges(src: Bitmap, onTrimOffsets: (Float, Float) -> Unit = { _, _ -> }): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src

        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1

        val rowPixels = IntArray(w)
        for (y in 0 until h) {
            src.getPixels(rowPixels, 0, w, 0, y, w, 1)
            var rowHasOpaque = false
            for (x in 0 until w) {
                if (Color.alpha(rowPixels[x]) > 0) {
                    rowHasOpaque = true
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                }
            }
            if (rowHasOpaque) {
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }

        if (maxX < minX || maxY < minY) return src
        onTrimOffsets(minX.toFloat(), minY.toFloat())

        val outW = (maxX - minX + 1).coerceAtLeast(1)
        val outH = (maxY - minY + 1).coerceAtLeast(1)
        if (outW == w && outH == h) return src
        return Bitmap.createBitmap(src, minX, minY, outW, outH)
    }
}
