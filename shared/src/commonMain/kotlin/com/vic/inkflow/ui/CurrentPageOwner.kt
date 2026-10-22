package com.vic.inkflow.ui

/**
 * 當前頁單一標準（純邏輯，無 Compose、無 Android，可 JVM 單測）。
 *
 * 定案語義：`CommittedCurrentPage ＝ 主紙可視面積最大那一頁，經過穩定門後提交，
 * 並夾在 [0, pageCount-1]`。側欄中央、VM pageIndex、DB lastPage 全部是跟隨者。
 *
 * 為什麼需要這台機器：歷史上每個症狀各加一個旗子（programmaticTarget、
 * sidebarFollowActive、lastSidebarDriveMs、800ms 慣性窗、2.5s 超時），結果同一個
 * 「當前頁」有 5 個寫入者、3 種時鐘。現在全部收斂成：一個候選、一個預期、
 * 一道穩定門，超時與接管規則寫死在這裡、用單測釘住。
 *
 * - 候選（candidate）：主紙最大可見頁／側欄中央，回報上來先當候選，不直接寫。
 * - 預期（expected）：程式化導航設下的目標；過渡頁忽略，直到到位／被接管／超時。
 * - 預覽（preview）：UI 層的顯示狀態（手指目標），由呼叫方持有，本機不管。
 */
enum class PageOwner {
    MainScroll,
    SidebarDrive,
    ProgrammaticNav,
    StructuralOp,
    Restore,
}

/** 一次提交：呼叫方負責套用到 currentPageIndex＋setActivePage。 */
data class PageCommit(val page: Int, val owner: PageOwner)

class CurrentPageOwner(
    val settleMs: Long = 150L,
    val userTakeoverMs: Long = 400L,
    val programmaticTimeoutMs: Long = 2500L,
) {
    private var candidate = -1
    private var candidateSinceMs = Long.MIN_VALUE
    private var expected = -1
    private var expectedSinceMs = Long.MIN_VALUE
    private var divertSinceMs = Long.MIN_VALUE

    /** 頁碼夾取（count<=0 守空文件）。結構索引換算仍走 resolveInsertionIndex 家族，這裡只做顯示夾。 */
    fun clamp(page: Int, pageCount: Int): Int =
        if (pageCount <= 0) 0 else page.coerceIn(0, pageCount - 1)

    /**
     * 立即提交（點選／插頁／刪頁／還原／放手結算）：清掉候選與預期，
     * 回傳夾取後的提交。呼叫方套用後，後續同值回報自然被忽略。
     */
    fun commitNow(page: Int, owner: PageOwner, pageCount: Int): PageCommit {
        candidate = -1
        expected = -1
        divertSinceMs = Long.MIN_VALUE
        return PageCommit(clamp(page, pageCount), owner)
    }

    /** 程式化導航設預期：過渡頁忽略，直到到位／被接管／超時。 */
    fun expectTarget(page: Int, pageCount: Int, nowMs: Long) {
        expected = clamp(page, pageCount)
        expectedSinceMs = nowMs
        divertSinceMs = Long.MIN_VALUE
    }

    /** 手勢接管（pinch）：未跑完的導航預期作廢，不許把紙拽回去。 */
    fun cancelExpected() {
        expected = -1
        divertSinceMs = Long.MIN_VALUE
    }

    /**
     * 捲動候選（主紙最大可見／側欄中央）：回傳非 null 表示可立即提交。
     * - 到位（== 預期）→ 立即提交並清預期
     * - 偏離預期超過 userTakeoverMs → 使用者接管，忘掉預期、候選進穩定門
     * - 預期超時 → 忘掉預期，候選進穩定門
     * - 無預期 → 換候選重計時，穩定門在 settle() 判
     */
    fun onCandidate(owner: PageOwner, page: Int, pageCount: Int, nowMs: Long): PageCommit? {
        val p = clamp(page, pageCount)
        if (expected >= 0) {
            if (p == expected) {
                val c = PageCommit(expected, owner)
                expected = -1
                candidate = -1
                divertSinceMs = Long.MIN_VALUE
                return c
            }
            if (nowMs - expectedSinceMs > programmaticTimeoutMs) {
                expected = -1
                divertSinceMs = Long.MIN_VALUE
            } else {
                if (divertSinceMs == Long.MIN_VALUE) divertSinceMs = nowMs
                if (nowMs - divertSinceMs > userTakeoverMs) {
                    expected = -1
                    divertSinceMs = Long.MIN_VALUE
                } else {
                    return null
                }
            }
        }
        if (p != candidate) {
            candidate = p
            candidateSinceMs = nowMs
        }
        return null
    }

    /**
     * 穩定門：候選不變滿 settleMs 才提交；已等於 committed 則回 null。
     * 呼叫方（EditorScreen）用延遲 job 驅動，不在這裡開協程——保持純潔可測。
     */
    fun settle(committed: Int, pageCount: Int, nowMs: Long): PageCommit? {
        if (candidate < 0) return null
        if (nowMs - candidateSinceMs < settleMs) return null
        val p = clamp(candidate, pageCount)
        candidate = -1
        return if (p == clamp(committed, pageCount)) null else PageCommit(p, PageOwner.MainScroll)
    }
}
