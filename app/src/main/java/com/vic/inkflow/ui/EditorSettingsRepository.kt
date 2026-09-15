package com.vic.inkflow.ui

import androidx.room.withTransaction
import com.vic.inkflow.data.AppDatabase
import com.vic.inkflow.data.DocumentPreferenceDao
import com.vic.inkflow.data.DocumentPreferenceEntity

data class DrawingPreferences(
    val tool: Tool,
    val colorArgb: Int,
    val highlighterColorArgb: Int,
    val penStrokeWidth: Float,
    val highlighterStrokeWidth: Float,
    val shapeSubType: ShapeSubType,
    val inputMode: InputMode,
    val palette: List<Int>,
    val background: PageBackground,
    val paperWidthPt: Float?,
    val paperHeightPt: Float?,
    val quickSwipeEraserEnabled: Boolean,
    val autoSwitchToPenAfterErase: Boolean,
    val strokeSpeedSensitivity: Float,
    // 手指觸控落筆校正（副廠電容筆走 Touch 通道時的 XY 偏移，全域共用）
    val touchCalEnabled: Boolean,
    val touchCalDxDp: Float,
    val touchCalDyDp: Float
)

class EditorSettingsRepository(
    private val db: AppDatabase,
    private val documentPreferenceDao: DocumentPreferenceDao,
    private val prefs: android.content.SharedPreferences
) {
    companion object {
        private const val DEFAULT_PEN_STROKE_WIDTH = 4f
        private const val DEFAULT_HIGHLIGHTER_STROKE_WIDTH = 8f
        private const val DEFAULT_STROKE_SPEED_SENSITIVITY = 1f
        // 粗細靈敏度（全域，不進 DB）：0=鈍 → 1=靈
        private const val DEFAULT_WIDTH_RESPONSIVENESS = 0.33f
        private const val DEFAULT_HIGHLIGHTER_COLOR_ARGB = 0xFFFFC700.toInt()
        private val DEFAULT_PALETTE = listOf(
            0xFF000000.toInt(),
            0xFFFFC700.toInt(),
            0xFFF44336.toInt(),
            0xFF4CAF50.toInt()
        )
    }

    suspend fun resolvePreferences(documentUri: String): DrawingPreferences {
        val local = documentPreferenceDao.getByDocumentUri(documentUri)
        
        // Read global defaults
        val defaultPenColor = prefs.getInt("default_pen_color", 0xFF000000.toInt())
        val defaultHighlighterColor = prefs.getInt("default_highlighter_color", DEFAULT_HIGHLIGHTER_COLOR_ARGB)
        val defaultPenWidth = prefs.getFloat("default_pen_width", DEFAULT_PEN_STROKE_WIDTH)
        val defaultHighlighterWidth = prefs.getFloat("default_highlighter_width", DEFAULT_HIGHLIGHTER_STROKE_WIDTH)
        val defaultInputModeStr = prefs.getString("default_input_mode", InputMode.FREE.name)
        val defaultInputMode = InputMode.values().find { it.name == defaultInputModeStr } ?: InputMode.FREE
        val defaultBackgroundStr = prefs.getString("default_paper_background", PageBackground.BLANK.name)
        val defaultBackground = PageBackground.values().find { it.name == defaultBackgroundStr } ?: PageBackground.BLANK
        val defaultQuickSwipe = prefs.getBoolean("default_quick_swipe_eraser_enabled", false)
        val defaultAutoSwitchToPenAfterErase = prefs.getBoolean("default_auto_switch_to_pen_after_erase", false)
        val defaultPaletteCsv = prefs.getString("default_recent_colors", null)
        val defaultPalette = defaultPaletteCsv?.let { csv ->
            csv.split(',').mapNotNull { it.toIntOrNull() }.takeIf { it.isNotEmpty() }
        } ?: DEFAULT_PALETTE
        val defaultStrokeSpeedSensitivity = prefs.getFloat("default_stroke_speed_sensitivity", DEFAULT_STROKE_SPEED_SENSITIVITY)
        val defaultTouchCalEnabled = prefs.getBoolean("default_touch_cal_enabled", false)
        val defaultTouchCalDxDp = prefs.getFloat("default_touch_cal_dx_dp", 0f)
        val defaultTouchCalDyDp = prefs.getFloat("default_touch_cal_dy_dp", 0f)

        return DrawingPreferences(
            tool = local?.tool?.toToolOrNull() ?: Tool.PEN,
            // 顏色一律以設定頁為準，筆記本本地舊色直接作廢
            colorArgb = defaultPenColor,
            highlighterColorArgb = defaultHighlighterColor,
            penStrokeWidth = local?.penStrokeWidth ?: local?.strokeWidth ?: defaultPenWidth,
            highlighterStrokeWidth = local?.highlighterStrokeWidth ?: local?.strokeWidth ?: defaultHighlighterWidth,
            shapeSubType = local?.shapeSubType?.toShapeSubTypeOrNull() ?: ShapeSubType.RECT,
            inputMode = local?.inputMode?.toInputModeOrNull()
                ?: (if (local?.stylusOnlyMode == true) InputMode.STYLUS_ONLY else defaultInputMode),
            palette = defaultPalette,
            background = local?.pageBackground?.toPageBackgroundOrNull() ?: defaultBackground,
            paperWidthPt = local?.paperWidthPt,
            paperHeightPt = local?.paperHeightPt,
            quickSwipeEraserEnabled = defaultQuickSwipe,
            autoSwitchToPenAfterErase = defaultAutoSwitchToPenAfterErase,
            strokeSpeedSensitivity = local?.strokeSpeedSensitivity ?: defaultStrokeSpeedSensitivity,
            touchCalEnabled = defaultTouchCalEnabled,
            touchCalDxDp = defaultTouchCalDxDp,
            touchCalDyDp = defaultTouchCalDyDp
        )
    }

    suspend fun setTool(documentUri: String, tool: Tool) {
        upsertDocument(documentUri) { copy(tool = tool.name) }
    }

    suspend fun setStrokeWidth(documentUri: String, tool: Tool, strokeWidth: Float) {
        upsertDocument(documentUri) {
            when (tool) {
                Tool.HIGHLIGHTER -> copy(strokeWidth = strokeWidth, highlighterStrokeWidth = strokeWidth)
                Tool.PEN -> copy(strokeWidth = strokeWidth, penStrokeWidth = strokeWidth)
                else -> this
            }
        }
    }

    suspend fun setShapeSubType(documentUri: String, shapeSubType: ShapeSubType) {
        upsertDocument(documentUri) { copy(shapeSubType = shapeSubType.name) }
    }

    suspend fun setInputMode(documentUri: String, inputMode: InputMode) {
        upsertDocument(documentUri) { copy(inputMode = inputMode.name) }
    }

    suspend fun setBackground(documentUri: String, background: PageBackground) {
        upsertDocument(documentUri) { copy(pageBackground = background.name) }
    }

    suspend fun setPaperStyle(documentUri: String, style: PaperStyle) {
        upsertDocument(documentUri) {
            copy(
                pageBackground = style.background.name,
                paperWidthPt = style.widthPt,
                paperHeightPt = style.heightPt
            )
        }
    }

    suspend fun setQuickSwipeEraserEnabled(documentUri: String, enabled: Boolean) {
        prefs.edit().putBoolean("default_quick_swipe_eraser_enabled", enabled).apply()
    }

    suspend fun setAutoSwitchToPenAfterErase(documentUri: String, enabled: Boolean) {
        prefs.edit().putBoolean("default_auto_switch_to_pen_after_erase", enabled).apply()
    }

    suspend fun setStrokeSpeedSensitivity(documentUri: String, sensitivity: Float) {
        upsertDocument(documentUri) { copy(strokeSpeedSensitivity = sensitivity) }
    }

    /** 粗細靈敏度是純全域偏好（不進 DB、不遷移），編輯器開啟時讀取。 */
    fun getWidthResponsiveness(): Float =
        prefs.getFloat("default_width_responsiveness", DEFAULT_WIDTH_RESPONSIVENESS)

    private suspend fun upsertDocument(
        documentUri: String,
        update: DocumentPreferenceEntity.() -> DocumentPreferenceEntity
    ) {
        db.withTransaction {
            val current = documentPreferenceDao.getByDocumentUri(documentUri)
                ?: DocumentPreferenceEntity(documentUri = documentUri)
            documentPreferenceDao.upsert(current.update())
        }
    }
}

private fun String.toToolOrNull(): Tool? =
    runCatching { Tool.valueOf(this) }.getOrNull()

private fun String.toShapeSubTypeOrNull(): ShapeSubType? =
    runCatching { ShapeSubType.valueOf(this) }.getOrNull()

private fun String.toInputModeOrNull(): InputMode? =
    runCatching { InputMode.valueOf(this) }.getOrNull()

private fun String.toPageBackgroundOrNull(): PageBackground? =
    runCatching { PageBackground.valueOf(this) }.getOrNull()

