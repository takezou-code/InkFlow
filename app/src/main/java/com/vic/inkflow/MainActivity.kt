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
            // 真實顯示幀率用 Choreographer 數（window frame metrics 只在 View 重繪時回報，
            // 背景動畫不改變整窗 invalidation，會漏掉大部分幀 → 量不準）。
            val vsync = intArrayOf(0)
            val frameCb = object : android.view.Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    vsync[0]++
                    android.view.Choreographer.getInstance().postFrameCallback(this)
                }
            }
            android.view.Choreographer.getInstance().postFrameCallback(frameCb)
            // 每 10 秒摘要一次（幀率 + 慢幀），流光 vs 靜 對照就看這行
            val samples = ArrayList<Long>(4096)
            var tick = 0
            val summary = object : Runnable {
                override fun run() {
                    try {
                    tick++
                    val fps = vsync[0] / 10.0
                    vsync[0] = 0
                    if (tick > 1 && samples.isNotEmpty()) {
                        // 第一個窗口是冷開機，不算
                        val sorted = samples.sorted()
                        val avg = samples.sum() / samples.size
                        val p90 = sorted[(sorted.size * 9 / 10).coerceAtMost(sorted.size - 1)]
                        val p99 = sorted[(sorted.size * 99 / 100).coerceAtMost(sorted.size - 1)]
                        val slow = samples.count { it > 16_666_000L }
                        com.vic.inkflow.util.InkLog.perf(
                            "FRAMES #$tick fps=$fps drawn=${samples.size} " +
                                "avg=${avg / 1_000_000.0}ms p90=${p90 / 1_000_000.0}ms " +
                                "p99=${p99 / 1_000_000.0}ms over16=$slow"
                        )
                    }
                    samples.clear()
                    } catch (t: Throwable) {
                        android.util.Log.d("InkFlowPerf", "summary failed: $t")
                    }
                    android.os.Handler(mainLooper).postDelayed(this, 10_000L)
                }
            }
            android.os.Handler(mainLooper).postDelayed(summary, 10_000L)
            window.addOnFrameMetricsAvailableListener({ _, metrics, _ ->
                val totalNs = metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION)
                if (samples.size < 512) samples.add(totalNs)
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
        val settings = getSharedPreferences("inkflow_settings", 0)
        // 靜模式不回來排備份（開機時就已經在靜，排了等於白排）
        val quiet = settings.getBoolean("power_saver", false)
        AutoBackupScheduler.ensureScheduled(this, quiet)
        com.vic.inkflow.util.InkLog.fingerprint(
            quiet = quiet,
            backdrop = "kind=${settings.getString("backdrop_kind", "ORB")}" +
                " theme=${settings.getString("backdrop_theme", "SOFT")}" +
                " scene=${settings.getString("backdrop_scene", "DUNE")}" +
                " image=" + !settings.getString("backdrop_image", "").isNullOrEmpty()
        )
        com.vic.inkflow.util.InkLog.mode(
            "BACKUP ensureScheduled quiet=$quiet autoEnabled=${AutoBackupScheduler.isEnabled(this)}"
        )
        runCatching {
            val d = display
            com.vic.inkflow.util.InkLog.mode(
                "DISPLAY panel=${d?.mode?.physicalWidth}x${d?.mode?.physicalHeight} " +
                    "refresh=${d?.refreshRate} supported=${d?.supportedModes?.joinToString { it.refreshRate.toString() }}"
            )
        }
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