package com.vic.inkflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.vic.inkflow.data.AppDatabase
import com.vic.inkflow.ui.InkLayerApp
import com.vic.inkflow.util.AutoBackupScheduler
import com.vic.inkflow.util.TrashCleanupScheduler
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 診斷開關（見 DevLog）：release 預設關，現場放 devlog 旗標檔就開。
        com.vic.inkflow.util.DevLog.init(applicationContext)
        // Haze 功能開關（**不是**診斷旗標，關掉整個玻璃效果會 no-op：
        // 不採樣、不 blur、tint 也不畫，畫面看起來跟沒套玻璃一樣）。
        // 必須在 effect node 掛上之前設定，所以放 onCreate。
        runCatching {
            dev.chrisbanes.haze.HazeFeatureFlags.isPlatformBackdropEnabled = true
        }
        // 144Hz：修飾符投票沒被系統採納，改視窗級偏好（保證註冊）。
        // 面板上限 144，開 App 切一次，之後全程 144。耗電會多一點，要絲滑就認了。
        try {
            val lp = window.attributes
            lp.preferredRefreshRate = 144f
            window.attributes = lp
        } catch (_: Exception) { }
PDFBoxResourceLoader.init(applicationContext)
        val db = AppDatabase.getDatabase(this)
        val settings = getSharedPreferences("inkflow_settings", 0)
        // 一次性打掃：早期版本的診斷格與「上次待的地方」把 OAuth 回呼網址
        // （含 id_token）原封不動存進 prefs，還把別站的 DOM 記成這家的。
        // 含憑證的一律刪，記錯站的也刪（照著它改選擇器只會愈改愈歪）。
        runCatching { com.vic.inkflow.ui.sanitizeAiDiagnostics(settings) }
        // 靜模式不回來排備份（開機時就已經在靜，排了等於白排）
        // 預設值必須跟 AppNav 一致（true＝靜）：三處預設曾經不一致，開關顯示關、實際卻是靜。
        val quiet = settings.getBoolean("power_saver", true)
        AutoBackupScheduler.ensureScheduled(this, quiet)
        // 垃圾桶清運：每天一次，保留天數由設定頁決定（見 TrashSettings）。
        TrashCleanupScheduler.ensureScheduled(this)
        // 記憶體告急通知入口（2026-11-15 M7）：把系統 TRIM_MEMORY_* 轉進 AppMemoryPressure。
        registerComponentCallbacks(object : android.content.ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) = com.vic.inkflow.util.AppMemoryPressure.notifyTrim(level)
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
            @Deprecated("deprecated in Java")
            override fun onLowMemory() =
                com.vic.inkflow.util.AppMemoryPressure.notifyTrim(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        })
        // v34 點位 BLOB 的背景搬遷（2026-11-15 M1）：migration 只建表，
        // 舊 `points` 表在背景分批打包；搬完之前讀取自動 fallback。
        // 必須在 IO：這是 154 萬點的 IO 工作，放主執行緒就是另一種凍結。
        // 用行程級 scope（不是 activity scope）：Activity 重建不該讓搬遷重來，
        // 重複啟動由 StrokeSamplesBackfill 內部的 AtomicBoolean 擋掉。
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            com.vic.inkflow.util.StrokeSamplesBackfill.run(applicationContext)
        }
        setContent {
            InkLayerApp(db = db)
        }
    }

    /**
     * 回到前景：記憶體總帳解除「真壓力」水位（2026-11-15 M8）。
     *
     * 為什麼需要：`TRIM_MEMORY_RUNNING_*` 是真壓力，收到後四層快取會等比縮小；
     * 但系統沒有「壓力解除」的回調。若不主動還原，使用者切出去再回來，快取永遠停在
     * 縮小後的水位 → 每頁重解、處處等待。前景化是唯一可靠的還原時機。
     */
    override fun onResume() {
        super.onResume()
        com.vic.inkflow.util.MemoryBudget.onForeground()
    }
}