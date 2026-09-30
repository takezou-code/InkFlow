package com.vic.inkflow.util

import android.util.Log
import com.vic.inkflow.BuildConfig

/**
 * 診斷 log 集中入口（AGENTS：診斷 log 定案即刪）。
 * 兩個 tag：
 *  - MODE：流光/靜決策、玻璃材質選擇、背景狀態、低電量自動切。這輪驗收主要看這條。
 *  - PERF：幀率摘要（平均/p90/慢幀數），流光 vs 靜 對照用。
 */
object InkLog {
    const val TAG_MODE = "InkFlowMode"
    const val TAG_PERF = "InkFlowPerf"

    fun mode(msg: String) {
        if (BuildConfig.DEBUG) Log.d(TAG_MODE, msg)
    }

    fun perf(msg: String) {
        if (BuildConfig.DEBUG) Log.d(TAG_PERF, msg)
    }

    /** 開機指紋：任何一次 logcat dump 都靠這行知道當下狀態。 */
    fun fingerprint(quiet: Boolean, backdrop: String) {
        if (!BuildConfig.DEBUG) return
        Log.d(
            TAG_MODE,
            "FINGERPRINT quiet=$quiet backdrop=$backdrop " +
                "sdk=${android.os.Build.VERSION.SDK_INT} " +
                "density=" + android.content.res.Resources.getSystem().displayMetrics.density
        )
    }
}