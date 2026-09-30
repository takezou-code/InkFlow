package com.vic.inkflow.ui

/**
 * P1 測試地基用的 hand-written fake。
 *
 * 刻意不錄影整個介面：只實作測試需要斷言的讀取面，寫入面全部記錄成
 * [writes] 供測試檢查「有沒有真的寫出去」。這樣漏接一個方法時編譯就爆，
 * 不會靜默變成 no-op。
 */
class FakeEditorSettingsRepository(
    var prefs: DrawingPreferences = FakeEditorSettingsRepository.defaultPrefs(),
    private val widthResponsiveness: Float = 0.33f
) : EditorSettingsRepository {

    val writes = mutableListOf<String>()

    override suspend fun resolvePreferences(documentUri: String): DrawingPreferences = prefs

    override suspend fun setTool(documentUri: String, tool: Tool) {
        writes += "setTool($documentUri,$tool)"
    }

    override suspend fun setStrokeWidth(documentUri: String, tool: Tool, strokeWidth: Float) {
        writes += "setStrokeWidth($documentUri,$tool,$strokeWidth)"
    }

    override suspend fun setShapeSubType(documentUri: String, shapeSubType: ShapeSubType) {
        writes += "setShapeSubType($documentUri,$shapeSubType)"
    }

    override suspend fun setInputMode(documentUri: String, inputMode: InputMode) {
        writes += "setInputMode($documentUri,$inputMode)"
    }

    override suspend fun setBackground(documentUri: String, background: PageBackground) {
        writes += "setBackground($documentUri,$background)"
    }

    override suspend fun setPaperStyle(documentUri: String, style: PaperStyle) {
        writes += "setPaperStyle($documentUri,${style.widthPt}x${style.heightPt})"
    }

    override suspend fun setQuickSwipeEraserEnabled(documentUri: String, enabled: Boolean) {
        writes += "setQuickSwipeEraserEnabled($documentUri,$enabled)"
    }

    override suspend fun setAutoSwitchToPenAfterErase(documentUri: String, enabled: Boolean) {
        writes += "setAutoSwitchToPenAfterErase($documentUri,$enabled)"
    }

    override suspend fun setStrokeSpeedSensitivity(documentUri: String, sensitivity: Float) {
        writes += "setStrokeSpeedSensitivity($documentUri,$sensitivity)"
    }

    override fun getWidthResponsiveness(): Float = widthResponsiveness

    companion object {
        fun defaultPrefs() = DrawingPreferences(
            tool = Tool.PEN,
            colorArgb = 0xFF000000.toInt(),
            highlighterColorArgb = 0xFFFFC700.toInt(),
            penStrokeWidth = 4f,
            highlighterStrokeWidth = 8f,
            shapeSubType = ShapeSubType.RECT,
            inputMode = InputMode.FREE,
            palette = listOf(0xFF000000.toInt(), 0xFFFFC700.toInt()),
            background = PageBackground.BLANK,
            paperWidthPt = null,
            paperHeightPt = null,
            quickSwipeEraserEnabled = false,
            autoSwitchToPenAfterErase = false,
            strokeSpeedSensitivity = 1f,
            touchCalEnabled = false,
            touchCalDxDp = 0f,
            touchCalDyDp = 0f
        )
    }
}
