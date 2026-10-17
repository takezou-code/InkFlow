package com.vic.inkflow.ui

import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import com.vic.inkflow.ui.theme.Motion
import com.vic.inkflow.ui.theme.ShapeSm
import com.vic.inkflow.ui.theme.ShapeMd
import com.vic.inkflow.ui.theme.ShapeLg
import com.vic.inkflow.ui.theme.ShapeXl

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.BackHand
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import android.util.Log
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vic.inkflow.R
import com.vic.inkflow.data.repository.InkFlowRepositories
import com.vic.inkflow.data.AppDatabase
import com.vic.inkflow.data.DocumentEntity
import com.vic.inkflow.data.FolderEntity
import com.vic.inkflow.data.ImageAnnotationEntity
import com.vic.inkflow.data.StrokeWithPoints
import com.vic.inkflow.data.TextAnnotationEntity
import com.vic.inkflow.ui.backdrop.BackdropScene
import com.vic.inkflow.ui.backdrop.DuneScene
import com.vic.inkflow.ui.theme.InkFlowTheme


import com.vic.inkflow.ui.theme.Slate50
import com.vic.inkflow.ui.theme.Slate100
import com.vic.inkflow.ui.theme.Slate900
import com.vic.inkflow.util.PdfManager
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// --- 3. Editor Screen ---
// 側欄階段定義在 SidebarStage.kt（含狀態機與單元測試），這裡不再重複定義。

@Composable
fun TabletEditorScreen(
    navController: NavController,
    uri: Uri,
    db: AppDatabase,
    isPowerSaver: Boolean = false,
    backdropTheme: BackdropTheme = BackdropTheme.SOFT,
    backdropKind: BackdropKind = BackdropKind.ORB,
    backdropScene: BackdropScene = DuneScene,
    backdropImageUri: android.net.Uri? = null,
    onTogglePowerSaver: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("inkflow_settings", 0) }
    // 当前文件身份：AI 记忆（provider＋上次对话 URL）按它 key。
    // 直接 toString，不 remember：重组每次都算，结果一样，比 key 漏 key 安全。
    val docKey = uri.toString()
    // AI 來源：工具列小圖示切換，持久化在設定 prefs（重開 App 記得）。
    // 第一優先是「這份文件上次用的站」，沒有才退回全局（老版本只有全局）。
    var aiProvider by rememberSaveable {
        mutableStateOf(
            runCatching {
                prefs.getString(aiDocProviderKey(docKey), null)?.let { AiProvider.valueOf(it) }
                    ?: AiProvider.valueOf(prefs.getString(KEY_AI_PROVIDER, AiProvider.GEMINI.name) ?: AiProvider.GEMINI.name)
            }.getOrDefault(AiProvider.GEMINI)
        )
    }
    // 切文件＝換記憶：先把這份文件記的站讀回來（沒有記錄就沿用全局，不強跳）。
    androidx.compose.runtime.LaunchedEffect(docKey) {
        val saved = runCatching {
            prefs.getString(aiDocProviderKey(docKey), null)?.let { AiProvider.valueOf(it) }
        }.getOrNull()
        if (saved != null && saved != aiProvider) aiProvider = saved
    }
    // 這份文件＋這個站該進的地址（記憶沒有就進站首頁）。provider 切換會重建
    // WebView（key 在面板裡），factory 用這個值當出生第一頁；同站切文件則
    // 走下面的還原 effect（不重建，只 loadUrl）。
    val aiInitialUrl = androidx.compose.runtime.remember(docKey, aiProvider) {
        aiProvider.resolveRestoreUrl(prefs.getString(aiDocUrlKey(docKey, aiProvider), null))
    }
    // P2：整個畫面只建一次 repository 容器，取代到處直接摸 AppDatabase。
    val repos = remember(db) { InkFlowRepositories(db) }
    val settingsRepository = remember(db, prefs) {
        DefaultEditorSettingsRepository(
            db = db,
            documentPreferences = repos.documentPreferences,
            prefs = prefs
        )
    }
    val viewModel: EditorViewModel = viewModel(
        factory = EditorViewModelFactory(repos, uri.toString(), settingsRepository)
    )
    val pdfViewModel: PdfViewModel = viewModel()
    val strokes by viewModel.currentStrokes.collectAsState()
    val scope = rememberCoroutineScope()

    val docViewModel: DocumentViewModel = viewModel(
        factory = DocumentViewModelFactory(repos)
    )
    // enum 不是 Bundle 可存的類型，所以存 ordinal 才有 rememberSaveable 的效果。
    var sidebarStageOrdinal by rememberSaveable { mutableIntStateOf(SidebarStage.RAIL.ordinal) }
    val sidebarStage: SidebarStage =
        SidebarStage.ordered[sidebarStageOrdinal.coerceIn(0, SidebarStage.ordered.lastIndex)]
    val activeTool by viewModel.selectedTool.collectAsState()
    var currentPageIndex by rememberSaveable { mutableIntStateOf(0) }
    var showStrokeWidthSlider by rememberSaveable { mutableStateOf(false) }
    // Guards against overwriting the DB value before we've read it on first open
    var initialPageRestored by rememberSaveable { mutableStateOf(false) }

    var showAiPanel by rememberSaveable { mutableStateOf(false) }
    var aiPanelWeight by rememberSaveable { mutableFloatStateOf(0.4f) }
    // L0 意圖層：兩個入口（整頁鈕／圈選快捷列）都只產生一個 AiRequest，
    // 不再各自維護 fileUri／prompt／autoSend 三個隱性變數。
    // seq 給每個請求一個遞增序號：去重靠序號，不靠內容推導（內容會在投遞後被清掉）。
    var aiRequestSeq by remember { mutableLongStateOf(0L) }
    var aiRequest by remember { mutableStateOf<AiRequest?>(null) }
    var isSendingPage by remember { mutableStateOf(false) }
    // 抽屜：面板寬度依內容區實寬算（不再用螢幕寬）、滑動進度、WebView 是否活著
    var contentW by remember { mutableIntStateOf(0) }
    val aiDrawerProgress = remember { Animatable(0f) }
    var aiPanelLive by remember { mutableStateOf(false) }
    // M2b-2：聰明圈選 — 引入鈕兩段式：①進圈選模式（段落打勾）②收集打勾段落
    var aiPickMode by remember { mutableStateOf(false) }
    var aiPickEnterId by remember { mutableStateOf(0) }
    // AI 面板黑白切換（預設亮底；只影響 Gemini 網頁，不動 App 主題）
    // Gemini 網頁自己的淺/深色不再手動：直接跟 App 深淺色走（見 isEditorDark，:420）。
    var aiPickCollectId by remember { mutableStateOf(0) }
    var aiWebView by remember { mutableStateOf<android.webkit.WebView?>(null) }
    // 回调用的 docKey 快照：面板报 URL 是异步的，直接读 docKey 会抓到旧闭包。
    val docKeyRef = androidx.compose.runtime.rememberUpdatedState(docKey)
    // 切文件／切站還原：目标地址跟 WebView 当前地址一样就不动，避免无谓重载
    // （SPA 切对话不闪）。WebView 还没出生（null）时跳过，factory 会用 aiInitialUrl。
    androidx.compose.runtime.LaunchedEffect(docKey, aiProvider, aiWebView) {
        val wv = aiWebView ?: return@LaunchedEffect
        val target = aiProvider.resolveRestoreUrl(prefs.getString(aiDocUrlKey(docKey, aiProvider), null))
        if (wv.url != target) {
            try {
                android.util.Log.d("InkFlowDbg", "AI restore doc=$docKey -> $target")
                wv.loadUrl(target)
            } catch (_: Throwable) { }
        }
    }
    fun switchAiProvider() {
        val next = aiProvider.other
        aiProvider = next
        // 全局照寫（新文件預設站），這份文件的站另外記（切文件回來還認得）。
        prefs.edit().putString(KEY_AI_PROVIDER, next.name).apply()
        prefs.edit().putString(aiDocProviderKey(docKey), next.name).apply()
        // 換站＝換對話。清掉待送請求/圈選武裝，避免舊站的東西跨站重複注入。
        aiRequest = null
        aiPickMode = false
        aiPickEnterId = 0
        aiPickCollectId = 0
        android.widget.Toast.makeText(context, "已切換到 ${next.label}", android.widget.Toast.LENGTH_SHORT).show()
    }
    val sidebarListState = rememberLazyListState()
    val mainListState = rememberLazyListState()
    val pinchActive by viewModel.pinchActive.collectAsState()
    // 頁數訂閱提前到當前頁狀態機之前：applyCommit／previewNavigate／settle 都要夾取，
    // 詞法作用域要求先宣告。只是 State 訂閱，提前無副作用。
    val pageCount by pdfViewModel.pageCount.collectAsState()
    // 初次捲到位旗標：PDF 載入前主列表停在第 0 頁，此時 PageWorkspace 的跟隨回報必須忽略，
    // 否則會把剛從 DB 讀回的頁碼洗回 0（記住上次頁面失效的主因之一）。
    // 純 remember（不 Saveable）：旋轉重建後回到 false，剛好重捲一次。
    var initialScrollDone by remember(uri) { mutableStateOf(false) }
    // 當前頁單一標準（見 CurrentPageOwner）：主紙最大可見頁經穩定門才提交；
    // 側欄拖動／scrub 的手指目標先當 preview（紙跟著走），放手／到位／接管才提交。
    // 這裡取代舊的 programmaticTarget＋2.5s 超時：預期／接管／超時規則寫死在狀態機裡，用單測釘住。
    val pageOwner = remember { CurrentPageOwner() }
    var previewPage by remember { mutableStateOf<Int?>(null) }
    val settleGen = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    var previewNavJob by remember { mutableStateOf<Job?>(null) }
    // 單一提交點：全編輯器只有這裡能寫 currentPageIndex＋setActivePage。
    // DB 補寫與側欄置中沿用下方既有 effect（只聽 committed 變化）。
    fun applyCommit(commit: PageCommit, prefetch: Boolean) {
        previewPage = null
        if (commit.page == currentPageIndex) return
        currentPageIndex = commit.page
        viewModel.setActivePage(commit.page, prefetch = prefetch)
    }
    // 穩定門驅動：候選不變滿 settleMs 才提交；結構操作中凍結（留著候選等操作結束）。
    fun scheduleSettleCommit() {
        val g = settleGen.incrementAndGet()
        scope.launch {
            kotlinx.coroutines.delay(pageOwner.settleMs)
            if (g != settleGen.get()) return@launch
            if (pdfViewModel.isPageOperationInProgress.value) return@launch
            val now = android.os.SystemClock.uptimeMillis()
            pageOwner.settle(currentPageIndex, pageCount, now)?.let { applyCommit(it, prefetch = false) }
        }
    }
    // 預覽導航（側欄拖動／scrub 中）：紙跟著手指走，但不提交；
    // 到位回報經 onScrollPage→owner 判為 arrival 才提交，放手也會結算。
    fun previewNavigate(index: Int) {
        val target = pageOwner.clamp(index, pageCount)
        if (target == currentPageIndex && previewPage == null) return
        previewPage = target
        pageOwner.expectTarget(target, pageCount, android.os.SystemClock.uptimeMillis())
        previewNavJob?.cancel()
        previewNavJob = scope.launch {
            kotlinx.coroutines.delay(80)
            runCatching { mainListState.animateScrollToItem(target) }
        }
    }
    // 程式化捲動的命：手勢一接管（pinchActive）立刻取消，未跑完的動畫不許在手勢中/手勢後
    // 把紙拽回舊目標（反方向回彈主因）。owner 的預期跟著清。
    var programmaticScrollJob by remember { mutableStateOf<Job?>(null) }
    androidx.compose.runtime.LaunchedEffect(pinchActive) {
        if (pinchActive) {
            programmaticScrollJob?.cancel()
            programmaticScrollJob = null
            previewNavJob?.cancel()
            previewNavJob = null
            pageOwner.cancelExpected()
        }
    }
    // 點選意圖（側欄點選／網格點選／放手結算）：立刻提交＋設預期，過渡頁忽略。
    // 用戶親自點了 = 接管，初次捲動不再搶回去。
    val onRequestPage: (Int) -> Unit = { index ->
        initialScrollDone = true
        val commit = pageOwner.commitNow(index, PageOwner.ProgrammaticNav, pageCount)
        pageOwner.expectTarget(commit.page, pageCount, android.os.SystemClock.uptimeMillis())
        applyCommit(commit, prefetch = true)
        previewNavJob?.cancel()
        programmaticScrollJob?.cancel()
        programmaticScrollJob = scope.launch { runCatching { mainListState.animateScrollToItem(commit.page) } }
    }
    // R3：提取整組撤銷的頁操作接線（VM 碰不到 PdfViewModel/Context，由這層提供；
    // model 尺寸每次現讀，避免閉包陳舊）。
    viewModel.extractPageOps = EditorViewModel.ExtractPageOps(
        deletePage = { target -> pdfViewModel.deletePages(uri.toString(), listOf(target)) },
        insertPageAfter = { after ->
            pdfViewModel.insertBlankPage(
                context, uri.toString(), after,
                pageWidthPt = viewModel.modelWidth,
                pageHeightPt = viewModel.modelHeight
            )
        }
    )

    // AI 引入管線實作見 AiImportFlow.kt（切塊→KaTeX→排版→錨定當前頁→寫入→跳轉）。
    // 薄包裝：勾選混排（文字＋公式裁圖），實作在 AiImportFlow.kt。
    // aiPlacing：整批放置中壓住「插頁自動導航」，中間每開一頁不跳，只留最後一次跳轉。
    var aiPlacing by remember { mutableStateOf(false) }
    fun importPickedJson(json: String) {
        aiPlacing = true
        scope.importPickedJson(json, context, viewModel, pdfViewModel, repos, uri.toString(), currentPageIndex, onRequestPage, context as? android.app.Activity, aiWebView,
            latestPage = { currentPageIndex },
            onSettled = { aiPlacing = false })
    }
    // AI 區「匯入回覆」兩段式：①進圈選模式（段落打勾）②收集打勾段落（沒勾則不插入）
    // 按鈕常駐工具列，所以抽屜收起時也要能用：武裝時順手把抽屜滑開，
    // 否則使用者看不到打勾框，等於按了沒反應。
    fun toggleAiImport() {
        // S2：能力檢查讀 capabilities，不再寫 `if (aiProvider != GEMINI)`。
        // 換 provider 時這裡不用動，開啟條件由 AiProvider 自己宣告。
        if (!aiProvider.capabilities.importReply) {
            android.widget.Toast.makeText(
                context,
                "${aiProvider.label} 還沒支援匯入回覆",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (!aiPickMode) {
            aiPickMode = true
            aiPickEnterId++
            if (!showAiPanel) showAiPanel = true
            android.widget.Toast.makeText(context, "點 ${aiProvider.label} 回覆的段落打勾，再按一次匯入抓取", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            aiPickMode = false
            aiPickCollectId++
        }
    }

    // AI 區「整頁送 AI」：整頁圖貼上＋填「解釋」即停，不自動送出
    // （與套索快捷列同一提示詞常數，差別只在這裡 autoSend=false）
    fun sendPageToAi() {
        if (isSendingPage) return
        // S2：同上，讀 capabilities。
        if (!aiProvider.capabilities.sendImage) {
            android.widget.Toast.makeText(
                context,
                "${aiProvider.label} 還沒支援自動送圖",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        isSendingPage = true
        scope.launch {
            try {
                val pageIdx = currentPageIndex
                val bmp = kotlinx.coroutines.withTimeoutOrNull(1200) {
                    pdfViewModel.getPageBitmap(pageIdx).filterNotNull().first()
                } ?: pdfViewModel.getPageBitmap(pageIdx).value
                val file = viewModel.capturePageToShareFile(context, pageIdx, bmp)
                android.util.Log.d("InkFlowDbg", "PAGESHOT send p=$pageIdx bmpNull=${bmp == null} bytes=${file?.length() ?: -1}")
                if (file != null) {
                    aiRequest = AiRequest(
                        id = ++aiRequestSeq,
                        action = AiAction.SEND_PROMPT,
                        image = androidx.core.content.FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", file
                        ),
                        prompt = AiQuickPrompt.EXPLAIN,
                        // 整頁鈕：圖貼上＋填詞即停，不自動送出（套索快捷列才自動送）
                        autoSend = false
                    )
                    showAiPanel = true
                } else {
                    android.widget.Toast.makeText(context, "整頁截圖失敗，請稍後再試", android.widget.Toast.LENGTH_SHORT).show()
                }
            } finally {
                isSendingPage = false
            }
        }
    }
    // 卷動跟隨：主列表的最大可見頁先當候選（不直接寫），穩定門／到位／接管由 pageOwner 判。
    // 不捲主列表（避免打架）；側欄置中與 DB 補寫由下方 committed effect 做。
    val onScrollPage: (Int) -> Unit = { index ->
        if (initialScrollDone && index != currentPageIndex) {
            val now = android.os.SystemClock.uptimeMillis()
            val arrival = pageOwner.onCandidate(PageOwner.MainScroll, index, pageCount, now)
            if (arrival != null) {
                // 到位／接管：提交。排放側已 prefetchPages(first, last)，此處不再重查。
                applyCommit(arrival, prefetch = false)
            } else {
                if (index != currentPageIndex) previewPage = pageOwner.clamp(index, pageCount)
                scheduleSettleCommit()
            }
        }
    }
    // 編輯器兩個玻璃 state，用途不同、不可合一：
    //
    // editorHaze —— source＝背景層 ＋ 整屏 root。給「跨視窗」的東西用（對話框、氣泡）。
    //   對話框是另一個視窗，採主視窗已畫好的像素，本來就正常。
    //
    // chromeHaze —— source＝背景層 ＋ 紙，**不含工具列自己**。給工具列／粗細滑桿用。
    //   為什麼要拆：若玻璃的來源包含自己（玻璃在 hazeSource 子樹內），上一幀的模糊結果
    //   又被這幀拿去模糊 → self-feedback，黑玻璃與折射會整個失效（症狀：整片糊、沒有折射）。
    //   這是實測踩過的坑，不是推測。
    val editorHaze = rememberHazeState()
    val chromeHaze = rememberHazeState()
    val isEditorDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    // 離開編輯器時刷新書庫封面（否則畫完墨水回主頁封面永遠是舊的）+ 補寫當前頁
    // （防抖寫入 150ms 還沒落定就退出時，DB 會停在舊頁碼；這裡用最新值再寫一次兜底）
    val latestPageRef = androidx.compose.runtime.rememberUpdatedState(currentPageIndex)
    androidx.compose.runtime.DisposableEffect(uri) {
        onDispose {
            docViewModel.updateThumbnail(context, uri.toString())
            docViewModel.updateLastPage(uri.toString(), latestPageRef.value)
        }
    }

    // Restore the last-viewed page from DB on first open; rememberSaveable keeps it
    // true across config changes so we don't reset the page on rotation.
    // 注意：這裡只讀 DB、不捲動——PDF 此时還沒 open（pageCount=0），捲了也沒用；
    // 真正的捲動等下方「pageCount > 0」effect 做（修：重開永遠停在第 1 頁）。
    androidx.compose.runtime.LaunchedEffect(uri) {
        if (!initialPageRestored) {
            val stored = docViewModel.getLastPageIndex(uri.toString())
            if (stored > 0) {
                // 還原走單一提交點（owner=Restore），不設預期：捲動等下方 pageCount effect 做。
                // 這裡 pageCount 還是 0（PDF 還沒 open），夾取必須 bypass（只做提交，不做夾）。
                applyCommit(pageOwner.commitNow(stored, PageOwner.Restore, Int.MAX_VALUE), prefetch = true)
            }
            initialPageRestored = true
        }
    }

    // Persist current page and sync sidebar scroll whenever the user navigates or changes sidebar mode
    // 跟隨門衛：主列表推側欄時舉旗，側欄推主列表的那條看到旗就讓路，
    // 否則兩邊互推、底端來回彈（4↔5跳不停）。State 寫一天幾次，重組成本忽略。
    var sidebarFollowActive by remember { mutableStateOf(false) }
    // 側欄帶頭時間戳：側欄點選/轉頁蓋章，1 秒內跟隨不回拉，讓 fling 飛完。
    var lastSidebarDriveMs by remember { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(currentPageIndex, sidebarStage) {
        // 旗子從 effect 一進來就舉（涵蓋防抖等待期），結束/取消才放下——
        // 之前只包著 animate，等待期有洞，迴圈從洞裡鑽。
        sidebarFollowActive = true
        try {
            if (initialPageRestored) {
                // 側欄帶的頭不回拉：1 秒內是側欄點選/轉頁造成的換頁，
                // 回拉會勒死側欄的 fling（只能一頁一頁切）。主列表自己動的不影響。
                // 但 DB 照寫，不然甩完直接退出、重開頁碼是舊的。
                if (SystemClock.uptimeMillis() - lastSidebarDriveMs < 1000) {
                    docViewModel.updateLastPage(uri.toString(), currentPageIndex)
                    return@LaunchedEffect
                }
                // 防抖：滑動中頁碼連跳時，每次重進 effect 會重設計時，
                // 只有停穩 150ms 才跟側欄 + 寫 DB。
                kotlinx.coroutines.delay(150)
                docViewModel.updateLastPage(uri.toString(), currentPageIndex)
                // P2 快滑：高速捲動中別排 animateScrollToCenter（每幀取消重進，
                // 動畫永遠排不到落地，側欄停在舊頁）。改成瞬時定位，不排隊。
                // 注意：用 scrollToCenter 不是 scrollToItem —— scrollToItem 只把頁碼對齊
                // viewport「起點」，不置中。收合模式 contentPadding 幾乎半個視窗高，
                // 用 scrollToItem 會把當前頁留在很靠上＝中央丸子那裡是空的（置中跑掉）。
                if (pdfViewModel.isScrollingFast.value) {
                    // scrollToCenter 本身已對陳舊 index 做邊界檢查，這裡直接呼叫
                    // （runCatching 的 block 不是 suspend lambda，會編譯不過）
                    sidebarListState.scrollToCenter(currentPageIndex)
                } else {
                    sidebarListState.animateScrollToCenter(currentPageIndex)
                }
            }
        } finally {
            sidebarFollowActive = false
        }
    }

    // Resolve the human-readable file name from the documents DB record.
    // ContentResolver does not work with file:// URIs (copied PDFs use UUID filenames),
    // so we look up the displayName that was saved at import time.
    val documents by docViewModel.documents.collectAsState()
    val documentTitle = remember(uri, documents) {
        documents.find { it.uri == uri.toString() }?.displayName
            ?: uri.lastPathSegment ?: "Untitled"
    }

    // Open PDF once; PdfViewModel survives config changes
    androidx.compose.runtime.LaunchedEffect(uri) {
        docViewModel.markDocumentOpened(uri.toString())
        pdfViewModel.openPdf(uri)
    }
    // pageCount 訂閱已上移（當前頁狀態機之前），這裡不再重複宣告。
    // PDF 載入完成後再捲到記憶頁：restore effect 跑時 pageCount 還是 0（openPdf 還沒回來），
    // 在那裡捲等於沒捲。等首個有效 pageCount 落定、DB 值已讀回，才一次捲到位；
    // 之後頁數變化（增刪頁）不再亂捲，只做夾取。門由 initialScrollDone 擋跟隨回寫。
    androidx.compose.runtime.LaunchedEffect(pageCount, initialPageRestored) {
        if (initialPageRestored && !initialScrollDone && pageCount > 0) {
            val safe = pageOwner.clamp(currentPageIndex, pageCount)
            applyCommit(pageOwner.commitNow(safe, PageOwner.Restore, pageCount), prefetch = true)
            runCatching { mainListState.scrollToItem(safe) }
            runCatching { sidebarListState.scrollToCenter(safe) }
            initialScrollDone = true
        }
    }
    val isPageOperationInProgress by pdfViewModel.isPageOperationInProgress.collectAsState()
    val pageOperationMessage by pdfViewModel.pageOperationMessage.collectAsState()

    androidx.compose.runtime.LaunchedEffect(pageOperationMessage) {
        val message = pageOperationMessage ?: return@LaunchedEffect
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        pdfViewModel.consumePageOperationMessage()
    }

    val insertPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        for (selectedUri in uris) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    selectedUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        // R2：插入 PDF 頁也是結構操作，先清棧再動頁（清棧必須在 launch 前，
        // 否則空窗內 undo 可插隊拿到錯位頁號）。
        viewModel.clearUndoStacks()
        if (uris.size == 1) {
            pdfViewModel.insertPdfPages(
                context = context,
                documentUri = uri.toString(),
                sourceUri = uris.first(),
                afterIndex = currentPageIndex
            )
        } else {
            pdfViewModel.insertMultiplePdfs(
                context = context,
                documentUri = uri.toString(),
                sourceUris = uris,
                afterIndex = currentPageIndex
            )
        }
    }

    // 插圖：整條流程（拷貝／量測／落位／開頁）住這裡，圖庫與相機共用同一條。
    //
    // 為什麼不在 InkCanvas：舊版每頁畫布各掛一個 launcher，靠「imagePickRequest 只增不減
    // 的令牌 + pageIndex == selectionPage()」挑一頁開。畫布掛在 LazyColumn item 上，
    // 捲出視窗或切到側欄第 3 階（EditorScreen 那個 gridOverlayProgress < 0.98 會把整個
    // 紙張移出 composition）都會 dispose 再重生 → 令牌仍是舊的非零值 → 系統圖庫自己彈出來。
    // 現在 launcher 跟按鈕同壽（EditorScreen 不會被逐頁 dispose），令牌整個消失。
    /** 拍攝暫存檔（cacheDir/shared）；相機回來前持有，拷貝完成後即刪。 */
    var pendingCaptureFile by remember { mutableStateOf<java.io.File?>(null) }

    fun importPickedImages(uris: List<Uri>, cleanupFile: java.io.File? = null) {
        if (uris.isEmpty()) return
        scope.launch {
            data class ImportedImage(val localUriString: String, val imagePixelWidth: Int, val imagePixelHeight: Int)

            val imported = withContext(Dispatchers.IO) {
                uris.mapNotNull { picked ->
                    val localUri = com.vic.inkflow.util.PdfManager.copyImageToAppDir(context, picked)
                        ?: return@mapNotNull null
                    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(localUri)?.use { stream ->
                        android.graphics.BitmapFactory.decodeStream(stream, null, opts)
                    }
                    ImportedImage(
                        localUriString = localUri.toString(),
                        imagePixelWidth = opts.outWidth.coerceAtLeast(0),
                        imagePixelHeight = opts.outHeight.coerceAtLeast(0)
                    )
                }
            }

            // 拍攝暫存檔：拷貝完成（成功或失敗）就刪 —— 絕不能早於讀取。
            // 舊 bug：在 coroutine 讀檔之前先 delete，讀到 ENOENT → copyImageToAppDir
            // 回 null → imported 全空 → 整張照片從沒被建立（實機 logcat 實證）。
            cleanupFile?.delete()

            if (imported.isEmpty()) return@launch

            suspend fun waitForInsertedPage(previousPageCount: Int): Boolean {
                return withTimeoutOrNull(10_000) {
                    while (pdfViewModel.pageCount.value < previousPageCount + 1) {
                        delay(50)
                    }
                    true
                } == true
            }

            // 落位基準＝按下按鈕當下的作用頁（不是 selectionPage()：選取可能留在別頁，
            // 那會讓圖插到使用者根本沒在看的那頁）。多選：第 1 張放本頁，後續各開新頁。
            val basePageIndex = currentPageIndex
            var lastInsertedImageId: String? = null
            if (imported.size == 1) {
                val item = imported.first()
                lastInsertedImageId = viewModel.placeImageAnnotationOnPage(
                    uri = item.localUriString,
                    targetPageIndex = basePageIndex,
                    imagePixelWidth = item.imagePixelWidth,
                    imagePixelHeight = item.imagePixelHeight
                )
            } else {
                var insertAfterIndex = basePageIndex
                imported.forEachIndexed { index, item ->
                    if (index == 0) {
                        lastInsertedImageId = viewModel.placeImageAnnotationOnPage(
                            uri = item.localUriString,
                            targetPageIndex = basePageIndex,
                            imagePixelWidth = item.imagePixelWidth,
                            imagePixelHeight = item.imagePixelHeight
                        )
                    } else {
                        // P0：多頁插入會移位頁號，先清復原棧（單張不動頁，不用清）。
                        viewModel.clearUndoStacks()
                        val previousPageCount = pdfViewModel.pageCount.value
                        pdfViewModel.insertBlankPage(
                            context = context,
                            documentUri = uri.toString(),
                            afterIndex = insertAfterIndex,
                            pageWidthPt = viewModel.modelWidth,
                            pageHeightPt = viewModel.modelHeight
                        )
                        val inserted = waitForInsertedPage(previousPageCount)
                        if (!inserted) return@forEachIndexed

                        val targetPageIndex = insertAfterIndex + 1
                        lastInsertedImageId = viewModel.placeImageAnnotationOnPage(
                            uri = item.localUriString,
                            targetPageIndex = targetPageIndex,
                            imagePixelWidth = item.imagePixelWidth,
                            imagePixelHeight = item.imagePixelHeight
                        )
                        insertAfterIndex = targetPageIndex
                    }
                }
            }

            // 選中最後一張，讓使用者接手就能拖／縮／旋（此時工具已是 IMAGE）。
            // 選取是每頁畫布的私選態 → 交給 ViewModel 的一次性通道，由擁有該 id 的頁面取走。
            lastInsertedImageId?.let(viewModel::requestImageSelect)
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 50)
    ) { uris -> importPickedImages(uris) }

    /**
     * 相機拍照。
     *
     * 權限：manifest 刻意不宣告 CAMERA（宣告了卻沒 grant 反而 SecurityException），
     * 走外部相機 App，零權限零授權框。輸出寫到 cacheDir/shared/<ts>.jpg，
     * file_paths.xml 的 <cache-path name="shared_images" path="shared/"/> 已涵蓋，
     * 再餵同一條 copyImageToAppDir 進 App 私有目錄 —— 與圖庫路徑完全一致。
     */
    // 小米相機自己就有「保留／重拍／取消」三選一，不再做第二次確認：
    // 拍完直接走同一條 importPickedImages 落位＋選中。
    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { saved ->
        val file = pendingCaptureFile
        pendingCaptureFile = null
        // saved=false = 使用者按返回／相機沒寫出檔 → 沒人在讀，直接刪。
        // 成功路徑的暫存刪除交給 importPickedImages（拷貝完成後），絕不早於讀取。
        if (saved && file != null && file.length() > 0L) {
            importPickedImages(
                listOf(
                    FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", file
                    )
                ),
                cleanupFile = file
            )
        } else {
            file?.delete()
        }
    }

    // 有沒有相機 App 能接 IMAGE_CAPTURE。Android 11+ 套件可見性靠 manifest 的
    // <queries> 開門（沒有它 resolveActivity 永遠 null → 子選單「拍照」會被誤 disable）。
    // 只是預先 disable 子選單那一列；真正 launch 仍有 runCatching 兜底。
    val hasCameraApp = remember {
        android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
            .resolveActivity(context.packageManager) != null
    }

    fun launchCameraCapture() {
        val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
        val file = java.io.File(dir, "capture_${System.currentTimeMillis()}.jpg")
        pendingCaptureFile = file
        runCatching {
            takePictureLauncher.launch(
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            )
        }.onFailure {
            // 沒有任何相機 App 可接 → ActivityNotFoundException。
            file.delete()
            pendingCaptureFile = null
            scope.launch {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "找不到相機 App", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 拍攝暫存檔的最終回收：相機還開著／拷貝還沒跑完就離開編輯器也不留孤兒檔。
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { pendingCaptureFile?.delete() }
    }

    // When the PDF first loads, initialize the EditorViewModel's model space to match the first page.
    val firstPageSize by pdfViewModel.firstPageSize.collectAsState()
    androidx.compose.runtime.LaunchedEffect(firstPageSize) {
        firstPageSize?.let { (w, h) -> viewModel.initializePaperSize(w, h) }
    }

    // Document settings dialog state
    var showDocumentSettingsDialog by remember { mutableStateOf(false) }
    var showExportConfirmDialog by remember { mutableStateOf(false) }
    var isExportingPdf by remember { mutableStateOf(false) }
    var selectedExportIndices by remember { mutableStateOf<List<Int>>(emptyList()) }
    var isExportingSelection by remember { mutableStateOf(false) }
    val paperStyle by viewModel.paperStyle.collectAsState()
    AnimatedDialog(visible = showDocumentSettingsDialog) {
        DocumentSettingsDialog(
            documentTitle = documentTitle,
            pageCount = pageCount,
            currentPageIndex = currentPageIndex,
            currentStyle = paperStyle,
            isPageOperationInProgress = isPageOperationInProgress,
            onDismiss = { showDocumentSettingsDialog = false },
            onConfirmStyle = { viewModel.setPaperStyle(it) },
            onInsertPdf = {
                showDocumentSettingsDialog = false
                insertPdfLauncher.launch(arrayOf("application/pdf"))
            },
            hazeState = editorHaze
        )
    }

    AnimatedDialog(visible = showExportConfirmDialog) {
        GlassDialogCustom(
            onDismissRequest = {
                if (!isExportingPdf) showExportConfirmDialog = false
            },
            isDark = isEditorDark,
            hazeState = editorHaze,
            title = { Text("確認輸出 PDF") },
            text = {
                Text(
                    if (isExportingPdf) "正在輸出，請稍候..."
                    else "將輸出目前文件的所有頁面與註記到 Downloads，是否繼續？"
                )
            },
            buttons = {
                GlassTextButton(
                    text = "取消",
                    onClick = { showExportConfirmDialog = false },
                    enabled = !isExportingPdf,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                GlassTextButton(
                    text = if (isExportingPdf) "輸出中" else "確認匯出",
                    onClick = {
                        if (isExportingPdf) return@GlassTextButton
                        isExportingPdf = true
                        scope.launch {
                            try {
                                // 長文件匯出走逐頁取（三表全拉常駐記憶體會爆 heap）；
                                // 圖片解碼另有界，見 PdfExporter.decodeBoundedForExport。
                                val docUriStr = uri.toString()
                                com.vic.inkflow.util.PdfExporter.export(
                                    originalPdfUri = uri,
                                    strokes = emptyList(),
                                    textAnnotations = emptyList(),
                                    imageAnnotations = emptyList(),
                                    context = context,
                                    fileName = "InkFlow_${System.currentTimeMillis()}.pdf",
                                    modelW = viewModel.modelWidth,
                                    modelH = viewModel.modelHeight,
                                    pageDataProvider = { pageIndex ->
                                        Triple(
                                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                repos.strokes.getStrokesForPageSync(docUriStr, pageIndex)
                                            },
                                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                repos.texts.getForPageSync(docUriStr, pageIndex)
                                            },
                                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                repos.images.getForPageSync(docUriStr, pageIndex)
                                            }
                                        )
                                    }
                                )
                            } finally {
                                isExportingPdf = false
                                showExportConfirmDialog = false
                            }
                        }
                    },
                    enabled = !isExportingPdf
                )
            }
        )
    }

    AnimatedDialog(visible = selectedExportIndices.isNotEmpty()) {
        val indices = selectedExportIndices.distinct().sorted()
        GlassDialogCustom(
            onDismissRequest = {
                if (!isExportingSelection) selectedExportIndices = emptyList()
            },
            isDark = isEditorDark,
            hazeState = editorHaze,
            title = { Text("輸出選取頁面") },
            text = {
                Text(
                    if (isExportingSelection) "正在輸出選取頁面，請稍候..."
                    else "將輸出選取的 ${indices.size} 頁與註記到 Downloads，是否繼續？"
                )
            },
            buttons = {
                GlassTextButton(
                    text = "取消",
                    onClick = { selectedExportIndices = emptyList() },
                    enabled = !isExportingSelection,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                GlassTextButton(
                    text = if (isExportingSelection) "輸出中" else "確認匯出",
                    onClick = {
                        if (isExportingSelection || indices.isEmpty()) return@GlassTextButton
                        isExportingSelection = true
                        scope.launch {
                            var extractedUri: android.net.Uri? = null
                            try {
                                extractedUri = PdfManager.extractSelectedPages(context, uri, indices)
                                if (extractedUri == null) {
                                    android.widget.Toast.makeText(context, "選取頁匯出失敗，請再試一次", android.widget.Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                val docUriStr = uri.toString()
                                com.vic.inkflow.util.PdfExporter.export(
                                    originalPdfUri = extractedUri,
                                    strokes = emptyList(),
                                    textAnnotations = emptyList(),
                                    imageAnnotations = emptyList(),
                                    context = context,
                                    fileName = "InkFlow_selected_${System.currentTimeMillis()}.pdf",
                                    modelW = viewModel.modelWidth,
                                    modelH = viewModel.modelHeight,
                                    pageDataProvider = { outputIndex ->
                                        val sourceIndex = indices.getOrNull(outputIndex) ?: -1
                                        Triple(
                                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                if (sourceIndex < 0) emptyList()
                                                else repos.strokes.getStrokesForPageSync(docUriStr, sourceIndex)
                                            },
                                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                if (sourceIndex < 0) emptyList()
                                                else repos.texts.getForPageSync(docUriStr, sourceIndex)
                                            },
                                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                if (sourceIndex < 0) emptyList()
                                                else repos.images.getForPageSync(docUriStr, sourceIndex)
                                            }
                                        )
                                    }
                                )
                            } finally {
                                extractedUri?.path?.let { java.io.File(it).delete() }
                                isExportingSelection = false
                                selectedExportIndices = emptyList()
                            }
                        }
                    },
                    enabled = !isExportingSelection
                )
            }
        )
    }

    // 插圖來源（圖庫／拍照）走工具列 IMAGE 子選單（見 EditorChrome），這裡不再彈框。

    // Per-page aspect ratio: follows each page's real dimensions once scanned;
    // falls back to the document model space (which annotations are normalised against).
    val pageSizeVersion by pdfViewModel.pageSizeVersion.collectAsState()
    val pageAspectRatio = remember(paperStyle.aspectRatio, currentPageIndex, pageSizeVersion) {
        pdfViewModel.getPageAspectRatio(currentPageIndex, paperStyle.aspectRatio)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        InkBackdrop(
            kind = backdropKind,
            orbTheme = backdropTheme,
            scene = backdropScene,
            imageUri = backdropImageUri,
            static = isPowerSaver,
            isDarkTheme = isEditorDark,
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(editorHaze)      // 對話框跨視窗用（整屏）
                .hazeSource(chromeHaze),    // 工具列玻璃用（背景層）
            orbCount = 5
        )
        // 整屏沉浸：工作區 Row 鋪滿全屏墊底，工具列疊在上面（後畫＝在上）。
        // 觸控：工具列只佔自己那塊，其餘落到工作區，跟以前一模一樣。
    // 屏根標記 source：對話框穿窗（Sources）採到整屏，不只背景（跟書庫同做法）
    Box(
        modifier = Modifier
            .fillMaxSize()
            .hazeSource(editorHaze)
    ) {

        // Auto-navigate to the newly inserted page（結構事件走單一提交點，owner=StructuralOp）
        // AI 整批放置中只消費事件不導航（壓住中間跳），最後由 placePages 的 onRequestPage 跳一次。
        val lastInsertedPage by pdfViewModel.lastInsertedPageIndex.collectAsState()
        androidx.compose.runtime.LaunchedEffect(lastInsertedPage) {
            val idx = lastInsertedPage ?: return@LaunchedEffect
            if (aiPlacing) {
                pdfViewModel.consumeInsertedPageEvent()
                return@LaunchedEffect
            }
            if (initialPageRestored) {
                val commit = pageOwner.commitNow(idx, PageOwner.StructuralOp, pageCount)
                pageOwner.expectTarget(commit.page, pageCount, android.os.SystemClock.uptimeMillis())
                applyCommit(commit, prefetch = true)
                sidebarListState.animateScrollToCenter(commit.page)
                programmaticScrollJob?.cancel()
                programmaticScrollJob = scope.launch { runCatching { mainListState.animateScrollToItem(commit.page) } }
                pdfViewModel.consumeInsertedPageEvent()
            }
        }

        // Adjust currentPageIndex after single/multi page delete.
        val lastDeletedPages by pdfViewModel.lastDeletedPageIndices.collectAsState()
        androidx.compose.runtime.LaunchedEffect(lastDeletedPages, pageCount) {
            if (lastDeletedPages.isEmpty()) return@LaunchedEffect
            val clamped = PdfViewModel.remapCurrentPageAfterDeletes(
                currentPageIndex = currentPageIndex,
                deletedIndices = lastDeletedPages,
                pageCountAfter = pageCount
            )
            val commit = pageOwner.commitNow(clamped, PageOwner.StructuralOp, pageCount)
            pageOwner.expectTarget(commit.page, pageCount, android.os.SystemClock.uptimeMillis())
            applyCommit(commit, prefetch = true)
            sidebarListState.animateScrollToCenter(commit.page)
            runCatching { mainListState.scrollToItem(commit.page) }
            pdfViewModel.consumeDeletedPageEvent()
        }

        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val totalWidth = maxWidth
            val density = androidx.compose.ui.platform.LocalDensity.current
            
            val collapsedWidth = 56.dp
            val normalWidth = 128.dp

            // ── 寬度：一個變數、一條動畫路徑 ────────────────────────────────
            //
            // 這裡刻意回到最簡單的形式（等同重構前的 2b928a9）。
            // 前一版把寬度做成「dragPosPx 連續位置 + 加速映射 + 可取消 Job」，
            // 結果是三套機制同時寫同一個值、互相打架 → 彈跳、閃爍、收合變黑屏。
            //
            // 現在只有：
            //   sidebarStage ─→ targetWidth ─→ animateDpAsState ─→ 側欄寬度
            // 第 3 階是獨立疊層，不在寬度軸上。
            val targetWidth = when (sidebarStage) {
                SidebarStage.RAIL -> collapsedWidth
                SidebarStage.PANEL, SidebarStage.GRID -> normalWidth
            }

            val animatableWidth = remember {
                androidx.compose.animation.core.Animatable(
                    when (sidebarStage) {
                        SidebarStage.RAIL -> collapsedWidth.value
                        SidebarStage.PANEL, SidebarStage.GRID -> normalWidth.value
                    }
                )
            }

            // 拖曳鎖：整個手勢期間只有拖曳在寫寬度。
            //
            // 前面幾版壞掉的根因就是「動畫和手指同時寫同一個數值」——
            // 這裡用這把鎖強制單寫入者：拖曳中階段動畫不動，放手後才交給動畫。
            var isDraggingSidebar by remember { mutableStateOf(false) }

            // 收合／展開的過渡動畫。56↔128dp 只差 198px，這段距離重量測側欄內容
            // 的成本可以接受 —— 也正是重構前你回報「手感可以」的那版行為。
            LaunchedEffect(targetWidth) {
                if (!isDraggingSidebar) {
                    animatableWidth.animateTo(
                        targetValue = targetWidth.value,
                        animationSpec = Motion.snapSpring()
                    )
                }
            }

            // animatableWidth.value 是 Float，單位是 **dp**（56 / 128）。
            // 必須用 `.dp` 封裝，**不能**用 Float.toDp() —— 後者會再乘一次 density
            // （56 × 2.75 = 154dp），是雙重換算，側欄寬度整個錯掉。
            val currentWidthDp = animatableWidth.value.dp

            // 第 3 階疊層的淡入淡出。0→1 與 1→0 都走這裡，不碰寬度軸。
            val gridOverlayProgress by animateFloatAsState(
                targetValue = if (sidebarStage == SidebarStage.GRID) 1f else 0f,
                animationSpec = tween(
                    durationMillis = if (sidebarStage == SidebarStage.GRID) {
                        Motion.DURATION_NORMAL
                    } else {
                        Motion.DURATION_FAST
                    },
                    easing = androidx.compose.animation.core.FastOutSlowInEasing
                ),
                label = "GridOverlay"
            )

            fun goToStage(stage: SidebarStage) {
                sidebarStageOrdinal = stage.ordinal
            }

            // 系統返回：逐階退回收合態，收合態再按才交還上層（離開編輯器）。
            // 只在 stage != RAIL 時啟用，否則會搶走全域返回行為。
            BackHandler(enabled = sidebarStage != SidebarStage.RAIL) {
                SidebarStageMachine.onSystemBack(sidebarStage)?.let { goToStage(it) }
            }

            // AI 抽屜的可用寬度基準：側欄寬度往右到螢幕右緣。
            val sidebarVisualWpx = with(density) { currentWidthDp.toPx() }


            // 工具列高度＝側欄／AI 欄要讓出的上邊界。只有紙（工作區）不讓，
            // 讓它往上穿過工具列從玻璃底下透出來。
            val sliderShown = showStrokeWidthSlider &&
                (activeTool == Tool.PEN || activeTool == Tool.HIGHLIGHTER)
            val toolbarH = 56.dp + if (sliderShown) 42.dp else 0.dp

            Box(Modifier.fillMaxSize()) {
                // ── 第 3 階時，編輯器內容「暫時消失」─────────────────────────
                //
                // 疊層完全顯示後把紙張與側欄整個移出 composition，而不是用擋板去擋。
                // 理由：它們若還留在畫面樹裡，會同時造成兩個問題 ——
                //   1. 玻璃的 haze 仍會從下面取樣到紙張與頁碼，
                //      於是網格卡片上會透出下面的東西（看起來像多了一塊容器）。
                //   2. 觸控仍會漏過疊層的空白處打到紙上。
                // 移出 composition 兩個問題一起消失，不需要任何擋板。
                //
                // 0.98 而不是 1.0：此時紙張 alpha 已剩 0.02（不可見），
                // 移除不會造成跳動，進出都走同一條淡入淡出。
                //
                // 工作區重新掛上時補捾到「當前頁」：
                // 第 3 階期間紙張被移出 composition，LazyColumn 不存在，
                // 這期間任何 scrollToItem/animateScrollToItem 全部落空（對著
                // 脫離的狀態物件呼叫）。症狀＝在全展開點第 N 頁：頁碼狀態有更新
                // （側欄選取框跟著動），但回到第 2 階紙面永遠停在第 1 頁。
                //
                // key 用「掛上與否」的布林而不是 gridOverlayProgress 本身：
                // 後者在淡入淡出期間每幀都變，拿它當 key 會每幀重跑這個捲動，
                // 直接跟使用者的捲動搶方向。只在掛上那一次轉折觸發。
                val workspaceMounted = gridOverlayProgress < 0.98f
                androidx.compose.runtime.LaunchedEffect(workspaceMounted, pageCount) {
                    if (workspaceMounted && pageCount > 0) {
                        // 等 LazyColumn 真的量好再捾：LaunchedEffect 在 composition
                        // 之後、layout 之前跑，此刻 layoutInfo 還是空的，
                        // 直接 scrollToItem 會靜默失敗（症狀正是「紙停在第 1 頁」）。
                        androidx.compose.runtime.snapshotFlow {
                            mainListState.layoutInfo.totalItemsCount
                        }.first { it > 0 }
                        val target = pageOwner.clamp(currentPageIndex, pageCount)
                        runCatching { mainListState.scrollToItem(target) }
                    }
                }
                if (gridOverlayProgress < 0.98f) {
                // ── 紙：整屏最底層 ────────────────────────────────────────────
                // 紙必須是「整個螢幕」大小，不能只佔側欄右邊那一塊。
                // 這樣它才會延伸到左邊頁碼底下，頁碼／+／展開鈕才能浮在紙上（iOS 全出血）。
                // 側欄與 AI 抽屜是後面畫的薄層，疊在紙上面。
                Box(
                    Modifier.fillMaxSize()
                        .onSizeChanged { contentW = it.width }
                        .clipToBounds()
                        // 第 3 階展開時紙張淡出，露出底層的 InkBackdrop（真正的編輯器背景）。
                        //
                        // 舊碼在第 3 階疊層鋪了一個 WorkspaceDeskDark/Light 平板色來擋住紙張，
                        // 但那不是編輯器真正的背景（真正的背景是 InkBackdrop，有漸層與 orb），
                        // 於是第 3 階變成一片死黑。
                        //
                        // 正確做法是讓紙張自己淡出，而不是蓋一塊假背景：
                        // 疊層淡入的同時紙張淡出，中間自然透出 InkBackdrop。
                        // 全程只動 alpha，不觸發量測。
                        .graphicsLayer { alpha = 1f - gridOverlayProgress }
                ) {
                    Workspace(
                        pageIndex = currentPageIndex,
                        pdfViewModel = pdfViewModel,
                        viewModel = viewModel,
                        modifier = Modifier
                            .fillMaxSize()
                            .hazeSource(chromeHaze),
                        pageAspectRatio = pageAspectRatio,
                        documentUri = uri.toString(),
                        onAiFileReady = { fileUri, prompt ->
                            // 圈選快捷列：和整頁鈕走同一條 AiRequest 通道，差別只在 autoSend。
                            aiRequest = AiRequest(
                                id = ++aiRequestSeq,
                                action = AiAction.SEND_PROMPT,
                                image = fileUri,
                                prompt = prompt,
                                autoSend = true
                            )
                            showAiPanel = true
                        },
                        hazeState = editorHaze,
                        isDarkTheme = isEditorDark,
                        db = db,
                        mainListState = mainListState,
                        onRequestPage = onRequestPage,
                        onScrollPage = onScrollPage
                    )
                }
                Row(Modifier.fillMaxSize()) {
                // 側欄寬度＝動畫後的 currentWidthDp（56↔128dp）。
                // 拖曳期最佳化已不需要：現在沒有跟手指走的連續寬度。
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        // 不要 padding(top = toolbarH)：側欄不該為了讓開工具列而被往下推。
                        // 頁碼／+／展開鈕都要浮在最上層（工具列玻璃壓在上面），跟工作區的紙同一套邏輯。
                        .layout { measurable, constraints ->
                            val w = with(density) { currentWidthDp.roundToPx() }
                            val placeable = measurable.measure(
                                constraints.copy(minWidth = w, maxWidth = w)
                            )
                            layout(w, placeable.height) {
                                placeable.place(0, 0)
                            }
                        }
                ) {
                    Sidebar(
                sidebarStage = sidebarStage,
                onModeChange = { goToStage(it) },
                pdfViewModel = pdfViewModel,
                pageCount = pageCount,
                currentPageIndex = currentPageIndex,
                repos = repos,
                documentUri = uri.toString(),
                modelWidth = viewModel.modelWidth,
                modelHeight = viewModel.modelHeight,
                onPageSelected = { index ->
                    lastSidebarDriveMs = SystemClock.uptimeMillis()
                    onRequestPage(index)
                },
                // RAIL 拖動中只報預覽（紙跟走、不提交）；放手／點選才經 onPageSelected 提交。
                onPagePreview = { previewNavigate(it) },
                onAddPage = { afterIndex ->
                    // R2：結構操作超出復原範圍——先清棧，杜絕舊命令錯位寫入。
                    viewModel.clearUndoStacks()
                    scope.launch {
                        pdfViewModel.insertBlankPage(
                            context,
                            uri.toString(),
                            afterIndex,
                            pageWidthPt = paperStyle.widthPt,
                            pageHeightPt = paperStyle.heightPt
                        )
                    }
                },
                onDeletePages = { indices ->
                    // R2：同上，清棧。
                    viewModel.clearUndoStacks()
                    pdfViewModel.deletePages(uri.toString(), indices)
                },
                // R2：側欄拖拽移頁是結構操作，清棧。
                onStructureChanged = { viewModel.clearUndoStacks() },
                listState = sidebarListState,
                modifier = Modifier.fillMaxSize(),
                hazeState = chromeHaze,   // 側欄玻璃：採背景＋紙。別用 editorHaze（其 source 是屏根，含側欄自己 → self-feedback → 玻璃失效）
                isDarkTheme = isEditorDark,
                isFollowingSidebar = sidebarFollowActive
            )

        }

Box(Modifier.weight(1f).fillMaxHeight()) {
            // ── Workspace 已移到整屏底層（見上方）──────────────────────────
            // 這裡只留 AI 抽屜：浮層玻璃卡，畫在紙上面。
            // 面板永不離開 composition → WebView 不死，對話與捲動位置留著。
            // 滑動只動 graphicsLayer 的 translationX：純合成器層，不觸發 layout/measure。
            // AI 抽屜可用寬度：側欄右緣 → 螢幕右緣。
            // 不拿 contentW（現在是整屏寬）當基準，否則閉合擋板會蓋到側欄。
            val density0 = LocalDensity.current
            val sidebarWpx = sidebarVisualWpx
            val aiAvailW = (contentW - sidebarWpx).coerceAtLeast(320f)
            val panelW = (aiAvailW * aiPanelWeight).coerceAtLeast(260f)
                .coerceAtMost(aiAvailW)
            val density = LocalDensity.current
            // AI 抽屜寬度基準＝側欄右緣到螢幕右緣（不是整屏 contentW）。
            // 整屏底層重構後 contentW 變成整屏 3200px，panelW 撐到 1280px，
            // 閉合時那塊「透明擋板」就蓋住 x=389..1251，把側欄觸控全吃掉
            // （實測 AI_BLOCKER 73 次、SIDEBAR_ITEM 0 次）。舊基準是內容區寬度所以沒事。
            val panelWdp = with(density) { panelW.toDp() }
            LaunchedEffect(showAiPanel) {
                if (showAiPanel) {
                    aiPanelLive = true
                    aiDrawerProgress.animateTo(
                        1f,
                        tween(durationMillis = 280, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                    )
                } else {
                    aiDrawerProgress.animateTo(
                        0f,
                        tween(durationMillis = 200, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                    )
                    aiPanelLive = false // 滑出畫面後才熄燈（此刻已不可見，熄燈不跳動）
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .graphicsLayer {
                        // 只在 layer 內讀動畫值：每幀不觸發任何重组
                        val p = aiDrawerProgress.value
                        // 必須滑過「側欄寬度 + 卡片寬度」才算完全離開畫面。
                        // 舊碼只滑 panelW，卡片收合後停在 x=-1064..154 —— 而側欄是 x=0..154，
                        // 兩者整塊重疊。那張卡片 alpha 0 看不見，但 consume() 照跑，
                        // 於是整條頁碼欄的觸控全被吃掉（實測 SIDEBAR_ITEM 0 次）。
                        // 握把已搬進卡片內側（疊層），外部沒有握把寬度可滑。
                        translationX = -(sidebarWpx + panelW) * (1f - p)
                        alpha = p
                    }
            ) {
                // 卡片本體：真玻璃＋圓角，內縮 5dp 留一圈玻璃邊
                Box(
                    modifier = Modifier
                        .width(panelWdp)
                        .fillMaxHeight()
                        // 四邊等距內縮：舊碼只有 start=8 沒有 end，右邊完全不內縮，
                        // 網頁因此沒填滿、跟 26dp 圓角容器對不齊（四角切出缺口＝你看到的縫隙）。
                        .padding(
                            top = toolbarH + 6.dp,
                            bottom = 6.dp,
                            start = 6.dp,
                            end = 6.dp
                        )
                        // 內層裁成同心圓角（26 - 6 = 20），讓網頁完整貼滿圓角容器。
                        // 不裁的話 WebView 是直角，四角會戳出圓角外緣＝那個縫隙。
                        .clip(RoundedCornerShape(20.dp))
// 關閉態擋觸控：alpha 0 不會自動停用 hit test，滑出去的卡片仍會吃掉紙的觸控。
                        // 但擋板只能在「卡片真的還壓在畫面上」時才開——收合到 p=0 之後
                        // 再 consume 就會連側欄頁碼一起吃掉（整條欄位死掉）。
                        .then(
                            if (showAiPanel) Modifier
                            else Modifier.graphicsLayer { }.pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        // 完全收起就放行，不再攔截任何事件
                                        if (aiDrawerProgress.value <= 0.001f) {
                                            awaitPointerEvent(PointerEventPass.Initial)
                                            continue
                                        }
                                        val e = awaitPointerEvent(PointerEventPass.Initial)
                                        e.changes.forEach { it.consume() }
                                    }
                                }
                            }
                        )
                        .glassPanel(chromeHaze, isEditorDark, RoundedCornerShape(26.dp))
                ) {
                    AiWebPanel(
                        request = aiRequest,
                        // 提示詞已投遞就清掉，避免重組時重複注入同一段字。
                        onPromptConsumed = { aiRequest = aiRequest?.copy(prompt = null) },
                        pickEnterId = aiPickEnterId,
                        pickCollectId = aiPickCollectId,
                        webLight = !isEditorDark,
                        provider = aiProvider,
                        initialUrl = aiInitialUrl,
                        // 地址落定就记到这份文件名下。站从 URL 反推，不读 aiProvider state：
                        // 回调异步，state 可能已经是切站后的值，按 URL 记才不会串站。
                        // 只写 prefs，不碰任何 state，所以不会触发重组、无循环。
                        onUrlChanged = { url ->
                            aiProviderForUrl(url)?.let { p ->
                                prefs.edit().putString(aiDocUrlKey(docKeyRef.value, p), url).apply()
                            }
                        },
                        onPickedJson = { json ->
                            scope.launch {
                                if (json.isBlank()) {
                                    android.widget.Toast.makeText(context, "沒抓到內容，請重選後再按引入", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    aiPickMode = false
                                    importPickedJson(json)
                                }
                            }
                        },
                        onWebView = { aiWebView = it },
                        active = aiPanelLive,
                        modifier = Modifier.padding(5.dp),
                        onClose = {
                            showAiPanel = false
                            aiRequest = null
                            aiPickMode = false
                        }
                    )
                    // 拉桿：從「卡片的 sibling」改成「卡片內側右緣的疊層」。
                    // 舊做法 [卡片 panelWdp][拉桿 28dp] 並排在抽屜 Row 裡 → 抽屜總寬
                    // 比卡片多 28dp，那 28dp 就是你看到的「多占的位置」。疊進卡片後
                    // 總寬 == 卡片寬，握把直接貼齊圓角容器的右緣。
                    // 按鈕全刪（關閉→工具列 ✦、匯入→工具列 ⬇、換主題→跟 App 深淺色自動），
                    // 只留分隔線＋握把，拖曳調寬照舊。
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            // 把手只佔內容區（跟 WebView 同一個上緣），把頂部 header 讓給切換鈕，
                            // 否則兩者都掛在同一個右緣、會疊在一起。
                            .padding(top = toolbarH + 6.dp, bottom = 6.dp)
                            .width(20.dp)
                            .pointerInput(Unit) {
                                detectHorizontalDragGestures { change, dragAmount ->
                                    change.consume()
                                    // 分母用 aiAvailW：panelW 的基準就是它，用 contentW 會拖不準
                                    aiPanelWeight = (aiPanelWeight + dragAmount / aiAvailW.coerceAtLeast(1f))
                                        .coerceIn(0.2f, 0.8f)
                                }
                            },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier.fillMaxHeight().width(10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.material3.VerticalDivider(
                                modifier = Modifier.fillMaxHeight(),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                            )
                        }
                    }
                    // AI 來源切換：原本掛在工具列（多一顆 32dp），現在放進面板頂部那條空白 header。
                    // 位置＝把手「正上方」：把手本身下移讓出 header（見上方 padding），
                    // 所以兩者完全不重疊——不是把鈕疊在握把上。
                    // top 用 header 高度對半算，縮放滑桿出現、header 變高時仍置中。
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = (toolbarH + 6.dp - 24.dp) / 2, end = 10.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .pressableGlass(
                                interactionSource = androidx.compose.runtime.remember {
                                    androidx.compose.foundation.interaction.MutableInteractionSource()
                                }
                            )
                            .pointerInput(Unit) {
                                detectTapGestures { switchAiProvider() }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (aiProvider == AiProvider.CHATGPT) {
                                Icons.Outlined.AutoAwesome
                            } else {
                                Icons.Filled.ChatBubble
                            },
                            contentDescription = "切換 AI 到 ${aiProvider.other.label}",
                            modifier = Modifier.size(14.dp),
                            tint = glassContentColor(isEditorDark)
                        )
                    }
                }
            }
        } // Box(內容區：Workspace ＋ AI 抽屜)
        } // Row（側欄＋AI 抽屜，疊在紙上）
                } // if（疊層完全顯示 → 編輯器內容暫時消失）


        // ── 第 3 階：頁面網格疊層 ────────────────────────────────────────
        // 蓋在紙／側欄／AI 抽屜之上、工具列之下（與 AI 卡片同一套讓位邏輯）。
        // 恆為 fillMaxSize：動畫只動 graphicsLayer，不觸發 measure，
        // 所以「幾百張縮圖的網格」不會在進場時被重排。
        if (gridOverlayProgress > 0.001f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 這裡刻意「不」鋪任何底色：底要透出真正的 InkBackdrop。
                    // 紙張在下面自己淡出（見紙張那層的 alpha），所以網格之間的間隙
                    // 看到的是編輯器原本的背景，而不是一片假色。
                    .graphicsLayer {
                        val p = gridOverlayProgress
                        alpha = p
                        // 微量上滑 + 收斂：純視覺，不影響量測
                        translationY = (1f - p) * 24.dp.toPx()
                        scaleX = 0.96f + 0.04f * p
                        scaleY = 0.96f + 0.04f * p
                    }
            ) {
                SidebarPageGrid(
                    pageCount = pageCount,
                    currentPageIndex = currentPageIndex,
                    pdfViewModel = pdfViewModel,
                    repos = repos,
                    documentUri = uri.toString(),
                    modelWidth = viewModel.modelWidth,
                    modelHeight = viewModel.modelHeight,
                    hazeState = chromeHaze,
                    isDarkTheme = isEditorDark,
                    onBack = { goToStage(SidebarStage.PANEL) },
                    onCollapse = { goToStage(SidebarStage.RAIL) },
                    onPageSelected = { index ->
                        lastSidebarDriveMs = SystemClock.uptimeMillis()
                        onRequestPage(index)
                    },
                    onDeletePages = { indices ->
                        // R2：同上，清棧。
                        viewModel.clearUndoStacks()
                        pdfViewModel.deletePages(uri.toString(), indices)
                    },
                    onExportSelectedPages = { indices ->
                        selectedExportIndices = indices.distinct().sorted()
                    },
                    isExportingSelection = isExportingSelection,
                    onStructureChanged = { viewModel.clearUndoStacks() },
                    // 讓整張網格從工具列下緣開始，避免 52dp 玻璃頂欄被工具列壓住一半，
                    // 工具列底下就不會再多出一截小玻璃。
                    modifier = Modifier.padding(top = toolbarH)
                )
            }
        } // Box（GRID 疊層）

        } // Box（紙底層＋chrome 疊層）

        // 浮空工具列：只蓋「工作區那一欄」，紙從它下面透上來；
        // 側欄維持自己的上邊界（不被工具列壓到），全螢幕態才吃滿寬。
        Column(modifier = Modifier.align(Alignment.TopStart).fillMaxWidth()) {
            TabletEditorTopBar(
                documentTitle = documentTitle,
                onBack = { navController.popBackStack() },
                viewModel = viewModel,
                showStrokeWidthSlider = showStrokeWidthSlider,
                onToggleStrokeWidthSlider = { showStrokeWidthSlider = !showStrokeWidthSlider },
                onHideStrokeWidthSlider = { showStrokeWidthSlider = false },
                onExport = {
                    showExportConfirmDialog = true
                },
                onDocumentSettings = { showDocumentSettingsDialog = true },
onToggleAiPanel = { showAiPanel = !showAiPanel },
    onSendPageToAi = { sendPageToAi() },
    onToggleAiImport = { toggleAiImport() },
    isAiImportArmed = aiPickMode,
    aiProvider = aiProvider,
                isAiPanelOpen = showAiPanel,
                isSendingPage = isSendingPage,
                isPowerSaver = isPowerSaver,
                onTogglePowerSaver = onTogglePowerSaver,
                onPickImage = {
                    // 子選單「從檔案選擇」：工具已經是 IMAGE（按圖片鈕時切的），
                    // 直接開圖庫，行為與之前一致。
                    imagePickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onTakePhoto = {
                    // 子選單「拍照」：工具已經是 IMAGE，不切；
                    // 拍完直接插入（小米相機自帶保留／重拍／取消，不再二次確認）。
                    launchCameraCapture()
                },
                isPhotoEnabled = hasCameraApp,
                hazeState = chromeHaze,   // 工具列用專屬 state：採背景＋紙，不採自己
                isDarkTheme = isEditorDark
            )

            AnimatedVisibility(
                visible = showStrokeWidthSlider && (activeTool == Tool.PEN || activeTool == Tool.HIGHLIGHTER),
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                StrokeWidthSlider(
                    viewModel = viewModel,
                    hazeState = chromeHaze,
                    isDarkTheme = isEditorDark,
                )
            }
        } // 10 overlay Column（僅工作區欄）
        } // 9 BoxWithConstraints
        } // 11 content Box
    } // 12 root Box(Aurora)
} // 13 TabletEditorScreen
