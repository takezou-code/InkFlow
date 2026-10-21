package com.vic.inkflow.ui

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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 樂觀隱藏的接線測試：刪除的可見回饋不等 DB。
 *
 * 測的是「DB 完全沒動，只有 hidden 集變了，畫面流就把列拿掉」——這正是修掉
 * 「刪了但半天不消失」的關鍵路徑。DB 用戶端（Room Flow）全程不發射，
 * 證明可見性只由 hidden 集決定。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OptimisticHideTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun swp(id: String) = StrokeWithSamples(
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
        // InkFlowRepositories 拿的是具體 Room* 類（不是介面），mock 必須對型別。
        val strokeRepo = mockk<RoomStrokeRepository>()
        every { strokeRepo.getStrokesForPage(any(), any()) } returns strokes
        val textRepo = mockk<RoomTextAnnotationRepository>()
        every { textRepo.getForPage(any(), any()) } returns texts
        val imageRepo = mockk<RoomImageAnnotationRepository>()
        every { imageRepo.getForPage(any(), any()) } returns images
        // 寬鬆 mock 打底（init 區塊不碰 DB，但其他 repos 屬性被讀到時不能炸），
        // 再把三條頁流換成可控的熱流。
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
    fun `hideIds removes rows from pageDataFlow with zero DB traffic`() = runTest(testDispatcher) {
        val strokes = MutableStateFlow(listOf(swp("s1"), swp("s2")))
        val vm = buildVm(strokes)
        vm.pageDataFlow(0).test {
            // stateIn 的初值是空 NeighborPageData（cachedNeighbor 無資料），
            // 上游 combine 緊接著發射真值：空的跳過，只認有東西的第一幀。
            var item = awaitItem()
            if (item.strokes.isEmpty()) item = awaitItem()
            assertEquals(
                setOf("s1", "s2"),
                item.strokes.map { it.stroke.id }.toSet()
            )
            // DB 全程靜默（上游 MutableStateFlow 沒發射過）：只有 hidden 集變了。
            vm.hideIds(listOf("s1"))
            assertEquals(
                listOf("s2"),
                awaitItem().strokes.map { it.stroke.id }
            )
            vm.showIds(listOf("s1"))
            assertEquals(
                setOf("s1", "s2"),
                awaitItem().strokes.map { it.stroke.id }.toSet()
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hide covers texts and images with one shared id space`() = runTest(testDispatcher) {
        val texts = MutableStateFlow(
            listOf(
                TextAnnotation(id = "t1", documentUri = DOC, pageIndex = 0, text = "a", modelX = 0f, modelY = 0f),
                TextAnnotation(id = "t2", documentUri = DOC, pageIndex = 0, text = "b", modelX = 0f, modelY = 0f)
            )
        )
        val images = MutableStateFlow(
            listOf(
                ImageAnnotation(
                    id = "img1", documentUri = DOC, pageIndex = 0, uri = "file:///x.png",
                    modelX = 0f, modelY = 0f, modelWidth = 10f, modelHeight = 10f
                )
            )
        )
        val vm = buildVm(MutableStateFlow(emptyList()), texts, images)
        vm.pageDataFlow(0).test {
            awaitItem() // initial
            vm.hideIds(listOf("t1", "img1"))
            val after = awaitItem()
            assertEquals(listOf("t2"), after.texts.map { it.id })
            assertEquals(emptyList<String>(), after.images.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `show of a never-hidden id is a no-op`() = runTest(testDispatcher) {
        val strokes = MutableStateFlow(listOf(swp("s1")))
        val vm = buildVm(strokes)
        vm.pageDataFlow(0).test {
            awaitItem() // initial
            // undo 恢復分支無條件 showIds：沒藏過的 id 必須不產生多餘發射
            //（StateFlow 對 equal 值不發射；若有多餘發射，這行直接失敗）。
            vm.showIds(listOf("ghost"))
            expectNoEvents()
        }
    }

    companion object {
        private const val DOC = "file:///storage/emulated/0/Documents/test.pdf"
    }
}
