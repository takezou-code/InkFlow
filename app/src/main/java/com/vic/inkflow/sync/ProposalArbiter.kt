package com.vic.inkflow.sync

/**
 * v5 提案仲裁的決策部分：純函數，無資料庫、無網路、無 Android。
 *
 * 從 [TabletSyncServer] 抽出來只有一個理由：這個決策是整個雙向同步裡最不能錯的
 * 三行——接受了不該接受的，就是靜默覆寫；駁回了不該駁回的，就是使用者的修改憑空
 * 消失。它必須能被單元測試釘住，而不是只能靠真機聯調。
 *
 * 規則只有兩條，按順序：
 * 1. 世代不同 → 拒絕。對端的 base 是上一個安裝的版本，連比都不用比。
 * 2. 版本不同 → 過期衝突。整筆駁回，沒有部分接受。
 * 3. 都相同 → 接受。
 */
object ProposalArbiter {

    sealed interface Decision {
        data object Accept : Decision
        data class ConflictStale(val conflictIds: List<String>) : Decision
        data class Reject(val message: String) : Decision
    }

    fun decide(
        currentVersion: String,
        currentInstance: String,
        baseVersion: String,
        baseInstance: String?,
        opIds: List<String>
    ): Decision {
        // 世代優先於版本：世代不同時版本字串沒有任何可比性（另一個安裝的雜湊），
        // 拿它們比只會得到一個隨機的接受/駁回。
        if (baseInstance != null && baseInstance != currentInstance) {
            return Decision.Reject("generation changed since base; wipe and re-pull first")
        }
        if (baseVersion != currentVersion) {
            return Decision.ConflictStale(opIds)
        }
        return Decision.Accept
    }
}
