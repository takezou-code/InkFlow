package com.vic.inkflow.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提取錯頁回歸網：圈選「提取」按了之後圖落在錯頁的根因——
 * `insertBlankPage` 回傳時只是送出背景任務（先寫檔、再跑 `PageOps.shift`、最後才刷新
 * `pageCount`），而寫圖緊接著就跑，於是新圖被後到的 `shiftPageIndicesUp(+1)`
 * 擠到下一頁。
 *
 * 釘住的是等待門 [awaitPageInserted] 的三條語意：
 * 1. 已落定（`pageCount == before + 1`）立刻放行，不空等；
 * 2. 後續落定才放行（寫圖發生在 DB 移位之後）；
 * 3. 只認 `before + 1`，別的值（`+2`、不變）不誤認，超時回 false。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LassoExtractGateTest {

    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun `已落定立刻回true`() = runTest(testDispatcher) {
        val pageCount = MutableStateFlow(4)
        assertTrue(awaitPageInserted(pageCount, before = 3, timeoutMs = 5_000))
    }

    @Test
    fun `後續落定才放行`() = runTest(testDispatcher) {
        val pageCount = MutableStateFlow(3)
        val deferred = async { awaitPageInserted(pageCount, before = 3, timeoutMs = 5_000) }
        // 只跑已排程的不推進虛擬時鐘：advanceUntilIdle 會把 5 秒超時也跑完、門先關。
        runCurrent()
        // DB 移位完成、pageCount 刷新後才放行：此時寫圖已在 shift 之後，不會被擠走。
        pageCount.value = 4
        assertTrue(deferred.await())
    }

    @Test
    fun `超時回false`() = runTest(testDispatcher) {
        val pageCount = MutableStateFlow(3)
        assertFalse(awaitPageInserted(pageCount, before = 3, timeoutMs = 100))
    }

    @Test
    fun `加兩頁不誤認成目標頁`() = runTest(testDispatcher) {
        val pageCount = MutableStateFlow(3)
        pageCount.value = 5
        assertFalse(awaitPageInserted(pageCount, before = 3, timeoutMs = 100))
    }
}
