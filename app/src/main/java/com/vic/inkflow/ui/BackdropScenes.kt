package com.vic.inkflow.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sin
import kotlin.random.Random

/** 背景三類：泡泡（現行）／特效場景／圖片。 */
enum class BackdropKind { ORB, SCENE, IMAGE }

/** 特效場景：沙丘／汐（深海）。霓城已刪除（太陽條紋驗收失敗）。 */
enum class BackdropScene { DUNE, TIDE }

/**
 * 背景統一入口：三類在此分流，書庫＋編輯器只調這一支。
 * haze 採樣掛外層 modifier（照舊由呼叫端帶 hazeSource 進來）。
 */
@Composable
fun InkBackdrop(
    kind: BackdropKind,
    orbTheme: BackdropTheme,
    scene: BackdropScene,
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

/** 特效場景：時間參數化，static 凍結首幀（跟 Aurora 同一套 tick 機制）。 */
@Composable
private fun SceneBackdrop(
    scene: BackdropScene,
    static: Boolean,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    val tick = remember { mutableLongStateOf(0L) }
    val t0 = remember { System.nanoTime() }
    // 幾何只算一次：普通 List，不是 State，永不觸發重組
    val sparks = remember { makeSparks() }
    LaunchedEffect(static) {
        if (!static) {
            while (true) {
                kotlinx.coroutines.delay(22)
                tick.longValue += 1
            }
        }
    }
    Canvas(modifier = modifier.fillMaxSize()) {
        tick.longValue
        val t = (System.nanoTime() - t0) / 1_000_000_000f
        val w = size.width.coerceAtLeast(1f)
        val h = size.height.coerceAtLeast(1f)
        when (scene) {
            BackdropScene.DUNE -> drawDune(t, w, h, isDarkTheme)
            BackdropScene.TIDE -> drawTide(t, w, h, isDarkTheme, sparks)
        }
    }
}

// ── 霓城已刪除（太陽條紋驗收失敗） ──

// ── 沙丘：層疊正弦 ridge＋月暈 ───────────────────────────────

private fun DrawScope.drawDune(t: Float, w: Float, h: Float, isDark: Boolean) {
    drawRect(
        brush = Brush.verticalGradient(
            colors = if (isDark) listOf(Color(0xFF060414), Color(0xFF141038), Color(0xFF241B4D))
            else listOf(Color(0xFFF1EDFB), Color(0xFFD9CFF3), Color(0xFFB7A6E4))
        )
    )
    // 月＋暈
    val moonC = Offset(w * 0.78f, h * 0.20f)
    val moonR = minOf(w, h) * 0.07f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFEDE9FE).copy(alpha = 0.5f), Color.Transparent),
            center = moonC,
            radius = moonR * 3.2f
        ),
        radius = moonR * 3.2f,
        center = moonC
    )
    drawCircle(color = Color(0xFFEDE9FE), radius = moonR, center = moonC)
    // ridge：4 層，後慢前快，填色前深
    val layers = listOf(
        Triple(0.55f, 0.030f, 0.10f),
        Triple(0.64f, 0.042f, 0.16f),
        Triple(0.73f, 0.055f, 0.24f),
        Triple(0.83f, 0.070f, 0.34f)
    )
    layers.forEachIndexed { idx, (base, amp, speed) ->
        val topPath = Path()
        val steps = 48
        for (s in 0..steps) {
            val x = w * s / steps
            val y = h * base + h * amp * sin(x / w * 6.2832f * (1.2f + idx * 0.35f) + t * speed + idx * 1.7f)
            if (s == 0) topPath.moveTo(x, y) else topPath.lineTo(x, y)
        }
        // 填色用閉合路徑，描邊只走頂緣（整圈描會描出底邊側邊，很框）
        val fillPath = Path().apply {
            addPath(topPath)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        val shade = idx / (layers.size - 1).toFloat()
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = if (isDark) listOf(
                    Color(0xFF2E2360).copy(alpha = 0.75f + 0.25f * shade),
                    Color(0xFF120C2E)
                ) else listOf(
                    Color(0xFF8F7BD8).copy(alpha = 0.65f + 0.35f * shade),
                    Color(0xFF5B4BA8)
                )
            )
        )
        // ridge 受光緣：只描頂，只提一點
        drawPath(
            path = topPath,
            color = Color.White.copy(alpha = if (isDark) 0.08f else 0.16f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f)
        )
    }
}

// ── 汐：多層正弦水體＋浮光 ───────────────────────────────────

private fun DrawScope.drawTide(t: Float, w: Float, h: Float, isDark: Boolean, sparks: List<Triple<Float, Float, Float>>) {
    drawRect(
        brush = Brush.verticalGradient(
            colors = if (isDark) listOf(Color(0xFF020B18), Color(0xFF062033), Color(0xFF0A3A52))
            else listOf(Color(0xFFE4F4FB), Color(0xFFB5DCF0), Color(0xFF7FB8DC))
        )
    )
    // 浮光：緩慢上升回繞
    sparks.forEach { (fx, fy, sp) ->
        val yy = ((1.05f - ((t * 0.03f * sp + fy) % 1.1f)) * h)
        val tw = 0.4f + 0.6f * (0.5f + 0.5f * sin(t * sp + fx * 6.28f))
        drawCircle(
            color = Color(0xFF9BE8FF).copy(alpha = 0.5f * tw),
            radius = 2.5f,
            center = Offset(fx * w, yy)
        )
    }
    // 水體：5 層，前快後慢
    for (idx in 0 until 5) {
        val f = idx / 4f
        val base = 0.45f + f * 0.38f
        val amp = 0.018f + f * 0.030f
        val speed = 0.55f - f * 0.32f
        val freq = 1.6f + idx * 0.5f
        val path = Path()
        val steps = 60
        for (s in 0..steps) {
            val x = w * s / steps
            val y = h * base + h * amp * sin(x / w * 6.2832f * freq + t * speed * 2f + idx * 2.1f)
            if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.lineTo(w, h)
        path.lineTo(0f, h)
        path.close()
        drawPath(
            path = path,
            color = (if (isDark) Color(0xFF0E5A7A) else Color(0xFF3E96C4)).copy(alpha = 0.28f + f * 0.5f)
        )
    }
}

private fun makeSparks(): List<Triple<Float, Float, Float>> {
    val rnd = Random(0x71DEL)
    return List(26) { Triple(rnd.nextFloat(), rnd.nextFloat(), 0.5f + rnd.nextFloat()) }
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
    androidx.compose.runtime.LaunchedEffect(imageUri) {
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
