package com.vic.inkflow.ui.selection

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.vic.inkflow.data.ImageAnnotation
import com.vic.inkflow.data.StrokeWithSamples
import com.vic.inkflow.data.TextAnnotation
import com.vic.inkflow.ui.rotatedImageBounds
import com.vic.inkflow.ui.textEstimatedBounds
import kotlin.math.abs

/**
 * 圈選（套索）管線的資料模型。
 *
 * 這裡只有**不可變的快照**，沒有任何 Flow、沒有任何 DB 呼叫。
 * 一次圈選的所有資訊（圈了哪裡、選到什麼、框在哪、拖到哪）都在
 * [SelectionState] 裡；其他地方不再各自保存一份，避免「框在、東西不在」。
 *
 * 座標紀律：
 * - [SelectionRegion] 的多邊形是 **model 座標、頁內座標**（不是 doc 座標）。
 * - [SelectionFrame] 的 bounds 也是 model／頁內座標。
 * - [SelectionTransform] 的 translation 是 **model 位移**；scale 是等比。
 */

/** 一次套索圈出的區域（可能跨多頁；同頁可能有多段閉合圈）。 */
data class SelectionRegion(
    val polygonsByPage: Map<Int, List<List<Offset>>>,
    /** 起手頁：泡泡錨點、空白圈選的歸屬頁。 */
    val srcPage: Int
) {
    /** 起手頁的第一段圈：提取遮罩與泡泡定位用。 */
    val srcPolygon: List<Offset>
        get() = polygonsByPage[srcPage]?.firstOrNull().orEmpty()

    val isEmpty: Boolean
        get() = polygonsByPage.values.all { polys -> polys.none { it.size >= 3 } }
}

/** 選中的三種物件快照（墨／圖／字）。 */
data class SelectionItems(
    val strokes: List<StrokeWithSamples> = emptyList(),
    val images: List<ImageAnnotation> = emptyList(),
    val texts: List<TextAnnotation> = emptyList()
) {
    val isEmpty: Boolean
        get() = strokes.isEmpty() && images.isEmpty() && texts.isEmpty()

    val strokeIds: Set<String>
        get() = strokes.mapTo(HashSet(strokes.size)) { it.stroke.id }

    val imageIds: Set<String>
        get() = images.mapTo(HashSet(images.size)) { it.id }

    val textIds: Set<String>
        get() = texts.mapTo(HashSet(texts.size)) { it.id }

    fun forPage(page: Int): SelectionItems = SelectionItems(
        strokes = strokes.filter { it.stroke.pageIndex == page },
        images = images.filter { it.pageIndex == page },
        texts = texts.filter { it.pageIndex == page }
    )

    fun bounds(): Rect? = boundsOf(strokes, images, texts)
}

/** 顯示框：框固定屬於某一頁（主導頁），bounds 為該頁頁內 model 座標。 */
data class SelectionFrame(val page: Int, val bounds: Rect)

/** 拖曳中的變換草稿。IDLE＝沒有變換。 */
data class SelectionTransform(
    val translation: Offset = Offset.Zero,
    val scale: Float = 1f,
    /** 縮放錨點（model）。拖曳平移時不重要；縮放時是對角把手。 */
    val anchor: Offset? = null
) {
    val isIdentity: Boolean
        get() = translation == Offset.Zero && abs(scale - 1f) < 0.0001f

    companion object {
        val IDENTITY = SelectionTransform()
    }
}

enum class SelectionPhase {
    /** 沒有選取。 */
    IDLE,

    /** 圈完手勢，正在背景判定命中。 */
    RESOLVING,

    /** 選取已成立。 */
    READY,

    /** 正在拖曳移動或縮放。 */
    TRANSFORMING
}

/**
 * 圈選的唯一狀態。
 *
 * 舊版把這些拆成 13 條 StateFlow（選中墨、圖 id、字 id、預覽、框、位移、比例、
 * 錨點、commit preview…），任何一條忘了同步就是畫面與狀態各說各話。現在只有這一個
 * 物件，消費端一律讀它。
 */
data class SelectionState(
    val phase: SelectionPhase = SelectionPhase.IDLE,
    val region: SelectionRegion? = null,
    val items: SelectionItems = SelectionItems(),
    val frame: SelectionFrame? = null,
    val transform: SelectionTransform = SelectionTransform.IDENTITY,
    /** DB 還沒回報前，先畫這批「已提交」的墨，避免閃回原位。 */
    val commitPreview: List<StrokeWithSamples>? = null,
    /** 非同步判定的世代號：舊結果晚到時直接丟棄。 */
    val revision: Long = 0L
) {
    val hasRegion: Boolean get() = region != null && !region.isEmpty
    val hasItems: Boolean get() = !items.isEmpty
    val isTransforming: Boolean get() = phase == SelectionPhase.TRANSFORMING

    /** 顯示框所在頁（泡泡、選取歸屬）。 */
    val page: Int get() = frame?.page ?: region?.srcPage ?: 0

    /** 本頁的選取內容（各紙只畫自己的）。 */
    fun itemsFor(page: Int): SelectionItems = items.forPage(page)

    /**
     * 本頁的顯示框 bounds：有選中物就用本頁物件的包絡；
     * 空白圈選（沒有物件）只在起手頁顯示圈出的區域。
     */
    fun frameFor(page: Int): Rect? {
        items.forPage(page).bounds()?.let { return it }
        // 有選中物時，別在沒有物件的頁留一個空框（跨頁圈選的起手頁常見）。
        if (hasItems) return null
        val r = region ?: return null
        if (r.srcPage != page) return null
        return LassoGeometry.polygonBounds(r.srcPolygon)
    }

    /** 本頁的變換錨點：縮放用把手錨點；平移用框中心。 */
    fun anchorFor(page: Int): Offset =
        transform.anchor ?: frameFor(page)?.center ?: Offset.Zero

    companion object {
        val IDLE = SelectionState()
    }
}

// ── 純函式：包絡 ─────────────────────────────────────────────────────────────

internal fun boundsOf(
    strokes: List<StrokeWithSamples>,
    images: List<ImageAnnotation>,
    texts: List<TextAnnotation>
): Rect? {
    var left = Float.POSITIVE_INFINITY
    var top = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    var bottom = Float.NEGATIVE_INFINITY
    var has = false

    strokes.forEach { swp ->
        val s = swp.stroke
        if (s.boundsLeft < left) left = s.boundsLeft
        if (s.boundsTop < top) top = s.boundsTop
        if (s.boundsRight > right) right = s.boundsRight
        if (s.boundsBottom > bottom) bottom = s.boundsBottom
        has = true
    }
    images.forEach { ann ->
        val b = rotatedImageBounds(ann)
        if (b.left < left) left = b.left
        if (b.top < top) top = b.top
        if (b.right > right) right = b.right
        if (b.bottom > bottom) bottom = b.bottom
        has = true
    }
    texts.forEach { ann ->
        val b = textEstimatedBounds(ann)
        if (b.left < left) left = b.left
        if (b.top < top) top = b.top
        if (b.right > right) right = b.right
        if (b.bottom > bottom) bottom = b.bottom
        has = true
    }
    if (!has || right <= left || bottom <= top) return null
    return Rect(left, top, right, bottom)
}
