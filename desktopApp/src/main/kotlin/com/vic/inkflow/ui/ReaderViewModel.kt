package com.vic.inkflow.ui

import com.vic.inkflow.ui.InkTool
import com.vic.inkflow.ui.INK_WIDTH_PT
import com.vic.inkflow.util.ShapeType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Screen state holder for the reader's ink settings, following UDF.
 */
class ReaderViewModel {

    data class InkSettings(
        val tool: InkTool = InkTool.Pen,
        val colour: Int = 0xFF121826.toInt(),
        val widthPt: Float = INK_WIDTH_PT,
        val shapeSubType: ShapeType = ShapeType.RECT
    )

    sealed interface ReaderEvent {
        data class SetTool(val tool: InkTool) : ReaderEvent
        data class SetColour(val colour: Int) : ReaderEvent
        data class SetWidth(val widthPt: Float) : ReaderEvent
        data class SetShapeSubType(val subType: ShapeType) : ReaderEvent
    }

    private val _ink = MutableStateFlow(InkSettings())
    val ink: StateFlow<InkSettings> = _ink.asStateFlow()

    fun onEvent(event: ReaderEvent) {
        when (event) {
            is ReaderEvent.SetTool -> _ink.value = _ink.value.copy(tool = event.tool)
            is ReaderEvent.SetColour -> _ink.value = _ink.value.copy(colour = event.colour)
            is ReaderEvent.SetWidth -> _ink.value = _ink.value.copy(widthPt = event.widthPt)
            is ReaderEvent.SetShapeSubType -> _ink.value = _ink.value.copy(shapeSubType = event.subType)
        }
    }
}
