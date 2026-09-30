package com.vic.inkflow.ui

import androidx.compose.ui.graphics.Color
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P1 測試地基的驗收測試：證明 EditorViewModel 已經可以在純 JVM 單元測試裡建構。
 *
 * 為什麼這條重要：EditorViewModel 有 2598 行、92 個函式、141 個 DAO 呼叫點，
 * 之前完全沒有任何測試保護，也「測不了」（依賴 AppDatabase + SharedPreferences
 * 這類具體類別與 Android framework）。現在：
 *   - AppDatabase 用 mockk 寬鬆 mock（EditorViewModel 的 init 區塊本來就不碰 DB）
 *   - EditorSettingsRepository 走 P1 抽出來的介面，傳 hand-written fake
 * 所以後續 P2/P3 重構（抽 repository、拆 ViewModel）才有東西可以擋回歸。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelInitTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        // viewModelScope 用 Dispatchers.Main.immediate，單元測試必須先接管。
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildVm(repo: EditorSettingsRepository) = EditorViewModel(
        db = mockk(relaxed = true),
        documentUri = "file:///storage/emulated/0/Documents/test.pdf",
        settingsRepository = repo
    )

    @Test
    fun `init 把 DrawingPreferences 完整對映到公開狀態`() = runTest(testDispatcher) {
        val repo = FakeEditorSettingsRepository(
            prefs = DrawingPreferences(
                tool = Tool.HIGHLIGHTER,
                colorArgb = 0xFF112233.toInt(),
                highlighterColorArgb = 0xFF445566.toInt(),
                penStrokeWidth = 5f,
                highlighterStrokeWidth = 9f,
                shapeSubType = ShapeSubType.CIRCLE,
                inputMode = InputMode.STYLUS_ONLY,
                palette = listOf(0xFF000000.toInt(), 0xFFFF0000.toInt()),
                background = PageBackground.GRID,
                paperWidthPt = 400f,
                paperHeightPt = 600f,
                quickSwipeEraserEnabled = true,
                autoSwitchToPenAfterErase = true,
                strokeSpeedSensitivity = 2.5f,
                touchCalEnabled = true,
                touchCalDxDp = 3f,
                touchCalDyDp = -4f
            ),
            widthResponsiveness = 0.9f
        )

        val vm = buildVm(repo)
        advanceUntilIdle()

        assertEquals(Tool.HIGHLIGHTER, vm.selectedTool.value)
        // tool=HIGHLIGHTER 時 selectedColor 應取 highlighter 色而不是筆色。
        // 用 Color 自身的 equals 比較，不要拆 .value 的打包位元（那是 Compose 內部格式）。
        assertEquals(Color(0xFF445566.toInt()), vm.selectedColor.value)
        assertEquals(9f, vm.strokeWidth.value, 1e-4f)
        assertEquals(ShapeSubType.CIRCLE, vm.selectedShapeSubType.value)
        assertEquals(InputMode.STYLUS_ONLY, vm.inputMode.value)
        assertTrue(vm.quickSwipeEraserEnabled.value)
        assertTrue(vm.autoSwitchToPenAfterErase.value)
        assertEquals(2.5f, vm.strokeSpeedSensitivity.value, 1e-4f)
        assertEquals(0.9f, vm.widthResponsiveness.value, 1e-4f)
        assertTrue(vm.touchCalEnabled.value)
        assertEquals(3f, vm.touchCalDxDp.value, 1e-4f)
        assertEquals(-4f, vm.touchCalDyDp.value, 1e-4f)
        assertEquals(2, vm.palette.value.size)
        assertEquals(PageBackground.GRID, vm.paperStyle.value.background)
        assertEquals(400f, vm.paperStyle.value.widthPt, 1e-4f)
        assertEquals(600f, vm.paperStyle.value.heightPt, 1e-4f)
    }

    @Test
    fun `PAPER 尺寸為 null 時保留 A4 預設 不被覆寫成 0`() = runTest(testDispatcher) {
        val repo = FakeEditorSettingsRepository(
            prefs = FakeEditorSettingsRepository.defaultPrefs().copy(
                paperWidthPt = null,
                paperHeightPt = null
            )
        )

        val vm = buildVm(repo)
        advanceUntilIdle()

        // 這條是實際踩過的坑：canvasW/canvasH 是 InkCanvas 的像素尺寸，
        // 若被 PDF point 值覆寫會讓筆跡整體放大/偏移。
        assertEquals(EditorViewModel.MODEL_W, vm.paperStyle.value.widthPt, 1e-4f)
        assertEquals(EditorViewModel.MODEL_H, vm.paperStyle.value.heightPt, 1e-4f)
    }

    @Test
    fun `PALM_REJECTION 在 init 被收斂成 STYLUS_ONLY`() = runTest(testDispatcher) {
        val repo = FakeEditorSettingsRepository(
            prefs = FakeEditorSettingsRepository.defaultPrefs().copy(inputMode = InputMode.PALM_REJECTION)
        )

        val vm = buildVm(repo)
        advanceUntilIdle()

        assertEquals(InputMode.STYLUS_ONLY, vm.inputMode.value)
    }

    @Test
    fun `initializePaperSize 觸發 docY 回填入口且不覆寫畫布尺寸`() = runTest(testDispatcher) {
        val vm = buildVm(FakeEditorSettingsRepository())
        advanceUntilIdle()

        val before = vm.paperStyle.value
        vm.initializePaperSize(0f, 0f)   // 非法尺寸必須自我忽略
        assertEquals(before, vm.paperStyle.value)

        vm.initializePaperSize(400f, 600f)
        advanceUntilIdle()

        assertEquals(400f, vm.paperStyle.value.widthPt, 1e-4f)
        assertEquals(600f, vm.paperStyle.value.heightPt, 1e-4f)
    }
}
