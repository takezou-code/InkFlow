package com.vic.inkflow.ui

import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.vic.inkflow.data.AppDatabase
import com.vic.inkflow.data.ImageAnnotationEntity
import com.vic.inkflow.data.PointEntity
import com.vic.inkflow.data.StrokeDao
import com.vic.inkflow.data.StrokeEntity
import com.vic.inkflow.data.StrokeWithPoints
import com.vic.inkflow.data.TextAnnotationEntity
import com.vic.inkflow.util.IntersectionUtils
import com.vic.inkflow.util.EnvelopeUtils
import com.vic.inkflow.util.StrokePoint
import com.vic.inkflow.util.StrokeTransformUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

enum class Tool {
    PEN,
    HIGHLIGHTER,
    ERASER,
    LASSO,
    SHAPE,
    TEXT,
    IMAGE
}

enum class ShapeSubType { RECT, CIRCLE, LINE, ARROW }

enum class LassoSubType { FREEFORM, RECT }

enum class InputMode {
    FREE,             // 全開放，所有觸控都可畫
    PALM_REJECTION,   // 演算法過濾手掌，保留細筆跡
    STYLUS_ONLY       // 僅硬體觸控筆（PointerType.Stylus）可畫
}

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModel(
    private val db: AppDatabase,
    val documentUri: String,
    private val settingsRepository: EditorSettingsRepository
) : ViewModel() {

    companion object {
        /** Default model coordinate space (A4 portrait PDF points). */
        const val MODEL_W = 595f
        const val MODEL_H = 842f
        private const val DEFAULT_PEN_STROKE_WIDTH = 4f
        private const val DEFAULT_HIGHLIGHTER_STROKE_WIDTH = 8f
    }

    // ── Paper Style ──────────────────────────────────────────────────────────────
    private val _paperStyle = MutableStateFlow(PaperStyle())
    val paperStyle: StateFlow<PaperStyle> = _paperStyle.asStateFlow()

    /** Model coordinate width for this document. All DB coordinates are in [0, modelWidth]. */
    val modelWidth: Float get() = _paperStyle.value.widthPt
    /** Model coordinate height for this document. All DB coordinates are in [0, modelHeight]. */
    val modelHeight: Float get() = _paperStyle.value.heightPt

    /**
     * Called once when a PDF is opened to set the model space to match the first page.
     * Must be called before any strokes are drawn.
     */
    fun initializePaperSize(w: Float, h: Float) {
        if (w > 0f && h > 0f) {
            _paperStyle.value = _paperStyle.value.copy(widthPt = w, heightPt = h)
            // NOTE: canvasW/canvasH are NOT updated here. They store the actual pixel
            // dimensions of the InkCanvas composable (set by setCanvasSize/onSizeChanged).
            // Overwriting them with PDF point values (e.g. 595) would break the
            // canvas-pixel ↔ model-space normalisation that every tool relies on.
        }
    }

    /** Updates the paper style (size + background template). */
    fun setPaperStyle(style: PaperStyle) {
        _paperStyle.value = style
        // NOTE: canvasW/canvasH remain as the actual pixel size from setCanvasSize.
        // modelWidth/modelHeight (derived from _paperStyle) change automatically.
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.setPaperStyle(documentUri, style)
        }
    }

    private val strokeDao: StrokeDao = db.strokeDao()
    private val textAnnotationDao = db.textAnnotationDao()
    private val imageAnnotationDao = db.imageAnnotationDao()

    // Canvas pixel dimensions — reported by InkCanvas via setCanvasSize().
    // Default to MODEL dimensions so normalisation is identity before the first size report.
    private var canvasW = modelWidth
    private var canvasH = modelHeight

    fun setCanvasSize(w: Float, h: Float) {
        if (w > 0f && h > 0f) {
            canvasW = w
            canvasH = h
        }
    }

    private val _selectedTool = MutableStateFlow(Tool.PEN)
    val selectedTool: StateFlow<Tool> = _selectedTool.asStateFlow()

    // For stylus button quick eraser: save the tool before button press
    private var toolBeforeStylusButton: Tool? = null

    private val _selectedColor = MutableStateFlow(Color(0xFF111827))
    val selectedColor: StateFlow<Color> = _selectedColor.asStateFlow()

    // Per-tool color memory (updated on init and whenever the user picks a color)
    private var penColor: Color = Color(0xFF111827)
    private var highlighterColor: Color = Color(0xFFFFC700)
    private var penStrokeWidth: Float = DEFAULT_PEN_STROKE_WIDTH
    private var highlighterStrokeWidth: Float = DEFAULT_HIGHLIGHTER_STROKE_WIDTH

    // 固定色盤：唯一顏色來源，取自設定頁（default_recent_colors）
    private val _palette = MutableStateFlow(
        listOf(
            Color(0xFF000000),
            Color(0xFFFFC700),
            Color(0xFFF87171),
            Color(0xFF4ADE80)
        )
    )
    val palette: StateFlow<List<Color>> = _palette.asStateFlow()

    private val _strokeWidth = MutableStateFlow(DEFAULT_PEN_STROKE_WIDTH)
    val strokeWidth: StateFlow<Float> = _strokeWidth.asStateFlow()

    private val _inputMode = MutableStateFlow(InputMode.FREE)
    val inputMode: StateFlow<InputMode> = _inputMode.asStateFlow()

    // 文件級縮放（整份同縮，Chrome 式）：Workspace 雙指寫入，InkCanvas 只讀判定。
    // pinchActive 為 true 時各畫筆迴圈必須棄筆；docZoom 變化即代表中途被縮放過。
    private val _docZoom = MutableStateFlow(1f)
    val docZoom: StateFlow<Float> = _docZoom.asStateFlow()
    fun setDocZoom(z: Float) { _docZoom.value = z }
    private val _pinchActive = MutableStateFlow(false)
    val pinchActive: StateFlow<Boolean> = _pinchActive.asStateFlow()
    fun setPinchActive(active: Boolean) { _pinchActive.value = active }

    // 二維平移的水平分量（px）：空白區單指＋雙指全域寫入，
    // 放手停留、跨頁保持；Workspace 負責鉗制（至少留一半紙在區內），這裡只存原始值。
    private val _panOffsetX = MutableStateFlow(0f)
    val panOffsetX: StateFlow<Float> = _panOffsetX.asStateFlow()
    fun setPanOffsetX(px: Float) { _panOffsetX.value = px }

    private val _quickSwipeEraserEnabled = MutableStateFlow(false)
    val quickSwipeEraserEnabled: StateFlow<Boolean> = _quickSwipeEraserEnabled.asStateFlow()

    private val _autoSwitchToPenAfterErase = MutableStateFlow(false)
    val autoSwitchToPenAfterErase: StateFlow<Boolean> = _autoSwitchToPenAfterErase.asStateFlow()

    // 擦除手勢級命中旗標 + 串行鎖：
    // Live erase（拖曳中每 24ms 一次）和手勢結尾的 final erase 是兩個併發協程，
    // live 先把墨刪光時 final 會看到「沒東西可刪」→ erasedAnything=false → 不切筆（偶發失效）。
    // 改手勢級累積：同手勢內任何一次命中都記旗，final 結束時再一次性判定切筆，
    // 切筆只發生在手勢結束（pointerInput key 不變，手勢不被中途重啟打斷）。
    // Mutex 保證 final 排在所有 live 之後執行，避免 final 搶先消費舊旗標。
    private val eraserMutex = Mutex()
    private val eraseHitPending = java.util.concurrent.atomic.AtomicBoolean(false)

    private val _strokeSpeedSensitivity = MutableStateFlow(1f)
    val strokeSpeedSensitivity: StateFlow<Float> = _strokeSpeedSensitivity.asStateFlow()

    // 粗細靈敏度（全域偏好，0=鈍 → 1=靈）：設定頁寫入，隨文件 VM 重建載入。
    // 映射到跟隨濾波 smoothOld = 0.95 - 0.45*r（預設 0.33 → 0.80）。
    private val _widthResponsiveness = MutableStateFlow(0.33f)
    val widthResponsiveness: StateFlow<Float> = _widthResponsiveness.asStateFlow()

    // 手指觸控落筆校正：只在手指模式(FREE)＋Touch 接觸時生效，觸控筆模式不受影響。
    // dp 為單位，套用時經 density 轉 px。設定頁寫全域 prefs，這裡隨文件 VM 重建載入。
    private val _touchCalEnabled = MutableStateFlow(false)
    val touchCalEnabled: StateFlow<Boolean> = _touchCalEnabled.asStateFlow()
    private val _touchCalDxDp = MutableStateFlow(0f)
    val touchCalDxDp: StateFlow<Float> = _touchCalDxDp.asStateFlow()
    private val _touchCalDyDp = MutableStateFlow(0f)
    val touchCalDyDp: StateFlow<Float> = _touchCalDyDp.asStateFlow()

    private val _selectedShapeSubType = MutableStateFlow(ShapeSubType.RECT)
    val selectedShapeSubType: StateFlow<ShapeSubType> = _selectedShapeSubType.asStateFlow()

    private val _selectedLassoSubType = MutableStateFlow(LassoSubType.FREEFORM)
    val selectedLassoSubType: StateFlow<LassoSubType> = _selectedLassoSubType.asStateFlow()

    fun onLassoSubTypeSelected(type: LassoSubType) {
        _selectedLassoSubType.value = type
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = settingsRepository.resolvePreferences(documentUri)
            withContext(Dispatchers.Main) {
                _selectedTool.value = prefs.tool
                penColor = Color(prefs.colorArgb)
                highlighterColor = Color(prefs.highlighterColorArgb)
                penStrokeWidth = prefs.penStrokeWidth
                highlighterStrokeWidth = prefs.highlighterStrokeWidth
                _selectedColor.value = if (prefs.tool == Tool.HIGHLIGHTER) highlighterColor else penColor
                _strokeWidth.value = strokeWidthFor(prefs.tool)
                _selectedShapeSubType.value = prefs.shapeSubType
                _inputMode.value = prefs.inputMode.let {
                    if (it == InputMode.PALM_REJECTION) InputMode.STYLUS_ONLY else it
                }
                _quickSwipeEraserEnabled.value = prefs.quickSwipeEraserEnabled
                _autoSwitchToPenAfterErase.value = prefs.autoSwitchToPenAfterErase
                _strokeSpeedSensitivity.value = prefs.strokeSpeedSensitivity
                _widthResponsiveness.value = settingsRepository.getWidthResponsiveness()
                _touchCalEnabled.value = prefs.touchCalEnabled
                _touchCalDxDp.value = prefs.touchCalDxDp
                _touchCalDyDp.value = prefs.touchCalDyDp
                _palette.value = prefs.palette.map { Color(it) }
                val restoredStyle = _paperStyle.value.copy(
                    background = prefs.background,
                    widthPt = prefs.paperWidthPt ?: _paperStyle.value.widthPt,
                    heightPt = prefs.paperHeightPt ?: _paperStyle.value.heightPt
                )
                _paperStyle.value = restoredStyle
                // NOTE: canvasW/canvasH must NOT be updated here.
                // They store the actual pixel dimensions of the InkCanvas widget,
                // reported by setCanvasSize() via onSizeChanged during layout.
                // restoredStyle.widthPt/heightPt are in PDF points (e.g. 595×842),
                // NOT screen pixels; writing them here would overwrite the correct
                // canvas size and cause strokes to appear magnified / offset.
            }
        }
    }

    fun cycleInputMode() {
        // 雙模式：手指模式 <-> 觸控筆模式（PALM_REJECTION 已併入觸控筆模式）
        val next = when (_inputMode.value) {
            InputMode.STYLUS_ONLY -> InputMode.FREE
            else -> InputMode.STYLUS_ONLY
        }
        _inputMode.value = next
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.setInputMode(documentUri, next)
        }
    }

    fun onQuickSwipeEraserEnabledChanged(enabled: Boolean) {
        _quickSwipeEraserEnabled.value = enabled
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.setQuickSwipeEraserEnabled(documentUri, enabled)
        }
    }

    fun onAutoSwitchToPenAfterEraseChanged(enabled: Boolean) {
        _autoSwitchToPenAfterErase.value = enabled
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.setAutoSwitchToPenAfterErase(documentUri, enabled)
        }
    }

    fun setStrokeSpeedSensitivity(sensitivity: Float) {
        _strokeSpeedSensitivity.value = sensitivity
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.setStrokeSpeedSensitivity(documentUri, sensitivity)
        }
    }

    fun onShapeSubTypeSelected(type: ShapeSubType) {
        _selectedShapeSubType.value = type
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.setShapeSubType(documentUri, type)
        }
    }

    private val _pageIndex = MutableStateFlow(0)
    val pageIndex: StateFlow<Int> = _pageIndex.asStateFlow()

    /**
     * 跨頁手勢鎖：InkCanvas 在筆/拖曳手勢 consume 後置 true，
     * Workspace 的 onScrollPage 在此期間忽略自動捲動帶來的作用頁切換，
     * 避免 InkCanvas 在手勢中途被 StaticPageOverlay 替換導致斷筆。
     * 手勢結束（提交/丟棄）必須清鎖；新手勢入口也會清鎖兜底。
     */
    private val _pageLock = MutableStateFlow(false)
    fun isPageLocked(): Boolean = _pageLock.value
    fun setPageLock(locked: Boolean) { _pageLock.value = locked }

    /**
     * 套索拖曳預覽旗標：LASSO 移動手勢進行中為 true。
     * Workspace 在此期間把作用頁 item 置頂（zIndex）並用 overlay 繪製選取預覽，
     * 拖出紙界的框/墨不會被後頁蓋住，放開提交後清旗。
     */
    private val _dragPreviewActive = MutableStateFlow(false)
    val dragPreviewActive: StateFlow<Boolean> = _dragPreviewActive.asStateFlow()
    fun setDragPreviewActive(active: Boolean) { _dragPreviewActive.value = active }

    private val _pendingStrokes = MutableStateFlow<Map<String, StrokeWithPoints>>(emptyMap())

    val currentStrokes: StateFlow<List<StrokeWithPoints>> = kotlinx.coroutines.flow.combine(
        pageIndex.flatMapLatest { index -> strokeDao.getStrokesForPage(documentUri, index) },
        _pendingStrokes
    ) { dbStrokes, pending ->
        val dbIds = dbStrokes.map { it.stroke.id }.toSet()
        val resolvedIds = pending.keys.intersect(dbIds)
        if (resolvedIds.isNotEmpty()) {
            _pendingStrokes.value = _pendingStrokes.value - resolvedIds
        }
        // 跨頁筆分段暫存時，只顯示屬於作用頁的那段，避免 B 頁的墨鬼影到 A 頁
        val activePage = pageIndex.value
        val unresolved = pending.filterKeys { it !in dbIds }.values
            .filter { it.stroke.pageIndex == activePage }
        dbStrokes + unresolved
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentTextAnnotations: StateFlow<List<TextAnnotationEntity>> = pageIndex.flatMapLatest { index ->
        textAnnotationDao.getForPage(documentUri, index)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentImageAnnotations: StateFlow<List<ImageAnnotationEntity>> = pageIndex.flatMapLatest { index ->
        imageAnnotationDao.getForPage(documentUri, index)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Undo / Redo stacks
    private val undoStack: ArrayDeque<DrawCommand> = ArrayDeque()
    private val redoStack: ArrayDeque<DrawCommand> = ArrayDeque()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private fun pushUndo(command: DrawCommand) {
        undoStack.addLast(command)
        redoStack.clear()
        _canUndo.value = true
        _canRedo.value = false
    }

    fun onToolSelected(tool: Tool) {
        val previousTool = _selectedTool.value
        _selectedTool.value = tool
        if (previousTool == Tool.LASSO && tool != Tool.LASSO) {
            clearSelection(keepLastRegion = true)
        }
        when (tool) {
            Tool.PEN -> {
                _selectedColor.value = penColor
                _strokeWidth.value = penStrokeWidth
            }
            Tool.HIGHLIGHTER -> {
                _selectedColor.value = highlighterColor
                _strokeWidth.value = highlighterStrokeWidth
            }
            else -> {}
        }
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.setTool(documentUri, tool)
        }
    }

    /**
     * Called when the stylus button is pressed.
     * Switches to ERASER tool and saves the current tool.
     */
    fun onStylusButtonPressed() {
        val currentTool = _selectedTool.value
        // Only switch if not already in eraser mode
        if (currentTool != Tool.ERASER) {
            toolBeforeStylusButton = currentTool
            // Directly switch to ERASER without saving to preferences
            _selectedTool.value = Tool.ERASER
        }
    }

    /**
     * Called when the stylus button is released.
     * Restores the tool that was active before the button press.
     */
    fun onStylusButtonReleased() {
        val previousTool = toolBeforeStylusButton
        if (previousTool != null) {
            toolBeforeStylusButton = null
            // Restore the previous tool
            onToolSelected(previousTool)
        }
    }

    fun setActivePage(index: Int) {
        _pageIndex.value = index
        prefetchPages(index, index)
    }

    /** 鄰頁預渲染快取：可視範圍 ±1 的已查筆跡，給 Workspace item 當 Flow 初始值，
     * 滑入視口第一幀就有墨，不再空白閃一下。版號守衛防亂序覆蓋，視窗外自動丟棄。 */
    data class NeighborPageData(
        val strokes: List<StrokeWithPoints> = emptyList(),
        val texts: List<TextAnnotationEntity> = emptyList(),
        val images: List<ImageAnnotationEntity> = emptyList()
    )
    private val _neighborCache = MutableStateFlow<Map<Int, NeighborPageData>>(emptyMap())
    fun cachedNeighbor(page: Int): NeighborPageData? = _neighborCache.value[page]

    /**
     * 各頁常駐熱流：同一頁在捲動中反覆組成/拆掉時不斷線、不重查，
     * 上游在無人訂閱 5 秒後自動停，有人回來即時吐舊值（無空白幀）。
     * 主線程調用（composition / setActivePage），map 增減只在主線程。
     */
    private val pageFlows = mutableMapOf<Int, StateFlow<NeighborPageData>>()
    fun pageDataFlow(page: Int): StateFlow<NeighborPageData> = pageFlows.getOrPut(page) {
        kotlinx.coroutines.flow.combine(
            strokeDao.getStrokesForPage(documentUri, page),
            textAnnotationDao.getForPage(documentUri, page),
            imageAnnotationDao.getForPage(documentUri, page)
        ) { s, t, i -> NeighborPageData(s, t, i) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), cachedNeighbor(page) ?: NeighborPageData())
    }

    private var prefetchGen = 0
    fun prefetchPages(firstVisible: Int, lastVisible: Int) {
        // 窗口 ±2：慢速拖時，進視口的前一站已查好，快取當初始值第一幀即畫
        val lo = (minOf(firstVisible, lastVisible) - 2).coerceAtLeast(0)
        val hi = maxOf(firstVisible, lastVisible) + 2
        // 熱流只留可視附近 ±3：滑走的頁放掉引用（已組成的 item 手上引用不受影響），記憶體有界
        pageFlows.keys.retainAll { it in lo - 2..hi + 2 }
        val gen = ++prefetchGen
        viewModelScope.launch(Dispatchers.IO) {
            val window = (lo..hi).filter { it >= 0 }
            val fresh = window.associateWith { p ->
                NeighborPageData(
                    strokes = strokeDao.getStrokesForPageSync(documentUri, p),
                    texts = textAnnotationDao.getForPageSync(documentUri, p),
                    images = imageAnnotationDao.getForPageSync(documentUri, p)
                )
            }
            if (gen == prefetchGen) _neighborCache.value = fresh
        }
    }

    fun onColorSelected(color: Color) {
        // 只能切換固定色盤內的顏色，不寫回任何持久層
        if (color !in _palette.value) return
        _selectedColor.value = color
        when (_selectedTool.value) {
            Tool.PEN -> penColor = color
            Tool.HIGHLIGHTER -> highlighterColor = color
            else -> {}
        }
    }

    fun onStrokeWidthChanged(width: Float) {
        _strokeWidth.value = width
        when (_selectedTool.value) {
            Tool.HIGHLIGHTER -> highlighterStrokeWidth = width
            Tool.PEN -> penStrokeWidth = width
            else -> return
        }
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.setStrokeWidth(documentUri, _selectedTool.value, width)
        }
    }

    private fun strokeWidthFor(tool: Tool): Float = when (tool) {
        Tool.HIGHLIGHTER -> highlighterStrokeWidth
        Tool.PEN -> penStrokeWidth
        else -> penStrokeWidth
    }

    fun saveStroke(
        points: List<StrokePoint>,
        color: Color,
        tool: Tool = Tool.PEN,
        baseWidth: Float = 5f,
        /** 跨頁筆分段歸屬。null = 作用頁（舊行為）。 */
        targetPage: Int? = null
    ) {
        if (points.size < 2) return
        val cW = canvasW
        val cH = canvasH
        val strokeId = UUID.randomUUID().toString()

        // Normalize immediately on the caller thread so the just-finished stroke can move
        // from the active layer to the pending layer without a visible gap on stylus lift.
        val scaleX = modelWidth / cW
        val scaleY = modelHeight / cH
        // 寬度用 zoom=1 基準歸一（× docZoom 還原）：座標仍用實際 canvas 尺寸映射，
        // 新墨在任何縮放下存入同樣的 model 寬，放大等比變粗。
        val widthScaleX = scaleX * _docZoom.value.coerceAtLeast(0.1f)
        val normalizedPoints = points.map {
            StrokePoint(it.x * scaleX, it.y * scaleY, it.width * widthScaleX)
        }
        val path = EnvelopeUtils.generateEnvelopePath(normalizedPoints)
        val bounds = path.getBounds()
        val strokeEntity = StrokeEntity(
            id = strokeId,
            documentUri = documentUri,
            pageIndex = targetPage ?: pageIndex.value,
            color = color.toArgb(),
            // Store the user-selected base width (normalized to model space) so PDF export
            // uses the correct line thickness rather than the velocity-derived per-point width.
            // 寬度同上用 zoom=1 基準。
            strokeWidth = baseWidth * widthScaleX,
            boundsLeft = bounds.left,
            boundsTop = bounds.top,
            boundsRight = bounds.right,
            boundsBottom = bounds.bottom,
            isHighlighter = tool == Tool.HIGHLIGHTER
        )
        val pointEntities = normalizedPoints.map {
            PointEntity(strokeId = strokeId, x = it.x, y = it.y, width = it.width)
        }
        val pendingStroke = StrokeWithPoints(strokeEntity, pointEntities)
        _pendingStrokes.value = _pendingStrokes.value + (strokeId to pendingStroke)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                db.withTransaction {
                    strokeDao.insertStroke(strokeEntity)
                    strokeDao.insertPoints(pointEntities)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _pendingStrokes.value = _pendingStrokes.value - strokeId
                }
                Log.e("EditorViewModel", "Failed to persist stroke $strokeId", e)
                return@launch
            }

            val command = DrawCommand.AddStroke(pendingStroke)
            withContext(Dispatchers.Main) { pushUndo(command) }
        }
    }

    /** 手勢被系統取消/縮放丟棄時清掉本手勢累積的命中，避免污染下次手勢。 */
    fun clearEraseHitPending() { eraseHitPending.set(false) }

    fun deleteStrokesIntersecting(
        eraserPointsCanvas: List<Offset>,
        switchToPenAfterEraseHit: Boolean = false,
        // 同一擦除手勢的中途命中是否計入切筆判定。quick-swipe（畫筆手勢兼職擦除）
        // 傳 false：它本來就是筆，不參與切筆，否則舊旗標會污染下一次真正的擦除手勢。
        markEraseHit: Boolean = true,
        /** 橡皮擦所在紙。null = 作用頁（舊行為）；全活頁下 InkCanvas 傳自己。 */
        page: Int? = null
    ) {        val cW = canvasW
        val cH = canvasH
        val targetPage = page ?: pageIndex.value
        // 主線程一次快照：熱流建流（動 map）+ 讀值都在這裡，協程內只用快照
        val targetData = pageDataFlow(targetPage).value
        // Pass the snapshot of eraser points to the coroutine
        val pointsCopy = eraserPointsCanvas.toList()
        viewModelScope.launch(Dispatchers.Default) {
            eraserMutex.withLock {
            // Scale eraser points from canvas-pixel space to model space before comparing.
            val scaleX = modelWidth / cW
            val scaleY = modelHeight / cH
            val modelEraserPoints = pointsCopy.map { Offset(it.x * scaleX, it.y * scaleY) }
            val intersectingStrokes = IntersectionUtils.findIntersectingStrokes(
                eraserPoints = modelEraserPoints,
                strokes = targetData.strokes
            )
            var erasedAnything = false
            if (intersectingStrokes.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    strokeDao.deleteStrokesByIds(intersectingStrokes.map { it.stroke.id })
                }
                val command = DrawCommand.RemoveStrokes(intersectingStrokes)
                withContext(Dispatchers.Main) { pushUndo(command) }
                erasedAnything = true
            }
            // Also erase text annotations whose model coords fall within the eraser bounds.
            var eMinX = Float.POSITIVE_INFINITY
            var eMinY = Float.POSITIVE_INFINITY
            var eMaxX = Float.NEGATIVE_INFINITY
            var eMaxY = Float.NEGATIVE_INFINITY
            for (p in modelEraserPoints) {
                if (p.x < eMinX) eMinX = p.x
                if (p.x > eMaxX) eMaxX = p.x
                if (p.y < eMinY) eMinY = p.y
                if (p.y > eMaxY) eMaxY = p.y
            }
            // 10f is eraser radius
            val eraserBounds = android.graphics.RectF(eMinX - 10f, eMinY - 10f, eMaxX + 10f, eMaxY + 10f)

            val hitTexts = targetData.texts.filter { ann ->
                // Estimate the bounding box of the text in model space.
                // isStamp = oversized emoji: treat as a square of fontSize × fontSize.
                // Regular text: width ≈ charCount × fontSize × 0.6, height ≈ fontSize × 1.2.
                // Y-axis: modelY is the text baseline, so the top edge is (modelY - height).
                val estimatedW = if (ann.isStamp) ann.fontSize else ann.text.length * ann.fontSize * 0.6f
                val estimatedH = if (ann.isStamp) ann.fontSize else ann.fontSize * 1.2f
                val annBounds = android.graphics.RectF(
                    ann.modelX,
                    ann.modelY - estimatedH,
                    ann.modelX + estimatedW,
                    ann.modelY
                )
                android.graphics.RectF.intersects(eraserBounds, annBounds)
            }
            hitTexts.forEach { ann ->
                withContext(Dispatchers.IO) { textAnnotationDao.deleteById(ann.id) }
                withContext(Dispatchers.Main) { pushUndo(DrawCommand.RemoveTextAnnotation(ann)) }
                erasedAnything = true
            }

            // 手勢級累積：本次命中先記旗（quick-swipe 不記，避免污染下次手勢）
            if (erasedAnything && markEraseHit) eraseHitPending.set(true)

            // 切筆只在手勢結尾判定一次：本次命中 或 同手勢稍早的 live 命中（Mutex 保證
            // final 最後執行，旗標一定已就位）。判定完即清旗，不留給下次手勢。
            if (switchToPenAfterEraseHit && _autoSwitchToPenAfterErase.value) {
                val hit = eraseHitPending.getAndSet(false)
                if (hit) {
                    withContext(Dispatchers.Main) {
                        // Do not override temporary stylus-button eraser state.
                        if (_selectedTool.value == Tool.ERASER && toolBeforeStylusButton == null) {
                            onToolSelected(Tool.PEN)
                        }
                    }
                }
            }
            } // eraserMutex
        }
    }

    /** Save a geometric shape (RECT / CIRCLE / LINE / ARROW) as a StrokeEntity. */
    fun saveShape(
        startPoint: Offset,
        endPoint: Offset,
        color: Color,
        strokeWidth: Float,
        targetPage: Int? = null
    ) {
        val cW = canvasW
        val cH = canvasH
        val shapeType = _selectedShapeSubType.value.name
        viewModelScope.launch(Dispatchers.IO) {
            val strokeId = UUID.randomUUID().toString()
            val scaleX = modelWidth / cW
            val scaleY = modelHeight / cH
            // 寬度用 zoom=1 基準歸一（× docZoom 還原），座標映射不動。
            val shapeWidthScale = scaleX * _docZoom.value.coerceAtLeast(0.1f)
            val p0 = Offset(startPoint.x * scaleX, startPoint.y * scaleY)
            val p1 = Offset(endPoint.x * scaleX, endPoint.y * scaleY)
            val strokeEntity = StrokeEntity(
                id = strokeId,
                documentUri = documentUri,
                pageIndex = targetPage ?: pageIndex.value,
                color = color.toArgb(),
                strokeWidth = strokeWidth * shapeWidthScale,
                boundsLeft = minOf(p0.x, p1.x),
                boundsTop = minOf(p0.y, p1.y),
                boundsRight = maxOf(p0.x, p1.x),
                boundsBottom = maxOf(p0.y, p1.y),
                isHighlighter = false,
                shapeType = shapeType
            )
            val pointEntities = listOf(
                PointEntity(strokeId = strokeId, x = p0.x, y = p0.y),
                PointEntity(strokeId = strokeId, x = p1.x, y = p1.y)
            )
            db.withTransaction {
                strokeDao.insertStroke(strokeEntity)
                strokeDao.insertPoints(pointEntities)
            }
            val command = DrawCommand.AddStroke(StrokeWithPoints(strokeEntity, pointEntities))
            withContext(Dispatchers.Main) { pushUndo(command) }
        }
    }

    fun addTextAnnotation(
        text: String,
        canvasX: Float,
        canvasY: Float,
        fontSize: Float,
        color: Color,
        isStamp: Boolean = false,
        /** 落點紙。null = 作用頁（舊行為）；全活頁下 InkCanvas 傳自己。 */
        targetPage: Int? = null
    ) {
        if (text.isBlank()) return
        val ann = TextAnnotationEntity(
            documentUri = documentUri,
            pageIndex = targetPage ?: pageIndex.value,
            text = text,
            modelX = canvasX * modelWidth / canvasW,
            modelY = canvasY * modelHeight / canvasH,
            fontSize = fontSize * modelWidth / canvasW,
            colorArgb = color.toArgb(),
            isStamp = isStamp
        )
        viewModelScope.launch(Dispatchers.IO) {
            textAnnotationDao.insert(ann)
            withContext(Dispatchers.Main) { pushUndo(DrawCommand.AddTextAnnotation(ann)) }
        }
    }

    /** M3：引入文字寫入 — 直接給 model 座標與目標頁（排版引擎已算好，不經 canvas 換算）。 */
    fun insertImportedText(docUri: String, targetPage: Int, text: String, modelX: Float, modelY: Float, fontSize: Float) {
        if (text.isBlank()) return
        val ann = TextAnnotationEntity(
            documentUri = docUri,
            pageIndex = targetPage,
            text = text,
            modelX = modelX,
            modelY = modelY,
            fontSize = fontSize
        )
        viewModelScope.launch(Dispatchers.IO) {
            textAnnotationDao.insert(ann)
            withContext(Dispatchers.Main) { pushUndo(DrawCommand.AddTextAnnotation(ann)) }
        }
    }

    fun commitTextAnnotationContent(id: String, newText: String) {
        val old = findTextAnnotation(id) ?: return
        if (old.text == newText || newText.isBlank()) return
        val updated = old.copy(text = newText)
        viewModelScope.launch(Dispatchers.IO) {
            textAnnotationDao.update(updated)
            withContext(Dispatchers.Main) { pushUndo(DrawCommand.EditTextAnnotation(old, updated)) }
        }
    }
    fun deleteTextAnnotation(id: String) {
        val ann = findTextAnnotation(id) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            textAnnotationDao.deleteById(id)
            withContext(Dispatchers.Main) { pushUndo(DrawCommand.RemoveTextAnnotation(ann)) }
        }
    }

    fun commitTextAnnotationMove(id: String, canvasDeltaX: Float, canvasDeltaY: Float) {
        if (canvasDeltaX == 0f && canvasDeltaY == 0f) return
        val old = findTextAnnotation(id) ?: return
        val scaleX = modelWidth / canvasW
        val scaleY = modelHeight / canvasH
        val updated = old.copy(
            modelX = old.modelX + canvasDeltaX * scaleX,
            modelY = old.modelY + canvasDeltaY * scaleY
        )
        viewModelScope.launch(Dispatchers.IO) {
            textAnnotationDao.update(updated)
            withContext(Dispatchers.Main) { pushUndo(DrawCommand.MoveTextAnnotation(old, updated)) }
        }
    }

    /**
     * 跨頁拖曳落點提交：文字整顆搬到 targetPage 的 (modelX, modelY)。
     * MoveTextAnnotation 存完整實體（含 pageIndex），undo/redo 天然支援跨頁。
     */
    fun commitTextAnnotationMoveToPage(id: String, targetPage: Int, modelX: Float, modelY: Float) {
        val old = findTextAnnotation(id) ?: return
        val updated = old.copy(
            pageIndex = targetPage.coerceAtLeast(0),
            modelX = modelX.coerceIn(0f, modelWidth),
            modelY = modelY.coerceIn(0f, modelHeight)
        )
        if (updated == old) return
        viewModelScope.launch(Dispatchers.IO) {
            textAnnotationDao.update(updated)
            withContext(Dispatchers.Main) { pushUndo(DrawCommand.MoveTextAnnotation(old, updated)) }
        }
    }

    fun commitTextAnnotationResize(id: String, newX: Float, newY: Float, newFontSizeModel: Float) {
        val old = findTextAnnotation(id) ?: return
        val clamped = newFontSizeModel.coerceAtLeast(4f)
        val updated = old.copy(
            modelX = newX,
            modelY = newY,
            fontSize = clamped
        )
        viewModelScope.launch(Dispatchers.IO) {
            textAnnotationDao.update(updated)
            withContext(Dispatchers.Main) { pushUndo(DrawCommand.ResizeTextAnnotation(old, updated)) }
        }
    }

    fun addImageAnnotation(
        uri: String,
        canvasX: Float,
        canvasY: Float,
        canvasWidth: Float,
        canvasHeight: Float,
        targetPage: Int? = null
    ) {
        val scaleX = modelWidth / canvasW
        val scaleY = modelHeight / canvasH
        val ann = ImageAnnotationEntity(
            documentUri = documentUri,
            pageIndex = targetPage ?: pageIndex.value,
            uri = uri,
            modelX = canvasX * scaleX,
            modelY = canvasY * scaleY,
            modelWidth = canvasWidth * scaleX,
            modelHeight = canvasHeight * scaleY
        )
        viewModelScope.launch(Dispatchers.IO) {
            imageAnnotationDao.insert(ann)
            withContext(Dispatchers.Main) { pushUndo(DrawCommand.AddImageAnnotation(ann)) }
        }
    }

    private fun computeInitialImageSize(imagePixelWidth: Int, imagePixelHeight: Int): Pair<Float, Float> {
        return if (imagePixelWidth > 0 && imagePixelHeight > 0) {
            val aspectRatio = imagePixelHeight.toFloat() / imagePixelWidth.toFloat()
            var w = modelWidth * 0.8f
            var h = w * aspectRatio
            if (h > modelHeight * 0.9f) {
                h = modelHeight * 0.9f
                w = h / aspectRatio
            }
            Pair(w, h)
        } else {
            Pair(modelWidth * 0.8f, modelHeight * 0.5f)
        }
    }

    private fun buildPlacedImageAnnotation(
        uri: String,
        targetPageIndex: Int,
        imagePixelWidth: Int,
        imagePixelHeight: Int,
        anchorModel: Offset? = null
    ): ImageAnnotationEntity {
        val (initW, initH) = computeInitialImageSize(imagePixelWidth, imagePixelHeight)
        // M4: anchor = tap point in model space → center the image on it (clamped in-page);
        // null keeps the legacy top-left default.
        val modelX = if (anchorModel != null)
            (anchorModel.x - initW / 2f).coerceIn(0f, maxOf(0f, modelWidth - initW))
        else modelWidth * 0.1f
        val modelY = if (anchorModel != null)
            (anchorModel.y - initH / 2f).coerceIn(0f, maxOf(0f, modelHeight - initH))
        else modelHeight * 0.1f
        return ImageAnnotationEntity(
            documentUri = documentUri,
            pageIndex = targetPageIndex,
            uri = uri,
            modelX = modelX,
            modelY = modelY,
            modelWidth = initW,
            modelHeight = initH
        )
    }

    suspend fun placeImageAnnotationOnPage(
        uri: String,
        targetPageIndex: Int,
        imagePixelWidth: Int = 0,
        imagePixelHeight: Int = 0,
        anchorModel: Offset? = null
    ): String {
        val ann = buildPlacedImageAnnotation(uri, targetPageIndex, imagePixelWidth, imagePixelHeight, anchorModel)
        withContext(Dispatchers.IO) {
            imageAnnotationDao.insert(ann)
        }
        withContext(Dispatchers.Main) {
            pushUndo(DrawCommand.AddImageAnnotation(ann))
        }
        return ann.id
    }

    /** Places an image at the center of the model canvas (used when picked via gallery). Returns the new annotation ID. */
    fun placeImageAnnotation(
        uri: String,
        imagePixelWidth: Int = 0,
        imagePixelHeight: Int = 0,
        targetPage: Int? = null
    ): String {
        val ann = buildPlacedImageAnnotation(uri, targetPage ?: pageIndex.value, imagePixelWidth, imagePixelHeight)
        viewModelScope.launch(Dispatchers.IO) {
            imageAnnotationDao.insert(ann)
            withContext(Dispatchers.Main) { pushUndo(DrawCommand.AddImageAnnotation(ann)) }
        }
        return ann.id
    }

    fun commitImageAnnotationMove(id: String, canvasDeltaX: Float, canvasDeltaY: Float) {
        if (canvasDeltaX == 0f && canvasDeltaY == 0f) return
        val old = findImageAnnotation(id) ?: return
        val scaleX = modelWidth / canvasW
        val scaleY = modelHeight / canvasH
        val updated = old.copy(
            modelX = old.modelX + canvasDeltaX * scaleX,
            modelY = old.modelY + canvasDeltaY * scaleY
        )
        viewModelScope.launch(Dispatchers.IO) {
            imageAnnotationDao.update(updated)
            withContext(Dispatchers.Main) { pushUndo(DrawCommand.MoveImageAnnotation(old, updated)) }
        }
    }

    /**
     * 跨頁拖曳落點提交：圖片整顆搬到 targetPage，左上角落在 (modelX, modelY)。
     * MoveImageAnnotation 存完整實體（含 pageIndex），undo/redo 天然支援跨頁。
     */
    fun commitImageAnnotationMoveToPage(
        id: String,
        targetPage: Int,
        modelX: Float,
        modelY: Float
    ) {
        val old = findImageAnnotation(id) ?: return
        val updated = old.copy(
            pageIndex = targetPage.coerceAtLeast(0),
            modelX = modelX.coerceIn(0f, modelWidth),
            modelY = modelY.coerceIn(0f, modelHeight)
        )
        if (updated == old) return
        viewModelScope.launch(Dispatchers.IO) {
            imageAnnotationDao.update(updated)
            withContext(Dispatchers.Main) { pushUndo(DrawCommand.MoveImageAnnotation(old, updated)) }
        }
    }

    fun commitImageAnnotationResize(id: String, newX: Float, newY: Float, newModelWidth: Float, newModelHeight: Float) {
        val old = findImageAnnotation(id) ?: return
        val updated = old.copy(
            modelX = newX,
            modelY = newY,
            modelWidth  = newModelWidth.coerceAtLeast(30f),
            modelHeight = newModelHeight.coerceAtLeast(30f)
        )
        viewModelScope.launch(Dispatchers.IO) {
            imageAnnotationDao.update(updated)
            withContext(Dispatchers.Main) { pushUndo(DrawCommand.ResizeImageAnnotation(old, updated)) }
        }
    }

    /** M5: commits a rotation (clockwise degrees). Rides ResizeImageAnnotation for undo/redo. */
    fun commitImageAnnotationRotation(id: String, degrees: Float) {
        val old = findImageAnnotation(id) ?: return
        val normalized = ((degrees % 360f) + 360f) % 360f
        if (old.rotation == normalized) return
        val updated = old.copy(rotation = normalized)
        viewModelScope.launch(Dispatchers.IO) {
            imageAnnotationDao.update(updated)
            withContext(Dispatchers.Main) { pushUndo(DrawCommand.ResizeImageAnnotation(old, updated)) }
        }
    }

    fun deleteImageAnnotation(id: String) {
        val ann = findImageAnnotation(id) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            imageAnnotationDao.deleteById(id)
            withContext(Dispatchers.Main) { pushUndo(DrawCommand.RemoveImageAnnotation(ann)) }
        }
    }

    fun undo() {
        val command = undoStack.removeLastOrNull() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            when (command) {
                is DrawCommand.AddStroke -> {
                    strokeDao.deleteStrokesByIds(listOf(command.stroke.stroke.id))
                }
                is DrawCommand.RemoveStrokes -> {
                    command.strokes.forEach { strokeWithPoints ->
                        db.withTransaction {
                            strokeDao.insertStroke(strokeWithPoints.stroke)
                            strokeDao.insertPoints(strokeWithPoints.points)
                        }
                    }
                }
                is DrawCommand.MoveStrokes -> {
                    command.originals.forEach { swp ->
                        db.withTransaction {
                            strokeDao.deletePointsForStroke(swp.stroke.id)
                            strokeDao.insertStroke(swp.stroke)
                            strokeDao.insertPoints(swp.points)
                        }
                    }
                }
                is DrawCommand.ResizeStrokes -> {
                    command.originals.forEach { swp ->
                        db.withTransaction {
                            strokeDao.deletePointsForStroke(swp.stroke.id)
                            strokeDao.insertStroke(swp.stroke)
                            strokeDao.insertPoints(swp.points)
                        }
                    }
                }
                is DrawCommand.AddTextAnnotation -> {
                    textAnnotationDao.deleteById(command.annotation.id)
                }
                is DrawCommand.RemoveTextAnnotation -> {
                    textAnnotationDao.insert(command.annotation)
                }
                is DrawCommand.MoveTextAnnotation -> {
                    textAnnotationDao.update(command.original)
                }
                is DrawCommand.ResizeTextAnnotation -> {
                    textAnnotationDao.update(command.original)
                }
                is DrawCommand.EditTextAnnotation -> {
                    textAnnotationDao.update(command.original)
                }
                is DrawCommand.AddImageAnnotation -> {
                    imageAnnotationDao.deleteById(command.annotation.id)
                }
                is DrawCommand.RemoveImageAnnotation -> {
                    imageAnnotationDao.insert(command.annotation)
                }
                is DrawCommand.MoveImageAnnotation -> {
                    imageAnnotationDao.update(command.original)
                }
                is DrawCommand.ResizeImageAnnotation -> {
                    imageAnnotationDao.update(command.original)
                }
                is DrawCommand.MoveSelectionMixed -> {
                    command.strokeOriginals.forEach { swp ->
                        db.withTransaction {
                            strokeDao.deletePointsForStroke(swp.stroke.id)
                            strokeDao.insertStroke(swp.stroke)
                            strokeDao.insertPoints(swp.points)
                        }
                    }
                    command.imageOriginals.forEach { imageAnnotationDao.update(it) }
                }
                is DrawCommand.ResizeSelectionMixed -> {
                    command.strokeOriginals.forEach { swp ->
                        db.withTransaction {
                            strokeDao.deletePointsForStroke(swp.stroke.id)
                            strokeDao.insertStroke(swp.stroke)
                            strokeDao.insertPoints(swp.points)
                        }
                    }
                    command.imageOriginals.forEach { imageAnnotationDao.update(it) }
                }
                is DrawCommand.AddSelectionCopies -> {
                    if (command.strokes.isNotEmpty()) {
                        strokeDao.deleteStrokesByIds(command.strokes.map { it.stroke.id })
                    }
                    command.images.forEach { imageAnnotationDao.deleteById(it.id) }
                }
                is DrawCommand.RemoveSelectionMixed -> {
                    command.strokes.forEach { strokeWithPoints ->
                        db.withTransaction {
                            strokeDao.insertStroke(strokeWithPoints.stroke)
                            strokeDao.insertPoints(strokeWithPoints.points)
                        }
                    }
                    command.images.forEach { imageAnnotationDao.insert(it) }
                }
            }
            withContext(Dispatchers.Main) {
                redoStack.addLast(command)
                _canUndo.value = undoStack.isNotEmpty()
                _canRedo.value = true
            }
        }
    }

    fun redo() {
        val command = redoStack.removeLastOrNull() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            when (command) {
                is DrawCommand.AddStroke -> {
                    db.withTransaction {
                        strokeDao.insertStroke(command.stroke.stroke)
                        strokeDao.insertPoints(command.stroke.points)
                    }
                }
                is DrawCommand.RemoveStrokes -> {
                    strokeDao.deleteStrokesByIds(command.strokes.map { it.stroke.id })
                }
                is DrawCommand.MoveStrokes -> {
                    // 跨頁移動：updated 帶新 pageIndex，直接恢復快照；
                    // 單頁移動：updated 為 null，走舊 delta 重放。
                    val crossSnap = command.updated
                    if (crossSnap != null) {
                        replaceStrokeSnapshots(crossSnap)
                    } else {
                        command.originals.forEach { swp ->
                            val shiftedPoints = swp.points.map { pt ->
                                PointEntity(strokeId = swp.stroke.id, x = pt.x + command.delta.x, y = pt.y + command.delta.y, width = pt.width)
                            }
                            val shiftedStroke = swp.stroke.copy(
                                boundsLeft   = swp.stroke.boundsLeft   + command.delta.x,
                                boundsTop    = swp.stroke.boundsTop    + command.delta.y,
                                boundsRight  = swp.stroke.boundsRight  + command.delta.x,
                                boundsBottom = swp.stroke.boundsBottom + command.delta.y
                            )
                            db.withTransaction {
                                strokeDao.deletePointsForStroke(swp.stroke.id)
                                strokeDao.insertStroke(shiftedStroke)
                                strokeDao.insertPoints(shiftedPoints)
                            }
                        }
                    }
                }
                is DrawCommand.ResizeStrokes -> {
                    command.updated.forEach { swp ->
                        db.withTransaction {
                            strokeDao.deletePointsForStroke(swp.stroke.id)
                            strokeDao.insertStroke(swp.stroke)
                            strokeDao.insertPoints(swp.points)
                        }
                    }
                }
                is DrawCommand.AddTextAnnotation -> {
                    textAnnotationDao.insert(command.annotation)
                }
                is DrawCommand.RemoveTextAnnotation -> {
                    textAnnotationDao.deleteById(command.annotation.id)
                }
                is DrawCommand.MoveTextAnnotation -> {
                    textAnnotationDao.update(command.updated)
                }
                is DrawCommand.ResizeTextAnnotation -> {
                    textAnnotationDao.update(command.updated)
                }
                is DrawCommand.EditTextAnnotation -> {
                    textAnnotationDao.update(command.updated)
                }
                is DrawCommand.AddImageAnnotation -> {
                    imageAnnotationDao.insert(command.annotation)
                }
                is DrawCommand.RemoveImageAnnotation -> {
                    imageAnnotationDao.deleteById(command.annotation.id)
                }
                is DrawCommand.MoveImageAnnotation -> {
                    imageAnnotationDao.update(command.updated)
                }
                is DrawCommand.ResizeImageAnnotation -> {
                    imageAnnotationDao.update(command.updated)
                }
                is DrawCommand.MoveSelectionMixed -> {
                    command.strokeUpdated.forEach { swp ->
                        db.withTransaction {
                            strokeDao.deletePointsForStroke(swp.stroke.id)
                            strokeDao.insertStroke(swp.stroke)
                            strokeDao.insertPoints(swp.points)
                        }
                    }
                    command.imageUpdated.forEach { imageAnnotationDao.update(it) }
                }
                is DrawCommand.ResizeSelectionMixed -> {
                    command.strokeUpdated.forEach { swp ->
                        db.withTransaction {
                            strokeDao.deletePointsForStroke(swp.stroke.id)
                            strokeDao.insertStroke(swp.stroke)
                            strokeDao.insertPoints(swp.points)
                        }
                    }
                    command.imageUpdated.forEach { imageAnnotationDao.update(it) }
                }
                is DrawCommand.AddSelectionCopies -> {
                    command.strokes.forEach { strokeWithPoints ->
                        db.withTransaction {
                            strokeDao.insertStroke(strokeWithPoints.stroke)
                            strokeDao.insertPoints(strokeWithPoints.points)
                        }
                    }
                    command.images.forEach { imageAnnotationDao.insert(it) }
                }
                is DrawCommand.RemoveSelectionMixed -> {
                    if (command.strokes.isNotEmpty()) {
                        strokeDao.deleteStrokesByIds(command.strokes.map { it.stroke.id })
                    }
                    command.images.forEach { imageAnnotationDao.deleteById(it.id) }
                }
            }
            withContext(Dispatchers.Main) {
                undoStack.addLast(command)
                _canUndo.value = true
                _canRedo.value = redoStack.isNotEmpty()
            }
        }
    }

    // Lasso selection state
    private val _selectedStrokes = MutableStateFlow<List<StrokeWithPoints>>(emptyList())
    val selectedStrokes: StateFlow<List<StrokeWithPoints>> = _selectedStrokes.asStateFlow()

    private val _selectedImageAnnotationIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedImageAnnotationIds: StateFlow<Set<String>> = _selectedImageAnnotationIds.asStateFlow()

    private val _selectedStrokePreview = MutableStateFlow<List<StrokeWithPoints>>(emptyList())
    val selectedStrokePreview: StateFlow<List<StrokeWithPoints>> = _selectedStrokePreview.asStateFlow()

    private val _lassoPolygon = MutableStateFlow<List<Offset>>(emptyList())
    val lassoPolygon: StateFlow<List<Offset>> = _lassoPolygon.asStateFlow()

    private val _selectionFramePolygon = MutableStateFlow<List<Offset>>(emptyList())
    val selectionFramePolygon: StateFlow<List<Offset>> = _selectionFramePolygon.asStateFlow()

    private val _lastLassoPolygon = MutableStateFlow<List<Offset>>(emptyList())
    val lastLassoPolygon: StateFlow<List<Offset>> = _lastLassoPolygon.asStateFlow()

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
    private val _commitPreview = MutableStateFlow<List<StrokeWithPoints>?>(null)
    val commitPreview: StateFlow<List<StrokeWithPoints>?> = _commitPreview.asStateFlow()

    // Guard extraction workflow from accidental re-entry (e.g., rapid double taps).
    private val extractionMutex = Mutex()

    // 座標/多邊形/命中測試見 SelectionGeometry.kt

    private suspend fun replaceStrokeSnapshots(strokes: List<StrokeWithPoints>) {
        db.withTransaction {
            strokes.forEach { swp ->
                strokeDao.deletePointsForStroke(swp.stroke.id)
                strokeDao.insertStroke(swp.stroke)
                strokeDao.insertPoints(swp.points)
            }
        }
    }

    // transformPolygon 見 SelectionGeometry.kt

    private fun currentSelectionAnchor(originals: List<StrokeWithPoints>): Offset {
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

    fun selectStrokesInLasso(polygon: List<Offset>, page: Int? = null) {
        val cW = canvasW
        val cH = canvasH
        // 主線程一次快照：圈選所在紙的資料（全活頁下 InkCanvas 傳自己）
        val targetData = pageDataFlow(page ?: pageIndex.value).value

        // Compose already applies the graphicsLayer inverse transform when routing
        // screen-space touches to InkCanvas (a child of the graphicsLayer-modified
        // Surface), so `polygon` arrives in canvas layout-space — the same space that
        // saveStroke() normalises with p.x * modelWidth / canvasW.  Applying the inverse
        // a second time would double-invert and shift the lasso at any zoom ≠ 1.
        val normalizedPolygon = polygon.map { Offset(it.x * modelWidth / cW, it.y * modelHeight / cH) }
        _lassoPolygon.value = normalizedPolygon
        _selectionFramePolygon.value = normalizedPolygon
        _lastLassoPolygon.value = normalizedPolygon
        viewModelScope.launch(Dispatchers.Default) {
            val selected = IntersectionUtils.findStrokesInLasso(normalizedPolygon, targetData.strokes)
            val selectedImages = targetData.images.filter { ann ->
                isImageSelectedByLasso(ann, normalizedPolygon)
            }
            withContext(Dispatchers.Main) {
                _selectedStrokes.value = selected
                _selectedImageAnnotationIds.value = selectedImages.map { it.id }.toSet()
                _lassoMoveOffset.value = Offset.Zero
                _selectedStrokeScale.value = 1f
                _selectedStrokeResizeAnchor.value = null
                _selectedStrokePreview.value = selected
                _selectedStrokePreviewBounds.value = StrokeTransformUtils.computeSelectionBounds(selected)
            }
        }
    }

    // isPointInPolygon / rotatedImageBounds / isImageSelectedByLasso 見 SelectionGeometry.kt

    fun moveSelectedStrokes(delta: Offset) {
        if (delta == Offset.Zero) return
        // Normalise drag delta to model space so it lines up with stored stroke coordinates.
        val normalizedDelta = Offset(delta.x * modelWidth / canvasW, delta.y * modelHeight / canvasH)
        _lassoMoveOffset.value = _lassoMoveOffset.value + normalizedDelta
        refreshSelectedStrokePreview()
    }

    fun beginSelectedStrokeResize(anchorCanvas: Offset) {
        _selectedStrokeResizeAnchor.value = canvasToModel(
            anchorCanvas, modelWidth, modelHeight, canvasW, canvasH
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
        val delta = _lassoMoveOffset.value
        if ((strokes.isEmpty() && images.isEmpty()) || (delta.x == 0f && delta.y == 0f)) {
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

        // 跨頁判定：整體中心掉出本頁 → 整組換頁（y 繞回），只認縱向
        val centerSamples = mutableListOf<Float>()
        StrokeTransformUtils.computeSelectionBounds(movedStrokes)?.let { centerSamples.add(it.center.y) }
        movedImages.forEach { centerSamples.add(it.modelY + it.modelHeight / 2f) }
        val centerY = if (centerSamples.isNotEmpty()) centerSamples.average().toFloat() else modelHeight / 2f
        val sourcePage = selectionPage()
        val targetPage = (sourcePage + kotlin.math.floor(centerY / modelHeight).toInt())
            .coerceIn(0, maxPageIndex.coerceAtLeast(0))
        val appliedShift = targetPage - sourcePage
        val crossPage = appliedShift != 0
        if (crossPage) {
            val wrapY = appliedShift * modelHeight
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
                    modelX = ann.modelX.coerceIn(0f, modelWidth),
                    modelY = ann.modelY.coerceIn(0f, modelHeight)
                )
            }
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
            val wrapY = appliedShift * modelHeight
            movedPolygon.map { Offset(it.x, it.y - wrapY) }
        } else {
            movedPolygon
        }
        _lassoPolygon.value = committedSelectionPolygon
        _selectionFramePolygon.value = committedSelectionPolygon
        _lastLassoPolygon.value = committedSelectionPolygon
        _selectedStrokePreviewBounds.value = StrokeTransformUtils.computeSelectionBounds(movedStrokes)

        _commitPreview.value = movedStrokes.takeIf { it.isNotEmpty() }

        viewModelScope.launch(Dispatchers.IO) {
            if (movedStrokes.isNotEmpty()) {
                replaceStrokeSnapshots(movedStrokes)
            }
            movedImages.forEach { imageAnnotationDao.update(it) }
            withContext(Dispatchers.Main) {
                if (_commitPreview.value === movedStrokes) {
                    _commitPreview.value = null
                }
                when {
                    images.isEmpty() && !crossPage -> pushUndo(DrawCommand.MoveStrokes(strokes, delta))
                    images.isEmpty() -> pushUndo(
                        DrawCommand.MoveStrokes(strokes, delta, movedStrokes)
                    )
                    else -> pushUndo(
                        DrawCommand.MoveSelectionMixed(
                            strokeOriginals = strokes,
                            strokeUpdated = movedStrokes,
                            imageOriginals = images,
                            imageUpdated = movedImages
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
        val translation = _lassoMoveOffset.value
        val scale = _selectedStrokeScale.value
        if ((originals.isEmpty() && images.isEmpty()) || (kotlin.math.abs(scale - 1f) < 0.001f && translation == Offset.Zero)) {
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

        // Keep selection active
        _selectedStrokes.value = updated
        _selectedStrokePreview.value = updated
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
        _selectedStrokePreviewBounds.value = StrokeTransformUtils.computeSelectionBounds(updated)

        _commitPreview.value = updated.takeIf { it.isNotEmpty() }
        viewModelScope.launch(Dispatchers.IO) {
            if (updated.isNotEmpty()) {
                replaceStrokeSnapshots(updated)
            }
            updatedImages.forEach { imageAnnotationDao.update(it) }
            withContext(Dispatchers.Main) {
                if (_commitPreview.value === updated) {
                    _commitPreview.value = null
                }
                when {
                    images.isEmpty() -> pushUndo(DrawCommand.ResizeStrokes(originals, updated))
                    else -> pushUndo(
                        DrawCommand.ResizeSelectionMixed(
                            strokeOriginals = originals,
                            strokeUpdated = updated,
                            imageOriginals = images,
                            imageUpdated = updatedImages
                        )
                    )
                }
            }
        }
    }

    /**
     * 全活頁查找：在常駐熱流裡按 id 找註解（跨紙可用），找不到才退回作用頁流。
     * M1 全活頁基礎：InkCanvas 只認自己的紙，不再假設東西都在作用頁。
     */
    private fun findTextAnnotation(id: String): TextAnnotationEntity? {
        for (flow in pageFlows.values) {
            flow.value.texts.firstOrNull { it.id == id }?.let { return it }
        }
        return currentTextAnnotations.value.firstOrNull { it.id == id }
    }

    private fun findImageAnnotation(id: String): ImageAnnotationEntity? {
        for (flow in pageFlows.values) {
            flow.value.images.firstOrNull { it.id == id }?.let { return it }
        }
        return currentImageAnnotations.value.firstOrNull { it.id == id }
    }

    /** 選取歸屬頁：筆看第一筆的頁，純圖選看圖所在頁，無選取退回作用頁。 */
    fun selectionPage(): Int {
        _selectedStrokes.value.firstOrNull()?.let { return it.stroke.pageIndex }
        val ids = _selectedImageAnnotationIds.value
        if (ids.isNotEmpty()) {
            for (flow in pageFlows.values) {
                if (flow.value.images.any { it.id in ids }) {
                    return flow.value.images.first { it.id in ids }.pageIndex
                }
            }
            currentImageAnnotations.value.firstOrNull { it.id in ids }?.let { return it.pageIndex }
        }
        return pageIndex.value
    }

    private fun selectedImagesSnapshot(): List<ImageAnnotationEntity> {
        val ids = _selectedImageAnnotationIds.value
        if (ids.isEmpty()) return emptyList()
        val found = mutableListOf<ImageAnnotationEntity>()
        for (flow in pageFlows.values) {
            found += flow.value.images.filter { it.id in ids }
        }
        if (found.isNotEmpty()) return found
        return currentImageAnnotations.value.filter { it.id in ids }
    }

    fun deleteSelection() {
        val selectedStrokeSnapshots = _selectedStrokes.value
        val selectedImageSnapshots = selectedImagesSnapshot()
        if (selectedStrokeSnapshots.isEmpty() && selectedImageSnapshots.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            if (selectedStrokeSnapshots.isNotEmpty()) {
                strokeDao.deleteStrokesByIds(selectedStrokeSnapshots.map { it.stroke.id })
            }
            selectedImageSnapshots.forEach { imageAnnotationDao.deleteById(it.id) }
            withContext(Dispatchers.Main) {
                clearSelection(keepLastRegion = true)
                pushUndo(DrawCommand.RemoveSelectionMixed(selectedStrokeSnapshots, selectedImageSnapshots))
            }
        }
    }

    fun copySelectionInPlace() {
        val sourceStrokes = _selectedStrokes.value
        val sourceImages = selectedImagesSnapshot()
        if (sourceStrokes.isEmpty() && sourceImages.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            val copiedStrokes = sourceStrokes.map { original ->
                val newStrokeId = UUID.randomUUID().toString()
                val copiedStroke = original.stroke.copy(id = newStrokeId)
                val copiedPoints = original.points.map { pt ->
                    pt.copy(id = 0, strokeId = newStrokeId)
                }
                db.withTransaction {
                    strokeDao.insertStroke(copiedStroke)
                    strokeDao.insertPoints(copiedPoints)
                }
                StrokeWithPoints(copiedStroke, copiedPoints)
            }

            val copiedImages = sourceImages.map { original ->
                val copied = original.copy(id = UUID.randomUUID().toString())
                imageAnnotationDao.insert(copied)
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
                pushUndo(DrawCommand.AddSelectionCopies(copiedStrokes, copiedImages))
            }
        }
    }

    fun clearSelection(keepLastRegion: Boolean = false) {
        _selectedStrokes.value = emptyList()
        _selectedImageAnnotationIds.value = emptySet()
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

    /**
     * Shared pipeline for both lasso extraction flows: renders the full-page
     * composite (PDF layer + image/stroke/text annotations), crops it to the
     * lasso bounding box, applies the polygon mask and trims transparent
     * edges. Returns the trimmed bitmap or null when the region is degenerate
     * or the PDF layer is unavailable.
     */
    private suspend fun renderLassoExtraction(
        context: android.content.Context,
        sourcePageIndex: Int,
        pdfPageBitmap: android.graphics.Bitmap?,
        polygon: List<Offset>
    ): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        val sourceStrokes = strokeDao.getStrokesForPage(documentUri, sourcePageIndex).first()
        val sourceImageAnnotations = imageAnnotationDao.getForPage(documentUri, sourcePageIndex).first()
        val sourceTextAnnotations = textAnnotationDao.getForPage(documentUri, sourcePageIndex).first()

        val minX = polygon.minOf { it.x }
        val minY = polygon.minOf { it.y }
        val maxX = polygon.maxOf { it.x }
        val maxY = polygon.maxOf { it.y }
        if (maxX <= minX || maxY <= minY) return@withContext null

        val cropW = maxX - minX
        val cropH = maxY - minY

        // ── Step 1: Render full model page onto a bitmap ──────────────────────
        // Each model unit = renderScale pixels; capped so huge pages cannot OOM.
        val renderScale = minOf(2f, 4096f / maxOf(modelWidth, modelHeight))
        val fullW = (modelWidth * renderScale).toInt()
        val fullH = (modelHeight * renderScale).toInt()
        val fullBitmap = android.graphics.Bitmap.createBitmap(
            fullW, fullH, android.graphics.Bitmap.Config.ARGB_8888
        )
        val fullCanvas = android.graphics.Canvas(fullBitmap)
        fullCanvas.scale(renderScale, renderScale)
        fullCanvas.drawColor(android.graphics.Color.WHITE)

        // PDF layer: prefer UI snapshot; if unavailable, render directly from source PDF.
        // Track whether the bitmap was created locally so we can recycle it after drawing.
        val localFallbackBitmap = if (pdfPageBitmap == null) renderPdfPageFromDocumentUri(sourcePageIndex, fullW, fullH) else null
        val resolvedPdfBitmap = pdfPageBitmap ?: localFallbackBitmap
        if (resolvedPdfBitmap == null) {
            // Avoid generating a wrong composite (missing PDF layer).
            return@withContext null
        }
        fullCanvas.drawBitmap(
            resolvedPdfBitmap,
            android.graphics.Rect(0, 0, resolvedPdfBitmap.width, resolvedPdfBitmap.height),
            android.graphics.RectF(0f, 0f, modelWidth, modelHeight),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        )
        // Caller-owned pdfPageBitmap must NOT be recycled here (it lives in PdfViewModel's LruCache).
        localFallbackBitmap?.recycle()

        // Image annotations layer
        for (img in sourceImageAnnotations) {
            val bmp = loadBitmapFromUri(context, img.uri)
            if (bmp != null) {
                fullCanvas.drawBitmap(
                    bmp, null,
                    android.graphics.RectF(img.modelX, img.modelY, img.modelX + img.modelWidth, img.modelY + img.modelHeight),
                    android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                )
                bmp.recycle()
            }
        }

        // Strokes layer
        val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        for (swp in sourceStrokes) {
            val isHL = swp.stroke.isHighlighter
            strokePaint.color = swp.stroke.color
            strokePaint.strokeWidth = swp.stroke.strokeWidth * (if (isHL) 3f else 1f)
            strokePaint.alpha = if (isHL) (255 * 0.4f).toInt() else 255
            strokePaint.xfermode = if (isHL) android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.MULTIPLY) else null

            if (swp.stroke.shapeType != null) {
                val r = android.graphics.RectF(swp.stroke.boundsLeft, swp.stroke.boundsTop, swp.stroke.boundsRight, swp.stroke.boundsBottom)
                when (swp.stroke.shapeType) {
                    "RECT"  -> fullCanvas.drawRect(r, strokePaint)
                    "OVAL"  -> fullCanvas.drawOval(r, strokePaint)
                    "LINE", "ARROW" -> if (swp.points.size >= 2) {
                        val p0 = swp.points.first(); val p1 = swp.points.last()
                        fullCanvas.drawLine(p0.x, p0.y, p1.x, p1.y, strokePaint)
                    }
                }
            } else {
                val pts = swp.points
                if (pts.size >= 2) {
                    val path = android.graphics.Path()
                    path.moveTo(pts[0].x, pts[0].y)
                    if (pts.size < 3) {
                        for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                    } else {
                        for (i in 1 until pts.size) {
                            val prev = pts[i - 1]; val curr = pts[i]
                            path.quadTo(prev.x, prev.y, (prev.x + curr.x) / 2f, (prev.y + curr.y) / 2f)
                        }
                        path.lineTo(pts.last().x, pts.last().y)
                    }
                    fullCanvas.drawPath(path, strokePaint)
                }
            }
        }

        // Text / stamp layer
        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
        }
        for (txt in sourceTextAnnotations) {
            textPaint.textSize = txt.fontSize
            textPaint.color = txt.colorArgb
            var y = txt.modelY + txt.fontSize
            txt.text.split("\n").forEach { line ->
                fullCanvas.drawText(line, txt.modelX, y, textPaint)
                y += txt.fontSize * 1.2f
            }
        }

        // ── Step 2: Crop lasso bounding box from full bitmap ──────────────────
        val cropPixX    = (minX * renderScale).toInt().coerceIn(0, fullW - 1)
        val cropPixY    = (minY * renderScale).toInt().coerceIn(0, fullH - 1)
        val cropPixW    = (cropW * renderScale).toInt().coerceAtLeast(1).coerceAtMost(fullW - cropPixX)
        val cropPixH    = (cropH * renderScale).toInt().coerceAtLeast(1).coerceAtMost(fullH - cropPixY)
        val croppedBitmap = android.graphics.Bitmap.createBitmap(fullBitmap, cropPixX, cropPixY, cropPixW, cropPixH)
        fullBitmap.recycle()

        // ── Step 3: Apply lasso polygon mask on the cropped bitmap ────────────
        val maskedBitmap = android.graphics.Bitmap.createBitmap(cropPixW, cropPixH, android.graphics.Bitmap.Config.ARGB_8888)
        val maskCanvas = android.graphics.Canvas(maskedBitmap)
        maskCanvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)

        val polyPath = android.graphics.Path()
        polyPath.moveTo((polygon.first().x - minX) * renderScale, (polygon.first().y - minY) * renderScale)
        for (i in 1 until polygon.size) {
            polyPath.lineTo((polygon[i].x - minX) * renderScale, (polygon[i].y - minY) * renderScale)
        }
        polyPath.close()
        maskCanvas.clipPath(polyPath)
        maskCanvas.drawBitmap(croppedBitmap, 0f, 0f, null)
        croppedBitmap.recycle()

        // Trim transparent borders so placement/aspect matches the actually selected region.
        val trimmedBitmap = trimTransparentEdges(maskedBitmap)
        if (trimmedBitmap !== maskedBitmap) maskedBitmap.recycle()
        trimmedBitmap
    }

    suspend fun extractRegionToNewPage(
        context: android.content.Context,
        sourcePageIndex: Int,
        targetPageIndex: Int,
        pdfPageBitmap: android.graphics.Bitmap?
    ) {
        val polygon = _lassoPolygon.value.ifEmpty { _lastLassoPolygon.value }
        if (polygon.isEmpty()) return

        if (!extractionMutex.tryLock()) return
        try {
            withContext(Dispatchers.IO) {
            val trimmedBitmap = renderLassoExtraction(context, sourcePageIndex, pdfPageBitmap, polygon)
                ?: return@withContext
            val renderScale = minOf(2f, 4096f / maxOf(modelWidth, modelHeight))

            // Save PNG
            val trimmedPixelW = trimmedBitmap.width.coerceAtLeast(1)
            val trimmedPixelH = trimmedBitmap.height.coerceAtLeast(1)
            val file = java.io.File(context.filesDir, "extracted_${System.currentTimeMillis()}.png")
            java.io.FileOutputStream(file).use { out ->
                trimmedBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            trimmedBitmap.recycle()

            // Place image on new page — centred horizontally, near top
            val finalW = trimmedPixelW / renderScale
            val finalH = trimmedPixelH / renderScale

            val topMarginPt = 24f   // ~8.5 mm from top edge (PDF points)
            val newModelX = ((modelWidth - finalW) / 2f).coerceAtLeast(0f)
            val newModelY = topMarginPt

            val newImage = ImageAnnotationEntity(
                id          = java.util.UUID.randomUUID().toString(),
                documentUri = documentUri,
                pageIndex   = targetPageIndex,
                modelX      = newModelX,
                modelY      = newModelY,
                modelWidth  = finalW,
                modelHeight = finalH,
                uri         = android.net.Uri.fromFile(file).toString()
            )

            db.withTransaction { imageAnnotationDao.insert(newImage) }

            withContext(Dispatchers.Main) {
                clearSelection()
                onToolSelected(Tool.PEN)
            }
            }
        } finally {
            extractionMutex.unlock()
        }
    }

    suspend fun extractRegionToShareFile(
        context: android.content.Context,
        sourcePageIndex: Int,
        pdfPageBitmap: android.graphics.Bitmap?
    ): java.io.File? {
        val polygon = _lassoPolygon.value.ifEmpty { _lastLassoPolygon.value }
        if (polygon.isEmpty()) return null

        if (!extractionMutex.tryLock()) return null
        return try {
            withContext(Dispatchers.IO) {
                val trimmedBitmap = renderLassoExtraction(context, sourcePageIndex, pdfPageBitmap, polygon)
                    ?: return@withContext null

                val sharedDir = java.io.File(context.cacheDir, "shared")
                if (!sharedDir.exists()) sharedDir.mkdirs()
                val file = java.io.File(sharedDir, "share_${System.currentTimeMillis()}.png")
                java.io.FileOutputStream(file).use { out ->
                    trimmedBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                }
                trimmedBitmap.recycle()

                withContext(Dispatchers.Main) {
                    clearSelection()
                    onToolSelected(Tool.PEN)
                }

                file
            }
        } finally {
            extractionMutex.unlock()
        }
    }

    /** Load a bitmap from content:// or file:// or raw file-path URI. */
    private fun loadBitmapFromUri(context: android.content.Context, uri: String): android.graphics.Bitmap? {
        return try {
            val parsed = android.net.Uri.parse(uri)
            if (parsed.scheme == "content") {
                context.contentResolver.openInputStream(parsed)?.use { android.graphics.BitmapFactory.decodeStream(it) }
            } else {
                // file:// or raw path
                val path = if (parsed.scheme == "file") parsed.path ?: uri else uri
                android.graphics.BitmapFactory.decodeFile(path)
            }
        } catch (_: Exception) { null }
    }

    /** Render a source PDF page directly from documentUri (file://) for extraction fallback. */
    private fun renderPdfPageFromDocumentUri(
        pageIndex: Int,
        outWidth: Int,
        outHeight: Int
    ): android.graphics.Bitmap? {
        return try {
            val parsed = android.net.Uri.parse(documentUri)
            if (parsed.scheme != "file") return null
            val path = parsed.path ?: return null
            val fd = android.os.ParcelFileDescriptor.open(
                java.io.File(path),
                android.os.ParcelFileDescriptor.MODE_READ_ONLY
            )
            try {
                val renderer = android.graphics.pdf.PdfRenderer(fd)
                try {
                    if (pageIndex < 0 || pageIndex >= renderer.pageCount) return null
                    val bmp = android.graphics.Bitmap.createBitmap(
                        outWidth.coerceAtLeast(1),
                        outHeight.coerceAtLeast(1),
                        android.graphics.Bitmap.Config.ARGB_8888
                    )
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    val page = renderer.openPage(pageIndex)
                    try {
                        page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
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

    /** Crop transparent margins from ARGB bitmap; returns same instance if no trimming is needed. */
    private fun trimTransparentEdges(src: android.graphics.Bitmap, onTrimOffsets: (Float, Float) -> Unit = { _, _ -> }): android.graphics.Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src

        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1

        // Scan row by row to minimise memory allocation (avoids 16MB array for full page)
        val rowPixels = IntArray(w)
        for (y in 0 until h) {
            src.getPixels(rowPixels, 0, w, 0, y, w, 1)
            var rowHasOpaque = false
            for (x in 0 until w) {
                if (android.graphics.Color.alpha(rowPixels[x]) > 0) {
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
        return android.graphics.Bitmap.createBitmap(src, minX, minY, outW, outH)
    }

}
