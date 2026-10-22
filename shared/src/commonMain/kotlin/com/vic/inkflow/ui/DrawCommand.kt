package com.vic.inkflow.ui

import androidx.compose.ui.geometry.Offset
import com.vic.inkflow.data.ImageAnnotation
import com.vic.inkflow.data.StrokeWithSamples
import com.vic.inkflow.data.TextAnnotation

sealed class DrawCommand {
    data class AddStroke(val stroke: StrokeWithSamples) : DrawCommand()
    data class RemoveStrokes(val strokes: List<StrokeWithSamples>) : DrawCommand()
    data class MoveStrokes(
        val originals: List<StrokeWithSamples>,
        val delta: Offset,
        /** 跨頁移動後的完整快照（含新 pageIndex）。單頁移動為 null，走 delta 重放。 */
        val updated: List<StrokeWithSamples>? = null
    ) : DrawCommand()
    data class ResizeStrokes(val originals: List<StrokeWithSamples>, val updated: List<StrokeWithSamples>) : DrawCommand()
    data class AddTextAnnotation(val annotation: TextAnnotation) : DrawCommand()
    data class RemoveTextAnnotation(val annotation: TextAnnotation) : DrawCommand()
    data class MoveTextAnnotation(val original: TextAnnotation, val updated: TextAnnotation) : DrawCommand()
    data class ResizeTextAnnotation(val original: TextAnnotation, val updated: TextAnnotation) : DrawCommand()
    data class EditTextAnnotation(val original: TextAnnotation, val updated: TextAnnotation) : DrawCommand()
    data class AddImageAnnotation(val annotation: ImageAnnotation) : DrawCommand()
    data class RemoveImageAnnotation(val annotation: ImageAnnotation) : DrawCommand()
    data class MoveImageAnnotation(val original: ImageAnnotation, val updated: ImageAnnotation) : DrawCommand()
    data class ResizeImageAnnotation(val original: ImageAnnotation, val updated: ImageAnnotation) : DrawCommand()
    data class MoveSelectionMixed(
        val strokeOriginals: List<StrokeWithSamples>,
        val strokeUpdated: List<StrokeWithSamples>,
        val imageOriginals: List<ImageAnnotation>,
        val imageUpdated: List<ImageAnnotation>,
        /** 套索移動的字（原＋新，與墨圖同批 undo/redo）。 */
        val textOriginals: List<TextAnnotation> = emptyList(),
        val textUpdated: List<TextAnnotation> = emptyList()
    ) : DrawCommand()
    data class ResizeSelectionMixed(
        val strokeOriginals: List<StrokeWithSamples>,
        val strokeUpdated: List<StrokeWithSamples>,
        val imageOriginals: List<ImageAnnotation>,
        val imageUpdated: List<ImageAnnotation>,
        /** 套索縮放的字（原＋新，與墨圖同批 undo/redo；舊命令預設空，向後相容）。 */
        val textOriginals: List<TextAnnotation> = emptyList(),
        val textUpdated: List<TextAnnotation> = emptyList()
    ) : DrawCommand()
    data class AddSelectionCopies(
        val strokes: List<StrokeWithSamples>,
        val images: List<ImageAnnotation>,
        /** 套索複製的字（與墨圖同批，undo 整組撤）。 */
        val texts: List<TextAnnotation> = emptyList()
    ) : DrawCommand()
    data class RemoveSelectionMixed(
        val strokes: List<StrokeWithSamples>,
        val images: List<ImageAnnotation>,
        /** 套索刪除的字（與墨圖同批，undo 整組回）。 */
        val texts: List<TextAnnotation> = emptyList()
    ) : DrawCommand()
    /** 單次擦除手勢的全部戰果（手勢級合併：一筆擦的一鍵全回）。texts 目前只有墨的字註解；圖片註解擦不掉故無。 */
    data class EraseGesture(val strokes: List<StrokeWithSamples>, val texts: List<TextAnnotation>) : DrawCommand()
    /**
     * 提取到新頁：targetPage 為新建頁索引，image 為貼上的裁剪圖。
     * pageKept = undo 時頁上有別的內容、只刪圖留頁；redo 時不再建頁、只重貼圖。
     */
    data class ExtractToNewPage(
        val targetPage: Int,
        val image: ImageAnnotation,
        val pageKept: Boolean = false
    ) : DrawCommand()
}

