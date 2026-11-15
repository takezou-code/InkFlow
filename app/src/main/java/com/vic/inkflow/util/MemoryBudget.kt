package com.vic.inkflow.util

import android.util.Log

/**
 * **位圖記憶體的唯一總帳**（2026-11-15 M8）。
 *
 * 為什麼要有這支：
 * Android 8 之後 Bitmap 的像素住在 **native heap**，ART 的 GC 只看 Java heap 水位，
 * 所以 `Runtime.maxMemory()` 對位圖幾乎沒有約束力。先前有四個各自獨立的快取，
 * 每個單看都「很保守」，加起來卻是：
 *
 * | 快取 | 舊預算（512MB heap） |
 * | --- | --- |
 * | PDF 高清頁 `bitmapCache` | heap/5 = 102MB |
 * | PDF 縮圖層 `thumbnailCache` | heap/20 = 25MB |
 * | 文件封面 `ThumbnailCacheManager` | heap/16 = 32MB |
 * | 公式圖／圖片註解 `ImageAnnotationCache` | heap/8 = 64MB |
 * | 合計 | **約 223MB（43.5% heap）** |
 *
 * 四個數字沒有任何一個地方看得到總和，也沒有人知道筆跡烘焙（單頁約 25MB，不可回收）
 * 還需要多少餘裕。這不是「哪個快取太大」的問題，是**沒有總帳**的問題。
 *
 * 這支做的事只有三件：
 * 1. **一份配額表**：四層的預算加起來 = heap 的 40%（512MB → 214MB），
 *    其餘 60% 留給筆跡烘焙、Compose graphics 與 Java heap。各層比例刻意讓
 *    PDF 高清層維持 heap/5（預取頁不被 LruCache 靜默擠掉，見 PdfViewModel 的註解）。
 * 2. **壓力時整組等比收縮**：`TRIM_MEMORY_RUNNING_*` 是真壓力 → 收縮且**記住**新水位；
 *    `UI_HIDDEN`／`BACKGROUND` 是「現在沒人看」→ 只做一次降載，回到前景允許重新長回來
 *    （[onForeground]）。舊寫法是門檻式 `evictAll()`，壓力一來全部丟光、下一幀全部重解，
 *    反而製造尖峰。
 * 3. **擁有者註冊回收動作**：快取自己不知道總帳，這裡統一呼叫 `trimToSize`。
 *
 * 明確不做的事：不去量 PSS、不猜系統還有多少記憶體。Android 已經用 `onTrimMemory`
 * 告訴我們壓力等級，這裡只負責把它翻譯成「每一層該剩多少」。
 */
object MemoryBudget {

    private const val TAG = "MemoryBudget"

    /**
     * 四層位圖預算，比例是 **heap 的分數**（不是總預算的分數）。
     *
     * - `PDF_PAGE` 維持 heap/5：這是既有調校結果，預取頁必須容得下，否則預取形同白渲。
     * - `IMAGE_ANNOTATION` 從 heap/8 收到 0.090：公式圖單張只有幾百 KB，
     *   46MB 仍可容納上百張，省下的還給筆跡烘焙。
     * - `DOC_COVER` 從 heap/16 收到 0.060：封面單張約 0.3MB，31MB 仍是 100+ 份文件。
     *   （使用者於 2026-11-15 指示總預算從 38% 調為 40%，多出來的 2% 分給這兩層；
     *    PDF 高清層與縮圖層維持 heap/5、heap/20 不動——那是調校過的，預取頁靠它。）
     */
    enum class Layer(val heapFraction: Double, val floorBytes: Long, val label: String) {
        PDF_PAGE(0.200, 64L * 1024 * 1024, "pdf-page"),
        PDF_THUMBNAIL(0.050, 16L * 1024 * 1024, "pdf-thumb"),
        DOC_COVER(0.060, 8L * 1024 * 1024, "doc-cover"),
        IMAGE_ANNOTATION(0.090, 16L * 1024 * 1024, "image-annot")
    }

    /** 壓力收縮後仍要留的最低比例，避免某一層被縮到 0 造成每幀重解。 */
    private const val MIN_EFFECTIVE_FACTOR = 0.05f

    /**
     * 目前的收縮倍率。**只有真壓力（`RUNNING_*`）會改它**；`UI_HIDDEN`／`BACKGROUND`
     * 只做一次性降載，因為那代表「使用者離開了」，不是「記憶體不夠」。
     */
    @Volatile
    private var factor: Float = 1f

    private val evictors = java.util.concurrent.CopyOnWriteArrayList<Evictor>()

    private class Evictor(val layer: Layer, val trimToKb: (Int) -> Unit)

    private val heapBytes: Long get() = Runtime.getRuntime().maxMemory()

    // ── 純函式（可單測；不讀裝置狀態）────────────────────────────────────

    /** 四層加總的位圖總預算：heap 的 40%。 */
    fun totalBytesFor(heapBytes: Long): Long =
        Layer.values().sumOf { (heapBytes * it.heapFraction).toLong() }

    /** 某一層在給定 heap 與收縮倍率下的位元組配額。 */
    fun quotaBytesFor(layer: Layer, heapBytes: Long, factor: Float): Long {
        val f = factor.coerceIn(MIN_EFFECTIVE_FACTOR, 1f)
        val fromHeap = (heapBytes * layer.heapFraction * f).toLong()
        val fromFloor = (layer.floorBytes * f).toLong()
        return maxOf(fromHeap, fromFloor)
    }

    /**
     * 系統 trim 等級 → 一次性降載倍率。
     *
     * 數值直接寫常數而不引用 `ComponentCallbacks2`：這支會被單測呼叫，
     * 不該為了拿一個 int 常數把 android.jar 拉進來。
     */
    fun factorForTrimLevel(level: Int): Float = when {
        level >= 80 -> 0.05f   // TRIM_MEMORY_COMPLETE
        level >= 60 -> 0.15f   // TRIM_MEMORY_MODERATE
        level >= 40 -> 0.20f   // TRIM_MEMORY_BACKGROUND
        level >= 20 -> 0.25f   // TRIM_MEMORY_UI_HIDDEN
        level >= 15 -> 0.35f   // TRIM_MEMORY_RUNNING_CRITICAL
        level >= 10 -> 0.60f   // TRIM_MEMORY_RUNNING_LOW
        level >= 5 -> 0.80f    // TRIM_MEMORY_RUNNING_MODERATE
        else -> 1f
    }

    /**
     * 這個等級是不是「真的記憶體不夠」。
     *
     * `UI_HIDDEN`(20) 以上只代表畫面不可見——使用者切去別的 App 時系統一定會送，
     * 若把它當成永久水位，回來之後所有快取永遠長不回去，變成每頁重解。
     */
    fun isPersistentLevel(level: Int): Boolean = level in 1 until 20

    // ── 執行期 ──────────────────────────────────────────────────────────

    /** 某一層**現在**的配額（已含壓力收縮）。 */
    fun quotaBytes(layer: Layer): Long = quotaBytesFor(layer, heapBytes, factor)

    /** 現在的有效倍率（診斷用）。 */
    fun currentFactor(): Float = factor

    /**
     * 擁有者登記自己的回收動作。註冊當下**立刻**套用一次配額：
     * 若快取是照舊公式建的（例如 heap/8），這一步就會把它收到新水位。
     *
     * [trimToKb] 收到的是「這一層應該縮到幾 KB」，直接餵給 `LruCache.trimToSize`。
     */
    fun register(layer: Layer, trimToKb: (Int) -> Unit) {
        evictors += Evictor(layer, trimToKb)
        runCatching { trimToKb((quotaBytes(layer) / 1024).toInt()) }
            .onFailure { Log.w(TAG, "initial trim failed for ${layer.label}: ${it.message}") }
    }

    /** 回到前景：真壓力解除，允許快取重新長回 100%（LruCache 會在 put 時自然長大）。 */
    fun onForeground() {
        if (factor >= 1f) return
        factor = 1f
        Log.i(TAG, "foreground: quotas restored to 100%")
    }

    private fun apply(level: Int) {
        val target = factorForTrimLevel(level)
        val persistent = isPersistentLevel(level)
        // 真壓力：記住水位（多層壓力取最嚴的那個）。一次性降載：只降這一次。
        if (persistent) factor = minOf(factor, target)
        val effective = if (persistent) factor else target
        for (e in evictors) {
            val kb = (quotaBytesFor(e.layer, heapBytes, effective) / 1024).toInt()
            runCatching { e.trimToKb(kb) }
                .onFailure { Log.w(TAG, "trim failed for ${e.layer.label} at level=$level: ${it.message}") }
        }
        Log.i(
            TAG,
            "trim level=$level factor=${String.format("%.2f", effective)} " +
                "persistent=$persistent total=${totalBytesFor(heapBytes) / 1024 / 1024}MB"
        )
    }

    init {
        // 由 MainActivity 的 ComponentCallbacks2 餵進來（見 AppMemoryPressure）。
        AppMemoryPressure.register { level -> apply(level) }
        // 開機自檢：真機上要能一眼看到「總帳真的生效」，而不是只有單測綠燈。
        Log.i(
            TAG,
            "budget total=${totalBytesFor(heapBytes) / 1024 / 1024}MB " +
                "heap=${heapBytes / 1024 / 1024}MB " +
                Layer.values().joinToString(" ") { l ->
                    "${l.label}=${quotaBytes(l) / 1024 / 1024}MB"
                }
        )
    }
}
