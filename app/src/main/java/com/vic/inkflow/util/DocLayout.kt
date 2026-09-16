package com.vic.inkflow.util

/**
 * 統一畫布座標（單一文件座標＋切頁）。
 *
 * 概念：整份文件一個座標系（PDF pt，與 DB 現有單位一致），
 * `x ∈ [0, W]`，`y ∈ [0, 文件總高)`；頁只是開在上面的窗口。
 *
 * - 資料步幅（無縫）：第 i 頁窗口 = `[tops[i], tops[i] + heights[i])`，
 *   `tops` 為各頁高前綴和；等大文件退化為 `docY = pageIndex × stride + 頁內頂邊`
 *  （與 S1 回填/雙寫公式一致，見 EditorViewModel.ensureDocSpaceMigrated）。
 * - 版式間隙只存在於螢幕（`layoutStride = canvasH + gapPx`），不進資料；
 *   新墨水落在縫裡時用 [gapOwnerIsUpper] 中線切（user 決議維持現狀）。
 * - `pageIndex` 是衍生視圖（錨點歸屬），真相來源是 `docY`。
 *
 * Pure Kotlin，無 Compose/Android 依賴——手勢層與資料層共用同一份數學。
 */
object DocLayout {

    /**
     * 一份文件的紙張規格。逐文件（首頁真實尺寸；混合尺寸走逐頁真實＋首頁 fallback）。
     * 非正尺寸在構造時鉗到 ≥1（與 DB 側 `coerceAtLeast(1f)` 同規則，兩邊一致）。
     */
    data class DocSpec(
        val pageWidths: List<Float>,
        val pageHeights: List<Float>
    ) {
        init {
            require(pageWidths.size == pageHeights.size) {
                "width/height count mismatch: ${pageWidths.size} vs ${pageHeights.size}"
            }
        }

        val pageCount: Int get() = pageHeights.size

        private val heights: List<Float> = pageHeights.map { it.coerceAtLeast(1f) }
        private val widths: List<Float> = pageWidths.map { it.coerceAtLeast(1f) }

        /** 各頁頂邊前綴和：`tops[i]` = 第 i 頁頂的 docY。 */
        val tops: List<Float> by lazy {
            val out = ArrayList<Float>(heights.size)
            var acc = 0f
            for (h in heights) {
                out.add(acc)
                acc += h
            }
            out
        }

        /** 文件總高（model 單位）。空文件為 0。 */
        val docHeight: Float by lazy { tops.lastOrNull()?.plus(heights.last()) ?: 0f }

        fun widthOf(i: Int): Float = widths.getOrElse(i.coerceIn(0, pageCount - 1)) { 595f }
        fun heightOf(i: Int): Float = heights.getOrElse(i.coerceIn(0, pageCount - 1)) { 842f }
        fun pageTop(i: Int): Float = tops.getOrElse(i.coerceIn(0, pageCount - 1)) { 0f }
        fun pageBottom(i: Int): Float = pageTop(i) + heightOf(i)

        /**
         * 錨點歸屬：docY 屬於「頂邊 ≤ docY 的最後一頁」，夾取到合法頁。
         * 空文件回 -1。已存物件的頁歸屬一律用它（`pageIndex` 衍生，不再當真相）。
         */
        fun pageIndexAt(docY: Float): Int {
            if (pageCount == 0) return -1
            var lo = 0
            var hi = pageCount - 1
            var ans = 0
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (tops[mid] <= docY) {
                    ans = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            return ans.coerceIn(0, pageCount - 1)
        }

        /** docY 轉頁內座標（`docY - 歸屬頁頂`）。空文件原值返回。 */
        fun localY(docY: Float): Float {
            val idx = pageIndexAt(docY)
            if (idx < 0) return docY
            return docY - tops[idx]
        }

        companion object {
            /** 等大文件快徑：N 頁同寬高（退化為 S1 公式 `pageIndex × stride + top`）。 */
            fun uniform(pageCount: Int, width: Float, height: Float): DocSpec {
                val n = pageCount.coerceAtLeast(0)
                return DocSpec(
                    pageWidths = List(n) { width },
                    pageHeights = List(n) { height }
                )
            }
        }
    }

    /**
     * 新墨水縫歸屬（中線切，user 決議維持現狀手感）：
     * [yInGap] 為縫內相對位置（0 = 縫頂），[gapH] 為縫高（同單位）。
     * 回傳 true = 歸上頁，false = 歸下頁。
     */
    fun gapOwnerIsUpper(yInGap: Float, gapH: Float): Boolean {
        if (gapH <= 0f) return true
        return yInGap < gapH / 2f
    }
}
