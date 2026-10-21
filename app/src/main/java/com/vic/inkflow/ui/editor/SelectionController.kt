package com.vic.inkflow.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.vic.inkflow.data.ImageAnnotation
import com.vic.inkflow.data.StrokeWithSamples
import com.vic.inkflow.data.TextAnnotation
import com.vic.inkflow.data.repository.InkFlowRepositories
import com.vic.inkflow.ui.EditorViewModel
import com.vic.inkflow.ui.DrawCommand
import com.vic.inkflow.ui.canvasToModel
import com.vic.inkflow.ui.isImageSelectedByLasso
import com.vic.inkflow.ui.isTextSelectedByLasso
import com.vic.inkflow.ui.redocWith
import com.vic.inkflow.ui.textEstimatedBounds
import com.vic.inkflow.ui.transformPolygon
import com.vic.inkflow.util.DocTransform
import com.vic.inkflow.util.StrokeTransformUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * å¥—ç´¢é¸å–ï¼šç‹€æ…‹ã€å‘½ä¸­åˆ¤å®šã€ç§»å‹•ï¼ç¸®æ”¾æäº¤ã€åˆªé™¤ã€è¤‡è£½ã€æ¸…é™¤ã€‚
 *
 * ç‚ºä»€éº¼å¾ž [EditorViewModel] æ‹†å‡ºä¾†ï¼š
 * é¸å–æ˜¯é€™å€‹ç·¨è¼¯å™¨è£¡**è€¦åˆæœ€å¯†**çš„ä¸€å¡Šâ€”â€”å®ƒåŒæ™‚ç¢°å¾—åˆ°å¥—ç´¢å¤šé‚Šå½¢ã€é¸ä¸­é›†åˆã€
 * é è¦½å¿«ç…§ã€åœ–ï¼å­—ï¼å¢¨ä¸‰ç¨®ç‰©ä»¶ã€è·¨é åº§æ¨™æ›ç®—ã€undo è¨˜éŒ„èˆ‡è³‡æ–™åº«å¯«å…¥ã€‚åŽŸä¾†å®ƒè·Ÿ
 * ç•«ç­†ã€é é¢ã€å·¥å…·åˆ—ã€AI é¢æ¿å…±ç”¨åŒä¸€å€‹é¡žåˆ¥ï¼Œæ–¼æ˜¯ã€Œç•«ç­†æ€Žéº¼ç•«ã€èˆ‡ã€Œé¸å–æ€Žéº¼æäº¤ã€
 * ä½åœ¨åŒä¸€å€‹ 2,500 è¡Œçš„æª”æ¡ˆè£¡ï¼Œèª°æ”¹èª°æ€•ã€‚
 *
 * é€™å€‹é¡žåˆ¥çš„é‚Šç•Œï¼š**é¸å–ç‹€æ…‹æ­¸å®ƒï¼Œé¸å–æ±ºç­–ä¹Ÿæ­¸å®ƒï¼›ä½†ã€Œè¦ undo å°± undoã€è¦å¯«åº«
 * å°±å¯«åº«ã€æ˜¯å®ƒèˆ‡å¤–ç•Œå”¯ä¸€çš„å…©å€‹å‡ºå£**ï¼ˆæ³¨å…¥çš„ callbackï¼‰ã€‚å®ƒä¸çŸ¥é“ ViewModel å­˜åœ¨ã€‚
 *
 * ä¾è³´ä»¥ lambda æ³¨å…¥ï¼Œå› ç‚ºå®ƒéœ€è¦çš„æ±è¥¿å…¨éƒ½æœƒè®Šï¼šç´™çš„å°ºå¯¸ï¼ˆæ¨¡åž‹åº§æ¨™æ›ç®—ï¼‰ã€
 * ç•«å¸ƒå°ºå¯¸ï¼ˆç•«å¸ƒåº§æ¨™æ›ç®—ï¼‰ã€ç›®å‰é ã€é¸å–æ­¸å±¬é ã€è¦è¨˜çš„é‚£ä¸€æ ¼ undoã€‚
 */
internal class SelectionController(
    private val repos: InkFlowRepositories,
    private val scope: CoroutineScope,
    private val modelWidth: () -> Float,
    private val modelHeight: () -> Float,
    private val canvasW: () -> Float,
    private val canvasH: () -> Float,
    /** S1 é›™å¯«æ­¥å¹…ï¼šdocY = pageIndex Ã— stride + é å…§é ‚é‚Šã€‚ */
    private val docStride: () -> Float,
    private val pageDataFlow: (Int) -> StateFlow<EditorViewModel.NeighborPageData>,
    private val pageFlows: () -> Map<Int, StateFlow<EditorViewModel.NeighborPageData>>,
    private val currentImageAnnotations: () -> StateFlow<List<ImageAnnotation>>,
    private val currentTextAnnotations: () -> StateFlow<List<TextAnnotation>>,
    private val pageIndex: () -> Int,
    private val pushUndo: (DrawCommand) -> Unit,
    /** è½åº«çš„å”¯ä¸€ chokeï¼šè¦†è“‹ï¼‹bump åœ¨åŒä¸€äº¤æ˜“è£¡ï¼ˆè¦‹ EditorViewModel çš„èªªæ˜Žï¼‰ã€‚ */
    private val replaceStrokeSnapshots: suspend (List<StrokeWithSamples>) -> Unit,
    private val hideIds: (Collection<String>) -> Unit,
    private val showIds: (Collection<String>) -> Unit
) {

    // Lasso selection state
    private val _selectedStrokes = MutableStateFlow<List<StrokeWithSamples>>(emptyList())
    val selectedStrokes: StateFlow<List<StrokeWithSamples>> = _selectedStrokes.asStateFlow()

    private val _selectedImageAnnotationIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedImageAnnotationIds: StateFlow<Set<String>> = _selectedImageAnnotationIds.asStateFlow()

    // 套索選中的字（與墨圖同等待遇：框線、移動、刪除、複製、復原全支援）。
    private val _selectedTextAnnotationIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedTextAnnotationIds: StateFlow<Set<String>> = _selectedTextAnnotationIds.asStateFlow()

    private val _selectedStrokePreview = MutableStateFlow<List<StrokeWithSamples>>(emptyList())
    val selectedStrokePreview: StateFlow<List<StrokeWithSamples>> = _selectedStrokePreview.asStateFlow()

    private val _lassoPolygon = MutableStateFlow<List<Offset>>(emptyList())
    val lassoPolygon: StateFlow<List<Offset>> = _lassoPolygon.asStateFlow()

    private val _selectionFramePolygon = MutableStateFlow<List<Offset>>(emptyList())
    val selectionFramePolygon: StateFlow<List<Offset>> = _selectionFramePolygon.asStateFlow()

    private val _lastLassoPolygon = MutableStateFlow<List<Offset>>(emptyList())
    val lastLassoPolygon: StateFlow<List<Offset>> = _lastLassoPolygon.asStateFlow()

    // 空白框選歸屬頁：無墨圖字時 selectionPage() 的錨（有物時仍以物為準）。
    private val _lassoPage = MutableStateFlow(0)
    val lassoPage: StateFlow<Int> = _lassoPage.asStateFlow()

    private val _lassoMoveOffset = MutableStateFlow(Offset.Zero)
    val lassoMoveOffset: StateFlow<Offset> = _lassoMoveOffset.asStateFlow()

    private val _selectedStrokeScale = MutableStateFlow(1f)
    val selectedStrokeScale: StateFlow<Float> = _selectedStrokeScale.asStateFlow()

    private val _selectedStrokePreviewBounds = MutableStateFlow<androidx.compose.ui.geometry.Rect?>(null)
    val selectedStrokePreviewBounds: StateFlow<androidx.compose.ui.geometry.Rect?> = _selectedStrokePreviewBounds.asStateFlow()

    private val _selectedStrokeResizeAnchor = MutableStateFlow<Offset?>(null)
    val selectedStrokeResizeAnchor: StateFlow<Offset?> = _selectedStrokeResizeAnchor.asStateFlow()

    /**
     * Holds the fully transformed stroke snapshots during the window between a selection commit
     * and Room emitting the updated rows. The canvas draws these transformed strokes directly so
     * there is no flicker back to the pre-transform position.
     */
    private val _commitPreview = MutableStateFlow<List<StrokeWithSamples>?>(null)
    val commitPreview: StateFlow<List<StrokeWithSamples>?> = _commitPreview.asStateFlow()

    // transformPolygon 見 SelectionGeometry.kt

    private fun currentSelectionAnchor(originals: List<StrokeWithSamples>): Offset {
        return _selectedStrokeResizeAnchor.value
            ?: StrokeTransformUtils.computeSelectionBounds(originals)?.center
            ?: Offset.Zero
    }

    private fun refreshSelectedStrokePreview() {
        val originals = _selectedStrokes.value
        if (originals.isEmpty()) {
            _selectedStrokePreview.value = emptyList()
            _selectedStrokePreviewBounds.value = null
            _selectionFramePolygon.value = emptyList()
            return
        }
        _selectedStrokePreview.value = originals
        _selectedStrokePreviewBounds.value = StrokeTransformUtils.computeSelectionBounds(originals)
    }

    /**
     * 跨頁套索（唯一入口；單頁是它的特例）：每頁若干閉合圈（呼叫方已按紙界切分＋裁剪＋轉頁內，
     * 見 clipPolygonToRect；同頁多段保留，不可 toMap 丟棄）。
     * 命中＝重心落在該頁任一圈內，分頁查後聯集。等大文件各頁 canvas 同尺寸，
     * 歸一化用傳入值。框取選中墨最多的那頁的包絡；選空則框留原圈（空白區 AI/提取仍可用）。
     */
    fun selectStrokesInLassoAcross(
        polygonsByPage: Map<Int, List<List<Offset>>>,
        srcPage: Int,
        canvasW: Float,
        canvasH: Float,
    ) {
        val cW = canvasW.coerceAtLeast(1f)
        val cH = canvasH.coerceAtLeast(1f)
        // 主線程一次快照：各頁資料流建流＋讀值都在這裡，協程內只用快照（同單頁版紀律）。
        val snaps = polygonsByPage.mapValues { (pg, _) -> pageDataFlow(pg).value }
        // src 首圈先行定位（框最終由選中墨最多的頁重算，見下；先給泡泡一個即時錨）。
        // 空白區也要留錨：AI 解析/提取只看 region，不看有沒有墨。
        val srcFirst = polygonsByPage[srcPage]?.firstOrNull().orEmpty()
        val srcNormSnapshot: List<Offset> = if (srcFirst.size >= 3) {
            srcFirst.map { DocTransform.canvasToModel(it, cW, cH, modelWidth(), modelHeight()) }
        } else {
            emptyList()
        }
        if (srcNormSnapshot.size >= 3) {
            val srcNorm = srcNormSnapshot
            _lassoPolygon.value = srcNorm
            _selectionFramePolygon.value = srcNorm
            _lastLassoPolygon.value = srcNorm
            _lassoPage.value = srcPage
        }
        scope.launch(Dispatchers.Default) {
            val allStrokes = mutableListOf<StrokeWithSamples>()
            val allImages = mutableListOf<ImageAnnotation>()
            val allTexts = mutableListOf<TextAnnotation>()
            for ((pg, polys) in polygonsByPage) {
                val norms = polys.filter { it.size >= 3 }
                    .map { poly -> poly.map { DocTransform.canvasToModel(it, cW, cH, modelWidth(), modelHeight()) } }
                if (norms.isEmpty()) continue
                val data = snaps[pg] ?: continue
                for (swp in data.strokes) {
                    val pts = swp.points
                    if (pts.isEmpty()) continue
                    val cx = pts.sumOf { it.x.toDouble() } / pts.size
                    val cy = pts.sumOf { it.y.toDouble() } / pts.size
                    if (isCentroidInAny(cx.toFloat(), cy.toFloat(), norms)) allStrokes += swp
                }
                for (norm in norms) {
                    allImages += data.images.filter { isImageSelectedByLasso(it, norm) }
                    allTexts += data.texts.filter { isTextSelectedByLasso(it, norm) }
                }
            }
            val distinct = allStrokes.distinctBy { it.stroke.id }
            val distinctImages = allImages.distinctBy { it.id }
            val distinctTexts = allTexts.distinctBy { it.id }
            // 顯示框：選中物最多的那頁的包絡四角（model 座標，該頁頁內；墨圖字一起比）。
            // 注意只動 _selectionFramePolygon：_lassoPolygon/_lastLassoPolygon 保持真實套索，
            // 那是提取遮罩＋泡泡錨點，變方就回歸了。
            data class PgBox(val page: Int, val l: Float, val t: Float, val r: Float, val b: Float)
            val boxes = mutableListOf<PgBox>()
            distinct.groupBy { it.stroke.pageIndex }.forEach { (pg, list) ->
                boxes += PgBox(
                    pg,
                    list.minOf { it.stroke.boundsLeft },
                    list.minOf { it.stroke.boundsTop },
                    list.maxOf { it.stroke.boundsRight },
                    list.maxOf { it.stroke.boundsBottom }
                )
            }
            distinctTexts.groupBy { it.pageIndex }.forEach { (pg, list) ->
                var l = Float.POSITIVE_INFINITY
                var t = Float.POSITIVE_INFINITY
                var r = Float.NEGATIVE_INFINITY
                var b = Float.NEGATIVE_INFINITY
                list.forEach { ann ->
                    val eb = textEstimatedBounds(ann)
                    if (eb.left < l) l = eb.left
                    if (eb.top < t) t = eb.top
                    if (eb.right > r) r = eb.right
                    if (eb.bottom > b) b = eb.bottom
                }
                boxes += PgBox(pg, l, t, r, b)
            }
            distinctImages.groupBy { it.pageIndex }.forEach { (pg, list) ->
                boxes += PgBox(
                    pg,
                    list.minOf { it.modelX },
                    list.minOf { it.modelY },
                    list.maxOf { it.modelX + it.modelWidth },
                    list.maxOf { it.modelY + it.modelHeight }
                )
            }
            // 主導頁＝選中物最多（墨＋圖＋字一起數）。
            fun countOn(pg: Int): Int =
                distinct.count { it.stroke.pageIndex == pg } +
                    distinctImages.count { it.pageIndex == pg } +
                    distinctTexts.count { it.pageIndex == pg }
            val domBox = boxes.maxByOrNull { countOn(it.page) }
            val frameNorm: List<Offset> = if (domBox != null) {
                listOf(
                    Offset(domBox.l, domBox.t), Offset(domBox.r, domBox.t),
                    Offset(domBox.r, domBox.b), Offset(domBox.l, domBox.b)
                )
            } else {
                emptyList()
            }
            withContext(Dispatchers.Main) {
                _selectedStrokes.value = distinct
                _selectedImageAnnotationIds.value = distinctImages.map { it.id }.toSet()
                _selectedTextAnnotationIds.value = distinctTexts.map { it.id }.toSet()
                // 有物用包絡框；選空（空白區）框留原圈，region 快照保留給泡泡＋AI/提取。
                _selectionFramePolygon.value = frameNorm.ifEmpty { srcNormSnapshot }
                // 注意不清空 _lassoPolygon/_lastLassoPolygon：那是提取遮罩＋泡泡錨點。
                _lassoMoveOffset.value = Offset.Zero
                _selectedStrokeScale.value = 1f
                _selectedStrokeResizeAnchor.value = null
                _selectedStrokePreview.value = distinct
                _selectedStrokePreviewBounds.value = StrokeTransformUtils.computeSelectionBounds(distinct)
            }
        }
    }

    /** 重心落在任一圈內（跨頁聯集用；單圈走 findStrokesInLasso 同語義）。 */
    private fun isCentroidInAny(cx: Float, cy: Float, polys: List<List<Offset>>): Boolean {
        for (poly in polys) {
            if (poly.size < 3) continue
            var inside = false
            var j = poly.lastIndex
            for (i in poly.indices) {
                val xi = poly[i].x; val yi = poly[i].y
                val xj = poly[j].x; val yj = poly[j].y
                if ((yi > cy) != (yj > cy) &&
                    cx < (xj - xi) * (cy - yi) / (yj - yi) + xi
                ) inside = !inside
                j = i
            }
            if (inside) return true
        }
        return false
    }

    fun moveSelectedStrokes(delta: Offset) {
        if (delta == Offset.Zero) return
        // Normalise drag delta to model space so it lines up with stored stroke coordinates.
        val normalizedDelta = DocTransform.canvasToModel(delta, canvasW(), canvasH(), modelWidth(), modelHeight())
        _lassoMoveOffset.value = _lassoMoveOffset.value + normalizedDelta
        refreshSelectedStrokePreview()
    }

    fun beginSelectedStrokeResize(anchorCanvas: Offset) {
        _selectedStrokeResizeAnchor.value = canvasToModel(
            anchorCanvas, modelWidth(), modelHeight(), canvasW(), canvasH()
        )
        _selectedStrokeScale.value = 1f
        refreshSelectedStrokePreview()
    }

    fun previewSelectedStrokeScale(scale: Float) {
        _selectedStrokeScale.value = StrokeTransformUtils.clampUniformScale(scale)
        refreshSelectedStrokePreview()
    }

    /**
     * 提交套索移動。整體選取中心被拖出本頁上下界時整組換頁（y 繞回新頁），
     * 並回傳目標頁（供呼叫方結算跨頁）；未跨頁回傳 null（舊行為）。
     * 源頁取自選取歸屬（selectionPage），不讀作用頁——全活頁下選取可在任意紙上。
     */
    fun commitMovedStrokes(maxPageIndex: Int = Int.MAX_VALUE): Int? {
        val strokes = _selectedStrokes.value
        val images = selectedImagesSnapshot()
        val texts = selectedTextsSnapshot()
        val delta = _lassoMoveOffset.value
        if ((strokes.isEmpty() && images.isEmpty() && texts.isEmpty()) ||
            (delta.x == 0f && delta.y == 0f)
        ) {
            clearSelection()
            return null
        }

        var movedStrokes = if (strokes.isNotEmpty()) {
            StrokeTransformUtils.transformStrokes(strokes, translation = delta)
        } else {
            emptyList()
        }
        var movedImages = images.map { ann ->
            ann.copy(
                modelX = ann.modelX + delta.x,
                modelY = ann.modelY + delta.y
            )
        }
        var movedTexts = texts.map { ann ->
            ann.copy(
                modelX = ann.modelX + delta.x,
                modelY = ann.modelY + delta.y
            )
        }

        // 跨頁判定：整體中心掉出本頁 → 整組換頁（y 繞回），只認縱向
        val centerSamples = mutableListOf<Float>()
        StrokeTransformUtils.computeSelectionBounds(movedStrokes)?.let { centerSamples.add(it.center.y) }
        movedImages.forEach { centerSamples.add(it.modelY + it.modelHeight / 2f) }
        movedTexts.forEach {
            val eb = textEstimatedBounds(it)
            centerSamples.add((eb.top + eb.bottom) / 2f)
        }
        val centerY = if (centerSamples.isNotEmpty()) centerSamples.average().toFloat() else modelHeight() / 2f
        val sourcePage = selectionPage()
        val targetPage = (sourcePage + kotlin.math.floor(centerY / modelHeight()).toInt())
            .coerceIn(0, maxPageIndex.coerceAtLeast(0))
        val appliedShift = targetPage - sourcePage
        val crossPage = appliedShift != 0
        if (crossPage) {
            val wrapY = appliedShift * modelHeight()
            movedStrokes = movedStrokes.map { swp ->
                val sh = swp.stroke
                swp.copy(
                    stroke = sh.copy(
                        pageIndex = targetPage,
                        boundsTop = sh.boundsTop - wrapY,
                        boundsBottom = sh.boundsBottom - wrapY
                    ),
                    points = swp.points.map { pt -> pt.copy(y = pt.y - wrapY) }
                )
            }
            movedImages = movedImages.map { ann ->
                ann.copy(
                    pageIndex = targetPage,
                    modelX = ann.modelX.coerceIn(0f, modelWidth()),
                    // 與墨同幅繞回（之前只鉗制沒減 wrapY，圖黏在目標頁紙邊）。
                    modelY = (ann.modelY - wrapY).coerceIn(0f, modelHeight())
                )
            }
            movedTexts = movedTexts.map { ann ->
                // 字與墨圖同幅繞回；docY 同步重算（見 S1 雙寫）。
                val newY = (ann.modelY - wrapY).coerceIn(0f, modelHeight())
                ann.copy(
                    pageIndex = targetPage,
                    modelX = ann.modelX.coerceIn(0f, modelWidth()),
                    modelY = newY,
                    docY = targetPage * docStride() + newY
                )
            }
        }

        // S1 雙寫：落庫/入 undo 指令前把 docY 重算（冪等；replaceStrokeSnapshots 會再算一次也無妨）
        movedStrokes = movedStrokes.map { it.redocWith(docStride()) }
        movedImages = movedImages.map { it.redocWith(docStride()) }
        movedTexts = movedTexts.map {
            it.copy(docY = it.pageIndex * docStride() + it.modelY)
        }

        // Apply new DB state but keep the selection active for further edits.
        _selectedStrokes.value = movedStrokes
        _selectedStrokePreview.value = movedStrokes
        _lassoMoveOffset.value = Offset.Zero
        _selectedStrokeScale.value = 1f
        _selectedStrokeResizeAnchor.value = null
        val movedPolygon = transformPolygon(
            polygon = _lassoPolygon.value,
            translation = delta,
            scale = 1f,
            anchor = Offset.Zero
        ).ifEmpty { _lassoPolygon.value }
        // 跨頁：框多邊形與筆跡同幅繞回新頁，否則框留在源頁座標、顯示錯位
        //（如下頁拖往上頁，框會跑到上頁紙的上方）。
        val committedSelectionPolygon = if (crossPage) {
            val wrapY = appliedShift * modelHeight()
            movedPolygon.map { Offset(it.x, it.y - wrapY) }
        } else {
            movedPolygon
        }
        _lassoPolygon.value = committedSelectionPolygon
        _selectionFramePolygon.value = committedSelectionPolygon
        _lastLassoPolygon.value = committedSelectionPolygon
        _selectedStrokePreviewBounds.value = StrokeTransformUtils.computeSelectionBounds(movedStrokes)

        _commitPreview.value = movedStrokes.takeIf { it.isNotEmpty() }

        scope.launch(Dispatchers.IO) {
            // 三表同一交易：抬手一次的落庫要麼全進，要麼全不進（內層各自帶交易，
            // 同協程自動合併，外層只是一個殼）。
            repos.transaction {
                if (movedStrokes.isNotEmpty()) {
                    replaceStrokeSnapshots(movedStrokes)
                }
                movedImages.forEach { repos.images.update(it) }
                movedTexts.forEach { repos.texts.updateAndBump(it) }
            }
            withContext(Dispatchers.Main) {
                if (_commitPreview.value === movedStrokes) {
                    _commitPreview.value = null
                }
                // 字視同墨圖：三者全空才走純筆路徑，否則進混合命令（texts 預設空，向後相容）。
                val noObjs = images.isEmpty() && texts.isEmpty()
                when {
                    noObjs && !crossPage -> pushUndo(DrawCommand.MoveStrokes(strokes, delta))
                    noObjs -> pushUndo(
                        DrawCommand.MoveStrokes(strokes, delta, movedStrokes)
                    )
                    else -> pushUndo(
                        DrawCommand.MoveSelectionMixed(
                            strokeOriginals = strokes,
                            strokeUpdated = movedStrokes,
                            imageOriginals = images,
                            imageUpdated = movedImages,
                            textOriginals = texts,
                            textUpdated = movedTexts
                        )
                    )
                }
            }
        }
        return if (crossPage) targetPage else null
    }

    fun commitResizedStrokes() {
        val originals = _selectedStrokes.value
        val images = selectedImagesSnapshot()
        val texts = selectedTextsSnapshot()
        val translation = _lassoMoveOffset.value
        val scale = _selectedStrokeScale.value
        if ((originals.isEmpty() && images.isEmpty() && texts.isEmpty()) || (kotlin.math.abs(scale - 1f) < 0.001f && translation == Offset.Zero)) {
            clearSelection()
            return
        }

        val anchor = currentSelectionAnchor(originals)
        val updated = if (originals.isNotEmpty()) {
            StrokeTransformUtils.transformStrokes(
                strokes = originals,
                translation = translation,
                scale = scale,
                anchor = anchor
            )
        } else {
            emptyList()
        }
        val clampedScale = StrokeTransformUtils.clampUniformScale(scale)
        val updatedImages = images.map { ann ->
            ann.copy(
                modelX = anchor.x + (ann.modelX - anchor.x) * clampedScale + translation.x,
                modelY = anchor.y + (ann.modelY - anchor.y) * clampedScale + translation.y,
                modelWidth = (ann.modelWidth * clampedScale).coerceAtLeast(30f),
                modelHeight = (ann.modelHeight * clampedScale).coerceAtLeast(30f)
            )
        }
        // 字與圖同式縮放：位置繞錨點，字級按同比例（下限 4f 與單字縮放一致）。
        val updatedTexts = texts.map { ann ->
            ann.copy(
                modelX = anchor.x + (ann.modelX - anchor.x) * clampedScale + translation.x,
                modelY = anchor.y + (ann.modelY - anchor.y) * clampedScale + translation.y,
                fontSize = (ann.fontSize * clampedScale).coerceAtLeast(4f)
            )
        }

        // S1 雙寫：落庫/入 undo 指令前把 docY 重算（resize 不跨頁，頁不變只算值）
        val redocUpdated = updated.map { it.redocWith(docStride()) }
        val redocUpdatedImages = updatedImages.map { it.redocWith(docStride()) }
        val redocUpdatedTexts = updatedTexts.map { it.redocWith(docStride()) }

        // Keep selection active
        _selectedStrokes.value = redocUpdated
        _selectedStrokePreview.value = redocUpdated
        _lassoMoveOffset.value = Offset.Zero
        _selectedStrokeScale.value = 1f
        _selectedStrokeResizeAnchor.value = null
        val committedSelectionPolygon = transformPolygon(
            polygon = _lassoPolygon.value,
            translation = translation,
            scale = scale,
            anchor = anchor
        ).ifEmpty { _lassoPolygon.value }
        _lassoPolygon.value = committedSelectionPolygon
        _selectionFramePolygon.value = committedSelectionPolygon
        _lastLassoPolygon.value = committedSelectionPolygon
        _selectedStrokePreviewBounds.value = StrokeTransformUtils.computeSelectionBounds(redocUpdated)

        _commitPreview.value = redocUpdated.takeIf { it.isNotEmpty() }
        scope.launch(Dispatchers.IO) {
            repos.transaction {
                if (redocUpdated.isNotEmpty()) {
                    replaceStrokeSnapshots(redocUpdated)
                }
                redocUpdatedImages.forEach { repos.images.update(it) }
                redocUpdatedTexts.forEach { repos.texts.updateAndBump(it) }
            }
            withContext(Dispatchers.Main) {
                if (_commitPreview.value === redocUpdated) {
                    _commitPreview.value = null
                }
                when {
                    images.isEmpty() && texts.isEmpty() -> pushUndo(DrawCommand.ResizeStrokes(originals, redocUpdated))
                    else -> pushUndo(
                        DrawCommand.ResizeSelectionMixed(
                            strokeOriginals = originals,
                            strokeUpdated = redocUpdated,
                            imageOriginals = images,
                            imageUpdated = redocUpdatedImages,
                            textOriginals = texts,
                            textUpdated = redocUpdatedTexts
                        )
                    )
                }
            }
        }
    }
    /** 選取歸屬頁：筆看第一筆的頁，純圖選看圖所在頁，空白框選看框落在哪頁，都無才退回作用頁。 */
    fun selectionPage(): Int {
        _selectedStrokes.value.firstOrNull()?.let { return it.stroke.pageIndex }
        val ids = _selectedImageAnnotationIds.value
        if (ids.isNotEmpty()) {
            for (flow in pageFlows().values) {
                if (flow.value.images.any { it.id in ids }) {
                    return flow.value.images.first { it.id in ids }.pageIndex
                }
            }
            currentImageAnnotations().value.firstOrNull { it.id in ids }?.let { return it.pageIndex }
        }
        selectedTextsSnapshot().firstOrNull()?.let { return it.pageIndex }
        if (_lassoPolygon.value.isNotEmpty() || _lastLassoPolygon.value.isNotEmpty()) {
            return _lassoPage.value
        }
        return pageIndex()
    }

    private fun selectedImagesSnapshot(): List<ImageAnnotation> {
        val ids = _selectedImageAnnotationIds.value
        if (ids.isEmpty()) return emptyList()
        val found = mutableListOf<ImageAnnotation>()
        for (flow in pageFlows().values) {
            found += flow.value.images.filter { it.id in ids }
        }
        if (found.isNotEmpty()) return found
        return currentImageAnnotations().value.filter { it.id in ids }
    }

    /** 套索選中的字快照（與圖同模式掃全頁流；呼叫方保證主線程）。 */
    private fun selectedTextsSnapshot(): List<TextAnnotation> {
        val ids = _selectedTextAnnotationIds.value
        if (ids.isEmpty()) return emptyList()
        val found = mutableListOf<TextAnnotation>()
        for (flow in pageFlows().values) {
            found += flow.value.texts.filter { it.id in ids }
        }
        if (found.isNotEmpty()) return found
        return currentTextAnnotations().value.filter { it.id in ids }
    }

    /**
     * Overlay 用：主線程同步快照（只讀 flows 當前值；拖曳中實體不變，安全）。
     * 跨頁拖曳時選中圖也要畫在紙上層。
     */
    fun selectedImagesNow(): List<ImageAnnotation> = selectedImagesSnapshot()

    /** 同上，給 overlay 畫選中字（拖曳跨頁全程可見）。 */
    fun selectedTextsNow(): List<TextAnnotation> = selectedTextsSnapshot()

    fun deleteSelection() {
        val selectedStrokeSnapshots = _selectedStrokes.value
        val selectedImageSnapshots = selectedImagesSnapshot()
        val selectedTextSnapshots = selectedTextsSnapshot()
        if (selectedStrokeSnapshots.isEmpty() && selectedImageSnapshots.isEmpty() &&
            selectedTextSnapshots.isEmpty()
        ) return

        // 樂觀隱藏先行：三種物件一次拿掉，DB 在 IO 線程跟上。
        hideIds(selectedStrokeSnapshots.map { it.stroke.id })
        hideIds(selectedImageSnapshots.map { it.id })
        hideIds(selectedTextSnapshots.map { it.id })
        scope.launch(Dispatchers.IO) {
            // 逐類回滾（不整包回滾）：三類各寫各的，部分失敗只放回失敗的那類；
            // 整包放回會讓已刪成功的閃回一幀（Flow 重發前）。
            val strokeIds = selectedStrokeSnapshots.map { it.stroke.id }
            val imageIds = selectedImageSnapshots.map { it.id }
            val textIds = selectedTextSnapshots.map { it.id }
            if (strokeIds.isNotEmpty()) {
                try {
                    // 同上：刪選取是使用者可見的刪除，走墓碑（undo/redo 會用 insertStroke
                    // 把整列覆蓋回來，deletedAt 自然歸 null，所以 undo 不需要特別處理）。
                    repos.strokes.tombstoneStrokesByIds(strokeIds)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    showIds(strokeIds)
                    android.util.Log.e("EditorViewModel", "deleteSelection strokes failed, unhidden", e)
                }
            }
            try {
                repos.transaction {
                    selectedImageSnapshots.forEach { repos.images.deleteById(it.id) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showIds(imageIds)
                android.util.Log.e("EditorViewModel", "deleteSelection images failed, unhidden", e)
            }
            try {
                selectedTextSnapshots.let { repos.texts.tombstoneByIds(it.map { t -> t.id }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showIds(textIds)
                android.util.Log.e("EditorViewModel", "deleteSelection texts failed, unhidden", e)
            }
            withContext(Dispatchers.Main) {
                clearSelection(keepLastRegion = true)
                pushUndo(
                    DrawCommand.RemoveSelectionMixed(
                        selectedStrokeSnapshots, selectedImageSnapshots, selectedTextSnapshots
                    )
                )
            }
        }
    }

    fun copySelectionInPlace() {
        val sourceStrokes = _selectedStrokes.value
        val sourceImages = selectedImagesSnapshot()
        val sourceTexts = selectedTextsSnapshot()
        if (sourceStrokes.isEmpty() && sourceImages.isEmpty() && sourceTexts.isEmpty()) return

        scope.launch(Dispatchers.IO) {
            val copiedStrokes = sourceStrokes.map { original ->
                val newStrokeId = UUID.randomUUID().toString()
                val copiedStroke = original.stroke.copy(id = newStrokeId)
                val copiedPoints = original.points.map { pt ->
                    pt.copy(id = 0, strokeId = newStrokeId)
                }
                repos.transaction {
                    repos.strokes.insertStroke(copiedStroke)
                    repos.strokes.insertPoints(copiedPoints)
                }
                StrokeWithSamples(copiedStroke, copiedPoints)
            }

            val copiedImages = sourceImages.map { original ->
                val copied = original.copy(id = UUID.randomUUID().toString())
                repos.images.insert(copied)
                copied
            }

            val copiedTexts = sourceTexts.map { original ->
                val copied = original.copy(id = UUID.randomUUID().toString())
                repos.texts.insert(copied)
                copied
            }

            withContext(Dispatchers.Main) {
                _selectedStrokes.value = copiedStrokes
                _selectedStrokePreview.value = copiedStrokes
                _selectedStrokePreviewBounds.value = StrokeTransformUtils.computeSelectionBounds(copiedStrokes)
                _selectedStrokeScale.value = 1f
                _selectedStrokeResizeAnchor.value = null
                _lassoMoveOffset.value = Offset.Zero
                _selectedImageAnnotationIds.value = copiedImages.map { it.id }.toSet()
                _selectedTextAnnotationIds.value = copiedTexts.map { it.id }.toSet()
                pushUndo(DrawCommand.AddSelectionCopies(copiedStrokes, copiedImages, copiedTexts))
            }
        }
    }

    fun clearSelection(keepLastRegion: Boolean = false) {
        _selectedStrokes.value = emptyList()
        _selectedImageAnnotationIds.value = emptySet()
        _selectedTextAnnotationIds.value = emptySet()
        _selectedStrokePreview.value = emptyList()
        _selectedStrokePreviewBounds.value = null
        _selectionFramePolygon.value = emptyList()
        _selectedStrokeScale.value = 1f
        _selectedStrokeResizeAnchor.value = null
        if (!keepLastRegion) {
            _lastLassoPolygon.value = emptyList()
        }
        _lassoPolygon.value = emptyList()
        _lassoMoveOffset.value = Offset.Zero
        _commitPreview.value = null
    }
}
