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
import com.vic.inkflow.ui.theme.ToolbarGlassDark
import com.vic.inkflow.ui.theme.ToolbarGlassLight
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
    fileUri: android.net.Uri?,
    prompt: String?,
    onPromptConsumed: () -> Unit,
    pickEnterId: Int = 0,
    pickCollectId: Int = 0,
    onTextGrabbed: (String) -> Unit = {},
    onClose: () -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var webView by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<android.webkit.WebView?>(null) }
    val currentFileUri = androidx.compose.runtime.rememberUpdatedState(fileUri)
    val currentPrompt = androidx.compose.runtime.rememberUpdatedState(prompt)
    val promptConsumedCallback = androidx.compose.runtime.rememberUpdatedState(onPromptConsumed)
    val grabbedCallback = androidx.compose.runtime.rememberUpdatedState(onTextGrabbed)
    val uploadState = androidx.compose.runtime.remember { 
        object {
            var lastProcessedUri: android.net.Uri? = null
            var isPageLoaded: Boolean = false
        }
    }

    // 快捷指令 prompt：圖貼上後另一下 JS 輪詢輸入框、填字自動送出（與貼圖腳本並行，內部延遲等圖先附著）。
    fun injectPromptIfNeeded(target: android.webkit.WebView?, uri: android.net.Uri) {
        val p = currentPrompt.value ?: return
        try {
            target?.evaluateJavascript(buildPromptSendJs(org.json.JSONObject.quote(p)), null)
            promptConsumedCallback.value()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    // M2b-2：拉桿「引入」鈕兩段式 — ①進圈選模式（段落打勾）②收集打勾段落（無勾選則取最後回覆全文）。
    androidx.compose.runtime.LaunchedEffect(pickEnterId) {
        if (pickEnterId > 0) {
            try {
                webView?.evaluateJavascript(buildPickJs(), null)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(pickCollectId) {
        if (pickCollectId > 0) {
            try {
                webView?.evaluateJavascript(buildCollectJs(), null)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Fix2d: WebView 原生底預設透明→首幀前是黑洞；先鋪主題 surface 色頂著
    val webViewBgArgb = androidx.compose.material3.MaterialTheme.colorScheme.surface.toArgb()
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .fillMaxSize()
            .background(androidx.compose.material3.MaterialTheme.colorScheme.surface)
    ) {
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
                    
                    webView = this
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
                            } catch (t: Throwable) { }
                        }
                        @android.webkit.JavascriptInterface
                        fun onTextGrabbed(text: String) {
                            try {
                                android.util.Log.d("AiWebPanel", "onTextGrabbed len=${text.length}")
                                grabbedCallback.value(text)
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
                            if (currentFileUri.value != null) {
                                filePathCallback?.onReceiveValue(arrayOf(currentFileUri.value!!))
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
                            if (url?.contains("gemini.google.com") == true) {
                                uploadState.isPageLoaded = true
                                val uri = currentFileUri.value
                                // 第一次載入完成時觸發，如果從未被處理過。
                                if (uri != null && uri != uploadState.lastProcessedUri) {
                                    uploadState.lastProcessedUri = uri
                                        // Build enhanced paste-and-fallback JS by encoding the image to Base64
                                        try {
                                            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                            if (bytes != null) {
                                                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                                                val js = """
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
                                                                const uniqueName = "inkflow_" + Date.now() + "_" + Math.random().toString(36).slice(2, 8) + ".png"; const file = new File([blob], uniqueName, { type: 'image/png' });

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
                                                            var chatInput = document.querySelector('rich-textarea, div[role="textbox"][contenteditable="true"]');
                                                            if (chatInput) {
                                                                clearInterval(interval);
                                                                var ok = simulateImagePaste(chatInput, "$base64");
                                                                if (!ok) {
                                                                    // try fallback strategies
                                                                    if (!tryFileInputClick()) {
                                                                        tryUploadButtonClick();
                                                                    }
                                                                }
                                                            } else {
                                                                attempts++;
                                                                if (attempts >= 20) {
                                                                    clearInterval(interval);
                                                                    try { if (window.AndroidBridge && AndroidBridge.onPasteResult) AndroidBridge.onPasteResult('NO_CHAT_INPUT_FOUND'); } catch(e){}
                                                                }
                                                            }
                                                        }, 500);
                                                    })();
                                                """.trimIndent()
                                                view?.evaluateJavascript(js, null)
                                                injectPromptIfNeeded(view, uri)
                                            }
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                }
                            }
                        }
                    }

                    loadUrl("https://gemini.google.com/app")
                }
            },
            update = { view ->
                // 當外部 fileUri 更新(如使用者再次點擊 AI 解析)時，若這沒被處理過，就直接對已開啟的網頁下指令。
                if (uploadState.isPageLoaded && fileUri != null && fileUri != uploadState.lastProcessedUri) {
                    uploadState.lastProcessedUri = fileUri
                    try {
                        val bytes = context.contentResolver.openInputStream(fileUri)?.use { it.readBytes() }
                        if (bytes != null) {
                            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                            val js = """
                                (function() {
                                    // same paste-and-fallback script as onPageFinished
                                    function simulateImagePaste(target, base64Data) {
                                        try {
                                            const byteCharacters = atob(base64Data);
                                            const byteNumbers = new Array(byteCharacters.length);
                                            for (let i = 0; i < byteCharacters.length; i++) {
                                                byteNumbers[i] = byteCharacters.charCodeAt(i);
                                            }
                                            const byteArray = new Uint8Array(byteNumbers);
                                            const blob = new Blob([byteArray], { type: 'image/png' });
                                            const uniqueName = "inkflow_" + Date.now() + "_" + Math.random().toString(36).slice(2, 8) + ".png"; const file = new File([blob], uniqueName, { type: 'image/png' });

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
                                        var chatInput = document.querySelector('rich-textarea, div[role="textbox"][contenteditable="true"]');
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
                                            if (attempts >= 10) {
                                                clearInterval(interval);
                                                try { if (window.AndroidBridge && AndroidBridge.onPasteResult) AndroidBridge.onPasteResult('NO_CHAT_INPUT_FOUND'); } catch(e){}
                                            }
                                        }
                                    }, 500);
                                })();
                            """.trimIndent()
                            view.evaluateJavascript(js, null)
                            injectPromptIfNeeded(view, fileUri)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            },
            modifier = androidx.compose.ui.Modifier.fillMaxSize()
        )
    }
}

/**
 * 快捷指令送出腳本：獨立輪詢 Gemini 輸入框（穿一層 shadow DOM），填入 prompt 並自動送出。
 * 與貼圖腳本並行執行，內部延遲 2.5s 等圖片先附著，避免圖文分家。
 * 執行結果透過 AndroidBridge.onPasteResult 回報（PROMPT_SENT / PROMPT_SENT_ENTER / …），可用 logcat 觀察。
 */
private fun buildPromptSendJs(promptQuoted: String): String {
    return """
        (function() {
            var PROMPT = $promptQuoted;
            function report(s) {
                try { if (window.AndroidBridge && window.AndroidBridge.onPasteResult) window.AndroidBridge.onPasteResult(s); } catch(e){}
            }
            function deepInput(root) {
                try {
                    var el = root.querySelector('div[contenteditable="true"], div[role="textbox"]');
                    if (el) return el;
                    var all = root.querySelectorAll('*');
                    for (var i = 0; i < all.length; i++) {
                        try {
                            if (all[i].shadowRoot) {
                                var f = deepInput(all[i].shadowRoot);
                                if (f) return f;
                            }
                        } catch(e){}
                    }
                } catch(e){}
                return null;
            }
            function findChatInput() {
                return deepInput(document) || document.querySelector('rich-textarea');
            }
            function fillAndSend(text) {
                try {
                    var el = findChatInput();
                    if (!el) return 'PROMPT_NO_INPUT';
                    try { el.focus(); } catch(e){}
                    try {
                        var range = document.createRange();
                        range.selectNodeContents(el);
                        range.collapse(false);
                        var sel = window.getSelection();
                        sel.removeAllRanges();
                        sel.addRange(range);
                    } catch(e){}
                    var ok = false;
                    try { ok = document.execCommand('insertText', false, text); } catch(e){}
                    if (!ok) {
                        try {
                            el.textContent = text;
                            el.dispatchEvent(new InputEvent('input', { bubbles: true }));
                            el.dispatchEvent(new Event('change', { bubbles: true }));
                            ok = true;
                        } catch(e){}
                    }
                    if (!ok) return 'PROMPT_INSERT_FAILED';
                    var sendBtn = document.querySelector('button[aria-label*="Send"]')
                        || document.querySelector('button[aria-label*="傳送"]')
                        || document.querySelector('button[aria-label*="发送"]');
                    if (sendBtn) {
                        sendBtn.click();
                        return 'PROMPT_SENT';
                    }
                    var ev;
                    try {
                        ev = new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true });
                    } catch(e) {
                        ev = document.createEvent('Event');
                        ev.initEvent('keydown', true, true);
                    }
                    (document.activeElement || el).dispatchEvent(ev);
                    return 'PROMPT_SENT_ENTER';
                } catch(err) {
                    return 'PROMPT_EXCEPTION';
                }
            }
            var attempts = 0;
            var interval = setInterval(function() {
                var el = findChatInput();
                if (el) {
                    clearInterval(interval);
                    setTimeout(function() { report(fillAndSend(PROMPT)); }, 2500);
                } else {
                    attempts++;
                    if (attempts >= 20) {
                        clearInterval(interval);
                        report('PROMPT_NO_INPUT_FOUND');
                    }
                }
            }, 500);
        })();
    """.trimIndent()
}

/**
 * M2b-2 圈選模式 v2：事件委派（document 級 capture listener，串流中新增/重渲染的段落照樣可點），
 * 打勾樣式走 <style> + ::before（不插入 DOM，innerText 永遠乾淨）。
 * 回報 PICK_MODE_ON:N（N=當下可點段落數）。
 */
private fun buildPickJs(): String {
    return """
        (function() {
            function note(s) {
                try { if (window.AndroidBridge && window.AndroidBridge.onPasteResult) window.AndroidBridge.onPasteResult(s); } catch(e){}
            }
            function deepAll(root, sel, out) {
                try {
                    var found = root.querySelectorAll(sel);
                    for (var i = 0; i < found.length; i++) out.push(found[i]);
                    var all = root.querySelectorAll('*');
                    for (var j = 0; j < all.length; j++) {
                        try { if (all[j].shadowRoot) deepAll(all[j].shadowRoot, sel, out); } catch(e){}
                    }
                } catch(e){}
                return out;
            }
            function inChrome(el) {
                try { return !!el.closest('header, nav, button, [role="navigation"], [role="button"]'); }
                catch(e){ return false; }
            }
            function findContainer() {
                var chains = ['div[data-message-author-role="model"]', 'message-content', '.response-container', '[class*="model-response"]', '[class*="response-content"]'];
                for (var c = 0; c < chains.length; c++) {
                    var hits = deepAll(document, chains[c], []);
                    if (hits.length > 0) return { el: hits[hits.length - 1], fb: false };
                }
                try {
                    var m = document.querySelector('main') || document.body;
                    if (m) return { el: m, fb: true };
                } catch(e){}
                return null;
            }
            try {
                window.__inkpick = false;
                if (window.__inkpickHandler) document.removeEventListener('click', window.__inkpickHandler, true);
                window.__inkpickHandler = null;
                var oldStyle = document.getElementById('inkpick-style');
                if (oldStyle) oldStyle.remove();
                document.querySelectorAll('[data-inkpick]').forEach(function(el) { el.removeAttribute('data-inkpick'); });
            } catch(e){}
            var found = findContainer();
            if (!found) { note('PICK_NO_CONTAINER'); return; }
            var container = found.el;
            var fallbackMain = found.fb;
            window.__inkpick = true;
            try {
                var st = document.createElement('style');
                st.id = 'inkpick-style';
                st.textContent = '[data-inkpick="0"]{outline:2px dashed #1a73e8 !important;outline-offset:2px !important;cursor:pointer !important;}'
                    + '[data-inkpick="1"]{outline:2px solid #1a73e8 !important;outline-offset:2px !important;background:rgba(26,115,232,0.15) !important;cursor:pointer !important;}'
                    + '[data-inkpick="1"]::before{content:"✓ ";color:#1a73e8;font-weight:bold;}';
                document.documentElement.appendChild(st);
            } catch(e){}
            function mark(el) {
                try {
                    if (!el || el.hasAttribute('data-inkpick')) return false;
                    if (!el.innerText || el.innerText.trim().length === 0) return false;
                    if (fallbackMain && inChrome(el)) return false;
                    el.setAttribute('data-inkpick', '0');
                    return true;
                } catch(e){ return false; }
            }
            var parts = container.querySelectorAll('p, li, h1, h2, h3, h4, pre, blockquote');
            var n = 0;
            if (parts.length === 0) {
                if (mark(container)) n = 1;
            } else {
                parts.forEach(function(el) { if (mark(el)) n++; });
            }
            window.__inkpickHandler = function(ev) {
                try {
                    if (!window.__inkpick) return;
                    var t = (ev.target && ev.target.closest) ? ev.target.closest('p, li, h1, h2, h3, h4, pre, blockquote') : null;
                    if (!t) return;
                    // 自癒：串流中重渲染導致容器換新時，不卡容器歸屬、有字就地標記（chrome 區除外）
                    if (inChrome(t)) return;
                    if (!t.hasAttribute('data-inkpick')) {
                        if (!t.innerText || t.innerText.trim().length === 0) return;
                        t.setAttribute('data-inkpick', '0');
                    }
                    ev.stopPropagation();
                    ev.preventDefault();
                    var on = t.getAttribute('data-inkpick') === '1';
                    t.setAttribute('data-inkpick', on ? '0' : '1');
                } catch(e){}
            };
            document.addEventListener('click', window.__inkpickHandler, true);
            note('PICK_MODE_ON:' + n);
        })();
    """.trimIndent()
}

/**
 * M2b-2 收集腳本 v2：照文件順序收打勾段落（排除被包在已勾段落內的子段，避免重複）；
 * 一個都沒勾則退回最後回覆全文。清掉委派 listener、樣式與全部標記。
 * 結果經 AndroidBridge.onTextGrabbed 回傳（上限 20000 字）。
 */
private fun buildCollectJs(): String {
    return """
        (function() {
            function note(s) {
                try { if (window.AndroidBridge && window.AndroidBridge.onPasteResult) window.AndroidBridge.onPasteResult(s); } catch(e){}
            }
            function report(t) {
                try { if (window.AndroidBridge && window.AndroidBridge.onTextGrabbed) window.AndroidBridge.onTextGrabbed(t); } catch(e){}
            }
            function deepAll(root, sel, out) {
                try {
                    var found = root.querySelectorAll(sel);
                    for (var i = 0; i < found.length; i++) out.push(found[i]);
                    var all = root.querySelectorAll('*');
                    for (var j = 0; j < all.length; j++) {
                        try { if (all[j].shadowRoot) deepAll(all[j].shadowRoot, sel, out); } catch(e){}
                    }
                } catch(e){}
                return out;
            }
            function lastReply() {
                var chains = ['div[data-message-author-role="model"]', 'message-content', '.response-container', '[class*="model-response"]', '[class*="response-content"]'];
                for (var c = 0; c < chains.length; c++) {
                    var hits = deepAll(document, chains[c], []);
                    if (hits.length > 0) {
                        var t = hits[hits.length - 1].innerText || '';
                        if (t.trim().length > 0) return t;
                    }
                }
                try {
                    var blocks = document.querySelectorAll('main p, main li, article p, article li');
                    var acc = [];
                    for (var k = 0; k < blocks.length; k++) {
                        var bt = blocks[k].innerText || '';
                        if (bt.trim().length > 0) acc.push(bt.trim());
                    }
                    if (acc.length > 0) return acc.join('\n\n');
                } catch(e){}
                return '';
            }
            try {
                window.__inkpick = false;
                if (window.__inkpickHandler) document.removeEventListener('click', window.__inkpickHandler, true);
                window.__inkpickHandler = null;
                var st = document.getElementById('inkpick-style');
                if (st) st.remove();
            } catch(e){}
            var all = Array.prototype.slice.call(document.querySelectorAll('[data-inkpick="1"]'));
            var roots = all.filter(function(el) {
                var p = el.parentElement;
                while (p) {
                    if (p.getAttribute && p.getAttribute('data-inkpick') === '1') return false;
                    p = p.parentElement;
                }
                return true;
            });
            var out = [];
            roots.forEach(function(el) {
                try {
                    var t = el.innerText || '';
                    if (t.trim().length > 0) out.push(t.trim());
                } catch(e){}
            });
            var src = 'picked x' + out.length;
            if (out.length === 0) {
                var fb = lastReply();
                if (fb.trim().length > 0) {
                    out = [fb];
                    src = 'fallback-full';
                }
            }
            try {
                document.querySelectorAll('[data-inkpick]').forEach(function(el) { el.removeAttribute('data-inkpick'); });
            } catch(e){}
            note('COLLECT src=' + src);
            report(out.join('\n\n').slice(0, 20000));
        })();
    """.trimIndent()
}
