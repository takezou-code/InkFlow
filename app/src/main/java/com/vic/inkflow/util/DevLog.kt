package com.vic.inkflow.util

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.vic.inkflow.BuildConfig
import java.io.File

/**
 * 開發期診斷的開關。
 *
 * ## 為什麼不是「release 全部不印」
 *
 * 這個 App 的 log 有兩種，性質完全不同，不能一起關：
 *
 *  - **診斷**：每個捲動幀的 JUMP／FLING、每次 zoom 重算的 renderPlan、
 *    每次回收的 evict…… 它們是開發期的顯微鏡，而且在平板上是真的貴
 *    （每幀組字串，再 IPC 寫進 logd）。release 沒有理由背著它。
 *  - **錯誤**：寫入失敗、同步失敗、AI provider 失敗…… 量級是「出事才印」，
 *    卻是使用者回報問題時**唯一的線索**。release 必須留。
 *
 * 所以診斷在 release 預設關（debug build 照舊全開，方便開發），
 * 錯誤與警告永遠開。
 *
 * ## 現場還是要能開診斷
 *
 * 這個專案的使用者跑的就是 release build，過去的問題（捲動卡頓、抬筆閃爍、
 * 慣性被中途取消）都是靠讀 release 的 logcat 抓到的。所以診斷不能焊死。
 *
 * 在 App 的外部檔案目錄放一個名為 `devlog` 的檔案就開：
 *
 *     adb shell touch /sdcard/Android/data/com.vic.inkflow/files/devlog
 *
 * 刪掉那個檔案就關。App 每秒最多檢查一次，所以開關在兩秒內生效，
 * 而且不需要重開 App。
 */
object DevLog {

    private const val FLAG_FILE = "devlog"
    private const val CHECK_INTERVAL_MS = 1000L

    /** 診斷要不要印。release 預設 false，除非現場放了旗標檔。 */
    @Volatile
    private var enabled = BuildConfig.DEBUG

    @Volatile
    private var lastCheck = 0L

    private var appContext: Context? = null

    /** App 啟動時呼叫一次。 */
    fun init(context: Context) {
        appContext = context.applicationContext
        refresh()
    }

    /** 診斷級（Log.d）。release 預設不印。 */
    fun d(tag: String, message: String) {
        if (!enabled && dueForCheck()) refresh()
        if (enabled) Log.d(tag, message)
    }

    /**
     * 診斷級，訊息延遲到真的要印時才組。
     *
     * Kotlin 的參數是**先求值**的：`d(tag, "live=$a size=$b")` 就算不印也已經把字串
     * 組好了。每幀都跑的路徑（renderPlan、evict、JUMP 取樣）用這個版本才真的省到。
     */
    fun d(tag: String, message: () -> String) {
        if (!enabled && dueForCheck()) refresh()
        if (enabled) Log.d(tag, message())
    }

    /**
     * 診斷級但用警告級別輸出。
     *
     * 捲動的 JUMP／FLING 走這裡：它們是**每個捲動幀**都在跑的取樣，
     * 而且要先把字組出來 —— 在 release 上這是實實在在的掉幀來源。
     */
    fun w(tag: String, message: String) {
        if (!enabled && dueForCheck()) refresh()
        if (enabled) Log.w(tag, message)
    }

    /** 診斷級（警告級別），訊息延遲到真的要印時才組。 */
    fun w(tag: String, message: () -> String) {
        if (!enabled && dueForCheck()) refresh()
        if (enabled) Log.w(tag, message())
    }

    /** 錯誤級：永遠印。 */
    fun e(tag: String, message: String, error: Throwable? = null) {
        if (error != null) Log.e(tag, message, error) else Log.e(tag, message)
    }

    private fun dueForCheck(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastCheck < CHECK_INTERVAL_MS) return false
        lastCheck = now
        return true
    }

    private fun refresh() {
        val ctx = appContext
        val flag = ctx?.getExternalFilesDir(null)?.let { File(it, FLAG_FILE)?.exists() } ?: false
        enabled = BuildConfig.DEBUG || flag
    }
}
