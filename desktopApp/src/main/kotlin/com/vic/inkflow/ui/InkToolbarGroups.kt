package com.vic.inkflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.Highlight
import androidx.compose.material.icons.rounded.LineWeight
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.NavigateBefore
import androidx.compose.material.icons.rounded.NavigateNext
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.Rectangle
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vic.inkflow.ui.INK_PALETTE
import com.vic.inkflow.ui.INK_WIDTH_PRESETS
import com.vic.inkflow.ui.SHAPE_LABELS
import com.vic.inkflow.ui.kit.DeskTextField
import com.vic.inkflow.util.ShapeType
import kotlin.math.roundToInt

/**
 * Pieces of the reader's command bar, split out of `PdfViewer.kt` so the
 * toolbar body reads as a sequence of named groups instead of one wall of icons.
 *
 * ## How the bar is put together
 *
 * ```
 * [file + armed tool] | [drawing tools][annotate tools][history] | [ink][page][zoom][more]
 *   fixed: who you are  | scrolls: what you do                    | fixed: where you are
 * ```
 *
 * ## Two rules that keep it still
 *
 * - **Nothing moves on hover.** An earlier revision reported the hovered
 *   tool's name up to the title block; the name's width changed, the row
 *   re-laid out, the tool slid out from under the pointer, hover cancelled,
 *   the name reverted - and the bar trembled for as long as the pointer
 *   rested on it. Hover state now stays inside the button and only paints a
 *   fill there; the armed tool's name lives in a slot of fixed width.
 * - **One bottom per surface.** The pill is [fauxGlassPanel]; the sub-groups
 *   (page stepper, zoom) are inset fields, not stacked surfaces.
 */

/**
 * A tool button.
 *
 * Hover paints a quiet fill on the button itself - it never reports upward,
 * never resizes anything, never re-lays out the row. That discipline is what
 * fixed the "toolbar trembles under the mouse" report.
 */
@Composable
fun InkToolIcon(
    icon: ImageVector,
    description: String,
    selected: Boolean,
    enabled: Boolean = true,
    badgeCount: Int = 0,
    onClick: () -> Unit
) {
    val isDark = InkThemeState.darkMode
    // Armed = the pearl pill in the theme colour; hover = a whisper of ink.
    // Both are painted on the button, so neither can shift the layout.
    val primary = MaterialTheme.colorScheme.primary
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .then(
                when {
                    selected -> Modifier.glassSelectionPill(CircleShape)
                    hovered && enabled -> Modifier.background(
                        glassContentColor(isDark).copy(alpha = 0.10f),
                        CircleShape
                    )
                    else -> Modifier
                }
            )
            .hoverable(interaction)
            .glassClickable(onClick = onClick, shape = CircleShape, enabled = enabled),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = when {
                !enabled -> glassContentColor(isDark).copy(alpha = 0.35f)
                selected -> primary
                else -> glassContentColor(isDark)
            },
            modifier = Modifier.size(20.dp)
        )
        // Selection count rides on the icon: the number is visible exactly
        // where the mode is armed, and the row stays compact.
        if (badgeCount > 0) {
            Text(
                text = if (badgeCount > 99) "99+" else badgeCount.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .padding(horizontal = 3.dp)
            )
        }
    }
}

/** A hairline between groups of controls. */
@Composable
fun InkToolbarDivider() {
    androidx.compose.material3.VerticalDivider(
        modifier = Modifier
            .height(20.dp)
            .padding(horizontal = 5.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
    )
}

/** File name and the tool that is armed. The tool name sits in a fixed-width slot. */
@Composable
fun InkToolbarTitle(
    documentTitle: String,
    tool: InkTool
) {
    if (documentTitle.isBlank()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            documentTitle,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = glassContentColor(InkThemeState.darkMode),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 130.dp)
                .padding(end = 6.dp)
        )
        // 現在手上拿的是哪一支：圖示列看不出來時，這裡是答案。
        // 槽位寬度固定——換工具時名字換字不換位，整條列不會因此左右挪動。
        Box(
            modifier = Modifier.width(46.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = inkToolLabel(tool),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
        }
    }
}

/**
 * The tools.
 *
 * Three groups with hairlines between them: the ones that put ink on the page
 * (pen, highlighter, eraser) and the ones that annotate it (lasso, text,
 * shape). The hand is a third thing entirely - it is navigation, not editing
 * - so it stands alone at the head.
 *
 * The glyphs are the real thing, not the nearest lookalike: the highlighter
 * was a paintbrush, the lasso was a dashed "select all" box, the eraser was
 * a trash can.
 */
@Composable
fun InkToolbarTools(
    tool: InkTool,
    selectionCount: Int,
    onToolChange: (InkTool) -> Unit
) {
    InkToolIcon(Icons.Rounded.PanTool, "手形（拖曳捲動）", tool == InkTool.Hand, onClick = { onToolChange(InkTool.Hand) })

    InkToolbarDivider()

    InkToolIcon(Icons.Rounded.Brush, "畫筆", tool == InkTool.Pen, onClick = { onToolChange(InkTool.Pen) })
    InkToolIcon(Icons.Rounded.Highlight, "螢光筆", tool == InkTool.Highlighter, onClick = { onToolChange(InkTool.Highlighter) })
    InkToolIcon(EraserGlyph, "橡皮擦", tool == InkTool.Eraser, onClick = { onToolChange(InkTool.Eraser) })

    InkToolbarDivider()

    InkToolIcon(
        Icons.Rounded.CropFree,
        if (selectionCount > 0) "套索選取（已選 $selectionCount）" else "套索選取",
        tool == InkTool.Select,
        badgeCount = selectionCount,
        onClick = { onToolChange(InkTool.Select) }
    )
    InkToolIcon(Icons.Rounded.Title, "文字註解", tool == InkTool.Text, onClick = { onToolChange(InkTool.Text) })
    InkToolIcon(Icons.Rounded.Rectangle, "圖形", tool == InkTool.Shape, onClick = { onToolChange(InkTool.Shape) })
}

@Composable
fun InkToolbarTextSelection(onCopy: () -> Unit) {
    GlassTextButton(text = "複製", onClick = onCopy)
}

@Composable
fun InkToolbarHistory(
    canUndo: Boolean,
    canRedo: Boolean,
    selectionCount: Int,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDeleteSelection: () -> Unit
) {
    InkToolIcon(Icons.AutoMirrored.Rounded.Undo, "復原", false, enabled = canUndo, onClick = onUndo)
    InkToolIcon(Icons.AutoMirrored.Rounded.Redo, "重做", false, enabled = canRedo, onClick = onRedo)
    if (selectionCount > 0) {
        InkToolIcon(Icons.AutoMirrored.Rounded.Backspace, "刪除選取", false, onClick = onDeleteSelection)
    }
}

@Composable
fun InkToolbarShapeSubtypes(
    tool: InkTool,
    shapeSubType: ShapeType,
    onShapeSubTypeChange: (ShapeType) -> Unit
) {
    if (tool != InkTool.Shape) return
    InkToolbarDivider()
    ShapeType.entries.forEach { st ->
        GlassOptionChip(
            text = SHAPE_LABELS[st] ?: st.name,
            selected = shapeSubType == st,
            onClick = { onShapeSubTypeChange(st) },
            isDark = InkThemeState.darkMode
        )
    }
}

/**
 * The ink settings: colour and stroke width, in one control.
 *
 * Four swatches and three width chips used to sit side by side in the bar -
 * 200dp of command bar spent on something you touch once per stroke. Now it
 * is one button that shows the current colour and opens the palette.
 */
@Composable
fun InkToolbarBrush(
    colour: Int,
    widthPt: Float,
    onColourChange: (Int) -> Unit,
    onWidthChange: (Float) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val isDark = InkThemeState.darkMode
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier
                .clip(CircleShape)
                .background(glassContentColor(isDark).copy(alpha = 0.10f))
                .glassClickable(onClick = { open = true }, shape = CircleShape)
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            // 目前的顏色：直接是那個顏色，不用顏色名的標籤。
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(Color(colour))
                    .border(1.dp, glassContentColor(isDark).copy(alpha = 0.55f), CircleShape)
            )
            // 三條粗細不同的線＝粗細設定（Material 的 line_weight）。
            Icon(
                Icons.Rounded.LineWeight,
                contentDescription = "筆刷設定（顏色與粗細）",
                tint = glassContentColor(isDark),
                modifier = Modifier.size(16.dp)
            )
        }
        GlassMenu(
            expanded = open,
            onDismissRequest = { open = false },
            isDark = isDark
        ) {
            Column {
                Text(
                    "顏色",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    INK_PALETTE.forEach { swatch ->
                        val chosen = colour == swatch
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(Color(swatch))
                                .then(
                                    if (chosen) Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                    else Modifier.border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f), CircleShape)
                                )
                                .clip(CircleShape)
                                .glassClickable(
                                    onClick = {
                                        onColourChange(swatch)
                                        open = false
                                    },
                                    shape = CircleShape
                                )
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "粗細",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    INK_WIDTH_PRESETS.forEach { (label, w) ->
                        GlassOptionChip(
                            text = label,
                            selected = widthPt == w,
                            onClick = {
                                onWidthChange(w)
                                open = false
                            },
                            isDark = isDark
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/**
 * The page stepper.
 *
 * Two arrows and the page number as one control on the fixed right-hand
 * side. The number is a field you can type into - the only sane way to reach
 * page 900 of a 1400-page textbook.
 */
@Composable
fun InkToolbarPageNav(
    focusedPage: Int,
    pageCount: Int,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onGoToPage: (Int) -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    var input by remember(focusedPage) { mutableStateOf((focusedPage + 1).toString()) }
    val focusManager = LocalFocusManager.current

    // 跳頁：數字有效才跳，之後一律結束編輯（否則輸入框留在那裡擋路）。
    fun commit() {
        val p = input.toIntOrNull()
        if (p != null && p in 1..pageCount) onGoToPage(p - 1)
        editing = false
        focusManager.clearFocus()
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(glassContentColor(InkThemeState.darkMode).copy(alpha = 0.10f))
            .padding(horizontal = 2.dp, vertical = 2.dp)
    ) {
        InkToolIcon(
            Icons.Rounded.NavigateBefore,
            "上一頁",
            false,
            enabled = focusedPage > 0,
            onClick = {
                commit()
                onPrevPage()
            }
        )
        if (editing) {
            DeskTextField(
                value = input,
                onValueChange = { v -> if (v.isEmpty() || (v.all { it.isDigit() } && v.length <= 6)) input = v },
                placeholder = "",
                singleLine = true,
                modifier = Modifier
                    .width(76.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyUp && event.key == Key.Enter) {
                            commit()
                            true
                        } else {
                            false
                        }
                    }
            )
        } else {
            Text(
                text = "${focusedPage + 1} / $pageCount",
                style = MaterialTheme.typography.labelMedium,
                color = glassContentColor(InkThemeState.darkMode),
                maxLines = 1,
                modifier = Modifier
                    .clip(CircleShape)
                    .glassClickable(
                        onClick = {
                            editing = true
                            input = (focusedPage + 1).toString()
                        },
                        shape = CircleShape
                    )
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            )
        }
        InkToolIcon(
            Icons.Rounded.NavigateNext,
            "下一頁",
            false,
            enabled = focusedPage < pageCount - 1,
            onClick = {
                commit()
                onNextPage()
            }
        )
    }
}

/** Zoom, as one group: minus, the number (click to reset), plus. Then fit width. */
@Composable
fun InkToolbarZoom(
    zoom: Float,
    onZoomOut: () -> Unit,
    onZoomReset: () -> Unit,
    onFitWidth: () -> Unit,
    onZoomIn: () -> Unit
) {
    val isDark = InkThemeState.darkMode
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(glassContentColor(isDark).copy(alpha = 0.10f))
            .padding(horizontal = 2.dp, vertical = 2.dp)
    ) {
        InkToolIcon(Icons.Rounded.ZoomOut, "縮小", false, onClick = onZoomOut)
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .glassClickable(onClick = onZoomReset, shape = CircleShape)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "${(zoom * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = glassContentColor(isDark)
            )
        }
        InkToolIcon(Icons.Rounded.ZoomIn, "放大", false, onClick = onZoomIn)
    }
    InkToolIcon(Icons.Rounded.FitScreen, "適合寬度", false, onClick = onFitWidth)
}

/**
 * Everything that happens once per document, behind one button.
 *
 * Export and clear used to be two text buttons at the end of the bar: 160dp
 * for actions you use once per file. They live in a menu now, which is also
 * how they read - "more" rather than "two more things to click".
 */
@Composable
fun InkToolbarMore(exporting: Boolean, onExport: () -> Unit, onClear: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        InkToolIcon(
            Icons.Rounded.MoreHoriz,
            "更多",
            false,
            enabled = !exporting,
            onClick = { open = true }
        )
        GlassMenu(
            expanded = open,
            onDismissRequest = { open = false },
            isDark = InkThemeState.darkMode
        ) {
            GlassMenuItem(
                text = if (exporting) "匯出中…" else "匯出 PDF",
                icon = Icons.Outlined.FileUpload,
                isDark = InkThemeState.darkMode,
                onClick = {
                    open = false
                    onExport()
                }
            )
            GlassMenuItem(
                text = "清除整份文件",
                icon = Icons.Outlined.DeleteSweep,
                isDark = InkThemeState.darkMode,
                onClick = {
                    open = false
                    onClear()
                }
            )
        }
    }
}

/**
 * The name of the armed tool.
 *
 * The toolbar's tools are glyphs, and several of them look alike at 20px, so
 * the current tool is named next to the document title.
 */
internal fun inkToolLabel(tool: InkTool): String = when (tool) {
    InkTool.Pen -> "畫筆"
    InkTool.Highlighter -> "螢光筆"
    InkTool.Eraser -> "橡皮擦"
    InkTool.Select -> "套索"
    InkTool.Text -> "文字"
    InkTool.Shape -> "圖形"
    InkTool.Hand -> "手形"
}

/**
 * An eraser.
 *
 * Material Icons has no eraser, so the eraser tool wore a trash can - which
 * says "delete everything", not "rub out this stroke". This is the classic
 * eraser block, drawn as two filled paths so it takes the icon tint like any
 * other glyph.
 */
private val EraserGlyph: ImageVector = ImageVector.Builder(
    name = "Eraser",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply {
    // 斜著的橡皮擦塊（四邊形，45 度傾斜）。
    path(fill = SolidColor(Color.Black)) {
        moveTo(5.5f, 13.5f)
        lineTo(12.5f, 6.5f)
        lineTo(17.5f, 11.5f)
        lineTo(10.5f, 18.5f)
        close()
    }
    // 下面的那條線＝紙面。
    path(fill = SolidColor(Color.Black)) {
        moveTo(3f, 20.6f)
        lineTo(21f, 20.6f)
        lineTo(21f, 22.2f)
        lineTo(3f, 22.2f)
        close()
    }
}.build()
