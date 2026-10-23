package com.vic.inkflow.ui

import com.vic.inkflow.ui.INK_WIDTH_PT
import com.vic.inkflow.ui.InkTool
import com.vic.inkflow.util.ShapeType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Screen state holder for the reader's ink settings.
 *
 * ## Why this exists
 *
 * `PdfViewer` is a 2600-line composable that owns every piece of reader state:
 * page count, focused page, the `PageHandle` map, the `LazyListState`, and the
 * ink settings (tool, colour, width, shape subtype). The ink settings are the
 * one group that is genuinely shared across pages — "switching pages must not
 * reset the tool, colour or shape" — and they have no dependency on Compose's
 * rendering pipeline, so they are the natural first thing to lift out.
 *
 * Lifting them into a `StateFlow`-backed holder (the desktop analogue of a
 * `ViewModel`) is Milestone 2b of the architecture rewrite: it proves the
 * pattern on the largest, most state-heavy screen before the page/navigation
 * state follows.
 *
 * ## Scope
 *
 * The holder is created with `remember { ReaderViewModel() }` inside `PdfViewer`
 * and lives as long as the reader is on screen. It does not own the
 * `PageHandle` map or the `LazyListState` — those stay in the composable because
 * they are bound to Compose's snapshot state and the rendering pipeline.
 */
class ReaderViewModel {

    data class InkSettings(
        val tool: InkTool = InkTool.Pen,
        val colour: Int = 0xFF121826.toInt(),
        val widthPt: Float = INK_WIDTH_PT,
        val shapeSubType: ShapeType = ShapeType.RECT
    )

    private val _ink = MutableStateFlow(InkSettings())
    val ink: StateFlow<InkSettings> = _ink.asStateFlow()

    fun setTool(tool: InkTool) { _ink.value = _ink.value.copy(tool = tool) }
    fun setColour(colour: Int) { _ink.value = _ink.value.copy(colour = colour) }
    fun setWidth(widthPt: Float) { _ink.value = _ink.value.copy(widthPt = widthPt) }
    fun setShapeSubType(subType: ShapeType) { _ink.value = _ink.value.copy(shapeSubType = subType) }
}
