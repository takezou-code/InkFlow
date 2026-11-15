package com.vic.inkflow.util

import android.content.ComponentCallbacks2

/**
 * OOM 前最後一道防線：系統通知記憶體壓力（`TRIM_MEMORY_*`）時，主動把「可重建」的快取丟掉。
 *
 * 為什麼要這支（2026-11-15 M7）：
 * 這台平板 `largeHeap="true"`（512MB），但 ART 在 heap 幾乎滿時並不會有機會自己降壓力——
 * 一切依賴我們自己買快取。2026-11-14/15 的三起 OOM 閃退都發生在 heap 0% free 的瞬間。
 * Android 的 `onTrimMemory` 在記憶體告急時把回調丟給 app；這裡做的事只有一件：
 * 把「再解碼就能回來」的東西丟掉（圖片、位圖），讓 ART 的回收門檻降下來。
 *
 * 使用方式：在 Activity 的 onCreate 以 `registerComponentCallbacks` 註冊一個
 * ComponentCallbacks2，把 level 餵進來；各擁有者在建立快取時呼叫 [register]。
 * 動作是冪等的（evictAll），重複觸發無害。
 */
object AppMemoryPressure {

    /** 由 MainActivity 餵進系統回調；每個 level 原封不動轉給註冊者。 */
    private val actions = java.util.concurrent.CopyOnWriteArrayList<(Int) -> Unit>()

    /** 擁有者登記自己的降載動作。[action] 收到原始 level，自行決定丟到哪一層。 */
    fun register(action: (Int) -> Unit) {
        actions += action
    }

    /** 系統通知（或應用自行注入）的入口。全部包 try：任何一個降載器失敗都不該影響其他人。 */
    fun notifyTrim(level: Int) {
        for (action in actions) {
            try {
                action(level)
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "trim action failed at level=$level: ${t.message}")
            }
        }
    }

    private const val TAG = "AppMemoryPressure"
}
