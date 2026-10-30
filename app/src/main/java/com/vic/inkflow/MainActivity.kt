package com.vic.inkflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.vic.inkflow.data.AppDatabase
import com.vic.inkflow.ui.InkLayerApp
import com.vic.inkflow.util.AutoBackupScheduler

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
        val quiet = settings.getBoolean("power_saver", false)
        AutoBackupScheduler.ensureScheduled(this, quiet)
        setContent {
            InkLayerApp(db = db)
        }
    }
}