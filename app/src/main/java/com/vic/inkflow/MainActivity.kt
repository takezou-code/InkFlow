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
        // 144Hz：修飾符投票沒被系統採納，改視窗級偏好（保證註冊）。
        // 面板上限 144，開 App 切一次，之後全程 144。耗電會多一點，要絲滑就認了。
        try {
            val lp = window.attributes
            lp.preferredRefreshRate = 144f
            window.attributes = lp
        } catch (_: Exception) { }
        // 幀耗時日誌（只在 debug）：>12ms 的幀才印，抓捏合卡頓用，release 零成本
        if (com.vic.inkflow.BuildConfig.DEBUG &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N
        ) {
            window.addOnFrameMetricsAvailableListener({ _, metrics, _ ->
                val totalNs = metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION)
                if (totalNs > 12_000_000L) {
                    android.util.Log.d(
                        "FRAMETIME",
                        "slow frame ${totalNs / 1_000_000L}ms " +
                            "(layout=${metrics.getMetric(android.view.FrameMetrics.LAYOUT_MEASURE_DURATION) / 1_000_000L}ms " +
                            "draw=${metrics.getMetric(android.view.FrameMetrics.DRAW_DURATION) / 1_000_000L}ms " +
                            "gpu=${metrics.getMetric(android.view.FrameMetrics.GPU_DURATION) / 1_000_000L}ms " +
                            "sync=${metrics.getMetric(android.view.FrameMetrics.SYNC_DURATION) / 1_000_000L}ms)"
                    )
                }
            }, android.os.Handler(mainLooper))
        }
        PDFBoxResourceLoader.init(applicationContext)
        val db = AppDatabase.getDatabase(this)
        AutoBackupScheduler.ensureScheduled(this)
        setContent {
            InkLayerApp(db = db)
        }
    }

    // P0-0 PROBE: log every hardware key (stylus buttons may arrive as PAGE_UP/DOWN).
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (com.vic.inkflow.BuildConfig.DEBUG) {
            android.util.Log.d(
                "PROBE_KEY",
                "DOWN keyCode=$keyCode repeat=${event?.repeatCount} source=${event?.source}"
            )
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (com.vic.inkflow.BuildConfig.DEBUG) {
            android.util.Log.d("PROBE_KEY", "UP keyCode=$keyCode")
        }
        return super.onKeyUp(keyCode, event)
    }
}