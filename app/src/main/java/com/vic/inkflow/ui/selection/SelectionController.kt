package com.vic.inkflow.ui.selection

import androidx.compose.ui.geometry.Offset
import com.vic.inkflow.data.ImageAnnotation
import com.vic.inkflow.data.StrokeWithSamples
import com.vic.inkflow.data.TextAnnotation
import com.vic.inkflow.data.repository.InkFlowRepositories
import com.vic.inkflow.ui.DrawCommand
import com.vic.inkflow.ui.EditorViewModel
import com.vic.inkflow.ui.redocWith
import com.vic.inkflow.util.DocTransform
import com.vic.inkflow.util.StrokeTransformUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.floor

/**
 * 圈選管線的執行端：持有唯一狀態 [state]，把意圖送進純 reducer，
 * 只負責 reducer 不能做的事——背景判定、DB 交易、undo。
 *
 * 刻意保留的硬性不變量（每一條都有歷史傷口）：
 * 1. **寫入序列化**：連續 move/resize 的 IO 交易順序不保證，舊 commit 會蓋回新位置。
 * 2. **三表同一交易**：墨／圖／字要麼全進、要麼全不進；undo 也只是一格。
 * 3. **docY 雙寫**：落庫與 undo 指令前重算（S1）。
 * 4. **跨頁換頁**：整組繞頁、`flowId` 斷開、框同步繞。
 * 5. **commit preview**：DB 回來前先畫已提交的墨，避免閃回原位。
 * 6. **提取只看 region.srcPage**：作用頁／選取歸屬頁都不能拿來裁圖。
 */
internal class SelectionController(
    private val repos: InkFlowRepositories,
    private val scope: CoroutineScope,
    private val modelWidth: () -> Float,
    private val modelHeight: () -> Float,
    private val canvasW: () -> Float,
    private val canvasH: () -> Float,
    /** S1 雙寫步幅：docY = pageIndex × stride + 頁內頂邊。 */
    private val docStride: () -> Float,
    private val pageDataFlow: (Int) -> StateFlow<EditorViewModel.NeighborPageData>,
    private val pageIndex: () -> Int,
    private val pushUndo: (DrawCommand) -> Unit,
    private val replaceStrokeSnapshots: suspend (List<StrokeWithSamples>) -> Unit,
    private val hideIds: (Collection<String>) -> Unit,
    private val showIds: (Collection<String>) -> Unit
) {
    private val _state = MutableStateFlow(SelectionState.IDLE)
    val state: StateFlow<SelectionState> = _state.asStateFlow()

    /** 見類別說明第 1 條。 */
    private val writeMutex = Mutex()

    /** 非同步判定世代：舊結果晚到直接丟棄。 */
    private var resolveRevision = 0L

    /** 拖曳起點的凍結快照：拖曳期間任何外部狀態變動都不能污染提交。 */
    private var dragOrigin: SelectionItems? = null
    private var dragRegion: SelectionRegion? = null
    private var dragFrame: SelectionFrame? = null

    private fun reduce(intent: SelectionIntent) {
        _state.value = SelectionReducer.reduce(_state.value, intent)
    }

    // ── 圈選 ────────────────────────────────────────────────────────────────

    /**
     * 手勢結束：區域已由 [LassoGeometry] 算好（model／頁內座標）。
     * 主執行緒取各頁快照，背景判定，回到主執行緒才套用。
     */
    fun select(region: SelectionRegion) {
        if (region.isEmpty) {
            clear()
            return
        }
        val revision = ++resolveRevision
        dragOrigin = null
        dragRegion = null
        dragFrame = null
        // 圈選區域立刻生效（舊版 selectStrokesInLassoAcross 也是同步寫 lassoPolygon）：
        // 背景判定還沒回來就按提取／AI，拿到的是「剛圈的那一塊」，不是上一次的。
        reduce(SelectionIntent.Probing(revision = revision, region = region))

        val snapshots = region.polygonsByPage.keys.associateWith { page ->
            val data = pageDataFlow(page).value
            SelectionPageSnapshot(
                page = page,
                strokes = data.strokes,
                images = data.images,
                texts = data.texts
            )
        }

        scope.launch(Dispatchers.Default) {
            val resolved = SelectionResolver.resolve(region, snapshots)
            withContext(Dispatchers.Main) {
                if (revision != resolveRevision) return@withContext
                reduce(
                    SelectionIntent.Resolved(
                        revision = revision,
                        region = region,
                        items = resolved.items,
                        frame = resolved.frame
                    )
                )
            }
        }
    }

    // ── 移動 ────────────────────────────────────────────────────────────────

    /** 開始平移：凍結當下選取快照，之後每幀只送絕對位移。 */
    fun beginMove() {
        val st = _state.value
        if (!st.hasItems || st.phase == SelectionPhase.RESOLVING) return
        dragOrigin = st.items
        dragRegion = st.region
        dragFrame = st.frame
        reduce(
            SelectionIntent.BeginTransform(
                anchor = st.frame?.bounds?.center ?: st.items.bounds()?.center ?: Offset.Zero
            )
        )
    }

    /**
     * 絕對平移（canvas 位移，從按下點算起）。每幀都從起點重算，
     * 不累積增量——事件被合併、掉幀、畫布尺寸中途改變都不會漂。
     */
    fun moveTo(totalCanvasDelta: Offset) {
        if (_state.value.phase != SelectionPhase.TRANSFORMING) return
        val modelDelta = DocTransform.canvasToModel(
            totalCanvasDelta, canvasW(), canvasH(), modelWidth(), modelHeight()
        )
        reduce(SelectionIntent.Translate(modelDelta))
    }

    // ── 縮放 ────────────────────────────────────────────────────────────────

    /** 開始縮放：[anchorCanvas] 是對角把手（縮放不動的那一點）。 */
    fun beginResize(anchorCanvas: Offset) {
        val st = _state.value
        if (!st.hasItems) return
        dragOrigin = st.items
        dragRegion = st.region
        dragFrame = st.frame
        val anchor = DocTransform.canvasToModel(
            anchorCanvas, canvasW(), canvasH(), modelWidth(), modelHeight()
        )
        reduce(SelectionIntent.BeginTransform(anchor))
    }

    /** 絕對比例（從手勢起點算）。 */
    fun resizeTo(scale: Float) {
        if (_state.value.phase != SelectionPhase.TRANSFORMING) return
        reduce(SelectionIntent.Scale(scale))
    }

    // ── 提交 ────────────────────────────────────────────────────────────────

    /**
     * 提交平移。選取中心離開本頁 → 整組換頁（回傳目標頁，供呼叫方結算）。
     * 沒有實際位移時**保留選取**（舊版會清掉，使用者點一下框內就失去選取）。
     */
    fun commitMove(maxPageIndex: Int = Int.MAX_VALUE): Int? {
        val st = _state.value
        val origin = dragOrigin ?: return null
        if (st.phase != SelectionPhase.TRANSFORMING) return null

        val transform = st.transform
        if (transform.isIdentity) {
            settle(origin, dragRegion, dragFrame)
            return null
        }

        val sourcePage = dragFrame?.page ?: st.page
        val frameCenterY = dragFrame?.bounds?.center?.y
            ?: origin.bounds()?.center?.y
            ?: (modelHeight() / 2f)
        val pageH = modelHeight().coerceAtLeast(1f)
        val rawShift = floor((frameCenterY + transform.translation.y) / pageH).toInt()
        val targetPage = (sourcePage + rawShift).coerceIn(0, maxPageIndex.coerceAtLeast(0))
        val appliedShift = targetPage - sourcePage
        val wrapY = appliedShift * pageH

        var movedStrokes = if (origin.strokes.isNotEmpty()) {
            StrokeTransformUtils.transformStrokes(origin.strokes, transform.translation)
        } else {
            emptyList()
        }
        var movedImages = origin.images.map { ann ->
            ann.copy(modelX = ann.modelX + transform.translation.x, modelY = ann.modelY + transform.translation.y)
        }
        var movedTexts = origin.texts.map { ann ->
            ann.copy(modelX = ann.modelX + transform.translation.x, modelY = ann.modelY + transform.translation.y)
        }

        if (appliedShift != 0) {
            movedStrokes = movedStrokes.map { wrapStroke(it, targetPage, wrapY) }
            movedImages = movedImages.map { wrapImage(it, targetPage, wrapY) }
            movedTexts = movedTexts.map { wrapText(it, targetPage, wrapY) }
        }

        // S1 雙寫：落庫／入 undo 指令前重算 docY（冪等）。
        val redocStrokes = movedStrokes.map { it.redocWith(docStride()) }
        val redocImages = movedImages.map { it.redocWith(docStride()) }
        val redocTexts = movedTexts.map { it.redocWith(docStride()) }

        val newRegion = dragRegion
            ?.let { LassoGeometry.transformRegion(it, transform.translation, 1f, Offset.Zero) }
            ?.let { if (appliedShift != 0) LassoGeometry.shiftRegion(it, appliedShift, pageH) else it }
        val newItems = SelectionItems(redocStrokes, redocImages, redocTexts)
        val newFrame = LassoGeometry.frameOf(newItems, newRegion)

        settle(newItems, newRegion, newFrame)
        reduce(SelectionIntent.SetCommitPreview(movedStrokes.takeIf { it.isNotEmpty() }))

        scope.launch(Dispatchers.IO) {
            writeMutex.withLock {
                repos.transaction {
                    if (movedStrokes.isNotEmpty()) replaceStrokeSnapshots(movedStrokes)
                    movedImages.forEach { repos.images.update(it) }
                    movedTexts.forEach { repos.texts.updateAndBump(it) }
                }
            }
            withContext(Dispatchers.Main) {
                if (_state.value.commitPreview === movedStrokes) {
                    reduce(SelectionIntent.SetCommitPreview(null))
                }
                val noObjs = origin.images.isEmpty() && origin.texts.isEmpty()
                when {
                    noObjs && appliedShift == 0 ->
                        pushUndo(DrawCommand.MoveStrokes(origin.strokes, transform.translation))
                    noObjs ->
                        pushUndo(DrawCommand.MoveStrokes(origin.strokes, transform.translation, movedStrokes))
                    else -> pushUndo(
                        DrawCommand.MoveSelectionMixed(
                            strokeOriginals = origin.strokes,
                            strokeUpdated = redocStrokes,
                            imageOriginals = origin.images,
                            imageUpdated = redocImages,
                            textOriginals = origin.texts,
                            textUpdated = redocTexts
                        )
                    )
                }
            }
        }
        return if (appliedShift != 0) targetPage else null
    }

    /** 提交縮放（等比，錨點為對角把手）。沒有實際變換時保留選取。 */
    fun commitResize() {
        val st = _state.value
        val origin = dragOrigin ?: return
        if (st.phase != SelectionPhase.TRANSFORMING) return

        val transform = st.transform
        if (transform.isIdentity) {
            settle(origin, dragRegion, dragFrame)
            return
        }

        val scale = StrokeTransformUtils.clampUniformScale(transform.scale)
        val anchor = transform.anchor ?: dragFrame?.bounds?.center ?: Offset.Zero
        val translation = transform.translation

        val updatedStrokes = if (origin.strokes.isNotEmpty()) {
            StrokeTransformUtils.transformStrokes(origin.strokes, translation, scale, anchor)
        } else {
            emptyList()
        }
        val updatedImages = origin.images.map { ann ->
            ann.copy(
                modelX = anchor.x + (ann.modelX - anchor.x) * scale + translation.x,
                modelY = anchor.y + (ann.modelY - anchor.y) * scale + translation.y,
                modelWidth = (ann.modelWidth * scale).coerceAtLeast(30f),
                modelHeight = (ann.modelHeight * scale).coerceAtLeast(30f)
            )
        }
        val updatedTexts = origin.texts.map { ann ->
            ann.copy(
                modelX = anchor.x + (ann.modelX - anchor.x) * scale + translation.x,
                modelY = anchor.y + (ann.modelY - anchor.y) * scale + translation.y,
                fontSize = (ann.fontSize * scale).coerceAtLeast(4f)
            )
        }

        val redocStrokes = updatedStrokes.map { it.redocWith(docStride()) }
        val redocImages = updatedImages.map { it.redocWith(docStride()) }
        val redocTexts = updatedTexts.map { it.redocWith(docStride()) }

        val newRegion = dragRegion?.let {
            LassoGeometry.transformRegion(it, translation, scale, anchor)
        }
        val newItems = SelectionItems(redocStrokes, redocImages, redocTexts)
        val newFrame = LassoGeometry.frameOf(newItems, newRegion)

        settle(newItems, newRegion, newFrame)
        reduce(SelectionIntent.SetCommitPreview(updatedStrokes.takeIf { it.isNotEmpty() }))

        scope.launch(Dispatchers.IO) {
            writeMutex.withLock {
                repos.transaction {
                    if (updatedStrokes.isNotEmpty()) replaceStrokeSnapshots(updatedStrokes)
                    updatedImages.forEach { repos.images.update(it) }
                    updatedTexts.forEach { repos.texts.updateAndBump(it) }
                }
            }
            withContext(Dispatchers.Main) {
                if (_state.value.commitPreview === updatedStrokes) {
                    reduce(SelectionIntent.SetCommitPreview(null))
                }
                when {
                    origin.images.isEmpty() && origin.texts.isEmpty() ->
                        pushUndo(DrawCommand.ResizeStrokes(origin.strokes, redocStrokes))
                    else -> pushUndo(
                        DrawCommand.ResizeSelectionMixed(
                            strokeOriginals = origin.strokes,
                            strokeUpdated = redocStrokes,
                            imageOriginals = origin.images,
                            imageUpdated = redocImages,
                            textOriginals = origin.texts,
                            textUpdated = redocTexts
                        )
                    )
                }
            }
        }
    }

    private fun settle(items: SelectionItems, region: SelectionRegion?, frame: SelectionFrame?) {
        reduce(SelectionIntent.Settled(items, region, frame))
        dragOrigin = null
        dragRegion = null
        dragFrame = null
    }

    // ── 查詢 ────────────────────────────────────────────────────────────────

    /** 選取歸屬頁：主導頁（泡泡、overlay 用）。 */
    fun selectionPage(): Int =
        _state.value.frame?.page ?: _state.value.region?.srcPage ?: pageIndex()

    /** 提取／AI 的來源頁：**圈選區域所在的起手頁**，不是作用頁、也不是主導頁。 */
    fun regionPage(): Int = _state.value.region?.srcPage ?: pageIndex()

    /** 提取遮罩：起手頁的第一段圈（model／頁內座標）。 */
    fun regionPolygon(): List<Offset> = _state.value.region?.srcPolygon.orEmpty()

    // ── 刪除／複製／清除 ────────────────────────────────────────────────────

    fun deleteSelected() {
        val items = _state.value.items
        if (items.isEmpty) return

        // 樂觀隱藏先行：三種物件一次拿掉，DB 在 IO 線程跟上。
        hideIds(items.strokeIds)
        hideIds(items.imageIds)
        hideIds(items.textIds)
        scope.launch(Dispatchers.IO) {
            // 逐類回滾（不整包回滾）：部分失敗只放回失敗的那類。
            val strokeIds = items.strokes.map { it.stroke.id }
            val imageIds = items.images.map { it.id }
            val textIds = items.texts.map { it.id }
            if (strokeIds.isNotEmpty()) {
                try {
                    repos.strokes.tombstoneStrokesByIds(strokeIds)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    showIds(strokeIds)
                    android.util.Log.e("Selection", "delete strokes failed, unhidden", e)
                }
            }
            if (items.images.isNotEmpty()) {
                try {
                    repos.transaction {
                        items.images.forEach { repos.images.deleteById(it.id) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    showIds(imageIds)
                    android.util.Log.e("Selection", "delete images failed, unhidden", e)
                }
            }
            if (items.texts.isNotEmpty()) {
                try {
                    repos.texts.tombstoneByIds(textIds)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    showIds(textIds)
                    android.util.Log.e("Selection", "delete texts failed, unhidden", e)
                }
            }
            withContext(Dispatchers.Main) {
                clear()
                pushUndo(DrawCommand.RemoveSelectionMixed(items.strokes, items.images, items.texts))
            }
        }
    }

    fun copySelected() {
        val source = _state.value.items
        if (source.isEmpty) return

        scope.launch(Dispatchers.IO) {
            val copiedStrokes = source.strokes.map { original ->
                val newId = UUID.randomUUID().toString()
                val copiedStroke = original.stroke.copy(id = newId)
                val copiedPoints = original.points.map { it.copy(id = 0, strokeId = newId) }
                repos.transaction {
                    repos.strokes.insertStroke(copiedStroke)
                    repos.strokes.insertPoints(copiedPoints)
                }
                StrokeWithSamples(copiedStroke, copiedPoints)
            }
            val copiedImages = source.images.map { original ->
                original.copy(id = UUID.randomUUID().toString()).also { repos.images.insert(it) }
            }
            val copiedTexts = source.texts.map { original ->
                // 複製品是獨立文字框：不能共用 flowId（否則重排會把兩份互相推擠）。
                original.copy(id = UUID.randomUUID().toString(), flowId = null)
                    .also { repos.texts.insert(it) }
            }
            withContext(Dispatchers.Main) {
                val st = _state.value
                val items = SelectionItems(copiedStrokes, copiedImages, copiedTexts)
                reduce(
                    SelectionIntent.Settled(
                        items = items,
                        region = st.region,
                        frame = LassoGeometry.frameOf(items, st.region)
                    )
                )
                pushUndo(DrawCommand.AddSelectionCopies(copiedStrokes, copiedImages, copiedTexts))
            }
        }
    }

    /** 全部清空（含 region，泡泡不再靠 lastLassoPolygon 留殘影）。 */
    fun clear() {
        resolveRevision++
        dragOrigin = null
        dragRegion = null
        dragFrame = null
        reduce(SelectionIntent.Clear)
    }

    /** 外部改了頁面／undo／redo：選取快照已不可信，直接作廢。 */
    fun invalidate() = clear()

    // ── 內部 ────────────────────────────────────────────────────────────────

    private fun wrapStroke(swp: StrokeWithSamples, targetPage: Int, wrapY: Float): StrokeWithSamples {
        val s = swp.stroke
        return swp.copy(
            stroke = s.copy(
                pageIndex = targetPage,
                boundsTop = s.boundsTop - wrapY,
                boundsBottom = s.boundsBottom - wrapY
            ),
            points = swp.points.map { it.copy(y = it.y - wrapY) }
        )
    }

    private fun wrapImage(ann: ImageAnnotation, targetPage: Int, wrapY: Float): ImageAnnotation =
        ann.copy(
            pageIndex = targetPage,
            modelX = ann.modelX.coerceIn(0f, modelWidth()),
            modelY = (ann.modelY - wrapY).coerceIn(0f, modelHeight())
        )

    private fun wrapText(ann: TextAnnotation, targetPage: Int, wrapY: Float): TextAnnotation {
        val newY = (ann.modelY - wrapY).coerceIn(0f, modelHeight())
        return ann.copy(
            pageIndex = targetPage,
            modelX = ann.modelX.coerceIn(0f, modelWidth()),
            modelY = newY,
            // 換頁＝離開原本那條文字流；留著 flowId 會讓它跟別頁的同伴「同名不同頁」。
            flowId = if (targetPage == ann.pageIndex) ann.flowId else null
        )
    }
}
