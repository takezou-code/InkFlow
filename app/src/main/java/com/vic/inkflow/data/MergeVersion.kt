package com.vic.inkflow.data

import java.security.SecureRandom

/**
 * v6 併發合併的 tiebreak nonce 來源（SYNC_PROTOCOL.md §15.1）。
 *
 * 它的唯一工作：在兩端各自把同一個物件改到**同一個 version** 時，提供一個
 * 「兩端必然算出同一個贏家」的值。規則是取小者贏（Excalidraw `versionNonce`），
 * 所以關鍵是它必須**不可預測**——如果可預測（例如用時間戳或序號），兩端可能算出
 * 相反的大小，於是兩台機器會永久停在各自的版本上，各自以為自己贏了。
 *
 * `SecureRandom` 而非 `Random`：預測值等於可以構造「永遠贏」的惡意版本。不值得為
 * 每次按筆畫一次加密隨機數，但這是同步路徑、頻率低，成本可以忽略。
 *
 * 範圍刻意用全 int 而非小範圍：範圍越小，打平的機率越高，而打平正是需要 nonce
 * 表態的情況。
 *
 * **誰贏的規則不在這裡**——那是 [com.vic.inkflow.sync.ProposalArbiter.incomingWins]
 * 的職責。規則與隨機源分開放，是為了避免同一條規則有兩份實現（那會讓兩端收斂到
 * 不同結果，且症狀是隨機覆寫，極難診斷）。
 */
object MergeVersion {

    private val random = SecureRandom()

    /**
     * 下一個 nonce。
     *
     * 刻意**不**排除 0：0 是合法值，而且舊資料預設就是 0。把它排除只會讓「沒改過」
     * 和「改過但剛好抽到 0」兩種狀態產生歧義——而歧義正是這個機制要消滅的東西。
     */
    fun nextNonce(): Int = random.nextInt()
}