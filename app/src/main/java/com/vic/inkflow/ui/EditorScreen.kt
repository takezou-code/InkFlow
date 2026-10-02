package com.vic.inkflow.ui

import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import com.vic.inkflow.ui.theme.Motion
import com.vic.inkflow.ui.theme.ShapeSm
import com.vic.inkflow.ui.theme.ShapeMd
import com.vic.inkflow.ui.theme.ShapeLg
import com.vic.inkflow.ui.theme.ShapeXl
import com.vic.inkflow.util.reorderable
import com.vic.inkflow.util.reorderableItem

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
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material.icons.filled.Image
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
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.ui.layout.ContentScale
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
import com.vic.inkflow.ui.theme.InkFlowTheme


import com.vic.inkflow.ui.theme.Slate50
import com.vic.inkflow.ui.theme.Slate100
import com.vic.inkflow.ui.theme.Slate900
import com.vic.inkflow.ui.theme.WorkspaceDeskDark
import com.vic.inkflow.ui.theme.WorkspaceDeskLight
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
    backdropScene: BackdropScene = BackdropScene.DUNE,
    backdropImageUri: android.net.Uri? = null,
    onTogglePowerSaver: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("inkflow_settings", 0) }
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
    var aiFileUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var aiPrompt by remember { mutableStateOf<String?>(null) }
    var aiAutoSend by remember { mutableStateOf(true) }
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
    val sidebarListState = rememberLazyListState()
    val mainListState = rememberLazyListState()
    val pinchActive by viewModel.pinchActive.collectAsState()
    // 初次捲到位旗標：PDF 載入前主列表停在第 0 頁，此時 PageWorkspace 的跟隨回報必須忽略，
    // 否則會把剛從 DB 讀回的頁碼洗回 0（記住上次頁面失效的主因之一）。
    // 純 remember（不 Saveable）：旋轉重建後回到 false，剛好重捲一次。
    var initialScrollDone by remember(uri) { mutableStateOf(false) }
    // 程式化捲動目標：點按/增刪頁觸發主列表動畫時記下目標頁，
    // 動畫中途經過的中間頁一律忽略（不改 currentPageIndex），到位才認。
    // 否則中間頁會觸發跟隨 effect 回拉側欄、側欄滑動又推回主列表 → 兩邊互推、
    // 放手後主列表還被 animateScrollToItem 拽走（彈跳感）。2.5s 超時自清，避免動畫被取消時卡住。
    var programmaticTarget by remember { mutableStateOf<Int?>(null) }
    androidx.compose.runtime.LaunchedEffect(programmaticTarget) {
        if (programmaticTarget != null) {
            kotlinx.coroutines.delay(2500)
            programmaticTarget = null
        }
    }
    // 程式化捲動的命：手勢一接管（pinchActive）立刻取消，未跑完的動畫不許在手勢中/手勢後
    // 把紙拽回舊目標（反方向回彈主因）。目標旗跟著清，2.5s 自清照舊當保險。
    var programmaticScrollJob by remember { mutableStateOf<Job?>(null) }
    androidx.compose.runtime.LaunchedEffect(pinchActive) {
        if (pinchActive) {
            programmaticScrollJob?.cancel()
            programmaticScrollJob = null
            programmaticTarget = null
        }
    }
    // 點選意圖（側欄/靜態頁）：換作用頁 + 主列表滑過去
    // 用戶親自點了 = 接管，初次捲動不再搶回去。
    val onRequestPage: (Int) -> Unit = { index ->
        initialScrollDone = true
        programmaticTarget = index
        currentPageIndex = index
        viewModel.setActivePage(index)
        programmaticScrollJob?.cancel()
        programmaticScrollJob = scope.launch { runCatching { mainListState.animateScrollToItem(index) } }
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

    // AI 引入管線實作見 AiImportFlow.kt（切塊→KaTeX→排版→掃空白頁→寫入→跳轉）。
    // 薄包裝：勾選混排（文字＋公式裁圖），實作在 AiImportFlow.kt。
    fun importPickedJson(json: String) {
        scope.importPickedJson(json, context, viewModel, pdfViewModel, repos, uri.toString(), currentPageIndex, onRequestPage, context as? android.app.Activity, aiWebView)
    }
    // AI 區「匯入回覆」兩段式：①進圈選模式（段落打勾）②收集打勾段落（沒勾則取最後回覆全文）
    // 按鈕常駐工具列，所以抽屜收起時也要能用：武裝時順手把抽屜滑開，
    // 否則使用者看不到打勾框，等於按了沒反應。
    fun toggleAiImport() {
        if (!aiPickMode) {
            aiPickMode = true
            aiPickEnterId++
            if (!showAiPanel) showAiPanel = true
            android.widget.Toast.makeText(context, "點 Gemini 回覆的段落打勾，再按一次匯入抓取", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            aiPickMode = false
            aiPickCollectId++
        }
    }

    // AI 區「整頁送 AI」：整頁圖貼上＋填「解釋」即停，不自動送出
    // （與套索快捷列同一提示詞常數，差別只在這裡 autoSend=false）
    fun sendPageToAi() {
        if (isSendingPage) return
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
                    aiFileUri = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", file
                    )
                    aiPrompt = AiQuickPrompt.EXPLAIN
                    aiAutoSend = false // 整頁鈕：圖貼上＋填詞即停，不自動送出（套索快捷列才自動送）
                    showAiPanel = true
                } else {
                    android.widget.Toast.makeText(context, "整頁截圖失敗，請稍後再試", android.widget.Toast.LENGTH_SHORT).show()
                }
            } finally {
                isSendingPage = false
            }
        }
    }
    // 卷動跟隨：主列表滑到哪頁就換作用頁（不捲主列表，避免打架；側欄由下方 effect 置中）
    val onScrollPage: (Int) -> Unit = { index ->
        if (initialScrollDone && index != currentPageIndex) {
            val target = programmaticTarget
            if (target == null || index == target) {
                if (index == target) programmaticTarget = null
                currentPageIndex = index
                viewModel.setActivePage(index)
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
                currentPageIndex = stored
                viewModel.setActivePage(stored)
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
    val pageCount by pdfViewModel.pageCount.collectAsState()
    // PDF 載入完成後再捲到記憶頁：restore effect 跑時 pageCount 還是 0（openPdf 還沒回來），
    // 在那裡捲等於沒捲。等首個有效 pageCount 落定、DB 值已讀回，才一次捲到位；
    // 之後頁數變化（增刪頁）不再亂捲，只做夾取。門由 initialScrollDone 擋跟隨回寫。
    androidx.compose.runtime.LaunchedEffect(pageCount, initialPageRestored) {
        if (initialPageRestored && !initialScrollDone && pageCount > 0) {
            val safe = currentPageIndex.coerceIn(0, pageCount - 1)
            currentPageIndex = safe
            viewModel.setActivePage(safe)
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

    // When the PDF first loads, initialize the EditorViewModel's model space to match the first page.
    val firstPageSize by pdfViewModel.firstPageSize.collectAsState()
    androidx.compose.runtime.LaunchedEffect(firstPageSize) {
        firstPageSize?.let { (w, h) -> viewModel.initializePaperSize(w, h) }
    }

    // Document settings dialog state
    var showDocumentSettingsDialog by remember { mutableStateOf(false) }
    var showExportConfirmDialog by remember { mutableStateOf(false) }
    var isExportingPdf by remember { mutableStateOf(false) }
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

        // Auto-navigate to the newly inserted page
        val lastInsertedPage by pdfViewModel.lastInsertedPageIndex.collectAsState()
        androidx.compose.runtime.LaunchedEffect(lastInsertedPage) {
            val idx = lastInsertedPage ?: return@LaunchedEffect
            if (initialPageRestored) {
                programmaticTarget = idx
                currentPageIndex = idx
                viewModel.setActivePage(idx)
                sidebarListState.animateScrollToCenter(idx)
                programmaticScrollJob?.cancel()
                programmaticScrollJob = scope.launch { runCatching { mainListState.animateScrollToItem(idx) } }
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
            currentPageIndex = clamped
            programmaticTarget = clamped
            viewModel.setActivePage(clamped)
            sidebarListState.animateScrollToCenter(clamped)
            runCatching { mainListState.scrollToItem(clamped) }
            pdfViewModel.consumeDeletedPageEvent()
        }

        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val totalWidth = maxWidth
            val density = androidx.compose.ui.platform.LocalDensity.current
            
            val collapsedWidth = 56.dp
            val normalWidth = 128.dp

            // 系統返回：逐階退回收合態，收合態再按才交還上層（離開編輯器）。
            // 只在 stage != RAIL 時啟用，否則會搶走全域返回行為。
            BackHandler(enabled = sidebarStage != SidebarStage.RAIL) {
                SidebarStageMachine.onSystemBack(sidebarStage)?.let {
                    sidebarStageOrdinal = it.ordinal
                }
            }

            // 寬度軸只負責 56dp ↔ 128dp。
            // 舊碼把 GRID 塞成 totalWidth（整屏約 800dp），那是 6.7 倍距離的 spring，
            // 而且動畫期間 fixedW 跟著 currentWidthDp 走 → 側欄整排縮圖每幀重排。
            // GRID 現在是獨立疊層，寬度完全不參與（見下方 gridOverlayProgress）。
            val targetWidth = when (sidebarStage) {
                SidebarStage.RAIL -> collapsedWidth
                SidebarStage.PANEL, SidebarStage.GRID -> normalWidth
            }

            val animatableWidth = remember {
                androidx.compose.animation.core.Animatable(
                    when (sidebarStage) {
                        SidebarStage.RAIL -> 56f
                        SidebarStage.PANEL, SidebarStage.GRID -> 128f
                    }
                )
            }
            val coroutineScope = rememberCoroutineScope()

            // 觸控迴圈（awaitEachGesture）是 restricted scope，不能直接呼叫 snapTo/scrollBy；
            // 而用 coroutineScope.launch 包又會讓每個 pointer 事件各排一個 coroutine，
            // 60–120Hz 就是每幀一個，堆積後動畫延遲落地 → 拖起來又卡又黏。
            // 解法：pointer 迴圈只做非 suspend 的 trySend（CONFLATED＝只留最新值），
            // 由這兩個 LaunchedEffect 各自套用。事件再密也不會堆積。
            val widthRequests = remember { kotlinx.coroutines.channels.Channel<WidthRequest>(kotlinx.coroutines.channels.Channel.CONFLATED) }
            val scrollRequests = remember { kotlinx.coroutines.channels.Channel<Float>(kotlinx.coroutines.channels.Channel.CONFLATED) }
            // 拖曳期間用：跟手的視覺寬度 ＋ 內容固定量測寬度（避免整排縮圖重測）
            var dragVisualWidth by remember { androidx.compose.runtime.mutableStateOf(normalWidth) }
            var dragMeasureWidth by remember { androidx.compose.runtime.mutableStateOf(normalWidth) }
            var isDraggingSidebar by remember { androidx.compose.runtime.mutableStateOf(false) }
            // GRID 右緣把手是否正在拖曳。拖曳中即使疊層進度歸零也必須留在
            // composition，否則把手會在放手前被移除、事件迴圈直接中斷。
            var isDraggingGridHandle by remember { androidx.compose.runtime.mutableStateOf(false) }
            LaunchedEffect(widthRequests) {
                for (req in widthRequests) {
                    when (req.kind) {
                        WidthRequestKind.DRAG -> {
                            // 拖曳中：只改外框，內容寬度仍鎖在拖曳起點
                            dragVisualWidth = req.width
                        }
                        WidthRequestKind.SETTLE -> {
                            // 放手收尾：解除拖曳鎖後把外框交給動畫接手
                            isDraggingSidebar = false
                            animatableWidth.snapTo(req.width.value)
                        }
                    }
                }
            }
            LaunchedEffect(scrollRequests) {
                for (dy in scrollRequests) mainListState.scrollBy(dy)
            }

            LaunchedEffect(targetWidth) {
                animatableWidth.animateTo(
                    targetValue = targetWidth.value,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
                )
            }

            // ── 疊層軸（第 3 階）────────────────────────────────────────────
            // GRID 是覆蓋在紙之上的浮層，不再是「一種寬度」。
            // 動畫只動 graphicsLayer（合成器層），浮層本身恆為 fillMaxSize，
            // 進場時量一次即可，動畫期間零重排。
            val gridOverlayProgress = remember { androidx.compose.animation.core.Animatable(0f) }
            val gridProgressRequests = remember {
                kotlinx.coroutines.channels.Channel<Float>(kotlinx.coroutines.channels.Channel.CONFLATED)
            }
            LaunchedEffect(gridProgressRequests) {
                for (p in gridProgressRequests) gridOverlayProgress.snapTo(p)
            }
            LaunchedEffect(sidebarStage) {
                val target = if (sidebarStage == SidebarStage.GRID) 1f else 0f
                gridOverlayProgress.animateTo(
                    targetValue = target,
                    animationSpec = tween(
                        durationMillis = if (target > 0f) Motion.DURATION_NORMAL else Motion.DURATION_FAST,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing
                    )
                )
            }

            val currentWidthDp = animatableWidth.value.dp
            // 工具列高度＝側欄／AI 欄要讓出的上邊界。只有紙（工作區）不讓，
            // 讓它往上穿過工具列從玻璃底下透出來。
            val sliderShown = showStrokeWidthSlider &&
                (activeTool == Tool.PEN || activeTool == Tool.HIGHLIGHTER)
            val toolbarH = 56.dp + if (sliderShown) 42.dp else 0.dp

            Box(Modifier.fillMaxSize()) {
                // ── 紙：整屏最底層 ────────────────────────────────────────────
                // 紙必須是「整個螢幕」大小，不能只佔側欄右邊那一塊。
                // 這樣它才會延伸到左邊頁碼底下，頁碼／+／展開鈕才能浮在紙上（iOS 全出血）。
                // 側欄與 AI 抽屜是後面畫的薄層，疊在紙上面。
                Box(
                    Modifier.fillMaxSize()
                        .onSizeChanged { contentW = it.width }
                        .clipToBounds()
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
                            aiFileUri = fileUri
                            aiPrompt = prompt
                            aiAutoSend = true
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
                // 拖曳期最佳化：外層寬度跟著手指走，但**內容只量一次**。
                // 用 Modifier.width(currentWidthDp) 會讓側欄裡整排縮圖每幀重新量測
                // （側欄可見數十頁，每頁還有 PageThumbnail 的疊圖繪製），
                // 60–120Hz 下拖起來又卡又黏——這才是真正的瓶頸，不是 coroutine。
                // 做法：子層固定用「起始寬」量一次，外框只報跟手寬度（裁切）。
                val fixedW = if (isDraggingSidebar) {
                    with(density) { dragMeasureWidth.toPx() }.roundToInt()
                } else {
                    with(density) { currentWidthDp.toPx() }.roundToInt()
                }
                val visualW = if (isDraggingSidebar) {
                    // 不能夾在 fixedW 之內。fixedW 是「拖曳起點的內容量測寬度」，
                    // 拿它當上限的話，從 PANEL(128dp) 往外拉時視覺寬度永遠不會超過 128dp
                    // ——拉桿看起來就是「不跟手／不理人」。
                    // 上限改用真實的目標寬度（PANEL 錨點），內容仍鎖在 fixedW 不重測。
                    val maxVisualPx = with(density) { normalWidth.toPx() }.roundToInt()
                    with(density) { dragVisualWidth.toPx() }.roundToInt().coerceIn(0, maxVisualPx)
                } else fixedW
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        // 不要 padding(top = toolbarH)：側欄不該為了讓開工具列而被往下推。
                        // 頁碼／+／展開鈕都要浮在最上層（工具列玻璃壓在上面），跟工作區的紙同一套邏輯。
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(
                                constraints.copy(minWidth = fixedW, maxWidth = fixedW)
                            )
                            layout(visualW, placeable.height) {
                                placeable.place(0, 0)
                            }
                        }
                ) {
                    Sidebar(
                sidebarStage = sidebarStage,
                onModeChange = { sidebarStageOrdinal = it.ordinal },
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
        // 拉桿在 RAIL 與 PANEL 都存在：往內撥收合、往外撥展開（1:1 跟手）。
        //
        // 舊碼只在 PANEL 畫拉桿，所以第 3 階整條消失＝「展開後手把不理人」。
        // 現在 RAIL 也保留它，讓「往內撥」在任何階段都是同一支手把、
        // 同一套行為——這才是可學習的互動。
        // GRID 階段改由疊層自己的右緣把手負責（見下），因為那時整屏都是網格。
        if (sidebarStage != SidebarStage.GRID) {
            Box(
                modifier = Modifier
                    .width(24.dp)
                    .fillMaxHeight()
                    .padding(top = toolbarH)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            try {
                            val down = awaitFirstDown()
                            // 跟手基準：起點寬度（px）＋ 累積位移。
                            // 這裡只讀不寫任何狀態——touch-down 不代表使用者要拖。
                            //
                            // 舊碼在 awaitFirstDown() 之後立刻寫 dragVisualWidth /
                            // dragMeasureWidth / isDraggingSidebar，症狀是「一觸碰把手就彈走」：
                            //   1. awaitFirstDown() 不等 slop，touch-down 當下就回傳，
                            //      於是純點也會先把寬度量測來源從 Animatable 切成 state，
                            //      中間有一幀落差，看起來就是彈一下。
                            //   2. isDraggingSidebar 一翻 true，下面 LaunchedEffect(widthRequests)
                            //      的 if 分支就再也收不到動畫路徑送來的寬度，
                            //      動畫被誤判成拖曳而 snapTo，寬度就釘死／跳動。
                            // 現在改成「過 slop 且判定橫向」才鎖定。
                            val dragStartWidthPx = with(density) { currentWidthDp.toPx() }
                            var accumulatedDxPx = 0f
                            // null = 未定；true = 橫向調寬；false = 直向捲動（先過 slop 先鎖定）
                            var horizontalLock: Boolean? = null
                            var prevX = down.position.x
                            var prevY = down.position.y
                            var accX = 0f
                            var accY = 0f
                            var velX = 0f
                            var lastT = down.uptimeMillis
                            val slop = viewConfiguration.touchSlop
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    // 收尾一律解除拖曳鎖，不分有沒有真的拖過。
                                    // 舊碼只在 horizontalLock == true 分支裡清，一旦有
                                    // 路徑沒清到，寬度就永遠停在「拖曳模式」。
                                    isDraggingSidebar = false
                                    if (horizontalLock == null) {
                                        // 純點＝展開（RAIL 與 PANEL 都是「往展開走」）。
                                        // 舊碼是 order[(i+1) % 3] 循環，PANEL 點一下進 GRID、
                                        // 而 GRID 沒有拉桿所以卡死。改成單向推進。
                                        SidebarStageMachine.expandTarget(sidebarStage)?.let {
                                            sidebarStageOrdinal = it.ordinal
                                        }
                                    } else if (horizontalLock == true) {
                                        // 寬度軸只有 RAIL↔PANEL 兩階，所以把實際寬度
                                        // 換算成 0..1 的連續進度，交給狀態機吸附。
                                        // 用本地 newWidthPx 算，不讀 dragVisualWidth——
                                        // 那個值要走 Channel → LaunchedEffect → state 才回來。
                                        val currentW = (dragStartWidthPx + accumulatedDxPx)
                                            .coerceIn(collapsedWidth.value, normalWidth.value)
                                        val railPx = collapsedWidth.value
                                        val panelPx = normalWidth.value
                                        val releaseProgress = if (panelPx - railPx <= 1f) {
                                            SidebarStage.PANEL.progress
                                        } else {
                                            ((currentW - railPx) / (panelPx - railPx))
                                                .coerceIn(0f, 1f)
                                        }
                                        // 往右甩過門檻 → 進第 3 階；往左甩 → 收合。
                                        // 這裡讓 GRID 只能靠「明確的快速右甩」進入，
                                        // 單純拖寬不會誤觸。
                                        val next = if (velX > SidebarStageMachine.SNAP_VELOCITY_PX_PER_SEC &&
                                            releaseProgress > 0.85f
                                        ) {
                                            SidebarStage.GRID
                                        } else {
                                            SidebarStageMachine.snap(releaseProgress, velX)
                                        }
                                        sidebarStageOrdinal = next.ordinal
                                        widthRequests.trySend(
                                            WidthRequest(
                                                when (next) {
                                                    SidebarStage.RAIL -> collapsedWidth
                                                    else -> normalWidth
                                                },
                                                WidthRequestKind.SETTLE
                                            )
                                        )
                                    }
                                    break
                                }
                                val dx = change.position.x - prevX
                                val dy = change.position.y - prevY
                                prevX = change.position.x
                                prevY = change.position.y
                                if (horizontalLock == null) {
                                    accX += dx
                                    accY += dy
                                    horizontalLock = when {
                                        kotlin.math.abs(accX) > slop && kotlin.math.abs(accX) >= kotlin.math.abs(accY) -> true
                                        kotlin.math.abs(accY) > slop -> false
                                        else -> null
                                    }
                                    // 這裡才鎖定拖曳狀態：確定是橫向調寬、且已過 slop，
                                    // 使用者確實要拉寬了。此刻才把內容量測寬度釘在起點，
                                    // 之後外框跟手但內容不重測。
                                    if (horizontalLock == true) {
                                        dragMeasureWidth = currentWidthDp
                                        dragVisualWidth = currentWidthDp
                                        isDraggingSidebar = true
                                    }
                                }
                                if (horizontalLock == true) {
                                    val now = change.uptimeMillis
                                    val dt = (now - lastT).coerceAtLeast(1L)
                                    lastT = now
                                    velX = velX * 0.75f + (dx / dt * 1000f) * 0.25f
                                    change.consume()
                                    // 用「拖曳起點寬度 ＋ 累積位移」，不是「每幀重讀 dragVisualWidth」。
                                    // dragVisualWidth 要走 Channel → LaunchedEffect → state 才回來，
                                    // 這條路比一幀慢，累加落後值就是橡皮筋感的來源。
                                    val newWidthPx = (dragStartWidthPx + accumulatedDxPx)
                                                // 只夾在兩個真實錨點之間。
                                                // 舊碼夾到 totalWidth，但 GRID 已經不在寬度軸上，
                                                // 夾到那裡會讓拖曳「拖到底也沒反應」。
                                                .coerceIn(collapsedWidth.value, normalWidth.value)
                                    accumulatedDxPx = newWidthPx - dragStartWidthPx
                                    // 只丟請求，實際套用在上面的 LaunchedEffect（見 widthRequests 註解）
                                    widthRequests.trySend(
                                        WidthRequest(
                                            with(density) { newWidthPx.toDp() },
                                            WidthRequestKind.DRAG
                                        )
                                    )
                                } else if (horizontalLock == false) {
                                    // 直向：把主列表跟著手指捲（內容跟手）
                                    change.consume()
                                    val dyPx = -dy
                                    if (dyPx != 0f) {
                                        scrollRequests.trySend(dyPx)
                                    }
                                }
                            }
                            } finally {
                                // 事件迴圈被系統中斷（break／例外）時也要清，
                                // 否則寬度會永久停在拖曳模式，之後動畫全部失效。
                                isDraggingSidebar = false
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (sidebarStage == SidebarStage.PANEL) {
                    Box(
                        Modifier
                            .width(6.dp)
                            .height(56.dp)
                            .glassPanel(chromeHaze, isEditorDark, CircleShape)
                    )
                }
            }
        }

Box(Modifier.weight(1f).fillMaxHeight()) {
            // ── Workspace 已移到整屏底層（見上方）──────────────────────────
            // 這裡只留 AI 抽屜：浮層玻璃卡，畫在紙上面。
            // 面板永不離開 composition → WebView 不死，對話與捲動位置留著。
            // 滑動只動 graphicsLayer 的 translationX：純合成器層，不觸發 layout/measure。
            // AI 抽屜可用寬度：側欄右緣 → 螢幕右緣。
            // 不拿 contentW（現在是整屏寬）當基準，否則閉合擋板會蓋到側欄。
            val density0 = LocalDensity.current
            val sidebarWpx = with(density0) { currentWidthDp.toPx() }
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
                        fileUri = aiFileUri,
                        prompt = aiPrompt,
                        onPromptConsumed = { aiPrompt = null },
                        pickEnterId = aiPickEnterId,
                        pickCollectId = aiPickCollectId,
                        webLight = !isEditorDark,
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
                        autoSend = aiAutoSend,
                        active = aiPanelLive,
                        modifier = Modifier.padding(5.dp),
                        onClose = {
                            showAiPanel = false
                            aiFileUri = null
                            aiPrompt = null
                            aiAutoSend = true
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
                }
            }
        } // Box(內容區：Workspace ＋ AI 抽屜)
        } // Row（側欄＋AI 抽屜，疊在紙上）


        // ── 第 3 階：頁面網格疊層 ────────────────────────────────────────
        // 蓋在紙／側欄／AI 抽屜之上、工具列之下（與 AI 卡片同一套讓位邏輯）。
        // 恆為 fillMaxSize：動畫只動 graphicsLayer，不觸發 measure，
        // 所以「幾百張縮圖的網格」不會在進場時被重排。
        if (gridOverlayProgress.value > 0f || isDraggingGridHandle) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val p = gridOverlayProgress.value
                        alpha = p
                        // 微量上滑 + 收斂：純視覺，不影響量測
                        translationY = (1f - p) * 48.dp.toPx()
                        scaleX = 0.98f + 0.02f * p
                        scaleY = 0.98f + 0.02f * p
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
                    onBack = { sidebarStageOrdinal = SidebarStage.PANEL.ordinal },
                    onCollapse = { sidebarStageOrdinal = SidebarStage.RAIL.ordinal },
                    onPageSelected = { index ->
                        lastSidebarDriveMs = SystemClock.uptimeMillis()
                        onRequestPage(index)
                    },
                    onDeletePages = { indices ->
                        // R2：同上，清棧。
                        viewModel.clearUndoStacks()
                        pdfViewModel.deletePages(uri.toString(), indices)
                    },
                    onStructureChanged = { viewModel.clearUndoStacks() }
                )
            }
        } // Box（GRID 疊層）

        // ── 第 3 階右緣把手 ────────────────────────────────────────────
        // 必須與 GRID 疊層**平級**，不能塞在疊層裡面：
        //   1. 疊層帶 alpha 與 scale，疊層內的把手座標跟視覺位置會對不上。
        //   2. 疊層是 fillMaxSize，把手排在 SidebarPageGrid 之後就被網格內容壓在
        //      下面、吃不到事件 —— 症狀正是「第 3 階手把不理人」。
        if (sidebarStage == SidebarStage.GRID || isDraggingGridHandle) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(28.dp)
                    .fillMaxHeight()
                    .padding(top = toolbarH)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            isDraggingGridHandle = true
                            try {
                            val startProgress = gridOverlayProgress.value
                            var prevX = down.position.x
                            var velX = 0f
                            var lastT = down.uptimeMillis
                            var moved = false
                            // 累積位移（跟手基準，不用會落後一幀的 gridOverlayProgress）
                            var accumulatedDx = 0f
                            val slop = viewConfiguration.touchSlop
                            val spanPx = with(density) { (normalWidth - collapsedWidth).toPx() }
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    if (moved) {
                                        val settled = if (gridOverlayProgress.value <=
                                            SidebarStageMachine.HIT_TEST_EPSILON
                                        ) {
                                            SidebarStage.RAIL
                                        } else {
                                            SidebarStageMachine.snap(startProgress, -velX)
                                        }
                                        sidebarStageOrdinal = settled.ordinal
                                    } else {
                                        // 純點＝直接收合到 RAIL
                                        SidebarStageMachine.collapseTarget(SidebarStage.GRID)?.let {
                                            sidebarStageOrdinal = it.ordinal
                                        }
                                    }
                                    break
                                }
                                val dx = change.position.x - prevX
                                prevX = change.position.x
                                if (!moved && kotlin.math.abs(dx) > slop) moved = true
                                if (!moved) continue
                                val now = change.uptimeMillis
                                val dt = (now - lastT).coerceAtLeast(1L)
                                lastT = now
                                velX = velX * 0.75f + (dx / dt * 1000f) * 0.25f
                                change.consume()
                                accumulatedDx += dx
                                // restricted scope 不能呼叫 snapTo，只能丟請求。
                                // CONFLATED＝事件再密也只留最新值，不會堆積成動畫延遲。
                                // 往左拖（dx 負）＝進度下降，所以取負號餵進 dragProgress。
                                gridProgressRequests.trySend(
                                    SidebarStageMachine.dragProgress(
                                        startProgress, -accumulatedDx, spanPx
                                    )
                                )
                            }
                            } finally {
                                // 事件被系統中斷時也要清，否則把手與疊層會永久留在畫面上。
                                isDraggingGridHandle = false
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .width(6.dp)
                        .height(72.dp)
                        .glassPanel(chromeHaze, isEditorDark, CircleShape)
                )
            }
        }
        } // Box（紙底層＋chrome 疊層）

        // 浮空工具列：只蓋「工作區那一欄」，紙從它下面透上來；
        // 側欄＋拖曳條維持自己的上邊界（不被工具列壓到），全螢幕態才吃滿寬。
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
                isAiPanelOpen = showAiPanel,
                isSendingPage = isSendingPage,
                isPowerSaver = isPowerSaver,
                onTogglePowerSaver = onTogglePowerSaver,
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
