package com.vic.inkflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Highlight
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.ShapeLine
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material.icons.Icons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import com.vic.inkflow.ui.INK_PALETTE
import com.vic.inkflow.ui.INK_WIDTH_PRESETS
import com.vic.inkflow.ui.SHAPE_LABELS
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vic.inkflow.ui.theme.ShapeLg
import com.vic.inkflow.ui.InkThemeState
import com.vic.inkflow.ui.glassClickable
import com.vic.inkflow.ui.glassContentColor
import com.vic.inkflow.ui.GlassOptionChip
import com.vic.inkflow.ui.GlassTextButton
import com.vic.inkflow.util.ShapeType
import kotlin.math.roundToInt

/**
 * Pieces of the reader's toolbar, split out of `PdfViewer.kt` so the 2600-line
 * toolbar body becomes a sequence of named groups instead of one wall of icons.
 *
 * Each group is a `@Composable` that renders its own icons/dividers inside the
 * caller's horizontal `Row`/`LazyRow`. Behaviour is unchanged: the parent still
 * owns the single scrollable row and the glass pill.
 */

@Composable
fun InkToolbarTitle(documentTitle: String, focusedPage: Int, pageCount: Int) {
    if (documentTitle.isNotBlank()) {
        Text(
            documentTitle,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            color = glassContentColor(InkThemeState.darkMode),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp).padding(end = 4.dp)
        )
    }
    Text(
        "${focusedPage + 1}/$pageCount",
        style = MaterialTheme.typography.labelMedium,
        color = glassContentColor(InkThemeState.darkMode).copy(alpha = 0.75f),
        maxLines = 1,
        modifier = Modifier.padding(end = 4.dp)
    )
}

@Composable
fun InkToolbarTools(
    tool: InkTool,
    selectionCount: Int,
    onToolChange: (InkTool) -> Unit
) {
    InkToolIcon(Icons.Rounded.PanTool, "抓手（拖曳捲動）", tool == InkTool.Hand, onClick = { onToolChange(InkTool.Hand) })
    InkToolIcon(Icons.Rounded.Brush, "筆", tool == InkTool.Pen, onClick = { onToolChange(InkTool.Pen) })
    InkToolIcon(Icons.Rounded.Highlight, "螢光筆", tool == InkTool.Highlighter, onClick = { onToolChange(InkTool.Highlighter) })
    InkToolIcon(Icons.Rounded.DeleteSweep, "橡皮擦", tool == InkTool.Eraser, onClick = { onToolChange(InkTool.Eraser) })
    InkToolIcon(Icons.Rounded.Gesture, if (selectionCount > 0) "選取（$selectionCount）" else "選取", tool == InkTool.Select, badgeCount = selectionCount, onClick = { onToolChange(InkTool.Select) })
    InkToolIcon(Icons.Rounded.TextFields, "文字", tool == InkTool.Text, onClick = { onToolChange(InkTool.Text) })
    InkToolIcon(Icons.Rounded.ShapeLine, "形狀", tool == InkTool.Shape, onClick = { onToolChange(InkTool.Shape) })
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
    androidx.compose.material3.VerticalDivider(
        modifier = Modifier.height(24.dp).padding(horizontal = 6.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    )
    ShapeType.entries.forEach { st ->
        GlassOptionChip(
            text = SHAPE_LABELS[st] ?: st.name,
            selected = shapeSubType == st,
            onClick = { onShapeSubTypeChange(st) },
            isDark = InkThemeState.darkMode
        )
    }
}

@Composable
fun InkToolbarWidthPresets(
    tool: InkTool,
    widthPt: Float,
    onWidthChange: (Float) -> Unit
) {
    if (tool != InkTool.Pen && tool != InkTool.Highlighter) return
    androidx.compose.material3.VerticalDivider(
        modifier = Modifier.height(24.dp).padding(horizontal = 6.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    )
    INK_WIDTH_PRESETS.forEach { (label, w) ->
        GlassOptionChip(
            text = label,
            selected = widthPt == w,
            onClick = { onWidthChange(w) },
            isDark = InkThemeState.darkMode
        )
    }
}

@Composable
fun InkToolbarZoom(
    zoom: Float,
    onZoomOut: () -> Unit,
    onZoomReset: () -> Unit,
    onZoomIn: () -> Unit
) {
    InkToolIcon(Icons.Rounded.ZoomOut, "縮小", false, onClick = onZoomOut)
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .glassClickable(onClick = onZoomReset, shape = CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "${(zoom * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = glassContentColor(InkThemeState.darkMode)
        )
    }
    InkToolIcon(Icons.Rounded.ZoomIn, "放大", false, onClick = onZoomIn)
}

@Composable
fun InkToolbarColors(colour: Int, onColourChange: (Int) -> Unit) {
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
                .glassClickable(onClick = { onColourChange(swatch) }, shape = CircleShape)
        )
    }
}

@Composable
fun InkToolbarExport(exporting: Boolean, onExport: () -> Unit, onClear: () -> Unit) {
    GlassTextButton(
        text = if (exporting) "匯出中…" else "匯出 PDF",
        onClick = onExport,
        enabled = !exporting
    )
    GlassTextButton(
        text = "清除",
        onClick = onClear,
        color = MaterialTheme.colorScheme.error
    )
}
