package com.vic.inkflow.ui

import androidx.compose.ui.geometry.Offset
import com.vic.inkflow.data.ImageAnnotationEntity
import com.vic.inkflow.data.StrokeWithPoints
import com.vic.inkflow.data.TextAnnotationEntity

sealed class DrawCommand {
    data class AddStroke(val stroke: StrokeWithPoints) : DrawCommand()
    data class RemoveStrokes(val strokes: List<StrokeWithPoints>) : DrawCommand()
    data class MoveStrokes(
        val originals: List<StrokeWithPoints>,
        val delta: Offset,
        /** 跨頁移動後的完整快照（含新 pageIndex）。單頁移動為 null，走 delta 重放。 */
        val updated: List<StrokeWithPoints>? = null
    ) : DrawCommand()
    data class ResizeStrokes(val originals: List<StrokeWithPoints>, val updated: List<StrokeWithPoints>) : DrawCommand()
    data class AddTextAnnotation(val annotation: TextAnnotationEntity) : DrawCommand()
    data class RemoveTextAnnotation(val annotation: TextAnnotationEntity) : DrawCommand()
    data class MoveTextAnnotation(val original: TextAnnotationEntity, val updated: TextAnnotationEntity) : DrawCommand()
    data class ResizeTextAnnotation(val original: TextAnnotationEntity, val updated: TextAnnotationEntity) : DrawCommand()
    data class EditTextAnnotation(val original: TextAnnotationEntity, val updated: TextAnnotationEntity) : DrawCommand()
    data class AddImageAnnotation(val annotation: ImageAnnotationEntity) : DrawCommand()
    data class RemoveImageAnnotation(val annotation: ImageAnnotationEntity) : DrawCommand()
    data class MoveImageAnnotation(val original: ImageAnnotationEntity, val updated: ImageAnnotationEntity) : DrawCommand()
    data class ResizeImageAnnotation(val original: ImageAnnotationEntity, val updated: ImageAnnotationEntity) : DrawCommand()
    data class MoveSelectionMixed(
        val strokeOriginals: List<StrokeWithPoints>,
        val strokeUpdated: List<StrokeWithPoints>,
        val imageOriginals: List<ImageAnnotationEntity>,
        val imageUpdated: List<ImageAnnotationEntity>
    ) : DrawCommand()
    data class ResizeSelectionMixed(
        val strokeOriginals: List<StrokeWithPoints>,
        val strokeUpdated: List<StrokeWithPoints>,
        val imageOriginals: List<ImageAnnotationEntity>,
        val imageUpdated: List<ImageAnnotationEntity>
    ) : DrawCommand()
    data class AddSelectionCopies(val strokes: List<StrokeWithPoints>, val images: List<ImageAnnotationEntity>) : DrawCommand()
    data class RemoveSelectionMixed(val strokes: List<StrokeWithPoints>, val images: List<ImageAnnotationEntity>) : DrawCommand()
    /** 單次擦除手勢的全部戰果（手勢級合併：一筆擦的一鍵全回）。texts 目前只有墨的字註解；圖片註解擦不掉故無。 */
    data class EraseGesture(val strokes: List<StrokeWithPoints>, val texts: List<TextAnnotationEntity>) : DrawCommand()
    /**
     * 提取到新頁：targetPage 為新建頁索引，image 為貼上的裁剪圖。
     * pageKept = undo 時頁上有別的內容、只刪圖留頁；redo 時不再建頁、只重貼圖。
     */
    data class ExtractToNewPage(
        val targetPage: Int,
        val image: ImageAnnotationEntity,
        val pageKept: Boolean = false
    ) : DrawCommand()
}

