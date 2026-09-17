package com.vic.inkflow.util

import androidx.compose.ui.geometry.Offset

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

    /**
     * F4：拖曳中物件在本頁窗口的局部縱向範圍（model 座標，頁內）。
     * [anchorPage]＋[offsetInAnchorPage] 定位物件頂（錨點頁內座標，含拖曳位移），
     * [extent] 為物件高。與本頁窗口無交集回 null。
     * 等大文件步幅即 pageH；呼叫方保證同座標系。
     */
    fun draggedLocalVertical(
        anchorPage: Int,
        offsetInAnchorPage: Float,
        extent: Float,
        pageIndex: Int,
        pageH: Float
    ): Pair<Float, Float>? {
        if (pageH <= 0f || extent <= 0f) return null
        val docTop = anchorPage * pageH + offsetInAnchorPage
        val winTop = pageIndex * pageH
        val top = maxOf(docTop, winTop) - winTop
        val bottom = minOf(docTop + extent, winTop + pageH) - winTop
        if (bottom <= top) return null
        return top to bottom
    }

    /**
     * 矩形按頁切分子矩形（矩形套索用）：框跨幾頁，每頁一個閉合四角（該頁頁內座標）。
     * 與點切分不同：矩形有面積，按窗口重疊切，不存在開弧問題；縫不屬於任何頁。
     * 輸入可任意方向拖曳（內部正規化）；零面積/非法輸入回空表。
     */
    fun rectsByPage(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        canvasW: Float,
        canvasH: Float,
        gapPx: Float,
        srcPage: Int,
        pageCount: Int
    ): Map<Int, List<Offset>> {
        val l = minOf(left, right).coerceAtLeast(0f)
        val r = maxOf(left, right)
        val t = minOf(top, bottom)
        val b = maxOf(top, bottom)
        if (canvasW <= 0f || canvasH <= 0f || pageCount <= 0 || r <= l || b <= t) return emptyMap()
        val rc = minOf(r, canvasW)
        if (rc <= l) return emptyMap()
        val lastPage = (pageCount - 1).coerceAtLeast(0)
        val stride = canvasH + gapPx.coerceAtLeast(0f)
        if (stride <= 0f) return emptyMap()
        val out = LinkedHashMap<Int, List<Offset>>()
        val k0 = kotlin.math.floor(t / stride).toInt()
        val k1 = kotlin.math.floor((b - 0.001f) / stride).toInt()
        for (k in k0..k1) {
            val pg = (srcPage + k).coerceIn(0, lastPage)
            if (out.containsKey(pg)) continue
            val base = (pg - srcPage) * stride
            val lt = maxOf(t - base, 0f)
            val lb = minOf(b - base, canvasH)
            if (lb > lt) {
                out[pg] = listOf(
                    Offset(l, lt),
                    Offset(rc, lt),
                    Offset(rc, lb),
                    Offset(l, lb)
                )
            }
        }
        return out
    }

    /**
     * canvas-space 點列按紙界切分到各頁（版式步幅 = canvasH + gapPx，紙界定在兩紙中線）。
     * 與舊 splitStrokeByPage 同算法（該函式已改走這裡）：連續同頁點成段；
     * 首/末頁外溢出併入邊界頁並把 localY 鉗到紙界。
     * [yOf] 取點的 canvas Y；[local] 把頁內 Y 寫回點（呼叫方決定點型別）。
     * 回傳 (頁索引, 該頁頁內點列)，保序。
     */
    fun <T> splitByPage(
        items: List<T>,
        yOf: (T) -> Float,
        canvasH: Float,
        gapPx: Float,
        srcPage: Int,
        pageCount: Int,
        local: (T, Float) -> T
    ): List<Pair<Int, List<T>>> {
        if (items.isEmpty() || canvasH <= 0f) return listOf(srcPage to items)
        val lastPage = (pageCount - 1).coerceAtLeast(0)
        val stride = canvasH + gapPx.coerceAtLeast(0f)
        if (stride <= 0f) return listOf(srcPage to items)
        val halfGap = gapPx.coerceAtLeast(0f) / 2f
        val out = mutableListOf<Pair<Int, MutableList<T>>>()
        var curK: Int? = null
        for (p in items) {
            val k = kotlin.math.floor((yOf(p) + halfGap) / stride).toInt()
            val page = (srcPage + k).coerceIn(0, lastPage)
            val walled = (srcPage + k) != page
            val effK = page - srcPage
            var localY = yOf(p) - effK * stride
            if (walled) localY = localY.coerceIn(0f, canvasH)
            if (curK == null || effK != curK || out.isEmpty()) {
                out.add(page to mutableListOf(local(p, localY)))
                curK = effK
            } else {
                out.last().second.add(local(p, localY))
            }
        }
        return out
    }
}
