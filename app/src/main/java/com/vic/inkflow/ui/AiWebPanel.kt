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
import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.toAndroidDragEvent
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@androidx.compose.runtime.Composable
fun AiWebPanel(
    // L1 契約：一個請求物件取代過去三個各自為政的隱性變數（fileUri／prompt／autoSend）。
    request: AiRequest?,
    onPromptConsumed: () -> Unit,
    pickEnterId: Int = 0,
    pickCollectId: Int = 0,
    onPickedJson: (String) -> Unit = {},
    onWebView: (android.webkit.WebView?) -> Unit = {},
    webLight: Boolean = true,
    onClose: () -> Unit,
    // 由工具列切換帶進來；面板不持有也不持久化，避免兩處狀態打架。
    provider: AiProvider = AiProvider.GEMINI,
    // 抽屜收起來＝false：WebView 熄燈（onPause）省電，但**物件與網頁狀態全留著**，
    // 下次打開立刻見到原畫面，不重載。true 時 onResume。
    active: Boolean = true,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var webView by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<android.webkit.WebView?>(null) }
    val currentRequest = androidx.compose.runtime.rememberUpdatedState(request)
    val promptConsumedCallback = androidx.compose.runtime.rememberUpdatedState(onPromptConsumed)
    val pickedCallback = androidx.compose.runtime.rememberUpdatedState(onPickedJson)
    val webViewCallback = androidx.compose.runtime.rememberUpdatedState(onWebView)
    val currentWebLight = androidx.compose.runtime.rememberUpdatedState(webLight)
    val currentProvider = androidx.compose.runtime.rememberUpdatedState(provider)
    val uploadState = androidx.compose.runtime.remember {
        object {
            // 舊版是 lastProcessedUri（只比對 Uri）。改用請求的去重鍵，
            // 因為同一張圖可能配不同提示詞（整頁=解釋／圈選=總結）。
            var lastHandledKey: String? = null
            var isPageLoaded: Boolean = false
            var lastProbeUrl: String? = null
        }
    }

    /**
     * 這次請求是否還沒被投遞過。
     * 舊邏輯是 `fileUri != uploadState.lastProcessedUri`，語意寫死在呼叫點，
     * 換 provider 時很容易漏掉其中一個比較；集中成一個方法後只有這裡能改。
     */
    fun notYetHandled(req: AiRequest?): Boolean {
        if (req == null || req.isEmpty) return false
        val key = req.dedupeKey()
        if (uploadState.lastHandledKey == key) return false
        uploadState.lastHandledKey = key
        return true
    }

    /** 送圖失敗的唯一出口，避免兩處各寫一份 Toast。 */
    fun toastUploadError(message: String?) {
        try {
            android.widget.Toast.makeText(context, "送圖失敗：$message", android.widget.Toast.LENGTH_LONG).show()
        } catch (_: Throwable) { }
    }

    // Phase 1 取證用 DOM 探針。刻意做成可延遲、可重複呼叫：
    // onPageFinished 會早於 SPA 渲染，單次探針量到的是空殼（實測一進頁面全 0）。
    fun runProbe(target: android.webkit.WebView?, tag: String) {
        try {
            target?.evaluateJavascript(currentProvider.value.domProbeJs()) { v ->
                android.util.Log.d("InkFlowDbg", "AI probe[$tag]: $v")
            }
        } catch (e: Exception) {
            android.util.Log.e("AiWebPanel", "dom probe failed", e)
        }
    }

    fun scheduleProbe(target: android.webkit.WebView?, tag: String, delayMs: Long) {
        if (target == null) return
        if (delayMs <= 0L) {
            runProbe(target, tag)
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runProbe(target, tag)
            }, delayMs)
        }
    }

    // 快捷指令 prompt：圖貼上後另一下 JS 輪詢輸入框、填字自動送出（與貼圖腳本並行，內部延遲等圖先附著）。
    // 純文字請求（沒圖）也要能走這條，所以 image 不再是必要參數。
    fun injectPromptIfNeeded(target: android.webkit.WebView?, req: AiRequest) {
        val p = req.prompt ?: return
        try {
            target?.evaluateJavascript(currentProvider.value.promptSendJs(
                org.json.JSONObject.quote(p),
                req.autoSend
            ), null)
            promptConsumedCallback.value()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ── S2：以下三個是 provider 無關的「單一處理點」──────────────────────────
    // 新增 provider 時，這裡**不用改**，因為差異已全部收斂到 capabilities。

    /**
     * 主題套用。**唯一**套主題的地方（原本 onPageFinished 的兩個分支各有一份）。
     * 不能只靠 LaunchedEffect(webLight)：那條在首次組合時 webView 還沒建立（null），
     * webLight 若改成常數推導值就永遠套不到，深色模式下網頁會一直是白的。
     */
    fun applyTheme(target: android.webkit.WebView?, tag: String) {
        try {
            target?.evaluateJavascript(buildThemeJs(currentWebLight.value)) { v ->
                android.util.Log.d("InkFlowDbg", "AI theme($tag): $v")
            }
        } catch (e: Exception) {
            android.util.Log.e("AiWebPanel", "theme apply failed ($tag)", e)
        }
    }

    /**
     * DOM 探針（臨時診斷，S3 定完 selector 就要整段刪掉）。
     * 每個 URL 只探一次：onPageFinished 早於 SPA 渲染，所以即刻探一次當基線，
     * 延遲 4 秒再探一次抓 hydration 後的真實 DOM。
     */
    fun probeOnce(target: android.webkit.WebView?, finishedUrl: String) {
        if (uploadState.lastProbeUrl == finishedUrl) return
        uploadState.lastProbeUrl = finishedUrl
        scheduleProbe(target, "load", 0L)
        scheduleProbe(target, "hydrated", 4000L)
    }

    /**
     * 請求投遞。**唯一**貼圖＋填詞的地方（原本 onPageFinished 與 update 各一份）。
     *
     * 依 [AiCapabilities.sendImage] 決定要不要貼圖：ChatGPT 尚未移植注入鏈，
     * capabilities 是 false，所以只填詞不貼圖——不會貼到一半失敗留下半張圖。
     */
    fun deliverPendingRequest(target: android.webkit.WebView?, attempts: Int, tag: String) {
        if (!notYetHandled(request)) return
        val req = request ?: return
        val provider = currentProvider.value
        val uri = req.image
        if (uri != null && provider.capabilities.sendImage) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) {
                    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    target?.evaluateJavascript(buildImagePasteJs(base64, attempts, currentProvider.value.imagePasteInputSelector), null)
                    android.util.Log.d("InkFlowDbg", "UPLOAD paste dispatched ($tag) b64len=${base64.length}")
                } else {
                    android.util.Log.d("InkFlowDbg", "UPLOAD read failed ($tag)")
                }
            } catch (e: Exception) {
                android.util.Log.e("InkFlowDbg", "UPLOAD $tag failed", e)
                toastUploadError(e.message)
            }
        }
        injectPromptIfNeeded(target, req)
    }
    
// AI 面板主題跟 App 深淺色變更時即時重套（自適應 invert，見 buildThemeJs）。
    // 與 applyTheme() 共用同一支腳本與同一組 log，這裡只是換呼叫點。
    androidx.compose.runtime.LaunchedEffect(webLight) {
        applyTheme(webView, "change")
    }

    // M2b-2：拉桿「引入」鈕兩段式 — ①進圈選模式（段落打勾）②收集打勾段落（無勾選則取最後回覆全文）。
    androidx.compose.runtime.LaunchedEffect(pickEnterId) {
        if (pickEnterId > 0) {
            try {
                webView?.evaluateJavascript(AiGeminiDriver.pickJs(), null)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(pickCollectId) {
        if (pickCollectId > 0) {
            try {
                webView?.evaluateJavascript(AiGeminiDriver.collectJs(), null)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Fix2d: WebView 原生底預設透明→首幀前是黑洞；鋪一層極淡的底頂著，
    // 但不能是不透明 surface 色：容器本身是玻璃，不透明底會把玻璃整片蓋死。
    // 半透明黑只在「還沒載入」時當底，載入後由網頁自己的內容接手。
    val webViewBgArgb = android.graphics.Color.TRANSPARENT
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
    ) {
        // 切換 provider = 換站點，必須重建 WebView（loadUrl 只在 factory 跑一次）。
        // key 放這裡而不是外面，是為了讓「按一下切換」有明確的一次完整換血。
        androidx.compose.runtime.key(provider) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                android.util.Log.d("InkFlowDbg", "WebView factory start ${System.currentTimeMillis()}")
                android.webkit.WebView(ctx).apply {
                    // 重要：手動設置 LayoutParams 填滿父容器，避免 Compose 與 WebView 測量時發生高度坍塌(黑畫面主因之一)
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(webViewBgArgb)
                    // WebView 本身不畫底，容器玻璃才有機會透出來。
                    // 但仍需不透明 fallback 避免載入前全黑 → 用很淡的深色當 placeholder。
                    setBackgroundColor(android.graphics.Color.argb(40, 20, 20, 24))
                    
                    webView = this
                    webViewCallback.value(this)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = true
                        
                        // 側邊欄空間狹窄，不可開啟 WideViewPort，這會讓 Gemini 用寬視圖塞進窄空間導致內容消失跑版
                        useWideViewPort = false
                        loadWithOverviewMode = false
                        
                        // 啟用縮放但隱藏縮放按鈕
                        builtInZoomControls = true
                        displayZoomControls = false
                        setSupportMultipleWindows(false)
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        mediaPlaybackRequiresUserGesture = false
                        
                        // 解決 Gemini 輸入框消失與黑畫面問題：
                        // 回歸手機版瀏覽器 UA，但把 WebView 的特徵 (wv / Version/4.0) 抹除，以繞過 Google OAuth 阻擋。
                        val baseAgent = android.webkit.WebSettings.getDefaultUserAgent(ctx)
                        userAgentString = baseAgent.replace("; wv", "").replace(" wv", "").replace("Version/4.0 ", "")
                    }
                    
                    // 啟用 Cookie 以確保能正常登入並保持登入狀態
                    val cookieManager = android.webkit.CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(this, true)

                    // JavaScript bridge: allow JS to notify Android about paste/click results + grabbed text
                    val jsBridge = object {
                        @android.webkit.JavascriptInterface
                        fun onPasteResult(result: String) {
                            try {
                                android.util.Log.d("AiWebPanel", "onPasteResult: $result")
                                android.util.Log.d("InkFlowDbg", "UPLOAD result=$result")
                                // 終端失敗才 Toast（成功只記 log；PASTE_DISPATCH_FAILED 會走 file-input 退路，不算死）
                                if (result == "NO_CHAT_INPUT_FOUND" || result == "PASTE_EXCEPTION" ||
                                    result == "PROMPT_INSERT_FAILED" || result == "PROMPT_EXCEPTION" ||
                                    result == "PROMPT_NO_INPUT_FOUND" || result == "PROMPT_NO_INPUT" ||
                                    result == "PROMPT_SEND_NOT_READY"
                                ) {
                                    val msg = if (result.startsWith("PROMPT")) {
                                        "提示詞填入失敗（$result）"
                                    } else {
                                        "圖片貼上失敗（$result），請確認已登入 ${currentProvider.value.label}"
                                    }
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        try {
                                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                                        } catch (_: Throwable) { }
                                    }
                                }
                            } catch (t: Throwable) { }
                        }
                        @android.webkit.JavascriptInterface
                        fun onPickedJson(json: String) {
                            try {
                                android.util.Log.d("AiWebPanel", "onPickedJson len=${json.length}")
                                pickedCallback.value(json)
                            } catch (t: Throwable) { }
                        }
                    }
                    this.addJavascriptInterface(jsBridge, "AndroidBridge")
                    
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onShowFileChooser(
                            webView: android.webkit.WebView?,
                            filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                            fileChooserParams: FileChooserParams?
                        ): Boolean {
                            // 貼圖退路：頁面自己點了 input[type=file] 時，由我們遞上請求裡的圖。
                            // 這條完全 provider-agnostic（沒有任何 AI 專屬 selector）。
                            val pendingImage = currentRequest.value?.image
                            if (pendingImage != null) {
                                filePathCallback?.onReceiveValue(arrayOf(pendingImage))
                                return true
                            }
                            return super.onShowFileChooser(webView, filePathCallback, fileChooserParams)
                        }
                    }

                    webViewClient = object : android.webkit.WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                            // 讓 WebView 內部處理跳轉，不觸發外部瀏覽器
                            return false
                        }

override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // S2：原本這裡是 if (CHATGPT) {...} else if (GEMINI) {...} 兩條分支，
                            // 主題套用甚至整段複製兩份。現在收斂成單一路徑，
                            // provider 差異只表現在 capabilities 上。
                            val finishedUrl = url.orEmpty()
                            val provider = currentProvider.value
                            if (!finishedUrl.contains(provider.host)) return

                            uploadState.isPageLoaded = true
                            applyTheme(view, "load")
                            probeOnce(view, finishedUrl)
                            deliverPendingRequest(view, UPLOAD_ATTEMPTS_LOAD, "load")
                        }
                    }

                    loadUrl(provider.startUrl)
                }
            },
update = { view ->
                // S2：與 onPageFinished 走同一個投遞點，provider 差異不再分兩條路。
                if (uploadState.isPageLoaded) {
                    deliverPendingRequest(view, UPLOAD_ATTEMPTS_UPDATE, "update")
                }
            },
            modifier = androidx.compose.ui.Modifier.fillMaxSize()
        )
        }
    }

    // 抽屜收起時熄燈：WebView 停止渲染與計時器（省電），但**不銷毀**，
    // 網頁 DOM／捲動位置／對話全部留著，重開即見原畫面。
    // 用 DisposableEffect 而非 update 塊：避免每次重組都重複呼叫 onResume/onPause。
    androidx.compose.runtime.DisposableEffect(webView, active) {
        val wv = webView
        if (wv != null) {
            if (active) wv.onResume() else wv.onPause()
            android.util.Log.d("InkFlowDbg", "AI panel webview active=$active (kept alive)")
        }
        onDispose { }
    }
}

// S3：Gemini 專屬的 DOM 細節已搬到 AiGeminiDriver.kt（選項、腳本、TeX 還原）。
// 這裡只留 provider-agnostic 的部分：主題、貼圖通用殼、DOM 探針。
internal fun buildDomProbeJs(): String {
    return """
        (function() {
            function count(sel) {
                try { return document.querySelectorAll(sel).length; } catch(e) { return -1; }
            }
            function sample(sel, max) {
                try {
                    var el = document.querySelector(sel);
                    if (!el) return 'none';
                    var t = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
                    return t.slice(0, max);
                } catch(e) { return 'err'; }
            }
            var roles = {};
            try {
                Array.prototype.forEach.call(document.querySelectorAll('[data-message-author-role]'), function(el) {
                    var role = el.getAttribute('data-message-author-role') || '?';
                    roles[role] = (roles[role] || 0) + 1;
                });
            } catch(e) {}
            return 'PROBE url=' + location.href
                + ' title=' + ((document.title || '').slice(0, 80))
                + ' promptTextarea=' + count('#prompt-textarea')
                + ' contenteditable=' + count('div[contenteditable="true"]')
                + ' sendButton=' + count('[data-testid="send-button"]')
                + ' stopButton=' + count('[data-testid="stop-button"]')
                + ' assistant=' + count('[data-message-author-role="assistant"]')
                + ' articles=' + count('article')
                + ' mains=' + count('main')
                + ' prose=' + count('[class*="prose"]')
                + ' katex=' + count('.katex')
                + ' fileInput=' + count('input[type="file"]')
                + ' roles=' + JSON.stringify(roles)
                + ' promptSample=' + sample('#prompt-textarea', 80);
        })();
    """.trimIndent()
}

/** 貼圖重試次數：首次載入給較寬的耐心（使用者可能正在登入），後續更新快一些。 */
private const val UPLOAD_ATTEMPTS_LOAD = 20
private const val UPLOAD_ATTEMPTS_UPDATE = 10

/**
 * 貼上圖片時要瞄准的輸入框由 provider 決定（見 AiProvider.imagePasteInputSelector）。
 * 退路（input[type=file] + onShowFileChooser）則與 provider 無關。
 */

/**
 * 貼圖腳本（S1：**原本整段複製兩份**，`onPageFinished` 一份、`update` 一份，
 * 逐字相同只差重試次數——兩份會各自腐爛，是「加更多 AI」最先爆的地方，故合成一份）。
 *
 * 策略不變：先試模擬貼上，失敗退回 file input（provider 無關），再退回上傳鈕。
 * [inputCandidates] 是唯一該隨 provider 變的參數，目前仍是 Gemini 的 rich-textarea。
 */
private fun buildImagePasteJs(base64: String, attempts: Int, inputSelector: String): String {
    return """
        (function() {
            function simulateImagePaste(target, base64Data) {
                try {
                    const byteCharacters = atob(base64Data);
                    const byteNumbers = new Array(byteCharacters.length);
                    for (let i = 0; i < byteCharacters.length; i++) {
                        byteNumbers[i] = byteCharacters.charCodeAt(i);
                    }
                    const byteArray = new Uint8Array(byteNumbers);
                    const blob = new Blob([byteArray], { type: 'image/png' });
                    const uniqueName = "inkflow_" + Date.now() + "_" + Math.random().toString(36).slice(2, 8) + ".png";
                    const file = new File([blob], uniqueName, { type: 'image/png' });

                    const dataTransfer = new DataTransfer();
                    dataTransfer.items.add(file);

                    const pasteEvent = new ClipboardEvent('paste', {
                        clipboardData: dataTransfer,
                        bubbles: true,
                        cancelable: true
                    });

                    target.focus();
                    const dispatched = target.dispatchEvent(pasteEvent);
                    try { if (window.AndroidBridge && AndroidBridge.onPasteResult) AndroidBridge.onPasteResult(dispatched ? 'PASTE_DISPATCHED' : 'PASTE_DISPATCH_FAILED'); } catch(e){}
                    return dispatched;
                } catch (e) {
                    console.error('Paste simulation failed:', e);
                    try { if (window.AndroidBridge && AndroidBridge.onPasteResult) AndroidBridge.onPasteResult('PASTE_EXCEPTION'); } catch(e){}
                    return false;
                }
            }

            function tryFileInputClick() {
                var input = document.querySelector('input[type="file"]');
                if (input) {
                    input.click();
                    try { if (window.AndroidBridge && AndroidBridge.onPasteResult) AndroidBridge.onPasteResult('FILE_INPUT_CLICKED'); } catch(e){}
                    return true;
                }
                return false;
            }

            function tryUploadButtonClick() {
                var selectors = [
                    'button[aria-label*="Upload"]',
                    'button[aria-label*="upload"]',
                    'button[aria-label*="Add"]',
                    'button[aria-label*="＋"]',
                    '.upload-button',
                    '.icon-button'
                    ];
                for (var i = 0; i < selectors.length; i++) {
                    var btn = document.querySelector(selectors[i]);
                    if (btn) {
                        btn.click();
                        try { if (window.AndroidBridge && AndroidBridge.onPasteResult) AndroidBridge.onPasteResult('UPLOAD_BUTTON_CLICKED'); } catch(e){}
                        return true;
                    }
                }
                return false;
            }

            var attempts = 0;
            var interval = setInterval(function() {
                var chatInput = document.querySelector('$inputSelector');
                if (chatInput) {
                    clearInterval(interval);
                    var ok = simulateImagePaste(chatInput, "$base64");
                    if (!ok) {
                        if (!tryFileInputClick()) {
                            tryUploadButtonClick();
                        }
                    }
                } else {
                    attempts++;
                    if (attempts >= $attempts) {
                        clearInterval(interval);
                        try { if (window.AndroidBridge && AndroidBridge.onPasteResult) AndroidBridge.onPasteResult('NO_CHAT_INPUT_FOUND'); } catch(e){}
                    }
                }
            }, 500);
        })();
    """.trimIndent()
}

/**
 * AI 面板主題（跟 App 深淺色）：**自適應**，不是無腦疊 invert。
 *
 * 為什麼不能只看 App 主題：WebView 會把系統的 `prefers-color-scheme` 傳給網頁，
 * Gemini 會**自己**渲染深色。舊碼在 App 深色時疊 `invert(1)`，剛好把頁面自渲染的
 * 深色又翻回淺色 → 深色模式看到亮色、亮色模式看到深色（整個寫反）。
 *
 * 現在：先問頁面「你自認是深色嗎」（matchMedia），只在跟 App 想要的**不一致**時
 * 才疊 invert。刻意**不再**設 `color-scheme`——它會改變 matchMedia 的結果，
 * 跟 invert 互相觸發、來回翻。invert 不影響 matchMedia，所以這版不會震盪。
 *
 * 不 reload（Gemini 是 SPA，重載不丟對話但會閃）。
 * 回傳診斷字串（pageDark／needInvert），由呼叫端 Log 印出來，方便實機對帳。
 */
private fun buildThemeJs(light: Boolean): String {
    return """
        (function() {
            var LIGHT = $light;
            var WANT_DARK = !LIGHT;
            var pageDark = false;
            try {
                pageDark = !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
            } catch(e){}
            // 頁面自認的深淺 == App 想要的 → 不動；不一致 → 用 invert 拉過來
            var needInvert = (pageDark !== WANT_DARK);
            try {
                document.documentElement.style.filter = needInvert ? 'invert(1) hue-rotate(180deg)' : '';
            } catch(e){}
            return 'THEME app=' + (LIGHT ? 'light' : 'dark') + ' page=' + (pageDark ? 'dark' : 'light') + ' invert=' + needInvert;
        })();
    """.trimIndent()
}
