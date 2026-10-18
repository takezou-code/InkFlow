package com.vic.inkflow.ui

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vic.inkflow.data.TextAnnotationEntity
import com.vic.inkflow.ui.theme.BrandIndigo
import kotlin.math.roundToInt

/**
 * 行內文字編輯器（P4 從 InkCanvas 抽出）。
 *
 * 為什麼這塊值得獨立：它是 InkCanvas 裡少數「純輸出、不碰手勢也不碰繪製管線」
 * 的區塊——只負責把一組已經算好的座標與樣式畫成一個輸入框。抽出後
 * InkCanvas 的繪製/手勢主體不再和輸入框樣式混在同一個 2200 行函式裡。
 *
 * 座標換算**刻意全部留在呼叫端**（model 空間 → 像素空間的比例是那裡的語意，
 * 依賴 viewModel.modelWidth/modelHeight）。這個元件對 model 空間完全無知，
 * 也就更不可能在重構中把換算搞錯——這正是原本把它留在 InkCanvas 的隱含理由。
 *
 * ## 所見即所得（打字時看到的＝送出後的樣子）
 *
 * 這塊以前長成一張**白底靛框卡片**，跟成品的裸字差很多（實機回報「大小也不一樣」）。
 * 三個原因，現已逐一對齊：
 *  - 白底是為了蓋住底下那段已提交的舊文字——因為畫布當時並沒有把被編輯的標註藏起來。
 *    現在 InkCanvas 在編輯時跳過那一段，所以不需要再遮，也就拿掉白底與內距。
 *  - 內距（6dp）與邊框讓文字內縮，位置與成品差一截 → 改成**不佔版面**的 `drawBehind` 淡框。
 *  - 行高與首位基線以前由 Compose 預設行高決定 → 改成與畫布同一顆 `Paint`（DEFAULT_BOLD）
 *    的 `fontMetrics`：行高 = `-ascent + descent + leading`，首位基線落在 `anchorPx.y`。
 *
 * `includeFontPadding` 不需要自己關：Compose BOM ≥ 2024.01.01 起預設已是 `false`，
 * 與 `Paint.fontMetrics` 同口徑；`PlatformTextStyle` 已棄用，不要再加回來。
 *
 * 殘留差異：Compose 文字排版與 `Paint.drawText` 不可能完全同一（半形字寬的估算、
 * 少數斷行規則）。CJK 每字 1em、兩邊同寬，斷行點一致；拉丁文偶爾會差一個字。
 */
@Composable
internal fun InlineTextEditor(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    focusRequester: FocusRequester,
    /** 正在編輯的既有標註；null 表示新建。 */
    editing: TextAnnotationEntity?,
    /** 新建時的錨點（像素）。 */
    newAnchorPx: Offset?,
    /** 畫布像素寬度（用來夾住輸入框不超出右緣）。 */
    canvasWidthPx: Float,
    /** model→像素 的 x 比例（canvasWidthPx / modelWidth）。 */
    scaleX: Float,
    /** model→像素 的 y 比例（canvasHeightPx / modelHeight）。 */
    scaleY: Float,
    /** 新建時的字級（像素）；編輯既有標註時用 [editing] 的 fontSize。 */
    defaultFontPx: Float,
    /** 游標/文字色（ARGB），新建時使用。 */
    cursorColorArgb: Int,
    /** 文字框寬（像素）；null = 舊資料（貼合內容、最寬到畫布右緣）。 */
    boxWidthPx: Float? = null,
    /** 對齊 0=左 1=中 2=右。 */
    alignment: Int = 0
) {
    val density = LocalDensity.current
    val anchorPx = when {
        editing != null -> Offset(editing.modelX * scaleX, editing.modelY * scaleY)
        newAnchorPx != null -> newAnchorPx
        else -> Offset.Zero
    }
    val fontPx = (editing?.fontSize?.times(scaleY) ?: defaultFontPx).coerceAtLeast(1f)
    // 與畫布同一顆字體度量：行高由它算（見類別註解）。
    val (lineHeightPx, ascentPx) = remember(fontPx) {
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textSize = fontPx
        }
        val fm = p.fontMetrics
        (-fm.ascent + fm.descent + fm.leading) to fm.ascent
    }
    // 首位基線的**實測值**（相對輸入框頂端）。null = 還沒量到，先用 Paint 估。
    //
    // 為什麼不直接用 Paint 的 ascent 定位：Compose 的行盒語意（lineHeightStyle 把多出來的
    // leading 放哪、第一個行盒頂端對齊誰）與 `Paint.fontMetrics` 不會剛好一致，**用估的就會
    // 差幾 px**——實機回報「文字往左上飄一點點，不是真正實際的位置」。
    // 改成量了再對位（`boxY = 基線 − 實測基線`），就**保證**首位基線落在 anchorPx.y，
    // 不論 Compose 內部怎麼排。字級/縮放變了會重新量（remember key = fontPx）。
    var measuredBaselinePx by remember(fontPx) { mutableStateOf<Float?>(null) }
    // 文字框：內容寬＝成品的折行寬。**不再**扣內距與邊框（以前扣了，斷行點就與成品不同）。
    val fixedBoxW = boxWidthPx?.let { w -> with(density) { w.coerceAtLeast(1f).toDp() } }
    // 有框時左緣就是框左緣（框本來就在紙內）；舊資料（貼合內容）才需要夾住不超出右緣。
    val boxX = if (fixedBoxW != null) anchorPx.x.coerceAtLeast(0f)
    else anchorPx.x.coerceIn(0f, (canvasWidthPx - 160f).coerceAtLeast(0f))
    val maxBoxW = with(density) { (canvasWidthPx - boxX - 8f).coerceAtLeast(120f).toDp() }
    // anchorPx 是第一行基線。頂端 = 基線 −（基線距頂端）：量到就用**實測值**（保證對齊），
    // 還沒量到才退回 Paint 的估法（-ascent）。舊算法 `-fontPx - 8f` 是「扣掉一行再退 8」，
    // 整塊因此偏低（實機回報位置不同）。
    val boxY = (anchorPx.y - (measuredBaselinePx ?: -ascentPx)).coerceAtLeast(0f)
    val textAlign = when (alignment) {
        1 -> TextAlign.Center
        2 -> TextAlign.End
        else -> TextAlign.Start
    }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            // roundToInt 不是 toInt：toInt 對正數是無條件捨去，輸入框會比成品偏左上約 1px。
            .offset { IntOffset(boxX.roundToInt(), boxY.roundToInt()) }
            .then(
                if (fixedBoxW != null) Modifier.width(fixedBoxW)
                else Modifier.widthIn(min = 140.dp, max = maxBoxW)
            )
            // 「正在輸入」提示：不佔版面的淡框。沒有內距、沒有白底，
            // 所以文字位置與成品一致（白底已由「畫布不畫被編輯的標註」取代）。
            .drawBehind {
                drawRoundRect(
                    color = BrandIndigo.copy(alpha = 0.40f),
                    cornerRadius = CornerRadius(with(density) { 6.dp.toPx() }),
                    style = Stroke(width = with(density) { 1.5.dp.toPx() })
                )
            }
            .focusRequester(focusRequester),
        textStyle = TextStyle(
            fontSize = with(density) { fontPx.toSp() },
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            lineHeight = with(density) { lineHeightPx.toSp() },
            // 第一行的字貼齊行盒頂端，首位基線才會正好落在 anchorPx.y。
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Top,
                trim = LineHeightStyle.Trim.None
            ),
            color = Color(editing?.colorArgb ?: cursorColorArgb),
            textAlign = textAlign
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        // 量 Compose 真正把第一行基線放在哪裡（見 measuredBaselinePx 的註解）。
        onTextLayout = { layout ->
            if (layout.lineCount > 0) {
                val b = layout.getLineBaseline(0)
                if (measuredBaselinePx != b) measuredBaselinePx = b
            }
        },
        singleLine = false,
        // 不設上限：以前 maxLines=8，打超過 8 行就看不到自己打的字，送出後才全部冒出來。
        maxLines = Int.MAX_VALUE,
        cursorBrush = SolidColor(BrandIndigo)
    )
}
