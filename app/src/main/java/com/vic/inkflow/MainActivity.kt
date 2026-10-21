package com.vic.inkflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.vic.inkflow.data.AppDatabase
import com.vic.inkflow.ui.InkLayerApp
import com.vic.inkflow.util.AutoBackupScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        // 靜模式不回來排備份（開機時就已經在靜，排了等於白排）
        val quiet = settings.getBoolean("power_saver", false)
        AutoBackupScheduler.ensureScheduled(this, quiet)
        setContent {
            InkLayerApp(db = db)
        }
        // TEMP-KATEX-SMOKE 測完移除
        if (com.vic.inkflow.BuildConfig.DEBUG) {
            CoroutineScope(Dispatchers.Main).launch {
                delay(1500)
                com.vic.inkflow.ui.MathSnapshot.ensure(this@MainActivity)
                repeat(2) { i ->
                    val bmp = com.vic.inkflow.ui.MathSnapshot.render("$$\\frac{a}{b} + x^2 = 1$$")
                    android.util.Log.d(
                        "InkFlowDbg",
                        "TEMP-KATEX-SMOKE #$i " + (bmp?.let { "${it.width}x${it.height}" } ?: "null")
                    )
                    bmp?.recycle()
                }
                android.util.Log.d("InkFlowDbg", "TEMP-KATEX-SMOKE done")
            }
        }
    }
}