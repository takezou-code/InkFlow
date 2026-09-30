package com.vic.inkflow.data.repository

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * S1 單畫布 docY 回填（P3 從 EditorViewModel 抽出）。
 *
 * 為什麼值得獨立成一個類別：這段是「舊文件能不能正確開啟」的隱藏關鍵路徑。
 * v24 前的舊資料 `strokes` / `text_annotations` / `image_annotations` 的 `docY`
 * 是 NULL，開檔時要用 live modelH 當 stride 全量重算。**如果這個呼叫在重構中
 * 被弄丟，舊文件照樣打得開、UI 照樣正常顯示，但墨跡會悄悄落在錯的位置而且不會
 * 報錯**——現場斷言在 2026-09-17 已被刻意降級成只記 log（P0-hotfix），
 * 所以沒有任何機制會提醒你。獨立成一個有名字、有單元測試的類別，
 * 至少讓「它在不在」變成可以斷言的事實。
 *
 * 職責邊界：只做回填與不變量抽查，不碰任何 UI 狀態。
 */
class DocSpaceMigrator(
    private val repos: InkFlowRepositories,
    private val documentUri: String
) {
    private val mutex = Mutex()
    private var done = false

    /**
     * 冪等：整份文件只跑一次。頁增刪/改紙造成的陳舊值由 backfill 的
     * 「無 NULL 守衛全量重算」自動修正，所以不需要重跑。
     */
    suspend fun run(modelH: Float) {
        mutex.withLock {
            if (done) return
            done = true
            val stride = modelH.coerceAtLeast(1f)

            // P0-hotfix(9/17)：開檔永不因遷移檢查而死——9/16 起真實文件被 parity check
            // 磚掉（紙改尺寸後舊墨超出新紙界，範圍查合法查不到，回填自洽卻判死刑；
            // 重開必閃）。原「維持閃退」決議收回：以下全部降級為記 log＋繼續；
            // 髒數據走 rebase 修，不擋開檔。S1 只寫不讀＋影子探針只記 log，
            // 開檔路徑無任何實質依賴，降級零行為變化。
            runCatching {
                repos.transaction {
                    val ns = repos.strokes.backfillStrokeDocY(documentUri, stride)
                    val nt = repos.texts.backfillTextDocY(documentUri, stride)
                    val ni = repos.images.backfillImageDocY(documentUri, stride)
                    Log.i(TAG, "backfilled doc=$documentUri stride=$stride strokes=$ns texts=$nt images=$ni")
                    verify(stride)
                }
            }.onFailure { e ->
                Log.e(TAG, "migration failed (open continues) doc=$documentUri", e)
            }
        }
    }

    /**
     * 不變量抽查：全部只記 log 不拋。任一項非 0 代表回填不完整或紙張尺寸中途變過，
     * 屬於髒資料（走 rebase 修），不擋開檔。
     */
    private suspend fun verify(stride: Float) {
        fun crumb(tag: String, detail: Any?) =
            Log.e(TAG, "ASSERT-FAIL $tag doc=$documentUri stride=$stride detail=$detail")

        val s = repos.pageOps.docYSanity(documentUri, stride)
        if (s.missingStrokes != 0) crumb("backfill-incomplete/strokes", s.missingStrokes)
        if (s.missingTexts != 0) crumb("backfill-incomplete/texts", s.missingTexts)
        if (s.missingImages != 0) crumb("backfill-incomplete/images", s.missingImages)
        if (s.mismatchedStrokes != 0) crumb("invariant-broken/strokes", s.mismatchedStrokes)
        if (s.mismatchedTexts != 0) crumb("invariant-broken/texts", s.mismatchedTexts)
        if (s.mismatchedImages != 0) crumb("invariant-broken/images", s.mismatchedImages)

        // 範圍查 vs 頁查一致性（第 0 頁）：探針性質，只記不拋。紙改小後舊墨合法地
        // 落在窗口外（parity 必然破），等 rebase 收。
        val page0 = repos.strokes.getStrokesForPageSync(documentUri, 0).map { it.stroke.id }.toSet()
        val range0 = repos.strokes.getStrokesForRange(documentUri, -1f, stride + 1)
            .filter { it.stroke.pageIndex == 0 }.map { it.stroke.id }.toSet()
        if (page0 != range0) crumb("range-page-parity", "page0=${page0.size} range0=${range0.size}")
    }

    /** fire-and-forget 版本，供 ViewModel 從初始化路徑呼叫。 */
    fun launch(scope: CoroutineScope, modelH: Float) {
        scope.launch(Dispatchers.IO) { run(modelH) }
    }

    /** 測試用：重設冪等旗標，讓同一個 instance 能測第二次呼叫被跳過。 */
    fun resetForTest() {
        done = false
    }

    companion object {
        const val TAG = "DocSpace"
    }
}
