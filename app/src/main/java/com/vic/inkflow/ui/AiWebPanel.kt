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
    onClose: () -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var webView by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<android.webkit.WebView?>(null) }
    val currentFileUri = androidx.compose.runtime.rememberUpdatedState(fileUri)
    val uploadState = androidx.compose.runtime.remember { 
        object {
            var lastProcessedUri: android.net.Uri? = null
            var isPageLoaded: Boolean = false
        }
    }
    
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .fillMaxSize()
            .background(androidx.compose.material3.MaterialTheme.colorScheme.surface)
    ) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                android.webkit.WebView(ctx).apply {
                    // 重要：手動設置 LayoutParams 填滿父容器，避免 Compose 與 WebView 測量時發生高度坍塌(黑畫面主因之一)
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    
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

                    // JavaScript bridge: allow JS to notify Android about paste/click results
                    val jsBridge = object {
                        @android.webkit.JavascriptInterface
                        fun onPasteResult(result: String) {
                            try {
                                android.util.Log.d("AiWebPanel", "onPasteResult: $result")
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
                                                                const file = new File([blob], "pasted_image.png", { type: 'image/png' });

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
                                            const file = new File([blob], "pasted_image.png", { type: 'image/png' });

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
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            },
            modifier = androidx.compose.ui.Modifier.fillMaxSize()
        )

        // 懸浮的半透明關閉按鈕
        androidx.compose.material3.IconButton(
            onClick = onClose,
            modifier = androidx.compose.ui.Modifier
                .align(androidx.compose.ui.Alignment.TopEnd)
                .padding(16.dp)
                .background(
                    color = androidx.compose.material3.MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                    shape = androidx.compose.foundation.shape.CircleShape
                )
        ) {
            androidx.compose.material3.Icon(
                imageVector = androidx.compose.material.icons.Icons.Outlined.Close,
                contentDescription = "Close",
                tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
