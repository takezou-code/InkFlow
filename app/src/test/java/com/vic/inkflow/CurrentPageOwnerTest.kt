package com.vic.inkflow

import com.vic.inkflow.ui.CurrentPageOwner
import com.vic.inkflow.ui.PageCommit
import com.vic.inkflow.ui.PageOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CurrentPageOwnerTest {

    // ── commitNow：立即提交＋夾取 ─────────────────────────────────────

    @Test
    fun `commitNow clamps to range`() {
        val o = CurrentPageOwner()
        assertEquals(PageCommit(0, PageOwner.Restore), o.commitNow(-5, PageOwner.Restore, 203))
        assertEquals(PageCommit(202, PageOwner.StructuralOp), o.commitNow(999, PageOwner.StructuralOp, 203))
        assertEquals(PageCommit(0, PageOwner.Restore), o.commitNow(3, PageOwner.Restore, 0))
    }

    @Test
    fun `commitNow clears pending candidate and expectation`() {
        val o = CurrentPageOwner()
        o.expectTarget(10, 203, 0L)
        o.onCandidate(PageOwner.MainScroll, 3, 203, 10L)
        // 直接提交 5：候選/預期全清，之後回報 5 不會再觸發任何提交
        assertEquals(PageCommit(5, PageOwner.ProgrammaticNav), o.commitNow(5, PageOwner.ProgrammaticNav, 203))
        assertNull(o.onCandidate(PageOwner.MainScroll, 5, 203, 20L))
        assertNull(o.settle(5, 203, 500L))
    }

    // ── 預期：過渡忽略、到位提交 ─────────────────────────────────────

    @Test
    fun `transient pages ignored until arrival`() {
        val o = CurrentPageOwner()
        o.expectTarget(10, 203, 0L)
        assertNull(o.onCandidate(PageOwner.MainScroll, 3, 203, 100L))
        assertNull(o.onCandidate(PageOwner.MainScroll, 7, 203, 200L))
        // 到位才認
        assertEquals(PageCommit(10, PageOwner.MainScroll), o.onCandidate(PageOwner.MainScroll, 10, 203, 300L))
    }

    @Test
    fun `user takeover after divert window`() {
        val o = CurrentPageOwner(settleMs = 150L, userTakeoverMs = 400L)
        o.expectTarget(10, 203, 0L)
        // 使用者把紙拖到別頁按住超過 400ms → 接管，預期作廢
        assertNull(o.onCandidate(PageOwner.MainScroll, 50, 203, 100L))
        assertNull(o.onCandidate(PageOwner.MainScroll, 50, 203, 600L))
        // 接管後 50 進穩定門，滿 150ms 提交
        assertNull(o.settle(3, 203, 700L))
        assertEquals(PageCommit(50, PageOwner.MainScroll), o.settle(3, 203, 800L))
    }

    @Test
    fun `stale expectation times out`() {
        val o = CurrentPageOwner(programmaticTimeoutMs = 2500L)
        o.expectTarget(10, 203, 0L)
        // 動畫被取消、3 秒後才有排放：預期已超時，候選進穩定門
        assertNull(o.onCandidate(PageOwner.MainScroll, 4, 203, 3000L))
        assertEquals(PageCommit(4, PageOwner.MainScroll), o.settle(3, 203, 3200L))
    }

    // ── 穩定門 ─────────────────────────────────────────────────────

    @Test
    fun `candidate needs settle window`() {
        val o = CurrentPageOwner(settleMs = 150L)
        assertNull(o.onCandidate(PageOwner.MainScroll, 9, 203, 0L))
        assertNull(o.settle(3, 203, 100L))
        assertEquals(PageCommit(9, PageOwner.MainScroll), o.settle(3, 203, 150L))
    }

    @Test
    fun `changing candidate restarts settle clock`() {
        val o = CurrentPageOwner(settleMs = 150L)
        assertNull(o.onCandidate(PageOwner.MainScroll, 9, 203, 0L))
        // 快滑中候選一直換：時鐘重算，不提交中間頁
        assertNull(o.onCandidate(PageOwner.MainScroll, 10, 203, 100L))
        assertNull(o.settle(3, 203, 200L))
        assertEquals(PageCommit(10, PageOwner.MainScroll), o.settle(3, 203, 250L))
    }

    @Test
    fun `settle is no-op when already committed`() {
        val o = CurrentPageOwner()
        assertNull(o.onCandidate(PageOwner.MainScroll, 9, 203, 0L))
        assertNull(o.settle(9, 203, 500L))
    }

    @Test
    fun `cancelExpected drops stale target`() {
        val o = CurrentPageOwner()
        o.expectTarget(10, 203, 0L)
        o.cancelExpected()
        // 預期已清：回報直接進穩定門
        assertNull(o.onCandidate(PageOwner.MainScroll, 4, 203, 100L))
        assertEquals(PageCommit(4, PageOwner.MainScroll), o.settle(3, 203, 300L))
    }
}
