package com.vic.inkflow.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.vic.inkflow.ui.backdrop.BackdropScene
import com.vic.inkflow.ui.backdrop.DuneScene
import com.vic.inkflow.ui.backdrop.SceneBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 背景三類：泡泡（[AuroraBackground]）／特效場景（`:shared` 的 [SceneBackdrop]）／圖片。
 * [label] 只有這一份，設定頁不准再寫 `when` 對照表。
 */
enum class BackdropKind(val label: String) {
    ORB("泡泡"),
    SCENE("特效"),
    IMAGE("圖片")
}

/**
 * 背景統一入口：三類在此分流，書庫＋編輯器只調這一支。
 *
 * 場景本體不住這裡：場景是純 Compose＋數學，下沉 `:shared`（桌機之後直接吃得到），
 * 加場景＝在 `:shared` 加一個檔＋註冊表一行，這一支不用改。
 * 只有 IMAGE 留在 app——它用 Android 的 SAF URI 與 `BitmapFactory` 解碼。
 *
 * haze 採樣掛外層 modifier（照舊由呼叫端帶 hazeSource 進來）。
 */
@Composable
fun InkBackdrop(
    kind: BackdropKind,
    orbTheme: BackdropTheme,
    scene: BackdropScene = DuneScene,
    imageUri: Uri?,
    static: Boolean,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier,
    orbCount: Int = 5
) {
    when (kind) {
        BackdropKind.ORB -> AuroraBackground(
            isDarkTheme = isDarkTheme,
            modifier = modifier,
            orbCount = orbCount,
            static = static,
            theme = orbTheme
        )
        BackdropKind.SCENE -> SceneBackdrop(
            scene = scene,
            static = static,
            isDarkTheme = isDarkTheme,
            modifier = modifier
        )
        BackdropKind.IMAGE -> ImageBackdrop(
            imageUri = imageUri,
            isDarkTheme = isDarkTheme,
            modifier = modifier
        )
    }
}

// ── 圖片：SAF 持久權限直讀＋罩紗保可讀 ─────────────────────────

@Composable
private fun ImageBackdrop(
    imageUri: Uri?,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var bitmap by remember(imageUri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(imageUri) {
        bitmap = if (imageUri == null) null else withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(imageUri)?.use { ins ->
                    BitmapFactory.decodeStream(ins)
                }
            }.getOrNull()
        }
    }
    Box(modifier = modifier.fillMaxSize()) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            // 無圖時退回深空底，不留黑洞
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (isDarkTheme) Color(0xFF0B0F1E) else Color(0xFFF1F5F9))
            )
        }
        // 罩紗：保玻璃上文字可讀
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (isDarkTheme) Color.Black.copy(alpha = 0.35f)
                    else Color.White.copy(alpha = 0.25f)
                )
        )
    }
}
