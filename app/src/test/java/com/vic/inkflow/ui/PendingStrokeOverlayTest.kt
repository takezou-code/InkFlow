package com.vic.inkflow.ui

import androidx.compose.ui.graphics.Color
import app.cash.turbine.test
import com.vic.inkflow.data.ImageAnnotation
import com.vic.inkflow.data.Point
import com.vic.inkflow.data.Stroke
import com.vic.inkflow.data.StrokeWithSamples
import com.vic.inkflow.data.TextAnnotation
import com.vic.inkflow.data.repository.InkFlowRepositories
import com.vic.inkflow.data.repository.RoomImageAnnotationRepository
import com.vic.inkflow.data.repository.RoomStrokeRepository
import com.vic.inkflow.data.repository.RoomTextAnnotationRepository
import com.vic.inkflow.util.StrokePoint
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 樂觀筆跡層的接線測試：提筆→落庫之間，這條頁流必須已經有那一筆。
 *
 * 這個 bug 的因果：頁面畫布只訂閱 `pageDataFlow`，而那條流原本只 combine 三張表的
 * DB 查詢。`saveStroke` 的樂觀層（`_pendingStrokes`）只有早已沒人訂閱的 `currentStrokes`
 * 在用 → DB 落定前的幾幀整頁是空的（快速連筆時甚至「上一筆消失一下」）。
 *
 * 這裡讓 DB 全程不發射，證明筆是從樂觀層來的；再讓 DB 補上同 id，
 * 證明它會退場（不重複畫）而且 `pendingIds` 會清空（畫布把墨烙回位圖）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingStrokeOverlayTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun dbRow(id: String) = StrokeWithSamples(
        Stroke(
            id = id,
            documentUri = DOC,
            pageIndex = 0,
            color = 0xFF000000.toInt(),
            strokeWidth = 4f
        ),
        listOf(Point(strokeId = id, x = 1f, y = 2f))
    )

    private fun buildVm(
        strokes: MutableStateFlow<List<StrokeWithSamples>>,
        texts: MutableStateFlow<List<TextAnnotation>> = MutableStateFlow(emptyList()),
        images: MutableStateFlow<List<ImageAnnotation>> = MutableStateFlow(emptyList())
    ): EditorViewModel {
        val strokeRepo = mockk<RoomStrokeRepository>()
        every { strokeRepo.getStrokesForPage(any(), any()) } returns strokes
        val textRepo = mockk<RoomTextAnnotationRepository>()
        every { textRepo.getForPage(any(), any()) } returns texts
        val imageRepo = mockk<RoomImageAnnotationRepository>()
        every { imageRepo.getForPage(any(), any()) } returns images
        // relaxed：saveStroke 背景那條 insert 交易不會真的落地（DB 全程靜默），
        // 正是這個測試要的「落庫前」狀態。
        val repos = mockk<InkFlowRepositories>(relaxed = true)
        every { repos.strokes } returns strokeRepo
        every { repos.texts } returns textRepo
        every { repos.images } returns imageRepo
        return EditorViewModel(
            repos = repos,
            documentUri = DOC,
            settingsRepository = FakeEditorSettingsRepository()
        )
    }

    @Test
    fun `saved stroke is visible on its page before the db reports it`() = runTest(testDispatcher) {
        val dbStrokes = MutableStateFlow<List<StrokeWithSamples>>(emptyList())
        val vm = buildVm(dbStrokes)
        vm.pageDataFlow(0).test {
            awaitItem() // stateIn 初值（空）
            val id = vm.saveStroke(
                points = listOf(StrokePoint(10f, 20f, 6f), StrokePoint(30f, 40f, 6f)),
                color = Color.Black,
                targetPage = 0
            )
            assertTrue(id != null)
            val onLift = awaitItem()
            // DB 還沒回報，但這一頁已經有那一筆了（提筆零空窗）。
            assertEquals(listOf(id), onLift.strokes.map { it.stroke.id })
            // 畫布靠這個集合把墨走向量層，不吃封頂位圖（避免提筆瞬間糊掉）。
            assertEquals(setOf(id), onLift.pendingIds)

            // DB 補上同一筆 → 樂觀層退場：只剩一份，且不再是「待落庫」。
            dbStrokes.value = listOf(dbRow(id!!))
            val settled = awaitItem()
            assertEquals(listOf(id), settled.strokes.map { it.stroke.id })
            assertTrue(settled.pendingIds.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    companion object {
        private const val DOC = "file:///storage/emulated/0/Documents/test.pdf"
    }
}
