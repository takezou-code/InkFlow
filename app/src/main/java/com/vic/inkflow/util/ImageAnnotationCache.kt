package com.vic.inkflow.util

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.LruCache
import com.vic.inkflow.ui.decodeBoundedBitmap

/**
 * 「圖片註解」的共享解碼快取（Android 端）。
 *
 * 為什麼要有這支：
 * Android 8+ 的 Bitmap 像素住在 native heap，ART GC 只看 Java heap 水位，
 * 圖只進不出就會重演 PDF 那次「Bitmap (malloced) 249MB → 捲到一半閃退」（見 pitfalls
 * 2026-11-12）。原本三個各自為政的快取：
 *   - InkCanvas  loadedImages/loadedImageBitmaps：無上限、雙份持有、刪圖不清
 *   - PageWorkspace 拖曳 overlay：無上限
 *   - SidebarPanel 側欄縮圖：**整張原圖直解**（88dp 的格子解 4000×3000 的照片）
 * 同一張圖會被解碼兩三次。這裡統一成一個預算制 LRU，三處共讀。
 *
 * 設計：
 * - 預算 heap/8 KB（與 PDF hi 層同一思想；至少 16MB），sizeOf 用 ARGB8888 4 bytes/px。
 * - key = uri。存的是 ImageBitmap（drawImage 直接可用）。
 * - 「夠大」判斷：快取長邊 * 2 >= 要求的 maxSidePx 就算命中；否則視為過小重解並取代
 *   （側欄先要 512、畫布後要 2048 → 畫布重解一次，之後都吃 2048 那份）。
 * - [version] 是 Compose state：任何寫入／驅逐都會 bump，Composable 讀它後
 *   用 LaunchedEffect 重查缺圖並補解。自癒畫面，不依賴「annotation list 有變」才修圖。
 * - 解碼失敗記入 [failed]（有上限），避免每次 version bump 都重打無法解碼的 uri。
 */
object ImageAnnotationCache {

    private val maxKb: Int =
        ((Runtime.getRuntime().maxMemory() / 1024) / 8).toInt().coerceAtLeast(16 * 1024)

    private val cache = object : LruCache<String, ImageBitmap>(maxKb) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            ((value.width.toLong() * value.height) / 256L).toInt().coerceAtLeast(1)

        override fun trimToSize(maxSize: Int) {
            super.trimToSize(maxSize)
            // 驅逐了東西 → 自癒訊號（畫面發現缺圖會觸發重解；超預算時這會收斂，見 pitfalls PDF 條）
            version++
        }
    }

    /** 解碼中的 uri 集合：跨頁／跨面板同時要求同一張圖時只解一次。 */
    private val inFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** 解碼失敗的 uri（有上限）：重打只會再失敗一次又一次，且每次失敗都會 bump version。 */
    private val failed = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** 任何寫入／驅逐都會遞增；Composable 在 Composition 範圍讀它來自癒。 */
    var version by mutableIntStateOf(0)
        private set

    /** 快取中是否有「夠大」的圖（長邊至少為 maxSidePx 的一半）。純粹查詢，不觸發解碼。 */
    fun getFor(uri: String, maxSidePx: Int): ImageBitmap? {
        val cached = cache.get(uri) ?: return null
        return if (isLargeEnough(maxOf(cached.width, cached.height), maxSidePx)) cached else null
    }

    /** 命中就回；否則（IO dispatcher 上）解碼、放入、bump version。失敗回 null 並記入 failed。 */
    suspend fun getOrDecode(context: Context, uri: String, maxSidePx: Int): ImageBitmap? {
        getFor(uri, maxSidePx)?.let { return it }
        if (uri in failed) return null
        // 同一張圖只准一個人解；後到的等前面的人放進快取後再查一次。
        if (!inFlight.add(uri)) {
            // 前一個解碼剛放進快取時這裡就會命中；否則回 null，呼叫端（畫面）下一個 frame 再來。
            return getFor(uri, maxSidePx)
        }
        try {
            val decoded = withContext(Dispatchers.IO) {
                try {
                    decodeBoundedBitmap(context, Uri.parse(uri), maxSidePx)?.asImageBitmap()
                } catch (_: Exception) {
                    null
                }
            }
            if (decoded == null) {
                if (failed.size > 64) failed.clear()
                failed.add(uri)
                return null
            }
            cache.put(uri, decoded)
            version++
            return decoded
        } finally {
            inFlight.remove(uri)
        }
    }

    /** 平板記憶體告急時（例如 PDF 預算收砍）可以主動清掉圖片註解圖。 */
    fun clear() {
        cache.evictAll()
        failed.clear()
    }
}

/** 快取長邊夠不夠大的純判斷（抽出來給單測釘住）。 */
internal fun isLargeEnough(cachedLongSide: Int, maxSidePx: Int): Boolean =
    cachedLongSide * 2 >= maxSidePx
