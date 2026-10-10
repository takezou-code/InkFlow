package com.vic.inkflow.sync

/**
 * v6 提案仲裁的決策部分：純函數，無資料庫、無網路、無 Android。
 *
 * 從 [TabletSyncServer] 抽出來只有一個理由：這個決策是整個雙向同步裡最不能錯的
 * 幾行——接受了不該接受的，就是靜默覆寫；駁回了不該駁回的，就是使用者的修改憑空
 * 消失。它必須能被單元測試釘住，而不是只能靠真機聯調。
 *
 * ## 粒度：從「整份文件」降到「單一物件」
 *
 * v5 只有兩條規則：世代不同 → 駁回；文件版本不同 → **整筆**駁回。整筆駁回意味著平板
 * 畫了第 3 頁、桌面畫了第 7 頁，桌面那份會一起消失——那不是「兩邊都不丟」。
 *
 * v6 逐 op 決定，非衝突的照常套用。
 *
 * ## 決定性排序（Excalidraw `reconcile.ts`）
 *
 * 誰贏由 `(version, versionNonce)` 決定：version 大者贏，打平則 nonce 小者贏。
 * 這個比較滿足交換律與結合律，所以兩端對同一組操作**必然算出同一個結果**——這是
 * 兩台機器能收斂的唯一前提。
 *
 * 刻意不用牆鐘：兩台裝置的時鐘無從對齊（§6 連 lastOpenedAt 都捨棄了，就是因為它
 * 會製造假陰性）。計數器＋nonce 不依賴任何跨機器的約定。
 *
 * ## 為什麼「同版本」也可能是衝突
 *
 * 兩端從同一個 v5 出發各改一次，兩邊都會寫成 v6。此時版本相等，內容不同，必須靠
 * nonce 分出勝負，且兩端要能算出同一個——這正是 tiebreak 存在的理由。
 */
object ProposalArbiter {

    sealed interface Decision {
        data object Accept : Decision
        data class ConflictStale(val conflictIds: List<String>) : Decision
        data class Reject(val message: String) : Decision

        /**
         * v6：部分接受。[acceptIds] 的 op 已被套用，[conflictIds] 的走了衝突路徑
         * （留副本）。兩者都非空才是「真的合併過」。
         *
         * 全空表示成 [Accept]，否則呼叫端要為「全都套用」與「套用一部分」寫兩套分支。
         */
        data class Merge(
            val acceptIds: List<String>,
            val conflictIds: List<String>
        ) : Decision
    }

    /**
     * 一個待仲裁 op 的三個版本。
     *
     * @param incomingVersion 提案帶來的版本（桌面改完後的新版本）。
     * @param currentVersion 平板現況版本；null = 平板沒有這筆物件（新增 op）。
     * @param baseVersion 桌面動手前看到的版本；只有刪除需要它（見 decideOps）。
     * @param currentDeleted 平板現況是墓碑（§15.4）。
     */
    data class OpState(
        val id: String,
        val incomingVersion: Int,
        val incomingNonce: Int,
        val currentVersion: Int?,
        val currentNonce: Int,
        val baseVersion: Int?,
        val currentDeleted: Boolean = false
    )

    /** op 的種類，決定用哪條規則。 */
    enum class OpKind { UPSERT, DELETE }

    fun decide(
        currentVersion: String,
        currentInstance: String,
        baseVersion: String,
        baseInstance: String?,
        opIds: List<String>
    ): Decision = decideOps(
        currentVersion, currentInstance, baseVersion, baseInstance,
        opIds, opStates = emptyList(), kinds = emptyMap()
    )

    /**
     * 逐 op 仲裁。
     *
     * @param opStates 每個 op 的版本狀態。**空清單代表沒有版本資訊**（v5 桌面不會送），
     *   此時退回 v5 的整筆語義——沒有版本就沒有「誰比較新」的判斷依據，猜一個只會
     *   製造靜默覆寫。保守退回舊語義是唯一安全的選擇。
     * @param kinds 每個 id 的 op 種類；缺失者一律當 UPSERT（新增物件本來就無爭議）。
     */
    fun decideOps(
        currentVersion: String,
        currentInstance: String,
        baseVersion: String,
        baseInstance: String?,
        opIds: List<String>,
        opStates: List<OpState>,
        kinds: Map<String, OpKind> = emptyMap()
    ): Decision {
        // 世代優先於版本：世代不同時版本字串沒有任何可比性（另一個安裝的雜湊），
        // 拿它們比只會得到一個隨機的接受/駁回。
        if (baseInstance != null && baseInstance != currentInstance) {
            return Decision.Reject("generation changed since base; wipe and re-pull first")
        }
        if (baseVersion != currentVersion) {
            return Decision.ConflictStale(opIds)
        }
        if (opStates.isEmpty()) return Decision.Accept

        val byId = opStates.associateBy { it.id }
        val accept = mutableListOf<String>()
        val conflict = mutableListOf<String>()
        for (id in opIds) {
            val state = byId[id]
            if (state == null) {
                // 平板查不到這筆的版本狀態：無法仲裁。不能因此接受（會覆寫）也不能
                // 因此駁回（會刪掉使用者的東西）——當成衝突，讓呼叫端走留副本路徑，
                // 那條路徑兩邊都不丟，是唯一安全的一邊。
                conflict.add(id)
                continue
            }
            if (wins(state, kinds[id] ?: OpKind.UPSERT)) accept.add(id) else conflict.add(id)
        }
        if (conflict.isEmpty()) return Decision.Accept
        return Decision.Merge(acceptIds = accept, conflictIds = conflict)
    }

    /** 一個 op 是否應該被接受。單獨抽出來是為了逐條測試規則本身。 */
    fun wins(state: OpState, kind: OpKind): Boolean {
        val current = state.currentVersion
        // 平板沒有這筆：桌面是唯一持有者，接受。
        if (current == null) return true

        return when (kind) {
            // 修改對修改：決定性排序（§15.2）。
            OpKind.UPSERT -> incomingWins(
                state.incomingVersion, state.incomingNonce, current, state.currentNonce
            )
            OpKind.DELETE -> {
                // 刪除 vs 沒動過 → 刪除勝。
                // 刪除 vs 對方改過 → **修改勝**（§15.4）：使用者刪掉的東西被別人改過時
                // 讓修改回來，比憑空刪掉別人的工作合理。反過來使用者刪了別人剛改的，
                // 走這裡 base < current 所以刪除不執行——這是同一條規則的兩面。
                //
                // 平板現況已是墓碑：刪除是冪等的，接受。
                state.currentDeleted || (state.baseVersion != null && state.baseVersion >= current)
            }
        }
    }

    /**
     * Excalidraw 的決定性排序：version 大者贏，打平 nonce 小者贏。
     *
     * 順序即 `reconcile.ts` 的規則。**兩端必須呼叫同一個函式**：若桌面端自己寫一份
     * 比較，兩邊對「誰贏」的判斷就可能分歧，而分歧的分歧會在下一次同步變成覆蓋與
     * 回捲的迴圈。
     */
    fun incomingWins(
        incomingVersion: Int,
        incomingNonce: Int,
        currentVersion: Int,
        currentNonce: Int
    ): Boolean =
        incomingVersion > currentVersion ||
            (incomingVersion == currentVersion && incomingNonce < currentNonce)
}