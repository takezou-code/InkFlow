package com.vic.inkflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vic.inkflow.data.TextAnnotationEntity
import com.vic.inkflow.ui.theme.BrandIndigo

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
    cursorColorArgb: Int
) {
    val density = LocalDensity.current
    val anchorPx = when {
        editing != null -> Offset(editing.modelX * scaleX, editing.modelY * scaleY)
        newAnchorPx != null -> newAnchorPx
        else -> Offset.Zero
    }
    val fontPx = editing?.fontSize?.times(scaleY) ?: defaultFontPx
    val boxX = anchorPx.x.coerceIn(0f, (canvasWidthPx - 160f).coerceAtLeast(0f))
    // anchorPx 是第一行基線；輸入框頂端往上讓一行
    val boxY = (anchorPx.y - fontPx - 8f).coerceAtLeast(0f)
    val maxBoxW = with(density) { (canvasWidthPx - boxX - 8f).coerceAtLeast(120f).toDp() }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .offset { IntOffset(boxX.toInt(), boxY.toInt()) }
            .widthIn(min = 140.dp, max = maxBoxW)
            .background(Color.White, RoundedCornerShape(6.dp))
            .border(1.5.dp, BrandIndigo, RoundedCornerShape(6.dp))
            .padding(6.dp)
            .focusRequester(focusRequester),
        textStyle = TextStyle(
            fontSize = with(density) { fontPx.toSp() },
            fontWeight = FontWeight.Bold,
            color = Color(editing?.colorArgb ?: cursorColorArgb)
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        singleLine = false,
        maxLines = 8,
        cursorBrush = SolidColor(BrandIndigo)
    )
}
