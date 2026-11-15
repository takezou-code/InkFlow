package com.vic.inkflow.util

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
 * - 「夠大」判斷：快取圖是逐圖記錄的「按 maxSidePx 解出來的」——只要這張圖就是為這次
 *   需求解出來的（native 上限 1020px 請 2048 也算夠，因為不可能更大），就算命中；
 *   否則要求長邊至少為 maxSidePx 的一半，才不算側欄縮圖冒充畫布原圖。
 * - [states] 是 Compose SnapshotStateMap：**DrawScope 讀它訂閱自己畫的那張圖**，
 *   某個 uri decode 完或被驅逐時只有訂閱它的頁面失效，不再是「全域 version++ 全部重畫」
 *   （2026-11-14 ANR 調查：decode 完成廣播給整個 items 區塊的 recomposition 風暴）。
 *   [version] 只保留給側欄縮圖（SidebarPanel 尚未遷移），畫布不要再讀。
 * - [resolutionVersion] 由任何寫入／驅逐 bump：SidebarPanel 的 LaunchedEffect 用它
 *   決定要不要重新檢查缺圖。畫布側請改讀 [states]。
 */
object ImageAnnotationCache {
    private const val TAG = "ImageAnnotationCache"

    private val maxKb: Int =
        ((Runtime.getRuntime().maxMemory() / 1024) / 8).toInt().coerceAtLeast(16 * 1024)

    /**
     * 快取裡每張圖記住它是「針對多大的 maxSidePx 解出來的」——解碼不會放大：
     * 一張 1020px 寬的公式圖請 2048 只會得到 1020 的原生上限，硬套「長邊至少一半」
     * 的規則會永遠判定它「太小」、永遠重解、永遠不畫（2026-11-14 公式圖空白事故）。
     */
    private data class Entry(val bitmap: ImageBitmap, val decodedForPx: Int)
    /**
     * 按顯示尺寸的分桶解碼請求長邊：256 / 512 / 1024 / 2048 向上取整桶。
     * 顯示尺寸連續變化（手勢縮放）時 bucket 穩定，不會每變一個像素就重解一次。
     */
    internal fun bucketPx(targetPx: Int): Int = when {
        targetPx <= 256 -> 256
        targetPx <= 512 -> 512
        targetPx <= 1024 -> 1024
        else -> 2048
    }

    /**
     * Per-URI 解碼完成標記（DrawScope 讀它做訂閱點），值是 decodedForPx。
     * **只存標記、不存 bitmap**——快取預算與原生記憶體一律以 LruCache 為準；
     * states 是 value 級小物件，不會讓位圖逃過驅逐。
     */
    val states = mutableStateMapOf<String, Int>()

    private val cache = object : LruCache<String, Entry>(maxKb) {
        override fun sizeOf(key: String, value: Entry): Int =
            ((value.bitmap.width.toLong() * value.bitmap.height) / 256L).toInt().coerceAtLeast(1)

        override fun trimToSize(maxSize: Int) {
            super.trimToSize(maxSize)
            // 驅逐了東西 → 自癒訊號（側欄會重新檢查缺圖；畫布靠 states 自動失效重查）
            version++
        }

        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Entry, newValue: Entry?) {
            // 驅逐（非替換）→ 移除標記：下一幀 draw 讀不到、走缺圖路徑重新 decode。
            if (newValue == null) states.remove(key)
        }
    }

    /** 解碼中的 uri 集合：跨頁／跨面板同時要求同一張圖時只解一次。 */
    private val inFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** 解碼失敗的 uri（有上限）：重打只會再失敗一次又一次，且每次失敗都會 bump version。 */
    private val failed = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** 任何寫入／驅逐都會遞增；**側欄**用它自癒。畫布請改讀 [states]。 */
    var version by mutableIntStateOf(0)
        private set

    /**
     * 快取裡有沒有「足夠大」的圖（長邊至少為 maxSidePx 的一半，或這張圖本來就是按
     * maxSidePx 解出來的原生上限）。純粹查詢，不觸發解碼。DrawScope 讀的就是這個。
     *
     * 先在 states 上「訂閱到這張圖」，再去 LRU 取位元組：畫布端等於只訂閱
     * 自己畫的那張圖；decode 完成／驅逐只讓該訂閱點失效。
     */
    fun getFor(uri: String, maxSidePx: Int): ImageBitmap? {
        val decodedForPx = states[uri] ?: return null
        val entry = cache.get(uri) ?: return null
        return if (cacheAccepts(maxOf(entry.bitmap.width, entry.bitmap.height), decodedForPx, maxSidePx)) {
            entry.bitmap
        } else {
            null
        }
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
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "decode exception uri=$uri: ${e.message}")
                    null
                }
            }
            if (decoded == null) {
                val p = if (uri.startsWith("file:")) Uri.parse(uri).path else null
                val sz = p?.let { java.io.File(it).length() }
                android.util.Log.w(TAG, "decode FAILED uri=$uri (scheme=${Uri.parse(uri).scheme}, exists=${p?.let { java.io.File(it).exists() }}, size=$sz)")
                if (failed.size > 64) failed.clear()
                failed.add(uri)
                return null
            }
            cache.put(uri, Entry(decoded, maxSidePx))
            states[uri] = maxSidePx
            version++
            return decoded
        } finally {
            inFlight.remove(uri)
        }
    }

    /** 解碼中／已知失敗的 uri：draw 端避免每幀重派（UNLIMITED 佇列會無限長）。 */
    fun isPendingOrFailed(uri: String): Boolean = uri in inFlight || uri in failed

    /** 平板記憶體告急時（例如 PDF 預算收砍）可以主動清掉圖片註解圖。 */
    fun clear() {
        cache.evictAll()
        states.clear()
        failed.clear()
    }

    /** 記憶體壓力降載（2026-11-15 M7）：RUNNING_LOW+ 時把可重新解碼的公式圖全丟掉。 */
    init {
        com.vic.inkflow.util.AppMemoryPressure.register { level ->
            if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) clear()
        }
    }
}

/**
 * 快取圖片夠不夠用的純判斷（抽出來給單測釘住）：
 * 只要這張圖是為 maxSidePx 這次需求解出來的（解碼不會放大，native 就是上限）→ 直接吃；
 * 否則要求長邊至少為 maxSidePx 的一半（避免側欄 512 的縮圖冒充畫布 2048 的原圖）。
 */
internal fun cacheAccepts(entryLongSide: Int, entryDecodedForPx: Int, maxSidePx: Int): Boolean =
    entryDecodedForPx >= maxSidePx || entryLongSide >= maxSidePx / 2
