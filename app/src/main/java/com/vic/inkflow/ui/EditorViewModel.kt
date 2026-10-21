package com.vic.inkflow.ui

import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vic.inkflow.data.AppDatabase
import com.vic.inkflow.data.ImageAnnotationEntity
import com.vic.inkflow.data.PointEntity
import com.vic.inkflow.data.repository.DocSpaceMigrator
import com.vic.inkflow.ui.editor.ExportController
import com.vic.inkflow.data.repository.InkFlowRepositories
import com.vic.inkflow.data.StrokeEntity
import com.vic.inkflow.data.StrokeWithPoints
import com.vic.inkflow.data.TextAnnotationEntity
import com.vic.inkflow.util.IntersectionUtils
import com.vic.inkflow.util.DocTransform
import com.vic.inkflow.util.EnvelopeUtils
import com.vic.inkflow.util.StrokePoint
import com.vic.inkflow.util.StrokeTransformUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModel(
    private val repos: InkFlowRepositories,
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
            // S1 單畫布：此處的 (w,h) 是回填 stride 唯一可信來源（真 PDF 尺寸），順手觸發。
            ensureDocSpaceMigrated(h)
        }
    }

    /**
     * S1 單畫布 docY 回填已於 P3 抽到 [DocSpaceMigrator]（舊檔案墨跡位置正確性的
     * 關鍵路徑，值得有自己的名字與測試）。這裡保留這個薄封裝，因為
     * initializePaperSize 是它的唯一觸發點，註解要留在觸發處才看得到因果。
     */
    private val docSpaceMigrator = DocSpaceMigrator(repos, documentUri)
    private fun ensureDocSpaceMigrated(modelH: Float) {
        docSpaceMigrator.launch(viewModelScope, modelH)
    }

    /** S1 雙寫步幅：live modelH（與回填同源；紙改尺寸後 S2 接 rebase）。 */
    private val docStride: Float get() = modelHeight.coerceAtLeast(1f)

    /**
     * R3：提取整組撤銷的頁操作回調（EditorScreen 接線：有 Context＋PdfViewModel 的那層，
     * EditorViewModel 碰不到 PdfViewModel）。
     */
    data class ExtractPageOps(
        /** 刪整頁（undo 用；VM 已先做「頁上無別的內容」守衛）。 */
        val deletePage: (Int) -> Unit,
        /** 在指定頁後插入空白頁（redo 用，頁尺寸用當下 model）。 */
        val insertPageAfter: (Int) -> Unit,
    )
    var extractPageOps: ExtractPageOps? = null

    /**
     * F4 圖片直接拖：拖曳中狀態（實體＋model 位移），overlay 跨頁畫在紙上層。
     * 與套索 overlay 同協議：放開/取消即清。src 紙原本照畫（同像素複畫無感）。
     */
    data class ImageDragPreview(
        val image: ImageAnnotationEntity,
        val dxModel: Float,
        val dyModel: Float
    )
    private val _imageDragPreview = MutableStateFlow<ImageDragPreview?>(null)
    val imageDragPreview: StateFlow<ImageDragPreview?> = _imageDragPreview.asStateFlow()
    fun publishImageDragPreview(p: ImageDragPreview?) {
        _imageDragPreview.value = p
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


    // Canvas pixel dimensions — reported by InkCanvas via setCanvasSize().
    // Default to MODEL dimensions so normalisation is identity before the first size report.
    //
    // Volatile 不是多餘的：寫入在主線程（onSizeChanged），但讀取有一處在
    // Dispatchers.Default（deleteStrokesIntersecting 的相交計算）。plain var 跨線程
    // 讀在 JVM 語義下允許無限期 stale——實務上 ART 幾乎都會刷新，但橡皮擦命中用錯
    // 一次縮放係數就是整批擦錯/擦空，而這裡修掉它只要兩個字。
    @Volatile
    private var canvasW = modelWidth
    @Volatile
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
    fun setPinchActive(active: Boolean) {
        _pinchActive.value = active
        if (active) watchLock(10000L, { _pinchActive.value }, { _pinchActive.value = false }, "pinchActive")
    }

    /**
     * P0：鎖看門狗——set(true) 後 10s 若還沒人清，強制清並記 log（協議：無超時的鎖視為 bug）。
     * 全活頁下誤觸成本低（只影響側欄跟隨／棄筆判定，不會斷筆）；epoch 保證後來的 set 蓋掉舊表。
     */
    private var lockWatchEpoch = 0L
    private fun watchLock(timeoutMs: Long, isStuck: () -> Boolean, clear: () -> Unit, tag: String) {
        val e = ++lockWatchEpoch
        viewModelScope.launch {
            kotlinx.coroutines.delay(timeoutMs)
            if (lockWatchEpoch == e && isStuck()) {
                android.util.Log.w("InkFlowLock", "$tag stuck > ${timeoutMs}ms, force-clear")
                clear()
            }
        }
    }

    // 二維平移的水平分量（px）：空白區單指＋雙指全域寫入，
    // 放手停留、跨頁保持；Workspace 負責鉗制（至少留一半紙在區內），這裡只存原始值。
    private val _panOffsetX = MutableStateFlow(0f)
    val panOffsetX: StateFlow<Float> = _panOffsetX.asStateFlow()
    fun setPanOffsetX(px: Float) { _panOffsetX.value = px }

    private val _quickSwipeEraserEnabled = MutableStateFlow(false)
    val quickSwipeEraserEnabled: StateFlow<Boolean> = _quickSwipeEraserEnabled.asStateFlow()

    private val _autoSwitchToPenAfterErase = MutableStateFlow(false)
    val autoSwitchToPenAfterErase: StateFlow<Boolean> = _autoSwitchToPenAfterErase.asStateFlow()

    // 擦除分兩個階段：命中＋樂觀隱藏（eraserMutex 串行，毫秒級）與背景墓碑（鎖外）。
    // 舊碼把 tombstone（UPDATE＋DELETE points＋fsync）包在同一把鎖裡，下一批擦除
    // 因此排在磁碟後面——連續快擦時畫面會落後手指。
    // 手勢結算不看鎖，看「這一個手勢自己的批次」是否寫完（EraseGestureAccumulator）。
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
    fun setPageLock(locked: Boolean) {
        _pageLock.value = locked
        if (locked) watchLock(10000L, { _pageLock.value }, { _pageLock.value = false }, "pageLock")
    }

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
        pageIndex.flatMapLatest { index -> repos.strokes.getStrokesForPage(documentUri, index) },
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
        repos.texts.getForPage(documentUri, index)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentImageAnnotations: StateFlow<List<ImageAnnotationEntity>> = pageIndex.flatMapLatest { index ->
        repos.images.getForPage(documentUri, index)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Undo / Redo stacks
    private val undoStack: ArrayDeque<DrawCommand> = ArrayDeque()
    private val redoStack: ArrayDeque<DrawCommand> = ArrayDeque()

    /**
     * 結構操作（清棧）的世代號。飛行中的擦除結算會晚幾毫秒才 pushUndo，
     * 若期間清了棧，那一格帶著舊 pageIndex 進來就是幽靈資料；用世代號擋掉。
     */
    @Volatile
    private var undoGeneration = 0

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private fun pushUndo(command: DrawCommand) {
        undoStack.addLast(command)
        // R2：歷史上限 200 格，裁頭（最舊先掉；無上限長會話快照膨脹）。
        while (undoStack.size > 200) undoStack.removeFirst()
        redoStack.clear()
        _canUndo.value = true
        _canRedo.value = false
    }

    /**
     * R2：頁面增刪移是結構操作、超出復原範圍——呼叫方在動頁前清棧，
     * 否則舊命令的 pageIndex 已錯位，復原會寫出幽靈資料。
     */
    fun clearUndoStacks() {
        undoGeneration++
        undoStack.clear()
        redoStack.clear()
        _canUndo.value = false
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

    fun setActivePage(index: Int, prefetch: Boolean = true) {
        _pageIndex.value = index
        // onScrollPage 路徑傳 prefetch=false：排放側已用可視窗預取過，
        // 這裡再查一次是重疊窗口（世代守衛會擋，但白跑一輪 IO）。
        // 冷啟動/點選/增刪頁路徑保持預取（那時排放還沒來）。
        if (prefetch) prefetchPages(index, index)
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
            repos.strokes.getStrokesForPage(documentUri, page),
            repos.texts.getForPage(documentUri, page),
            repos.images.getForPage(documentUri, page),
            // 第四路：樂觀隱藏集的版本號。隱藏集本身是 concurrent set，
            // 這裡不複製、只讀；版本號變一次就重組一次（同一批多個 id 只發一幀）。
            hiddenVersion
        ) { s, t, i, _ ->
            NeighborPageData(
                strokes = if (hiddenIds.isEmpty()) s else s.filter { it.stroke.id !in hiddenIds },
                texts = if (hiddenIds.isEmpty()) t else t.filter { it.id !in hiddenIds },
                images = if (hiddenIds.isEmpty()) i else i.filter { it.id !in hiddenIds }
            )
        }
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
                    strokes = repos.strokes.getStrokesForPageSync(documentUri, p),
                    texts = repos.texts.getForPageSync(documentUri, p),
                    images = repos.images.getForPageSync(documentUri, p)
                )
            }
            if (gen == prefetchGen) _neighborCache.value = fresh
            // 影子比對（單一畫布遷移 S2 前哨）：同窗口再跑一次 docY 範圍查，
            // 頁查⊆範圍查才算一致；只記 log，不動行為。等大文件必須零差異；
            // 混合尺寸會有差（stride 逐頁不同），那正是 T4 要收的。
            runCatching {
                val stride = docStride
                for (p in window) {
                    val y0 = p * stride
                    val y1 = (p + 1) * stride
                    val pageIds = fresh[p]?.strokes?.map { it.stroke.id }?.toSet().orEmpty()
                    val rangeIds = repos.strokes.getStrokesForRange(documentUri, y0, y1)
                        .map { it.stroke.id }.toSet()
                    val missing = pageIds - rangeIds
                    val extra = rangeIds - pageIds
                    if (missing.isNotEmpty() || extra.isNotEmpty()) {
                        android.util.Log.w(
                            "DocSpace",
                            "shadow-mismatch page=$p doc=$documentUri " +
                                "missing=${missing.size} extra=${extra.size} " +
                                "missingIds=${missing.take(5)} extraIds=${extra.take(5)}"
                        )
                    }
                }
            }
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
    ): String? {
        if (points.size < 2) return null
        val cW = canvasW
        val cH = canvasH
        val strokeId = UUID.randomUUID().toString()

        // Normalize immediately on the caller thread so the just-finished stroke can move
        // from the active layer to the pending layer without a visible gap on stylus lift.
        val scaleX = DocTransform.invScaleXUnchecked(cW, modelWidth)
        val scaleY = DocTransform.invScaleYUnchecked(cH, modelHeight)
        // 寬度用 zoom=1 基準歸一（× docZoom 還原）：座標仍用實際 canvas 尺寸映射，
        // 新墨在任何縮放下存入同樣的 model 寬，放大等比變粗。
        val widthScaleX = scaleX * _docZoom.value.coerceAtLeast(0.1f)
        val normalizedPoints = points.map {
            StrokePoint(it.x * scaleX, it.y * scaleY, it.width * widthScaleX)
        }
        val path = EnvelopeUtils.generateEnvelopePath(normalizedPoints)
        val bounds = path.getBounds()
        // S1 雙寫：頁空間照舊，docY 錨點同步存（S2 才讀）。
        val strokePage = targetPage ?: pageIndex.value
        val strokeEntity = StrokeEntity(
            id = strokeId,
            documentUri = documentUri,
            pageIndex = strokePage,
            docY = strokePage * docStride + bounds.top,
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
        // 存檔護欄（release 也可見，不是 Log.d——優化版 release 會把 Log.d/Log.v 拿掉，
        // 之前那行法醫線在平板上根本看不到）。這行是「提筆偏移」的判定線：
        // 落點＝手指位置 ×(繪製當下 size.width / 這裡的 canvas)，比值≠1 就是整筆等比偏移。
        // over＝正規化後超出紙面的量（用錯框架時會整片越界，只是筆寬造成的貼邊不算）。
        val firstPt = normalizedPoints.first()
        val lastPt = normalizedPoints.last()
        val overX = maxOf(-bounds.left, bounds.right - modelWidth, 0f)
        val overY = maxOf(-bounds.top, bounds.bottom - modelHeight, 0f)
        android.util.Log.w(
            "InkFlowDiag",
            "SAVE id=${strokeId.take(8)} n=${normalizedPoints.size} " +
                "canvas=${cW.toInt()}x${cH.toInt()} model=${modelWidth.toInt()}x${modelHeight.toInt()} " +
                "zoom=${_docZoom.value} page=$strokePage " +
                "px=[${points.first().x.toInt()},${points.first().y.toInt()}" +
                "..${points.last().x.toInt()},${points.last().y.toInt()}] " +
                "model=[${firstPt.x.toInt()},${firstPt.y.toInt()}" +
                "..${lastPt.x.toInt()},${lastPt.y.toInt()}] " +
                "bounds=[${bounds.left.toInt()},${bounds.top.toInt()}," +
                "${bounds.right.toInt()},${bounds.bottom.toInt()}] " +
                "over=[${overX.toInt()},${overY.toInt()}]"
        )
        val pendingStroke = StrokeWithPoints(strokeEntity, pointEntities)
        _pendingStrokes.value = _pendingStrokes.value + (strokeId to pendingStroke)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                repos.transaction {
                    repos.strokes.insertStroke(strokeEntity)
                    repos.strokes.insertPoints(pointEntities)
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
        // F3：回傳 id 供提筆交接（呼叫方等資料流出現該 id 才清活路徑，不再按幀猜）。
        return strokeId
    }

    /** 手勢被系統取消/縮放丟棄時清掉本手勢累積的命中，避免污染下次手勢。 */
    fun clearEraseHitPending() { eraseHitPending.set(false) }

    // ── R1 手勢級合併：單次擦除手勢內只刪不記，結尾一次記一格 ──────────────
    /**
     * 一次擦除手勢的累積器（每個手勢一個物件）。
     *
     * 為什麼不是共享 MutableList＋begin 清空：手勢結算要等背景墓碑，等待期間使用者
     * 可能已經開始下一個手勢；共享 list 會被下一個 begin 清掉，上一手勢尚未 append
     * 的戰果就靜默消失。批次在呼叫當下（Main）捕獲自己的 acc，結算只等這一個。
     */
    private class EraseGestureAccumulator(val undoGeneration: Int) {
        val strokes = mutableListOf<StrokeWithPoints>()
        val texts = mutableListOf<TextAnnotationEntity>()
        private val pending = java.util.concurrent.atomic.AtomicInteger(0)
        private val idle = MutableStateFlow(true)

        fun beginBatch() {
            pending.incrementAndGet()
            idle.value = false
        }

        fun endBatch() {
            if (pending.decrementAndGet() == 0) idle.value = true
        }

        /** 等這個手勢的所有批次（含背景墓碑）落定。鎖必配超時。 */
        suspend fun awaitIdle() {
            if (withTimeoutOrNull(5_000) { idle.first { it } } == null) {
                android.util.Log.w("EditorViewModel", "erase batches still in flight after 5s; snapshotting anyway")
            }
        }
    }

    @Volatile
    private var eraseAccumulator: EraseGestureAccumulator? = null

    /**
     * 樂觀隱藏：使用者可見的刪除先從畫面拿掉，DB 慢慢跟上。
     *
     * 為什麼需要：刪除的可見回饋鏈是「DB 交易 → Room 失效 → Flow 重發 →
     * combine 重組 → InkCanvas 重繪」，每一批擦除都要走完整趟，手指等的是整條鏈。
     * 這裡只管「畫面」，不管「真相」：pageDataFlow 把這集合裡的 id 濾掉，
     * InkCanvas 下一幀就看不見，DB 交易在 IO 線程慢慢落。
     *
     * 正確性靠三條不變式：
     *  1. id 永不重用（UUID），跨文件也不會撞 —— 隱藏集不會誤傷別的列。
     *  2. undo 恢復同 id 前必先 showIds（否則復活的墨永遠隱形）。
     *  3. 刪除提交後不清集合（不清＝留在裡面無害；清了＝Flow 還沒重發時閃回一幀）。
     * 用 ConcurrentHashMap 的 set ＋ 版本號，而不是每批複製整個 Set：連續快擦時
     * 集合可能有上萬個 id，每 24ms 複製一次只會把剛修掉的延遲從 DB 搬回 CPU。
     */
    private val hiddenIds: MutableSet<String> = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )
    private val hiddenVersion = MutableStateFlow(0L)

    /** 從畫面拿掉（刪除／redo 刪除時先呼叫，DB 隨後跟上）。執行緒安全，隨處可呼叫。 */
    internal fun hideIds(ids: Collection<String>) {
        if (ids.isEmpty()) return
        var changed = false
        for (id in ids) if (hiddenIds.add(id)) changed = true
        if (changed) hiddenVersion.update { it + 1 }
    }

    /** 放回畫面（undo 恢復時先呼叫，DB 隨後跟上）。 */
    internal fun showIds(ids: Collection<String>) {
        if (ids.isEmpty()) return
        var changed = false
        for (id in ids) if (hiddenIds.remove(id)) changed = true
        if (changed) hiddenVersion.update { it + 1 }
    }

    /** 擦除手勢起手呼叫（InkCanvas 橡皮擦接管處）。 */
    fun beginEraseGesture() {
        eraseAccumulator = EraseGestureAccumulator(undoGeneration)
    }

    /** 擦除手勢正常結束呼叫：等同手勢所有 async 寫入落定後，有戰果則合併推一格，否則不留痕。 */
    fun endEraseGesture() {
        val acc = eraseAccumulator ?: return
        eraseAccumulator = null
        finishEraseGesture(acc)
    }

    /**
     * 擦除手勢被取消/縮放丟棄呼叫。
     *
     * 舊語義是「累積作廢，不留復原格」——但飛行中的批次墓碑可能已經提交了，
     * 作廢等於靜默丟失（跟 latch 丟失同一條：DB 動了、undo 裡沒有）。既然攔不住
     * 已提交的寫入，唯一正確的是跟 endEraseGesture 一樣結算：有戰果就記一格。
     * 「取消」只決定手勢本身不繼續，不決定已發生的刪除要不要負責。
     */
    fun discardEraseGesture() {
        // 丟棄＝這一手勢沒有 final 會消費切筆旗標，先清掉避免污染下一次手勢。
        eraseHitPending.set(false)
        val acc = eraseAccumulator ?: return
        eraseAccumulator = null
        finishEraseGesture(acc)
    }

    /**
     * 結算單一手勢：等「這個 acc 的」批次全部落盤才快照。
     *
     * 關鍵是 acc 已經從欄位卸下：使用者可以立刻開始下一個手勢（新的 acc），
     * 這裡的 append 不會被清掉，也不會把下一手勢的命中算進這一格 undo。
     */
    private fun finishEraseGesture(acc: EraseGestureAccumulator) {
        viewModelScope.launch(Dispatchers.Default) {
            acc.awaitIdle()
            // 等待期間可能發生結構操作（增刪移頁）→ 棧已清，這一格 pageIndex 已錯位。
            // 跟 clearUndoStacks 的語意一致：結構操作後不接受舊手勢的 undo。
            if (acc.undoGeneration != undoGeneration) {
                android.util.Log.w("EditorViewModel", "erase gesture outlived clearUndoStacks; undo step dropped")
                return@launch
            }
            val (strokes, texts) = withContext(Dispatchers.Main) {
                // 去重防禦：同一筆若被兩批都命中（Flow 尚未重發），累積器裡也只留一份。
                val s = acc.strokes.distinctBy { it.stroke.id }
                val t = acc.texts.distinctBy { it.id }
                acc.strokes.clear()
                acc.texts.clear()
                s to t
            }
            if (strokes.isNotEmpty() || texts.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    pushUndo(DrawCommand.EraseGesture(strokes, texts))
                }
            }
        }
    }

    /**
     * F1 進行中預覽共享：src 紙切好＋轉頁內的點段（寬已 ×docZoom，與提交同管線）；
     * 鄰頁按自己窗口畫，不管提交。src 紙走直接路徑（segments 內不含 src 頁）。
     * 結束/取消/提交即 publish(null)。
     */
    data class InFlightPreview(
        val tool: Tool,
        val segments: Map<Int, List<com.vic.inkflow.util.StrokePoint>>,
        val colorArgb: Int
    )
    private val _inFlightPreview = MutableStateFlow<InFlightPreview?>(null)
    val inFlightPreview: StateFlow<InFlightPreview?> = _inFlightPreview.asStateFlow()
    fun publishInFlight(p: InFlightPreview?) {
        _inFlightPreview.value = p
    }

    fun deleteStrokesIntersecting(
        eraserPointsCanvas: List<Offset>,
        switchToPenAfterEraseHit: Boolean = false,
        // 同一擦除手勢的中途命中是否計入切筆判定。quick-swipe（畫筆手勢兼職擦除）
        // 傳 false：它本來就是筆，不參與切筆，否則舊旗標會污染下一次真正的擦除手勢。
        markEraseHit: Boolean = true,
        /** 橡皮擦所在紙。null = 作用頁（舊行為）；全活頁下 InkCanvas 傳自己。 */
        page: Int? = null,
        /**
         * R1：true = 命中只進手勢累積器、不推復原格（結尾由 endEraseGesture 一次記）。
         * 「一個擦除手勢一格 undo」的唯一入口：橡皮擦與快滑擦除都傳 true
         *（快滑的門在手勢中途觸發那一刻才開，見 ui/InkCanvas.kt 的 eraseGestureBegan）。
         * false 只留給「手勢結束才刪一次、本來就一格」的形狀工具快滑。
         */
        accumulateToGesture: Boolean = false
    ) {
        val cW = canvasW
        val cH = canvasH
        val targetPage = page ?: pageIndex.value
        // 主線程一次快照：熱流建流（動 map）+ 讀值都在這裡，協程內只用快照
        val targetData = pageDataFlow(targetPage).value
        // Pass the snapshot of eraser points to the coroutine
        val pointsCopy = eraserPointsCanvas.toList()

        // 手勢累積器在「呼叫當下」捕獲（Main 序列）：之後就算使用者立刻放開、
        // end 卸下欄位，這批仍會 append 到正確的那一格 undo。
        val gestureAcc = if (accumulateToGesture) eraseAccumulator else null
        gestureAcc?.beginBatch()
        viewModelScope.launch(Dispatchers.Default) {
            try {
                // ── 快路徑：命中＋樂觀隱藏，絕不碰 DB ──────────────────────────
                // 舊碼把 tombstone（UPDATE＋DELETE points＋fsync）包在同一把鎖裡，
                // 下一批擦除因此排在磁碟後面——連續快擦時畫面會落後手指。
                // 現在鎖只保護「命中＋隱藏」這個毫秒級區段，DB 在鎖外背景完成。
                val hits = eraserMutex.withLock {
                    // 已被前一批隱藏的筆不再命中：省一次 fsync，也不會產生重複 undo。
                    val visibleStrokes = if (hiddenIds.isEmpty()) targetData.strokes
                        else targetData.strokes.filter { it.stroke.id !in hiddenIds }
                    val visibleTexts = if (hiddenIds.isEmpty()) targetData.texts
                        else targetData.texts.filter { it.id !in hiddenIds }

                    // Scale eraser points from canvas-pixel space to model space before comparing.
                    val scaleX = DocTransform.invScaleXUnchecked(cW, modelWidth)
                    val scaleY = DocTransform.invScaleYUnchecked(cH, modelHeight)
                    val modelEraserPoints = pointsCopy.map { Offset(it.x * scaleX, it.y * scaleY) }
                    val intersectingStrokes = IntersectionUtils.findIntersectingStrokes(
                        eraserPoints = modelEraserPoints,
                        strokes = visibleStrokes
                    )

                    // Also erase text annotations whose box the path actually touches.
                    val hitTexts = visibleTexts.filter { ann ->
                        // 與套索／選取框共用同一個估算框（全形感知、多行、boxWidth）；
                        // 舊碼自己算 charCount×0.6，中文永遠窄一截，右半邊擦不到。
                        val b = textEstimatedBounds(ann)
                        // 逐段判相交，不用整條路徑的 bbox：斜線的 bbox 會誤擦路徑兩側的字。
                        IntersectionUtils.polylineIntersectsRect(
                            points = modelEraserPoints,
                            rectLeft = b.left,
                            rectTop = b.top,
                            rectRight = b.right,
                            rectBottom = b.bottom,
                            radius = 10f
                        )
                    }

                    // 樂觀隱藏先行：畫面下一幀就不見，不等 DB 交易＋Flow 重發整條鏈。
                    if (intersectingStrokes.isNotEmpty()) hideIds(intersectingStrokes.map { it.stroke.id })
                    if (hitTexts.isNotEmpty()) hideIds(hitTexts.map { it.id })
                    EraseHits(intersectingStrokes, hitTexts)
                }

                if (hits.strokes.isEmpty() && hits.texts.isEmpty()) {
                    // 沒有新命中也要消費切筆旗標：稍早的 live 命中可能已立旗，
                    // 而 final 是唯一會消費它的地方（否則舊旗標污染下次手勢）。
                    maybeSwitchToPenAfterErase(switchToPenAfterEraseHit)
                    return@launch
                }

                val strokeIds = hits.strokes.map { it.stroke.id }
                val textIds = hits.texts.map { it.id }

                // ── 慢路徑：背景墓碑；筆跡＋文字同一個交易，不會只擦半套 ────────
                try {
                    withContext(Dispatchers.IO) {
                        // 走墓碑而非硬刪除：橡皮擦刪掉的是使用者看得到的筆跡，平板與桌面
                        // 各自獨立工作時，桌面端可能同時在改同一筆。留墓碑讓
                        // 「刪除 vs 修改」有狀態可比（§15.4 修改勝），硬刪除則會讓對端
                        // 無從得知這裡曾經刪過東西。
                        repos.transaction {
                            if (strokeIds.isNotEmpty()) repos.strokes.tombstoneStrokesByIds(strokeIds)
                            if (textIds.isNotEmpty()) repos.texts.tombstoneByIds(textIds)
                        }
                    }
                } catch (e: CancellationException) {
                    // 取消也要收畫面：否則這筆 DB 還在、畫面本 session 永久隱形。
                    showIds(strokeIds)
                    showIds(textIds)
                    throw e
                } catch (e: Exception) {
                    // 寫失敗回滾：DB 裡還在、畫面沒了的話，這批墨連重擦都擦不到
                    //（命中讀的是過濾後流）。放回來讓使用者重試。
                    // 之前這裡無 catch：異常會一路炸掉整個 viewModelScope。
                    showIds(strokeIds)
                    showIds(textIds)
                    // 寫失敗就沒有戰果，清掉可能來自更早批次的旗標，避免誤切筆。
                    if (switchToPenAfterEraseHit) eraseHitPending.set(false)
                    android.util.Log.e("EditorViewModel", "erase tombstone failed, unhidden", e)
                    return@launch
                }

                // 只有真的落盤成功才進 undo 累積器：寫失敗卻先記 undo，之後
                // undo→redo 會把從沒刪過的筆寫墓碑（幽靈刪除＝真的刪資料）。
                withContext(Dispatchers.Main) {
                    if (gestureAcc != null) {
                        // 無條件累積：這個手勢的結算會等 acc.pending 歸零，
                        // 所有 append 都保證在快照之前跑完。
                        gestureAcc.strokes += hits.strokes
                        gestureAcc.texts += hits.texts
                    } else {
                        if (hits.strokes.isNotEmpty()) {
                            pushUndo(DrawCommand.RemoveStrokes(hits.strokes))
                        }
                        // 非手勢路徑（形狀工具快滑）本來就是一次一格，逐字記。
                        hits.texts.forEach { pushUndo(DrawCommand.RemoveTextAnnotation(it)) }
                    }
                    // 手勢級累積：本次命中記旗（quick-swipe 不記，避免污染下次手勢）
                    if (markEraseHit) eraseHitPending.set(true)
                    maybeSwitchToPenAfterErase(switchToPenAfterEraseHit)
                }
            } finally {
                gestureAcc?.endBatch()
            }
        }
    }

    /** 一次擦除批次命中的墨＋字（快路徑結果，供背景墓碑使用）。 */
    private data class EraseHits(
        val strokes: List<StrokeWithPoints>,
        val texts: List<TextAnnotationEntity>
    )

    /**
     * 手勢結尾的切筆判定：只有「本手勢曾有成功命中」且設定開啟才切回筆。
     * 旗標消費一次即清；quick-swipe 傳 false 直接不參與（不污染下一次手勢）。
     */
    private suspend fun maybeSwitchToPenAfterErase(switchToPenAfterEraseHit: Boolean) {
        if (!switchToPenAfterEraseHit || !_autoSwitchToPenAfterErase.value) return
        val hit = eraseHitPending.getAndSet(false)
        if (!hit) return
        withContext(Dispatchers.Main) {
            // Do not override temporary stylus-button eraser state.
            if (_selectedTool.value == Tool.ERASER && toolBeforeStylusButton == null) {
                onToolSelected(Tool.PEN)
            }
        }
    }

    /**
     * 跨頁擦除：src 紙 canvas 座標的擦除點按紙界切分到各頁，分頁刪
     * （墨＋字都由單頁版處理）。undo 按有命中的頁各記一筆；
     * 切筆旗累積照傳，換筆判定只在最後一段跑一次（與單頁版同語義）。
     */
    fun deleteStrokesIntersectingAcrossPages(
        eraserPointsCanvas: List<Offset>,
        srcPage: Int,
        canvasH: Float,
        gapPx: Float,
        pageCount: Int,
        markEraseHit: Boolean = true,
        switchToPenAfterEraseHit: Boolean = false,
        /** R1：見 [deleteStrokesIntersecting]——橡皮擦與快滑擦除都傳 true（結尾合併一格）。 */
        accumulateToGesture: Boolean = false,
    ) {
        val segs = com.vic.inkflow.util.DocLayout.splitByPage(
            items = eraserPointsCanvas,
            yOf = { it.y },
            canvasH = canvasH,
            gapPx = gapPx,
            srcPage = srcPage,
            pageCount = pageCount,
            local = { p, ly -> Offset(p.x, ly) }
        ).filter { it.second.size >= 2 }
        // 全是單點碎段（極端）：退回本頁整筆，保證 tap 可擦。
        if (segs.isEmpty()) {
            deleteStrokesIntersecting(
                eraserPointsCanvas = eraserPointsCanvas,
                switchToPenAfterEraseHit = switchToPenAfterEraseHit,
                markEraseHit = markEraseHit,
                page = srcPage,
                accumulateToGesture = accumulateToGesture
            )
            return
        }
        segs.forEachIndexed { si, (pg, pts) ->
            deleteStrokesIntersecting(
                eraserPointsCanvas = pts,
                switchToPenAfterEraseHit = si == segs.lastIndex && switchToPenAfterEraseHit,
                markEraseHit = markEraseHit,
                page = pg,
                accumulateToGesture = accumulateToGesture
            )
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
            val scaleX = DocTransform.invScaleXUnchecked(cW, modelWidth)
            val scaleY = DocTransform.invScaleYUnchecked(cH, modelHeight)
            // 寬度用 zoom=1 基準歸一（× docZoom 還原），座標映射不動。
            val shapeWidthScale = scaleX * _docZoom.value.coerceAtLeast(0.1f)
            val p0 = Offset(startPoint.x * scaleX, startPoint.y * scaleY)
            val p1 = Offset(endPoint.x * scaleX, endPoint.y * scaleY)
            // S1 雙寫：頁空間照舊，docY 錨點同步存。
            val shapePage = targetPage ?: pageIndex.value
            val shapeEntityTop = minOf(p0.y, p1.y)
            val strokeEntity = StrokeEntity(
                id = strokeId,
                documentUri = documentUri,
                pageIndex = shapePage,
                docY = shapePage * modelHeight.coerceAtLeast(1f) + shapeEntityTop,
                color = color.toArgb(),
                strokeWidth = strokeWidth * shapeWidthScale,
                boundsLeft = minOf(p0.x, p1.x),
                boundsTop = shapeEntityTop,
                boundsRight = maxOf(p0.x, p1.x),
                boundsBottom = maxOf(p0.y, p1.y),
                isHighlighter = false,
                shapeType = shapeType
            )
            val pointEntities = listOf(
                PointEntity(strokeId = strokeId, x = p0.x, y = p0.y),
                PointEntity(strokeId = strokeId, x = p1.x, y = p1.y)
            )
            repos.transaction {
                repos.strokes.insertStroke(strokeEntity)
                repos.strokes.insertPoints(pointEntities)
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
        val textPage = targetPage ?: pageIndex.value
        val textModelY = canvasY * DocTransform.invScaleYUnchecked(canvasH, modelHeight)
        val textModelX = canvasX * DocTransform.invScaleXUnchecked(canvasW, modelWidth)
        // 文字框化：預設框寬＝落點到右邊距，超過就折行（見 ui/TextWrapping.kt）。
        val textBoxWidth = (modelWidth - textModelX - TEXT_BOX_DEFAULT_MARGIN)
            .coerceAtLeast(TEXT_BOX_MIN_WIDTH)
        val ann = TextAnnotationEntity(
            documentUri = documentUri,
            pageIndex = textPage,
            // S1 雙寫：docY 錨點同步存。
            docY = textPage * docStride + textModelY,
            text = text,
            modelX = textModelX,
            modelY = textModelY,
            fontSize = fontSize * DocTransform.invScaleXUnchecked(canvasW, modelWidth),
            colorArgb = color.toArgb(),
            isStamp = isStamp,
            // 圖章（超大 emoji）不進文字框：折行會把它拆成好幾行。
            boxWidth = if (isStamp) null else textBoxWidth
        )
        viewModelScope.launch(Dispatchers.IO) {
            repos.texts.insert(ann)
            withContext(Dispatchers.Main) { pushUndo(DrawCommand.AddTextAnnotation(ann)) }
        }
    }

    /** M3：引入文字寫入 — 直接給 model 座標與目標頁（排版引擎已算好，不經 canvas 換算）。 */
    fun insertImportedText(docUri: String, targetPage: Int, text: String, modelX: Float, modelY: Float, fontSize: Float) {
        if (text.isBlank()) return
        // 框寬＝AI 排版的內容寬（同一支 aiTextContentWidth）。有了它，AI 文字就是一般文字框：
        // 折行交給渲染器、可以改寬、可以對齊；存的行再折一次是冪等，所以外觀與匯入時一致。
        val boxWidth = aiTextContentWidth(modelWidth)
        val ann = TextAnnotationEntity(
            documentUri = docUri,
            pageIndex = targetPage,
            // S1 雙寫：docY 錨點同步存（他 AI 的 M3 寫入路徑一併帶上）。
            docY = targetPage * docStride + modelY,
            text = text,
            modelX = modelX,
            modelY = modelY,
            fontSize = fontSize,
            boxWidth = boxWidth
        )
        viewModelScope.launch(Dispatchers.IO) {
            repos.texts.insert(ann)
            withContext(Dispatchers.Main) { pushUndo(DrawCommand.AddTextAnnotation(ann)) }
        }
    }

    /** M5：引入數學圖寫入 — KaTeX 渲染 PNG 直接給 model 矩形與目標頁。 */
    fun insertImportedImage(docUri: String, targetPage: Int, fileUri: String, modelX: Float, modelY: Float, modelW: Float, modelH: Float) {
        if (modelW <= 0f || modelH <= 0f) return
        val ann = ImageAnnotationEntity(
            documentUri = docUri,
            pageIndex = targetPage,
            // S1 雙寫：docY 錨點同步存。
            docY = targetPage * docStride + modelY,
            uri = fileUri,
            modelX = modelX,
            modelY = modelY,
            modelWidth = modelW,
            modelHeight = modelH
        )
        viewModelScope.launch(Dispatchers.IO) {
            repos.images.insert(ann)
            withContext(Dispatchers.Main) { pushUndo(DrawCommand.AddImageAnnotation(ann)) }
        }
    }

    fun commitTextAnnotationContent(id: String, newText: String) {
        val old = findTextAnnotation(id) ?: return
        if (old.text == newText || newText.isBlank()) return
        val updated = old.copy(text = newText)
        viewModelScope.launch(Dispatchers.IO) {
            repos.texts.updateAndBump(updated)
            withContext(Dispatchers.Main) { pushUndo(DrawCommand.EditTextAnnotation(old, updated)) }
        }
    }
    fun deleteTextAnnotation(id: String) {
        val ann = findTextAnnotation(id) ?: return
        hideIds(listOf(id))
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 走墓碑而非硬刪除：同筆跡刪除的理由（§15.4）。
                repos.texts.tombstoneByIds(listOf(id))
                withContext(Dispatchers.Main) { pushUndo(DrawCommand.RemoveTextAnnotation(ann)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 失敗不記 undo（沒刪掉的東西不需要恢復格），只放回畫面。
                showIds(listOf(id))
                android.util.Log.e("EditorViewModel", "deleteText failed, unhidden", e)
            }
        }
    }

    fun commitTextAnnotationMove(id: String, canvasDeltaX: Float, canvasDeltaY: Float) {
        if (canvasDeltaX == 0f && canvasDeltaY == 0f) return
        val old = findTextAnnotation(id) ?: return
        val scaleX = DocTransform.invScaleXUnchecked(canvasW, modelWidth)
        val scaleY = DocTransform.invScaleYUnchecked(canvasH, modelHeight)
        val newModelY = old.modelY + canvasDeltaY * scaleY
        val updated = old.copy(
            modelX = old.modelX + canvasDeltaX * scaleX,
            modelY = newModelY,
            // S1 雙寫：docY 與座標同步算。
            docY = old.pageIndex * docStride + newModelY
        )
        viewModelScope.launch(Dispatchers.IO) {
            repos.texts.update(updated)
            withContext(Dispatchers.Main) { pushUndo(DrawCommand.MoveTextAnnotation(old, updated)) }
        }
    }

    /**
     * 跨頁拖曳落點提交：文字整顆搬到 targetPage 的 (modelX, modelY)。
     * MoveTextAnnotation 存完整實體（含 pageIndex），undo/redo 天然支援跨頁。
     */
    fun commitTextAnnotationMoveToPage(id: String, targetPage: Int, modelX: Float, modelY: Float) {
        val old = findTextAnnotation(id) ?: return
        // S1 雙寫：docY 與座標同步算。
        val newPage = targetPage.coerceAtLeast(0)
        val newModelX = modelX.coerceIn(0f, modelWidth)
        val newModelY = modelY.coerceIn(0f, modelHeight)
        val updated = old.copy(
            pageIndex = newPage,
            modelX = newModelX,
            modelY = newModelY,
            docY = newPage * docStride + newModelY
        )
        if (updated == old) return
        viewModelScope.launch(Dispatchers.IO) {
            repos.texts.updateAndBump(updated)
            withContext(Dispatchers.Main) { pushUndo(DrawCommand.MoveTextAnnotation(old, updated)) }
        }
    }

    fun commitTextAnnotationResize(id: String, newX: Float, newY: Float, newFontSizeModel: Float) {
        val old = findTextAnnotation(id) ?: return
        val clamped = newFontSizeModel.coerceAtLeast(4f)
        val updated = old.copy(
            modelX = newX,
            modelY = newY,
            fontSize = clamped,
            // S1 雙寫：docY 與座標同步算。
            docY = old.pageIndex * docStride + newY
        )
        viewModelScope.launch(Dispatchers.IO) {
            repos.texts.updateAndBump(updated)
            withContext(Dispatchers.Main) { pushUndo(DrawCommand.ResizeTextAnnotation(old, updated)) }
        }
    }

    /** 文字框寬調整（拖曳右緣把手）：重排框內文字。undo 走 ResizeTextAnnotation（同為幾何變更）。 */
    fun commitTextAnnotationWidth(id: String, newBoxWidthModel: Float) {
        val old = findTextAnnotation(id) ?: return
        val clamped = newBoxWidthModel.coerceAtLeast(TEXT_BOX_MIN_WIDTH)
        if (kotlin.math.abs((old.boxWidth ?: 0f) - clamped) < 0.01f) return
        val updated = old.copy(boxWidth = clamped)
        viewModelScope.launch(Dispatchers.IO) {
            repos.texts.updateAndBump(updated)
            withContext(Dispatchers.Main) { pushUndo(DrawCommand.ResizeTextAnnotation(old, updated)) }
        }
    }

    /** 對齊變更（左/中/右）：只影響 render/hit/export，不改座標。undo 走 EditTextAnnotation。 */
    fun commitTextAnnotationAlignment(id: String, alignment: Int) {
        val old = findTextAnnotation(id) ?: return
        val a = alignment.coerceIn(TEXT_ALIGN_LEFT, TEXT_ALIGN_RIGHT)
        if (old.alignment == a) return
        val updated = old.copy(alignment = a)
        viewModelScope.launch(Dispatchers.IO) {
            repos.texts.updateAndBump(updated)
            withContext(Dispatchers.Main) { pushUndo(DrawCommand.EditTextAnnotation(old, updated)) }
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
    val scaleX = DocTransform.invScaleXUnchecked(canvasW, modelWidth)
    val scaleY = DocTransform.invScaleYUnchecked(canvasH, modelHeight)
    // S1 雙寫：docY 錨點同步存。
    val imgPage = targetPage ?: pageIndex.value
    val imgModelY = canvasY * scaleY
    val ann = ImageAnnotationEntity(
        documentUri = documentUri,
        pageIndex = imgPage,
        docY = imgPage * docStride + imgModelY,
        uri = uri,
        modelX = canvasX * scaleX,
        modelY = imgModelY,
            modelWidth = canvasWidth * scaleX,
            modelHeight = canvasHeight * scaleY
        )
        viewModelScope.launch(Dispatchers.IO) {
            repos.images.insert(ann)
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
        // 插入由工具列觸發（不再靠點紙面帶錨點）→ 無錨點時置中在頁面中央，
        // 位置可預期；有錨點仍以它為圖心（相容舊路徑）。
        // 見 centeredImageOrigin（純函數，有回歸測試）。
        val origin = if (anchorModel != null) {
            Offset(
                (anchorModel.x - initW / 2f).coerceIn(0f, maxOf(0f, modelWidth - initW)),
                (anchorModel.y - initH / 2f).coerceIn(0f, maxOf(0f, modelHeight - initH))
            )
        } else {
            centeredImageOrigin(modelWidth, modelHeight, initW, initH)
        }
        return ImageAnnotationEntity(
            documentUri = documentUri,
            pageIndex = targetPageIndex,
            // S1 雙寫：docY 錨點同步存。
            docY = targetPageIndex * docStride + origin.y,
            uri = uri,
            modelX = origin.x,
            modelY = origin.y,
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
            repos.images.insert(ann)
        }
        withContext(Dispatchers.Main) {
            pushUndo(DrawCommand.AddImageAnnotation(ann))
        }
        return ann.id
    }

    /**
     * 一次性「選中剛插進來的圖」通道。
     *
     * 插圖流程現在整條住在 EditorScreen（launcher 只宣告一次），但「選取」是每頁
     * InkCanvas 的私選狀態（selectedImageAnnotationId），要由擁有該 id 的那頁取走。
     *
     * 放 ViewModel 而不是 Compose state：InkCanvas 掛在 LazyColumn item 上，捲出視窗就會
     * 被 dispose；記在畫面層的令牌在頁面回來時會重播（舊 bug：imagePickRequest 只增不減
     * → 捲頁／開側欄第 3 階網格後系統圖庫自己彈出來）。這裡取走即清空，語意是「一次」。
     */
    private val _pendingImageSelectId = MutableStateFlow<String?>(null)
    val pendingImageSelectId: StateFlow<String?> = _pendingImageSelectId.asStateFlow()

    fun requestImageSelect(id: String) {
        _pendingImageSelectId.value = id
    }

    fun consumeImageSelectRequest() {
        _pendingImageSelectId.value = null
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
            repos.images.insert(ann)
            withContext(Dispatchers.Main) { pushUndo(DrawCommand.AddImageAnnotation(ann)) }
        }
        return ann.id
    }

    fun commitImageAnnotationMove(id: String, canvasDeltaX: Float, canvasDeltaY: Float) {
        if (canvasDeltaX == 0f && canvasDeltaY == 0f) return
        val old = findImageAnnotation(id) ?: return
        val scaleX = DocTransform.invScaleXUnchecked(canvasW, modelWidth)
        val scaleY = DocTransform.invScaleYUnchecked(canvasH, modelHeight)
        val newModelY = old.modelY + canvasDeltaY * scaleY
        val updated = old.copy(
            modelX = old.modelX + canvasDeltaX * scaleX,
            modelY = newModelY,
            // S1 雙寫：docY 與座標同步算。
            docY = old.pageIndex * docStride + newModelY
        )
        viewModelScope.launch(Dispatchers.IO) {
            repos.images.update(updated)
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
    // S1 雙寫：docY 與座標同步算。
    val newImgPage = targetPage.coerceAtLeast(0)
    val newImgModelY = modelY.coerceIn(0f, modelHeight)
    val updated = old.copy(
        pageIndex = newImgPage,
        modelX = modelX.coerceIn(0f, modelWidth),
        modelY = newImgModelY,
        docY = newImgPage * docStride + newImgModelY
    )
    if (updated == old) return
        viewModelScope.launch(Dispatchers.IO) {
            repos.images.update(updated)
            withContext(Dispatchers.Main) { pushUndo(DrawCommand.MoveImageAnnotation(old, updated)) }
        }
    }

    fun commitImageAnnotationResize(id: String, newX: Float, newY: Float, newModelWidth: Float, newModelHeight: Float) {
        val old = findImageAnnotation(id) ?: return
        val updated = old.copy(
            modelX = newX,
            modelY = newY,
            modelWidth  = newModelWidth.coerceAtLeast(30f),
            modelHeight = newModelHeight.coerceAtLeast(30f),
            // S1 雙寫：docY 與座標同步算。
            docY = old.pageIndex * docStride + newY
        )
        viewModelScope.launch(Dispatchers.IO) {
            repos.images.update(updated)
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
            repos.images.update(updated)
            withContext(Dispatchers.Main) { pushUndo(DrawCommand.ResizeImageAnnotation(old, updated)) }
        }
    }

    fun deleteImageAnnotation(id: String) {
        val ann = findImageAnnotation(id) ?: return
        hideIds(listOf(id))
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repos.images.deleteById(id)
                withContext(Dispatchers.Main) { pushUndo(DrawCommand.RemoveImageAnnotation(ann)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showIds(listOf(id))
                android.util.Log.e("EditorViewModel", "deleteImage failed, unhidden", e)
            }
        }
    }

    fun undo() {
        val command = undoStack.removeLastOrNull() ?: return
        // R3：默認 redo 原樣奉還；提取守衛留頁時改記 pageKept 版（redo 不再建頁）。
        var redoCommand: DrawCommand = command
        viewModelScope.launch(Dispatchers.IO) {
            when (command) {
                is DrawCommand.AddStroke -> {
                    // 剛畫的拿掉：先藏（它從沒被 show 過，不在集合裡也無害）。
                    hideIds(listOf(command.stroke.stroke.id))
                    repos.strokes.deleteStrokesByIds(listOf(command.stroke.stroke.id))
                }
                is DrawCommand.RemoveStrokes -> {
                    // 恢復：先放回畫面（同 id 之前被 hide 過，不先清會永久隱形）。
                    showIds(command.strokes.map { it.stroke.id })
                    // 整包同一交易：逐筆交易＝N 次提交＋N 次 Flow 重發＋N 次重建，
                    // undo 一大包時就是連發卡頓。Room 失效以交易為單位合併，一次就行。
                    // 走 replaceStrokeSnapshots 漏斗：覆蓋＋bump 同交易。快照帶的是
                    // 讀取時的舊 version，直接蓋回去等於版本倒退——v6 的 incomingWins
                    // 前提是版本只進不退，undo 一次就說謊一次（§15.1）。
                    repos.strokes.replaceStrokeSnapshots(command.strokes)
                }
                is DrawCommand.EraseGesture -> {
                    // R1：一筆擦的整包戰果一次還原（墨＋字）。
                    showIds(command.strokes.map { it.stroke.id })
                    showIds(command.texts.map { it.id })
                    try {
                        repos.transaction {
                            repos.strokes.replaceStrokeSnapshots(command.strokes)
                            command.texts.forEach {
                                repos.texts.insert(it)
                                repos.texts.bumpTextVersion(it.id)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 還原失敗：畫面不能留著「復活了但 DB 沒有」的墨——下一次 Flow
                        // 重發就閃一下不見。藏回去，undo 格已彈出但內容還在，可重試。
                        hideIds(command.strokes.map { it.stroke.id })
                        hideIds(command.texts.map { it.id })
                        android.util.Log.e("EditorViewModel", "undo of erase failed, re-hidden", e)
                    }
                }
                is DrawCommand.MoveStrokes -> {
                    repos.strokes.replaceStrokeSnapshots(command.originals)
                }
                is DrawCommand.ResizeStrokes -> {
                    repos.strokes.replaceStrokeSnapshots(command.originals)
                }
                is DrawCommand.AddTextAnnotation -> {
                    hideIds(listOf(command.annotation.id))
                    repos.texts.deleteById(command.annotation.id)
                }
                is DrawCommand.RemoveTextAnnotation -> {
                    showIds(listOf(command.annotation.id))
                    repos.texts.insert(command.annotation)
                    repos.texts.bumpTextVersion(command.annotation.id)
                }
                is DrawCommand.MoveTextAnnotation -> {
                    repos.texts.updateAndBump(command.original)
                }
                is DrawCommand.ResizeTextAnnotation -> {
                    repos.texts.updateAndBump(command.original)
                }
                is DrawCommand.EditTextAnnotation -> {
                    repos.texts.updateAndBump(command.original)
                }
                is DrawCommand.AddImageAnnotation -> {
                    hideIds(listOf(command.annotation.id))
                    repos.images.deleteById(command.annotation.id)
                }
                is DrawCommand.RemoveImageAnnotation -> {
                    showIds(listOf(command.annotation.id))
                    repos.images.insert(command.annotation)
                }
                is DrawCommand.MoveImageAnnotation -> {
                    repos.images.update(command.original)
                }
                is DrawCommand.ResizeImageAnnotation -> {
                    repos.images.update(command.original)
                }
                is DrawCommand.MoveSelectionMixed -> {
                    // 單一外層交易（內層 withTransaction 同協程自動合併）：三表同生同死。
                    // 拆成兩個交易的話，崩潰在中間會留下「墨回來了、字沒回來」的半包。
                    repos.transaction {
                        repos.strokes.replaceStrokeSnapshots(command.strokeOriginals)
                        command.imageOriginals.forEach { repos.images.update(it) }
                        command.textOriginals.forEach { repos.texts.updateAndBump(it) }
                    }
                }
                is DrawCommand.ResizeSelectionMixed -> {
                    repos.transaction {
                        repos.strokes.replaceStrokeSnapshots(command.strokeOriginals)
                        command.imageOriginals.forEach { repos.images.update(it) }
                        command.textOriginals.forEach { repos.texts.updateAndBump(it) }
                    }
                }
                is DrawCommand.AddSelectionCopies -> {
                    hideIds(command.strokes.map { it.stroke.id })
                    hideIds(command.images.map { it.id })
                    hideIds(command.texts.map { it.id })
                    repos.transaction {
                        if (command.strokes.isNotEmpty()) {
                            repos.strokes.deleteStrokesByIds(command.strokes.map { it.stroke.id })
                        }
                        command.images.forEach { repos.images.deleteById(it.id) }
                        command.texts.forEach { repos.texts.deleteById(it.id) }
                    }
                }
                is DrawCommand.RemoveSelectionMixed -> {
                    showIds(command.strokes.map { it.stroke.id })
                    showIds(command.images.map { it.id })
                    showIds(command.texts.map { it.id })
                    repos.transaction {
                        repos.strokes.replaceStrokeSnapshots(command.strokes)
                        command.images.forEach { repos.images.insert(it) }
                        command.texts.forEach {
                            repos.texts.insert(it)
                            repos.texts.bumpTextVersion(it.id)
                        }
                    }
                }
                is DrawCommand.ExtractToNewPage -> {
                    hideIds(listOf(command.image.id))
                    repos.images.deleteById(command.image.id)
                    // 整組撤銷：頁上若無別的內容，連頁一起收掉；否則只拿掉圖、留頁。
                    val hasStrokes = repos.strokes.getStrokesForPageSync(documentUri, command.targetPage).isNotEmpty()
                    val hasTexts = repos.texts.getForPageSync(documentUri, command.targetPage).isNotEmpty()
                    val hasImages = repos.images.getForPageSync(documentUri, command.targetPage).isNotEmpty()
                    if (!hasStrokes && !hasTexts && !hasImages) {
                        withContext(Dispatchers.Main) { extractPageOps?.deletePage(command.targetPage) }
                    } else {
                        redoCommand = command.copy(pageKept = true)
                    }
                }
            }
            withContext(Dispatchers.Main) {
                redoStack.addLast(redoCommand)
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
                    // 重畫回來：先放回畫面（undo 時被 hide 過）。
                    showIds(listOf(command.stroke.stroke.id))
                    repos.strokes.replaceStrokeSnapshots(listOf(command.stroke))
                }
                is DrawCommand.RemoveStrokes -> {
                    // redo 重新蓋墓碑，而不是硬刪（undo 是 insertStroke 把列覆蓋回來，
                    // 所以 redo 必須回到「有列但已刪」的狀態才對稱）。
                    hideIds(command.strokes.map { it.stroke.id })
                    repos.strokes.tombstoneStrokesByIds(command.strokes.map { it.stroke.id })
                }
                is DrawCommand.EraseGesture -> {
                    hideIds(command.strokes.map { it.stroke.id })
                    hideIds(command.texts.map { it.id })
                    try {
                        // 筆＋字同一個交易：redo 中途失敗不會留下半套狀態。
                        repos.transaction {
                            if (command.strokes.isNotEmpty()) {
                                repos.strokes.tombstoneStrokesByIds(command.strokes.map { it.stroke.id })
                            }
                            if (command.texts.isNotEmpty()) {
                                repos.texts.tombstoneByIds(command.texts.map { it.id })
                            }
                        }
                    } catch (e: CancellationException) {
                        showIds(command.strokes.map { it.stroke.id })
                        showIds(command.texts.map { it.id })
                        throw e
                    } catch (e: Exception) {
                        // 寫失敗就把畫面放回來：hid 了但 DB 沒刪＝這批墨本 session 永久隱形，
                        // 連重擦都擦不到（命中讀的是過濾後流）。跟 undo 失敗的處置對稱。
                        showIds(command.strokes.map { it.stroke.id })
                        showIds(command.texts.map { it.id })
                        withContext(Dispatchers.Main) { _canRedo.value = redoStack.isNotEmpty() }
                        android.util.Log.e("EditorViewModel", "redo of erase failed, unhidden", e)
                        return@launch
                    }
                }
                is DrawCommand.MoveStrokes -> {
                    // 跨頁移動：updated 帶新 pageIndex，直接恢復快照；
                    // 單頁移動：updated 為 null，走舊 delta 重放。
                    val crossSnap = command.updated
                    if (crossSnap != null) {
                        replaceStrokeSnapshots(crossSnap)
                    } else {
                        // 整包同一交易（同 undo 的理由：N 筆 N 交易＝N 次重發重建）。
                        // 點列純計算先備好，交易裡只做寫（shiftedPoints/Stroke 是 CPU，
                        // 不碰 DB，放外面不影響原子性，還讓交易窗口更短）。
                        val shifted = command.originals.map { swp ->
                            val shiftedPoints = swp.points.map { pt ->
                                PointEntity(strokeId = swp.stroke.id, x = pt.x + command.delta.x, y = pt.y + command.delta.y, width = pt.width)
                            }
                            val shiftedStroke = swp.stroke.copy(
                                boundsLeft   = swp.stroke.boundsLeft   + command.delta.x,
                                boundsTop    = swp.stroke.boundsTop    + command.delta.y,
                                boundsRight  = swp.stroke.boundsRight  + command.delta.x,
                                boundsBottom = swp.stroke.boundsBottom + command.delta.y
                            ).let {
                                // S1 雙寫：redo 重建的實體把 docY 重算。
                                it.copy(docY = it.pageIndex * docStride + it.boundsTop)
                            }
                            shiftedStroke to shiftedPoints
                        }
                        repos.strokes.replaceStrokeSnapshots(shifted.map { (stroke, points) ->
                            StrokeWithPoints(stroke, points)
                        })
                    }
                }
                is DrawCommand.ResizeStrokes -> {
                    repos.strokes.replaceStrokeSnapshots(command.updated)
                }
                is DrawCommand.AddTextAnnotation -> {
                    showIds(listOf(command.annotation.id))
                    repos.texts.insert(command.annotation)
                    repos.texts.bumpTextVersion(command.annotation.id)
                }
                is DrawCommand.RemoveTextAnnotation -> {
                    hideIds(listOf(command.annotation.id))
                    repos.texts.deleteById(command.annotation.id)
                }
                is DrawCommand.MoveTextAnnotation -> {
                    repos.texts.updateAndBump(command.updated)
                }
                is DrawCommand.ResizeTextAnnotation -> {
                    repos.texts.updateAndBump(command.updated)
                }
                is DrawCommand.EditTextAnnotation -> {
                    repos.texts.updateAndBump(command.updated)
                }
                is DrawCommand.AddImageAnnotation -> {
                    showIds(listOf(command.annotation.id))
                    repos.images.insert(command.annotation)
                }
                is DrawCommand.RemoveImageAnnotation -> {
                    hideIds(listOf(command.annotation.id))
                    repos.images.deleteById(command.annotation.id)
                }
                is DrawCommand.MoveImageAnnotation -> {
                    repos.images.update(command.updated)
                }
                is DrawCommand.ResizeImageAnnotation -> {
                    repos.images.update(command.updated)
                }
                is DrawCommand.MoveSelectionMixed -> {
                    repos.transaction {
                        repos.strokes.replaceStrokeSnapshots(command.strokeUpdated)
                        command.imageUpdated.forEach { repos.images.update(it) }
                        command.textUpdated.forEach { repos.texts.updateAndBump(it) }
                    }
                }
                is DrawCommand.ResizeSelectionMixed -> {
                    repos.transaction {
                        repos.strokes.replaceStrokeSnapshots(command.strokeUpdated)
                        command.imageUpdated.forEach { repos.images.update(it) }
                        command.textUpdated.forEach { repos.texts.updateAndBump(it) }
                    }
                }
                is DrawCommand.AddSelectionCopies -> {
                    showIds(command.strokes.map { it.stroke.id })
                    showIds(command.images.map { it.id })
                    showIds(command.texts.map { it.id })
                    repos.transaction {
                        repos.strokes.replaceStrokeSnapshots(command.strokes)
                        command.images.forEach { repos.images.insert(it) }
                        command.texts.forEach {
                            repos.texts.insert(it)
                            repos.texts.bumpTextVersion(it.id)
                        }
                    }
                }
                is DrawCommand.RemoveSelectionMixed -> {
                    hideIds(command.strokes.map { it.stroke.id })
                    hideIds(command.images.map { it.id })
                    hideIds(command.texts.map { it.id })
                    repos.transaction {
                        if (command.strokes.isNotEmpty()) {
                            repos.strokes.tombstoneStrokesByIds(command.strokes.map { it.stroke.id })
                        }
                        command.images.forEach { repos.images.deleteById(it.id) }
                        command.texts.let { repos.texts.tombstoneByIds(it.map { t -> t.id }) }
                    }
                }
                is DrawCommand.ExtractToNewPage -> {
                    // pageKept（undo 守衛留頁）時不再建頁，只重貼圖。
                    if (!command.pageKept) {
                        withContext(Dispatchers.Main) { extractPageOps?.insertPageAfter(command.targetPage - 1) }
                    }
                    showIds(listOf(command.image.id))
                    repos.images.insert(command.image)
                }
            }
            withContext(Dispatchers.Main) {
                undoStack.addLast(command)
                // R2：redo 回填也要裁頭（上限 200 格，與 pushUndo 同規）。
                while (undoStack.size > 200) undoStack.removeFirst()
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

    // 套索選中的字（與墨圖同等待遇：框線、移動、刪除、複製、復原全支援）。
    private val _selectedTextAnnotationIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedTextAnnotationIds: StateFlow<Set<String>> = _selectedTextAnnotationIds.asStateFlow()

    private val _selectedStrokePreview = MutableStateFlow<List<StrokeWithPoints>>(emptyList())
    val selectedStrokePreview: StateFlow<List<StrokeWithPoints>> = _selectedStrokePreview.asStateFlow()

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
    private val _commitPreview = MutableStateFlow<List<StrokeWithPoints>?>(null)
    val commitPreview: StateFlow<List<StrokeWithPoints>?> = _commitPreview.asStateFlow()

    // Guard extraction workflow from accidental re-entry (e.g., rapid double taps).


    // 座標/多邊形/命中測試見 SelectionGeometry.kt

    /**
     * S1 雙寫：把頁空間實體的 docY 重算（docY = pageIndex × stride + 頁內頂邊）。
     * transformStrokes/copy 會攜帶陳舊 docY，所有落庫前必經此處（replaceStrokeSnapshots
     * 是移動提交的唯一 choke；新建在構造時直接填）。
     */
    private fun StrokeWithPoints.redoc(): StrokeWithPoints {
        val s = stroke
        return copy(stroke = s.copy(docY = s.pageIndex * docStride + s.boundsTop))
    }

    private fun TextAnnotationEntity.redoc(): TextAnnotationEntity =
        copy(docY = pageIndex * docStride + modelY)

    private fun ImageAnnotationEntity.redoc(): ImageAnnotationEntity =
        copy(docY = pageIndex * docStride + modelY)

    private suspend fun replaceStrokeSnapshots(strokes: List<StrokeWithPoints>) {
        // 走 repository 的漏斗：覆蓋＋bump 在同一交易里。自己拼三步會漏 bump，
        // 序號被蓋回舊值，仲裁就看不出這筆被改過（§15.1）。
        repos.strokes.replaceStrokeSnapshots(strokes.map { it.redoc() })
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
            srcFirst.map { DocTransform.canvasToModel(it, cW, cH, modelWidth, modelHeight) }
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
        viewModelScope.launch(Dispatchers.Default) {
            val allStrokes = mutableListOf<StrokeWithPoints>()
            val allImages = mutableListOf<ImageAnnotationEntity>()
            val allTexts = mutableListOf<TextAnnotationEntity>()
            for ((pg, polys) in polygonsByPage) {
                val norms = polys.filter { it.size >= 3 }
                    .map { poly -> poly.map { DocTransform.canvasToModel(it, cW, cH, modelWidth, modelHeight) } }
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
        val normalizedDelta = DocTransform.canvasToModel(delta, canvasW, canvasH, modelWidth, modelHeight)
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
                    // 與墨同幅繞回（之前只鉗制沒減 wrapY，圖黏在目標頁紙邊）。
                    modelY = (ann.modelY - wrapY).coerceIn(0f, modelHeight)
                )
            }
            movedTexts = movedTexts.map { ann ->
                // 字與墨圖同幅繞回；docY 同步重算（見 S1 雙寫）。
                val newY = (ann.modelY - wrapY).coerceIn(0f, modelHeight)
                ann.copy(
                    pageIndex = targetPage,
                    modelX = ann.modelX.coerceIn(0f, modelWidth),
                    modelY = newY,
                    docY = targetPage * docStride + newY
                )
            }
        }

        // S1 雙寫：落庫/入 undo 指令前把 docY 重算（冪等；replaceStrokeSnapshots 會再算一次也無妨）
        movedStrokes = movedStrokes.map { it.redoc() }
        movedImages = movedImages.map { it.redoc() }
        movedTexts = movedTexts.map {
            it.copy(docY = it.pageIndex * docStride + it.modelY)
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
        val redocUpdated = updated.map { it.redoc() }
        val redocUpdatedImages = updatedImages.map { it.redoc() }
        val redocUpdatedTexts = updatedTexts.map { it.redoc() }

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
        viewModelScope.launch(Dispatchers.IO) {
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

    /** 選取歸屬頁：筆看第一筆的頁，純圖選看圖所在頁，空白框選看框落在哪頁，都無才退回作用頁。 */
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
        selectedTextsSnapshot().firstOrNull()?.let { return it.pageIndex }
        if (_lassoPolygon.value.isNotEmpty() || _lastLassoPolygon.value.isNotEmpty()) {
            return _lassoPage.value
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

    /** 套索選中的字快照（與圖同模式掃全頁流；呼叫方保證主線程）。 */
    private fun selectedTextsSnapshot(): List<TextAnnotationEntity> {
        val ids = _selectedTextAnnotationIds.value
        if (ids.isEmpty()) return emptyList()
        val found = mutableListOf<TextAnnotationEntity>()
        for (flow in pageFlows.values) {
            found += flow.value.texts.filter { it.id in ids }
        }
        if (found.isNotEmpty()) return found
        return currentTextAnnotations.value.filter { it.id in ids }
    }

    /**
     * Overlay 用：主線程同步快照（只讀 flows 當前值；拖曳中實體不變，安全）。
     * 跨頁拖曳時選中圖也要畫在紙上層。
     */
    fun selectedImagesNow(): List<ImageAnnotationEntity> = selectedImagesSnapshot()

    /** 同上，給 overlay 畫選中字（拖曳跨頁全程可見）。 */
    fun selectedTextsNow(): List<TextAnnotationEntity> = selectedTextsSnapshot()

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
        viewModelScope.launch(Dispatchers.IO) {
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

        viewModelScope.launch(Dispatchers.IO) {
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
                StrokeWithPoints(copiedStroke, copiedPoints)
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

    /**
     * 整頁合成、套索裁切與落檔：見 [com.vic.inkflow.ui.editor.ExportController]。
     *
     * 這裡只做接線。ViewModel 保留同名 public 方法轉發，所以呼叫點
     * （EditorScreen、PageWorkspace）不需要改；會變的是「這件事住在哪個類別」。
     */
    private val exportController = ExportController(
        repos = repos,
        documentUri = documentUri,
        modelWidth = { modelWidth },
        modelHeight = { modelHeight },
        lassoPolygon = { _lassoPolygon.value.ifEmpty { _lastLassoPolygon.value } },
        // R3：整組記一格（圖＋新建頁），復原整組撤銷。
        onRegionExtracted = { image, pageIndex ->
            pushUndo(DrawCommand.ExtractToNewPage(pageIndex, image))
            clearSelection()
            onToolSelected(Tool.PEN)
        },
        onShareFinished = {
            clearSelection()
            onToolSelected(Tool.PEN)
        }
    )

    /** 整頁截圖存分享檔（工具列 AI 區「整頁送 AI」用；不裁剪不遮罩不寫 DB）。 */
    suspend fun capturePageToShareFile(
        context: android.content.Context,
        sourcePageIndex: Int,
        pdfPageBitmap: android.graphics.Bitmap?
    ): java.io.File? = exportController.capturePageToShareFile(context, sourcePageIndex, pdfPageBitmap)

    /** 套索裁切 → 成一張新頁上的圖片註解（並記一格可復原的 undo）。 */
    suspend fun extractRegionToNewPage(
        context: android.content.Context,
        sourcePageIndex: Int,
        targetPageIndex: Int,
        pdfPageBitmap: android.graphics.Bitmap?
    ): Boolean = exportController.extractRegionToNewPage(context, sourcePageIndex, targetPageIndex, pdfPageBitmap)

    /** 套索裁切 → 存成分享用的 PNG（不寫 DB）。 */
    suspend fun extractRegionToShareFile(
        context: android.content.Context,
        sourcePageIndex: Int,
        pdfPageBitmap: android.graphics.Bitmap?
    ): java.io.File? = exportController.extractRegionToShareFile(context, sourcePageIndex, pdfPageBitmap)
}