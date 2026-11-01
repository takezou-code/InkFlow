package com.vic.inkflow.ui

import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import com.vic.inkflow.ui.theme.Motion
import com.vic.inkflow.ui.theme.ShapeSm
import com.vic.inkflow.ui.theme.ShapeMd
import com.vic.inkflow.ui.theme.ShapeLg
import com.vic.inkflow.ui.theme.ShapeXl

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
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
import com.vic.inkflow.data.Document
import com.vic.inkflow.data.Folder
import com.vic.inkflow.data.ImageAnnotation
import com.vic.inkflow.data.StrokeWithSamples
import com.vic.inkflow.data.TextAnnotation
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

/**
 * 等回覆生成完（串流指標消失）。
 *
 * 只對有 [AiProvider.replyIdleSelector] 的 provider 有意義；Gemini 直接回 true，
 * 行為與過往完全相同。
 *
 * @return true＝可以安全收集；false＝逾時，放棄這次匯入（寧可不要，也不要匯入半截的回覆）。
 */
private suspend fun awaitReplyIdle(
    target: android.webkit.WebView?,
    idleSelector: String,
    timeoutMs: Long = 90_000L,
    intervalMs: Long = 700L
): Boolean {
    if (target == null) return false
    val js = "(function(){try{return document.querySelectorAll('$idleSelector').length;}catch(e){return 0;}})()"
    var waited = 0L
    while (waited < timeoutMs) {
        val busy = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            try {
                target.evaluateJavascript(js) { v ->
                    val n = v?.trim()?.removeSurrounding("\"")?.toIntOrNull() ?: 0
                    if (cont.isActive) cont.resume(n > 0) {}
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(false) {}
            }
        }
        if (!busy) {
            android.util.Log.d("InkFlowDbg", "COLLECT reply idle after ${waited}ms")
            return true
        }
        kotlinx.coroutines.delay(intervalMs)
        waited += intervalMs
    }
    android.util.Log.d("InkFlowDbg", "COLLECT reply still busy after ${waited}ms")
    return false
}

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
    // 自訂起始網址推導出的 host（設定頁可改）。null＝用 provider 內建 host。
    // 少了這個，「網站開得起來但送圖／探針完全不動作」——注入閘門會把自訂站擋掉。
    hostOverride: String? = null,
    // 每份文件的 AI 記憶：初次建 WebView 時進這個地址（null／空白＝進站首頁）。
    // 切文件不重建 WebView，還原由呼叫端 loadUrl；這裡只管「出生第一頁」。
    initialUrl: String? = null,
    // 頁面落定就回報（onPageFinished＋doUpdateVisitedHistory 兩處，SPA 切對話
    // 不觸發 onPageFinished，只靠後者）。只報同站 URL，登入中轉頁不上報。
    // 面板只管報，存不存由呼叫端決定（面板不持有文件概念）。
    onUrlChanged: (String) -> Unit = {},
    // DOM 探針結果（PROBE3）。面板只負責「量」與回報，存不存、存到哪由呼叫端決定
    // ——與 onUrlChanged 同一個理由：面板不持有文件／設定概念。
    // 為什麼要回報：選擇器只能靠實機資料鎖定，logcat 只有開發者看得到；
    // 存進 prefs 後設定頁就能顯示，使用者不必學 adb。
    onProbe: (provider: AiProvider, tag: String, raw: String) -> Unit = { _, _, _ -> },
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
    val currentHostGate = androidx.compose.runtime.rememberUpdatedState(hostOverride)
    val urlChangedCallback = androidx.compose.runtime.rememberUpdatedState(onUrlChanged)
    val probeResultCallback = androidx.compose.runtime.rememberUpdatedState(onProbe)
    // 同站才回報：登入中轉（accounts.google.com 等）不上報，還原才不會落在中轉頁。
    // 判定走 matchesHost（含 www. 等子網域，以及設定頁填的自訂 host）：
    // 比解析出的 host，不用 contains，因為中轉頁 query 裡也帶著站名。
    fun reportUrl(url: String) {
        if (url.isBlank()) return
        if (!currentProvider.value.matchesHost(url, currentHostGate.value)) return
        // 登入回呼頁不是對話頁：記成「這份文件上次待的地方」只會讓下次進來
        // 繼續落在空白頁，而且那串網址帶著登入權杖，不該進 prefs。
        if (looksLikeAuthCallback(url)) {
            AiDiag.log(context, currentProvider.value, "URL_SKIP", "reason=auth_callback")
            return
        }
        try {
            urlChangedCallback.value(url)
        } catch (_: Throwable) { }
    }
    val uploadState = androidx.compose.runtime.remember {
        object {
            // 舊版是 lastProcessedUri（只比對 Uri）。改用請求的去重鍵，
            // 因為同一張圖可能配不同提示詞（整頁=解釋／圈選=總結）。
            var lastHandledKey: String? = null
            var isPageLoaded: Boolean = false
            var lastProbeUrl: String? = null
            // 自動貼圖退路（feed pendingImage）每個請求只餵一次；
            // 之後使用者手動點上傳就走系統選擇器，不會被舊圖卡住。
            var fedImageKey: String? = null
        }
    }

    // 站方 window.open() 開的子視窗（Google／Apple／Microsoft 登入）。null＝目前沒有。
    var authPopup by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<AiAuthPopup?>(null)
    }
    // 已經「救回首頁」的回呼網址：同一個網址只救一次，否則會無限重載。
    var callbackRecovered by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }

    /** 面板的首頁：使用者自訂網址優先，否則站方內建首頁（自訂 AI 未填時是空字串）。 */
    fun panelHomeUrl(): String =
        initialUrl?.takeIf { it.isNotBlank() } ?: currentProvider.value.startUrl

    // ── 使用者手動上傳：WebView file chooser 橋（圖片／PDF／文件） ──
    // 與 app 自動貼圖共用 onShowFileChooser 入口：自動退路先餵 pending 圖（每請求一次），
    // 沒有待餵的圖就開系統選擇器。舊碼這裡直接 super（= 拒絕），手動上傳完全點不開。
    val fileChooser = androidx.compose.runtime.remember {
        object {
            var callback: android.webkit.ValueCallback<Array<Uri>>? = null
        }
    }
    val filePickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = fileChooser.callback
        fileChooser.callback = null
        try {
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                val data = result.data
                val uris = mutableListOf<Uri>()
                val cd = data?.clipData
                if (cd != null) {
                    for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri)
                }
                data?.data?.let { uris.add(it) }
                cb?.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
            } else {
                cb?.onReceiveValue(null)
            }
        } catch (_: Throwable) {
            try { cb?.onReceiveValue(null) } catch (_: Throwable) { }
        }
    }
    fun launchFilePicker(params: android.webkit.WebChromeClient.FileChooserParams?) {
        try {
            val accepts = params?.acceptTypes?.filter { it.isNotBlank() } ?: emptyList()
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = accepts.firstOrNull() ?: "*/*"
                if (accepts.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, accepts.toTypedArray())
                putExtra(
                    Intent.EXTRA_ALLOW_MULTIPLE,
                    params?.mode == android.webkit.WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
                )
            }
            filePickLauncher.launch(intent)
        } catch (t: Throwable) {
            try { fileChooser.callback?.onReceiveValue(null) } catch (_: Throwable) { }
            fileChooser.callback = null
        }
    }

    // ── 語音輸入：進面板先要麥克風授權（只問一次；拒絕也不擋面板，網頁點麥克風時沒授權就 deny） ──
    val audioPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        } catch (_: Throwable) { }
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

    /**
     * 探針結果落檔。
     *
     * 這是整條診斷鏈的核心：一行就能回答「這頁到底是沒登入、被擋、還是版面改了」，
     * 不必再回頭問使用者看到什麼。`hydrated` 那次另存原始資料，供事後鎖選擇器。
     */
    fun logProbe(tag: String, raw: String) {
        val report = parseProbeReport(raw) ?: run {
            AiDiag.log(context, currentProvider.value, "PROBE_BAD", "tag=$tag raw=${AiDiag.q(raw.take(200))}")
            return
        }
        val readiness = report.readiness()
        AiDiag.log(
            context,
            currentProvider.value,
            "PROBE",
            "tag=$tag state=${readiness.state} url=${AiDiag.url(report.url)}" +
                " input=${report.inputScore()} send=${report.sendScore()} reply=${report.replyScore()}" +
                " idle=${report.idle?.count ?: 0} katex=${report.katex} mathjax=${report.mathjax}" +
                " file=${report.fileInputs} pwd=${report.passwordFields} captcha=${report.captcha}" +
                " title=${AiDiag.q(report.title)}"
        )
        // 原始資料只在 hydrated（4 秒後）存一次：0ms 那次幾乎都是空殼，存了只會誤導。
        // 寫檔前先把網址欄位脫敏：PROBE3 的網址可能帶著 id_token（OAuth 回呼），
        // 選擇器要留、權杖不能留。
        if (tag == "hydrated") {
            AiDiag.log(context, currentProvider.value, "PROBE_RAW", redactProbeUrl(raw))
        }
    }

    // Phase 1 取證用 DOM 探針。刻意做成可延遲、可重複呼叫：
    // onPageFinished 會早於 SPA 渲染，單次探針量到的是空殼（實測一進頁面全 0）。
    fun runProbe(target: android.webkit.WebView?, tag: String) {
        try {
            target?.evaluateJavascript(currentProvider.value.domProbeJs()) { v ->
                android.util.Log.d("InkFlowDbg", "AI probe[$tag]: $v")
                // v 是 JSON 字串（外層引號＋\" 轉義）；先解碼再回報，
                // 否則存進 prefs 的報告滿是反斜線，設定頁也沒法讀。
                val raw = decodeJsString(v) ?: return@evaluateJavascript
                val report = parseProbeReport(raw)
                logProbe(tag, raw)
                if (report == null) return@evaluateJavascript

                // 換站那一瞬間，前一個站的延遲探針還會打回來（實測：切到 CLAUDE
                // 卻量到 Gemini 的 DOM，還被寫進 CLAUDE 的診斷格，之後看診斷只會
                // 一直修錯地方）。host 不符就只留紀錄、不採信、也不回報。
                val gate = currentHostGate.value?.takeIf { it.isNotBlank() }
                    ?: currentProvider.value.host
                val onSite = gate.isBlank() || currentProvider.value.matchesHost(report.url, gate)
                if (!onSite) {
                    AiDiag.log(
                        context, currentProvider.value, "PROBE_STALE",
                        "tag=$tag url=${AiDiag.url(report.url)} gate=${AiDiag.q(gate)}"
                    )
                    return@evaluateJavascript
                }

                // ①登入回呼頁卡住：這種頁面永遠不會是對話頁，回首頁一定比留在空白好。
                //   同一個網址只救一次，否則會 loadUrl→onPageFinished→探針→再 loadUrl 無限繞。
                if (tag == "hydrated" && report.firstInput() == null && looksLikeAuthCallback(report.url)) {
                    val home = panelHomeUrl()
                    if (home.isNotBlank() && callbackRecovered != report.url) {
                        callbackRecovered = report.url
                        AiDiag.log(
                            context, currentProvider.value, "AUTH_STUCK",
                            "url=${AiDiag.url(report.url)} home=${AiDiag.url(home)}"
                        )
                        target.loadUrl(home)
                        return@evaluateJavascript
                    }
                }

                // ②登入子視窗剛關：session 可能已經在 cookie 裡，但主頁還是登入前的畫面。
                //   重載一次讓它自己認人；還是不行就只是多一次載入，不會壞。
                if (tag == "after-login" && report.firstInput() == null) {
                    AiDiag.log(context, currentProvider.value, "LOGIN_RELOAD", "reason=no_input_after_popup")
                    target.reload()
                    return@evaluateJavascript
                }

                try { probeResultCallback.value(currentProvider.value, tag, raw) } catch (_: Throwable) { }
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
        AiDiag.log(
            context, provider, "DELIVER",
            "tag=$tag kind=${if (uri != null) "image" else "prompt"}" +
                " sendImage=${provider.capabilities.sendImage} autoSend=${req.autoSend}"
        )
        if (uri != null && provider.capabilities.sendImage) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null || bytes.isEmpty()) {
                    // 這裡以前只寫 android.util.Log：使用者看到「圖完全沒反應」，
                    // 而診斷頁一個字都沒有。讀不到檔就是讀不到，要留下證據。
                    AiDiag.log(context, provider, "DELIVER_FAIL", "tag=$tag reason=image_unreadable")
                    android.util.Log.d("InkFlowDbg", "UPLOAD read failed ($tag)")
                } else {
                    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    val key = req.dedupeKey()
                    val sel = provider.imagePasteInputSelector
                    if (provider.capabilities.fileInputFirst) {
                        // 第一段：只點站方上傳入口（不夾帶 base64）。chooser 有沒有開，
                        // 只有 onShowFileChooser 知道，所以第二段由這裡延遲決定。
                        AiDiag.log(context, provider, "IMG_JS", "mode=click key=$key b64=${base64.length}")
                        target?.evaluateJavascript(
                            buildImagePasteJs(
                                base64 = "",
                                attempts = attempts,
                                inputSelector = sel,
                                requestKey = key,
                                mode = IMAGE_MODE_CLICK
                            ),
                            null
                        )
                        target?.postDelayed({
                            // chooser 沒開（fedImageKey 沒被設）＝`input.click()` 被 WebView 擋掉，
                            // 圖根本沒進去。這時才補合成 paste——只補一次，所以不會兩張。
                            if (uploadState.fedImageKey != key) {
                                AiDiag.log(
                                    context, provider, "IMG_FALLBACK",
                                    "reason=chooser_not_fired wait=${CHOOSER_WAIT_MS}ms"
                                )
                                target?.evaluateJavascript(
                                    buildImagePasteJs(
                                        base64 = base64,
                                        attempts = attempts,
                                        inputSelector = sel,
                                        requestKey = key,
                                        mode = IMAGE_MODE_PASTE
                                    ),
                                    null
                                )
                            } else {
                                AiDiag.log(context, provider, "IMG_OK", "path=chooser key=$key")
                            }
                        }, CHOOSER_WAIT_MS)
                    } else {
                        AiDiag.log(context, provider, "IMG_JS", "mode=paste key=$key b64=${base64.length}")
                        target?.evaluateJavascript(
                            buildImagePasteJs(
                                base64 = base64,
                                attempts = attempts,
                                inputSelector = sel,
                                requestKey = key,
                                mode = IMAGE_MODE_PASTE
                            ),
                            null
                        )
                    }
                    android.util.Log.d("InkFlowDbg", "UPLOAD dispatched ($tag) b64len=${base64.length}")
                }
            } catch (e: Exception) {
                android.util.Log.e("InkFlowDbg", "UPLOAD $tag failed", e)
                AiDiag.log(context, provider, "DELIVER_FAIL", "tag=$tag err=${AiDiag.q(e.message.orEmpty())}")
                toastUploadError(e.message)
            }
        } else if (uri != null) {
            // 圖被 capability 擋掉（Tier 1 以外的站）：使用者看到「沒反應」，
            // 這裡留一行才不會被誤判成貼圖壞掉。
            AiDiag.log(context, provider, "DELIVER_SKIP", "tag=$tag reason=sendImage_false")
        }
        injectPromptIfNeeded(target, req)
    }
    
// AI 面板主題跟 App 深淺色變更時即時重套（自適應 invert，見 buildThemeJs）。
    // 與 applyTheme() 共用同一支腳本與同一組 log，這裡只是換呼叫點。
    androidx.compose.runtime.LaunchedEffect(webLight) {
        applyTheme(webView, "change")
    }

    // 圈選高亮配色取自 App 主題（不在 JS 裡寫死品牌色）。
    // accent 用 colorScheme.primary，其餘 rgba 由同一顏色推導，深淺色自然一致。
val isDarkPanel = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // 注意：MaterialTheme 必須在 remember **外面**讀——remember 的計算 lambda 不是
    // @Composable，裡面讀 colorScheme 編譯不過。
    val primaryColor = MaterialTheme.colorScheme.primary
    val pickPalette = androidx.compose.runtime.remember(primaryColor, isDarkPanel) {
        val c = primaryColor
        val r = (c.red * 255f).toInt().coerceIn(0, 255)
        val g = (c.green * 255f).toInt().coerceIn(0, 255)
        val b = (c.blue * 255f).toInt().coerceIn(0, 255)
        PickPalette(
            accent = String.format("#%02X%02X%02X", r, g, b),
            tint = "rgba($r,$g,$b,0.16)",
            hover = "rgba($r,$g,$b,0.10)",
            outline = "rgba($r,$g,$b,0.40)",
            barBg = if (isDarkPanel) "rgba(20,20,26,0.88)" else "rgba(28,28,34,0.90)",
            barFg = "rgba(255,255,255,0.94)",
            barBtn = "rgba(255,255,255,0.16)"
        )
    }

    // M2b-2：拉桿「引入」鈕兩段式 — ①進圈選模式（段落打勾）②收集打勾段落（無勾選則不插入）。
    // 圈選模式下「完成」鈕由網頁端回報，用這個計數器觸發收集。
    val pickDoneSignal = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }

    androidx.compose.runtime.LaunchedEffect(pickEnterId) {
        if (pickEnterId > 0) {
            try {
                webView?.evaluateJavascript(currentProvider.value.pickJs(palette = pickPalette), null)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(pickCollectId, pickDoneSignal.value) {
        if (pickCollectId <= 0) return@LaunchedEffect
        val provider = currentProvider.value
        val idleSelector = provider.replyIdleSelector
        try {
            // ChatGPT：回覆還在生成時去抓會拿到殘缺內容（且長對話虛擬滾動，畫面外的不在 DOM）。
            // 這道閘門刻意做在 Kotlin 側輪詢，**不去改 Gemini 已驗收的腳本結構**。
            if (idleSelector != null) {
                if (!awaitReplyIdle(webView, idleSelector)) {
                    android.util.Log.d("InkFlowDbg", "COLLECT aborted: reply still streaming after timeout")
                    AiDiag.log(context, provider, "IMPORT_ABORT", "reason=still_streaming idle=${AiDiag.q(idleSelector)}")
                    try {
                        android.widget.Toast.makeText(
                            context,
                            "AI 還在回覆中，請等它回完再匯入",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } catch (_: Throwable) { }
                    return@LaunchedEffect
                }
            }
            webView?.evaluateJavascript(provider.collectJs(), null)
            AiDiag.log(context, provider, "IMPORT_COLLECT", "idle=${idleSelector != null}")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Fix2d: WebView 原生底預設透明→首幀前是黑洞；鋪一層極淡的底頂著，
    // 但不能是不透明 surface 色：容器本身是玻璃，不透明底會把玻璃整片蓋死。
    // 半透明黑只在「還沒載入」時當底，載入後由網頁自己的內容接手。
    // 換站／收面板時把登入子視窗一起關掉：留著會變成一個蓋在別的站上面的殭屍視窗。
    androidx.compose.runtime.DisposableEffect(provider, hostOverride) {
        onDispose { authPopup?.close() }
    }

    val webViewBgArgb = android.graphics.Color.TRANSPARENT
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
    ) {
        // 切換 provider = 換站點，必須重建 WebView（loadUrl 只在 factory 跑一次）。
        // key 放這裡而不是外面，是為了讓「按一下切換」有明確的一次完整換血。
        androidx.compose.runtime.key(provider, hostOverride) {
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
                        // 一定要開：站方的登入（Google／Apple／Microsoft OAuth）走 window.open()，
                        // 關掉的話回呼頁會直接蓋掉主頁、window.opener 變 null，
                        // 權杖交不回來 → 使用者看到一片空白（實測 Kimi 就是這樣）。
                        setSupportMultipleWindows(true)
                        javaScriptCanOpenWindowsAutomatically = true
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
                                // 每一條結果都落檔（成功也要）：判斷「卡在哪一步」靠的是順序，
                                // 不是只有失敗。
                                AiDiag.log(context, currentProvider.value, "JS", result)
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
                                AiDiag.log(context, currentProvider.value, "PICK", "len=${json.length}")
                                pickedCallback.value(json)
                            } catch (t: Throwable) { }
                        }
                        /** 圈選浮動列的「完成」：網頁端按下 → 觸發收集，不必再回工具列。 */
                        @android.webkit.JavascriptInterface
                        fun onPickAction(action: String) {
                            try {
                                android.util.Log.d("InkFlowDbg", "PICK action=$action")
                                if (action == "done") {
                                    pickDoneSignal.value = pickDoneSignal.value + 1
                                }
                            } catch (t: Throwable) { }
                        }
                    }
                    this.addJavascriptInterface(jsBridge, "AndroidBridge")
                    
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        // 語音輸入放行：只 grant 麥克風（且系統授權已拿到才放）。
                        // 相機刻意不放（manifest 沒宣告 CAMERA，一律 deny，不裝會動）。
                        override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                            try {
                                if (request == null) return
                                val res = request.resources ?: emptyArray()
                                val wantAudio = res.contains(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                                val audioOk = ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (wantAudio && audioOk) {
                                    request.grant(
                                        res.filter {
                                            it == android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE
                                        }.toTypedArray()
                                    )
                                } else {
                                    request.deny()
                                }
                            } catch (_: Throwable) {
                                try { request?.deny() } catch (_: Throwable) { }
                            }
                        }
                        /**
                         * 站方開新視窗：登入用（Google／Apple／Microsoft OAuth 幾乎都是 popup）。
                         *
                         * 不接這個回呼，`window.open()` 等於沒發生，站方永遠等不到登入結果；
                         * 讓它「就地開在同一頁」更糟——回呼頁的 `window.opener` 是 null，
                         * 站方的 `opener.postMessage()` 直接 TypeError，權杖交不回主頁，
                         * 使用者看到的就是一片空白（Kimi 實測）。所以一定要真的開子視窗。
                         */
                        override fun onCreateWindow(
                            view: android.webkit.WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?
                        ): Boolean {
                            val transport =
                                resultMsg?.obj as? android.webkit.WebView.WebViewTransport
                                    ?: return false
                            val popup = (authPopup ?: AiAuthPopup(
                                context = context,
                                provider = currentProvider.value,
                                // 與主面板同一個（去掉 wv 字樣的）UA：換 UA 會被 Google 當成可疑裝置。
                                userAgent = view?.settings?.userAgentString.orEmpty(),
                                onClosed = {
                                    authPopup = null
                                    // 登入有沒有成功只信 DOM：cookie 生效與否，探針量的才是實話。
                                    // 延遲一下讓 SPA 先把 postMessage 處理完。
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        webView?.let { runProbe(it, "after-login") }
                                    }, 1200L)
                                }
                            ).also { authPopup = it }).show() ?: return false
                            transport.webView = popup
                            resultMsg.sendToTarget()
                            AiDiag.log(
                                context, currentProvider.value, "POPUP_OPEN",
                                "dialog=$isDialog gesture=$isUserGesture"
                            )
                            return true
                        }

                        override fun onShowFileChooser(
                            webView: android.webkit.WebView?,
                            filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                            fileChooserParams: FileChooserParams?
                        ): Boolean {
                            // 自動貼圖退路：頁面自己點了 input[type=file] 時，由我們遞上請求裡的圖。
                            // 每個請求只餵一次，避免之後使用者手動上傳被舊圖卡住。
                            val req = currentRequest.value
                            val pendingImage = req?.image
                            if (pendingImage != null && !req.isEmpty && uploadState.fedImageKey != req.dedupeKey()) {
                                uploadState.fedImageKey = req.dedupeKey()
                                // 這一行是「圖到底有沒有交到網頁手上」的唯一證據：
                                // 有 CHOOSER tag=feed 才代表點上傳入口那一步真的成功。
                                AiDiag.log(
                                    context, currentProvider.value, "CHOOSER",
                                    "tag=feed key=${req.dedupeKey()}"
                                )
                                filePathCallback?.onReceiveValue(arrayOf(pendingImage))
                                return true
                            }
                            // 使用者手動點上傳：開系統檔案選擇器（圖片／PDF／文件）。
                            AiDiag.log(
                                context, currentProvider.value, "CHOOSER",
                                "tag=picker pending=${pendingImage != null}"
                            )
                            if (filePathCallback == null) return false
                            try { fileChooser.callback?.onReceiveValue(null) } catch (_: Throwable) { }
                            fileChooser.callback = filePathCallback
                            launchFilePicker(fileChooserParams)
                            return true
                        }

                        override fun onConsoleMessage(
                            consoleMessage: android.webkit.ConsoleMessage?
                        ): Boolean {
                            // 只收 error：warning／info 幾乎都是雜訊，但擋站、CSP、
                            // 登入被 403 這種事一定以 error 出現在 console。
                            val m = consoleMessage
                            if (m != null && m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                                AiDiag.log(
                                    context, currentProvider.value, "CONSOLE",
                                    "msg=${AiDiag.q(redactSecrets(m.message(), 300))}" +
                                        " src=${AiDiag.q("${m.sourceId()}:${m.lineNumber()}")}"
                                )
                            }
                            return super.onConsoleMessage(m)
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
                            if (!provider.matchesHost(finishedUrl, currentHostGate.value)) {
                                // 載入了但不是本站：最常見是登入中轉或站方把人踢到別網域。
                                // 這一行少了，就會變成「什麼都沒發生」的假死。
                                AiDiag.log(
                                    context, provider, "PAGE_OTHER",
                                    "url=${AiDiag.url(finishedUrl)} gate=${AiDiag.q(currentHostGate.value ?: provider.host)}"
                                )
                                return
                            }

                            AiDiag.log(
                                context, provider, "PAGE",
                                "url=${AiDiag.url(finishedUrl)} capabilities=${provider.capabilities}"
                            )
                            uploadState.isPageLoaded = true
                            reportUrl(finishedUrl)
                            applyTheme(view, "load")
                            probeOnce(view, finishedUrl)
                            deliverPendingRequest(view, UPLOAD_ATTEMPTS_LOAD, "load")
                        }

                        // ── 診斷：載入失敗的真相 ───────────────────────────
                        // 這三個回呼不改變任何行為，只記錄。少了它們，「頁面一片白」
                        // 就只剩猜測：是 DNS、被擋 403/429、還是憑證被拒。
                        override fun onReceivedError(
                            view: android.webkit.WebView?,
                            request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            if (request?.isForMainFrame != true) return
                            AiDiag.log(
                                context, currentProvider.value, "NETERR",
                                "code=${error?.errorCode} desc=${AiDiag.q(error?.description?.toString())}" +
                                    " url=${AiDiag.url(request.url.toString())}"
                            )
                        }

                        override fun onReceivedHttpError(
                            view: android.webkit.WebView?,
                            request: android.webkit.WebResourceRequest?,
                            response: android.webkit.WebResourceResponse?
                        ) {
                            super.onReceivedHttpError(view, request, response)
                            // 只記主文件：子資源（追蹤腳本、字型）的 4xx 天天有，記了只是噪音。
                            if (request?.isForMainFrame != true) return
                            AiDiag.log(
                                context, currentProvider.value, "HTTPERR",
                                "status=${response?.statusCode} url=${AiDiag.url(request.url.toString())}"
                            )
                        }

                        override fun onReceivedSslError(
                            view: android.webkit.WebView?,
                            handler: android.webkit.SslErrorHandler?,
                            error: android.net.http.SslError?
                        ) {
                            AiDiag.log(
                                context, currentProvider.value, "SSLERR",
                                "primary=${error?.primaryError} url=${AiDiag.url(error?.url.orEmpty())}"
                            )
                            // 行為不變：預設就是取消，不降級驗證。
                            super.onReceivedSslError(view, handler, error)
                        }

                        // SPA 內跳轉（切對話）不走 onPageFinished，這裡才抓得到。
                        // 只回報、不做任何注入或重載，避免跟投遞鏈互相踩。
                        override fun doUpdateVisitedHistory(view: android.webkit.WebView?, url: String?, isReload: Boolean) {
                            super.doUpdateVisitedHistory(view, url, isReload)
                            reportUrl(url.orEmpty())
                        }
                    }

                    // 自訂 AI 沒填網址時兩者都空：不要 loadUrl("")，留給使用者去設定頁填。
                    val birthUrl = initialUrl?.takeIf { it.isNotBlank() } ?: provider.startUrl
                    AiDiag.log(
                        context, provider, "OPEN",
                        "url=${AiDiag.url(birthUrl)}" +
                            " gate=${AiDiag.q(hostOverride?.takeIf { it.isNotBlank() } ?: provider.host)}" +
                            " customOverride=${!hostOverride.isNullOrBlank()}" +
                            " capabilities=${provider.capabilities} strategy=${provider.importStrategy}"
                    )
                    if (birthUrl.isNotBlank()) loadUrl(birthUrl)
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
 * 等站方開檔案選擇器的時間。
 *
 * 為什麼要有這個等待：`input[type=file]` 的 `click()` 在 WebView 裡**不一定**會
 * 觸發 `onShowFileChooser`（沒有使用者手勢時可能被擋）。以前我們點完就當成功了，
 * 圖就安靜地消失——實測 Kimi／Qwen／Grok「填詞成功但圖沒進去」就是這個。
 * 現在點完等一段，chooser 沒開才改走模擬 paste（只走一條，不會兩張）。
 */
private const val CHOOSER_WAIT_MS = 1500L

/** [buildImagePasteJs] 的兩種模式：只點站方上傳入口／只合成 paste。 */
internal const val IMAGE_MODE_CLICK = "click"
internal const val IMAGE_MODE_PASTE = "paste"

/**
 * 貼圖腳本（單一路徑，`mode` 決定走哪一條）。
 *
 * ## 為什麼不再「paste 失敗就點 file input」
 *
 * `dispatchEvent(paste)` 的回傳值只代表**有沒有 listener 取消預設行為**，
 * 不代表圖真的進去了。實測 Kimi／Qwen／Grok 一律回 `PASTE_DISPATCH_FAILED`，
 * 但其中有些站其實已經在 paste handler 裡非同步把圖塞進去了——我們照著這個
 * 假訊號再點一次 file input，同一張圖就進去兩份（使用者看到的「變成 2 張」）。
 *
 * ## 現在的分工
 *
 * - [`IMAGE_MODE_CLICK`]：只點站方自己的上傳入口，等 `onShowFileChooser` 餵檔。
 *   這一步不夾帶 base64（少幾 MB 的字串跨層，也讓 click 不受圖檔大小影響）。
 * - [`IMAGE_MODE_PASTE`]：只合成 paste。**不會**再點 file input。
 *
 * 兩條路由 [AiCapabilities.fileInputFirst] 決定先後，並由 Kotlin 端的
 * `CHOOSER_WAIT_MS` 判斷要不要補 paste，所以同一次請求最多注入一次。
 */
internal fun buildImagePasteJs(
    base64: String,
    attempts: Int,
    inputSelector: String,
    requestKey: String,
    mode: String = IMAGE_MODE_PASTE
): String {
    // 單引號包字串：selector 裡有雙引號（見 AiGenericScripts 的說明）。
    val reqKey = requestKey.replace("'", "")
    val safeMode = if (mode == IMAGE_MODE_CLICK) IMAGE_MODE_CLICK else IMAGE_MODE_PASTE
    // click 模式不需要圖：把 base64 留在 Kotlin 端。少一次幾 MB 的字串跨層，
    // 也讓「點上傳入口」這一步不受圖檔大小影響。
    val b64 = if (safeMode == IMAGE_MODE_CLICK) "" else base64
    return """
        (function() {
            function report(s) {
                try { if (window.AndroidBridge && AndroidBridge.onPasteResult) AndroidBridge.onPasteResult(s); } catch(e){}
            }
            var REQ = '$reqKey';
            var MODE = '$safeMode';
            var CLICK = MODE === 'click';
            // 兩條路**各一把鎖**（不能共用）：click 這一步若鎖住 paste 的鎖，
            // 等不到 chooser 時的貼圖退路就永遠不會跑；反之 paste 先跑也不能擋掉 click。
            var lockName = CLICK ? '__inkClickReq' : '__inkImgReq';
            if (window[lockName] === REQ) { report('IMAGE_SKIP_DUP:' + MODE); return; }

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
                    report(dispatched ? 'PASTE_DISPATCHED' : 'PASTE_DISPATCH_FAILED');
                    return dispatched;
                } catch (e) {
                    report('PASTE_EXCEPTION');
                    return false;
                }
            }

            /**
             * 站方真正的上傳入口。頁面上可能有好幾個 file input（頭像、側欄、
             * 對話上傳），第一個不一定是對的——挑錯就等於「圖填到別的地方」。
             * 評分：accept 是圖片加分、在 composer／form 內加分、在 header／側欄／
             * 頭像裡扣分；同分取 document 後面那個（對話區通常在側欄之後）。
             */
            function pickFileInput() {
                var all = [];
                try { all = Array.prototype.slice.call(document.querySelectorAll('input[type="file"]')); } catch(e) {}
                var best = null, bestScore = -1000;
                for (var i = 0; i < all.length; i++) {
                    var inp = all[i];
                    var score = 0;
                    var accept = (inp.getAttribute('accept') || '').toLowerCase();
                    if (accept.indexOf('image') >= 0) score += 3;
                    else if (accept && accept.indexOf('*') < 0) score -= 2;
                    try {
                        if (inp.closest('header, nav, [class*="avatar"], [class*="profile"], [class*="sidebar"], [class*="user-menu"]')) score -= 6;
                        if (inp.closest('form, [class*="chat"], [class*="composer"], [class*="input"], [class*="upload"]')) score += 3;
                    } catch(e) {}
                    if (inp.disabled) score -= 5;
                    if (score >= bestScore) { bestScore = score; best = inp; }
                }
                if (best) report('FILE_INPUT_PICK:n=' + all.length + ':score=' + bestScore);
                return best;
            }

            var delivered = false;
            function deliver(chatInput) {
                if (delivered) return;
                delivered = true;
                // 真正投遞時才上鎖：輸入框還沒出現就鎖，會讓重試永遠被跳過。
                window[lockName] = REQ;
                if (CLICK) {
                    report('IMAGE_PATH:file_input');
                    var input = pickFileInput();
                    if (!input) { report('FILE_INPUT_NONE'); return; }
                    try {
                        input.click();
                        report('FILE_INPUT_CLICKED');
                    } catch(e) {
                        report('FILE_INPUT_CLICK_THREW');
                    }
                    return;
                }
                report('IMAGE_PATH:paste');
                if (!simulateImagePaste(chatInput, "$b64")) report('PASTE_ONLY_FAILED');
            }

            var attempts = 0;
            var interval = setInterval(function() {
                var chatInput = document.querySelector('$inputSelector');
                if (chatInput) {
                    clearInterval(interval);
                    deliver(chatInput);
                } else {
                    attempts++;
                    if (attempts >= $attempts) {
                        clearInterval(interval);
                        report('NO_CHAT_INPUT_FOUND:' + MODE);
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
