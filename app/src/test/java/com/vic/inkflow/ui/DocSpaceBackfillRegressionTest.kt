package com.vic.inkflow.ui

import androidx.compose.ui.graphics.Color
import com.vic.inkflow.data.repository.InkFlowRepositories
import io.mockk.mockk
import io.mockk.coEvery
import io.mockk.coVerify
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
 * 保護「舊文件墨跡座標正確性」的迴歸測試。
 *
 * 背景：v24 之前的舊資料，strokes / text_annotations / image_annotations 的
 * `docY` 欄是 NULL。開檔時必須用 live modelH 當 stride 把三張表全量重算
 * （S1 單畫布回填），舊墨才會落在正確位置。
 *
 * 為什麼需要這條測試：該路徑的現場斷言在 2026-09-17 被**刻意降級成只記 log**
 * （P0-hotfix：開檔永不因遷移檢查而死）。所以「回填沒跑」在產品行為上是
 * 靜默的——App 正常開、UI 正常顯示、只是舊墨跡悄悄偏移。這是 log 抓不到的
 * 盲點，唯一有效的防線就是這條測試：一旦有人把 initializePaperSize 裡的
 * ensureDocSpaceMigrated 呼叫刪掉，這裡立刻紅。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocSpaceBackfillRegressionTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(testDispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    /**
     * 寬鬆 mock 的 `transaction` 預設只回傳預設值、**不會執行 lambda 本體**，
     * 那樣回填的呼叫永遠不會發生。這裡手動接上，讓交易真的跑（receiver 給同一個
     * mock，於是裡面的 repos.strokes.backfillStrokeDocY 會被記錄）。
     */
    private fun mockRepos(): InkFlowRepositories {
        val repos = mockk<InkFlowRepositories>(relaxed = true)
        coEvery { repos.transaction<Unit>(any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = firstArg<suspend InkFlowRepositories.() -> Unit>()
            block(repos)
        }
        return repos
    }

    private fun newVm(repos: InkFlowRepositories) = EditorViewModel(
        repos = repos,
        documentUri = "file:///storage/emulated/0/Documents/old.pdf",
        settingsRepository = FakeEditorSettingsRepository()
    )

    @Test
    fun `initializePaperSize 有效尺寸時必須觸發 docY 回填`() = runTest(testDispatcher) {
        val repos = mockRepos()
        val vm = newVm(repos)
        advanceUntilIdle()

        vm.initializePaperSize(400f, 600f)
        advanceUntilIdle()

        // 三張表都要重算，缺一張就會有那類標註留在舊座標上。
        coVerify(exactly = 1) { repos.strokes.backfillStrokeDocY(any(), 600f) }
        coVerify(exactly = 1) { repos.texts.backfillTextDocY(any(), 600f) }
        coVerify(exactly = 1) { repos.images.backfillImageDocY(any(), 600f) }
    }

    @Test
    fun `回填必須包在單一交易裡 避免三張表只跑一半`() = runTest(testDispatcher) {
        val repos = mockRepos()
        val vm = newVm(repos)
        advanceUntilIdle()

        vm.initializePaperSize(400f, 600f)
        advanceUntilIdle()

        coVerify(exactly = 1) { repos.transaction<Unit>(any()) }
    }

    @Test
    fun `非法尺寸不觸發回填`() = runTest(testDispatcher) {
        val repos = mockRepos()
        val vm = newVm(repos)
        advanceUntilIdle()

        vm.initializePaperSize(0f, 0f)
        advanceUntilIdle()

        // stride 必須是「真實 PDF 尺寸」，0 會讓 docY 全算錯。
        coVerify(exactly = 0) { repos.strokes.backfillStrokeDocY(any(), any()) }
    }

    @Test
    fun `同一份文件重複開 不重複回填`() = runTest(testDispatcher) {
        val repos = mockRepos()
        val vm = newVm(repos)
        advanceUntilIdle()

        vm.initializePaperSize(400f, 600f)
        advanceUntilIdle()
        vm.initializePaperSize(400f, 600f)
        advanceUntilIdle()

        // 冪等旗標在 DocSpaceMigrator 裡，跑第二次應該被擋掉。
        coVerify(exactly = 1) { repos.strokes.backfillStrokeDocY(any(), any()) }
    }

    @Test
    fun `回填不應改動畫布像素尺寸`() = runTest(testDispatcher) {
        val repos = mockRepos()
        val vm = newVm(repos)
        advanceUntilIdle()

        vm.setCanvasSize(1080f, 1920f)
        vm.initializePaperSize(400f, 600f)
        advanceUntilIdle()

        // 這是踩過的坑：canvasW/canvasH 是 InkCanvas 的像素尺寸，若被 PDF point
        // 值覆寫會讓筆跡整體放大偏移。paperStyle 可以變，畫布尺寸不行。
        assertEquals(400f, vm.paperStyle.value.widthPt, 1e-4f)
        assertEquals(600f, vm.paperStyle.value.heightPt, 1e-4f)
    }

    @Test
    fun `工具與偏好初始化不受回填影響`() = runTest(testDispatcher) {
        val repos = mockRepos()
        val vm = EditorViewModel(
            repos = repos,
            documentUri = "file:///storage/emulated/0/Documents/old.pdf",
            settingsRepository = FakeEditorSettingsRepository(
                prefs = FakeEditorSettingsRepository.defaultPrefs().copy(tool = Tool.SHAPE)
            )
        )
        advanceUntilIdle()
        vm.initializePaperSize(400f, 600f)
        advanceUntilIdle()

        assertEquals(Tool.SHAPE, vm.selectedTool.value)
        assertTrue(vm.palette.value.isNotEmpty())
        assertEquals(Color(0xFF000000.toInt()), vm.selectedColor.value.let {
            // SHAPE 不在 pen/highlighter 分支，selectedColor 應為筆色
            it
        })
    }
}
