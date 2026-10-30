package com.vic.inkflow.ui

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * AI 診斷記錄。**一行一件事，固定欄位順序，寫檔也寫 logcat。**
 *
 * 為什麼要另開一份檔而不是只靠 logcat：logcat 是環形緩衝，使用者測一輪
 * （開四家、登入、送圖、匯入）很容易把前面的證據擠掉；而且平板不一定連著電腦。
 * 落檔之後，「使用者照平常操作 → 開發者讀檔」就能還原整段流程。
 *
 * 為什麼寫檔而不是塞 prefs：診斷是 append-only 的流水帳，可能上百行；
 * prefs 是 key-value，塞進去只會讓每次讀寫都變重。
 *
 * 執行緒：呼叫端（主執行緒、WebView 回呼）只負責組字串與寫 logcat，
 * 真正的檔案 I/O 全丟到單一背景執行緒，不阻塞 UI，也不需要鎖。
 */
object AiDiag {

    /** logcat tag（`ink log` 撈得到；與檔案內容同一份字串）。 */
    private const val TAG = "InkFlowDiag"

    /** 檔案名；放 `filesDir`（`adb shell run-as <pkg> cat files/ai_diag.log` 讀得到）。 */
    const val FILE_NAME = "ai_diag.log"

    /** 超過就輪替成 `.1`。512 KB 約等於上萬行，夠一整輪測試。 */
    private const val ROTATE_BYTES = 512L * 1024L

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ai-diag").apply { isDaemon = true }
    }

    // SimpleDateFormat 不是執行緒安全的；log() 可能在主執行緒與 WebView 執行緒同時被呼叫。
    private val stamp = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    /**
     * 寫一行。[provider] 可為 null（全域事件）。
     *
     * @param event 事件名（大寫、不含空白，例如 `PAGE`／`PROBE`／`JS`／`VERDICT`）。
     * @param detail `key=value key=value`；值含空白就用引號包起來，方便人工與 grep 兩用。
     */
    fun log(context: Context?, provider: AiProvider?, event: String, detail: String = "") {
        val line = buildString {
            append(stamp.get()!!.format(Date()))
            append(' ')
            append(provider?.name ?: "-")
            append(' ')
            append(event)
            if (detail.isNotBlank()) {
                append(' ')
                append(detail.replace('\n', ' '))
            }
        }
        // logcat 先寫：檔案 I/O 是非同步的，使用者當場回報時 logcat 已經有東西。
        try {
            android.util.Log.d(TAG, line)
        } catch (_: Throwable) { }
        val ctx = context?.applicationContext ?: return
        executor.execute {
            try {
                val file = File(ctx.filesDir, FILE_NAME)
                if (file.length() > ROTATE_BYTES) {
                    val old = File(ctx.filesDir, "$FILE_NAME.1")
                    old.delete()
                    file.renameTo(old)
                }
                file.appendText(line + "\n")
            } catch (_: Throwable) {
                // 診斷永遠不該讓主流程失敗。
            }
        }
    }

    /**
     * 把含空白的值包成 `"..."`，讓 `key="a b"` 在人工閱讀與 grep 之間都站得住。
     * 值內部不可能出現 `"`（換成 `'`），所以不需要轉義，也不會撐破格式。
     */
    fun q(value: String?): String =
        "\"" + value.orEmpty().replace('"', '\'').replace('\n', ' ').replace('\r', ' ') + "\""

    /** 讀最後 [maxLines] 行（設定頁要給使用者看／貼給我看）。 */    fun readTail(context: Context, maxLines: Int = 200): String {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (!file.exists()) return ""
        return try {
            file.useLines { lines -> lines.toList().takeLast(maxLines) }.joinToString("\n")
        } catch (_: Throwable) {
            ""
        }
    }

    fun clear(context: Context) {
        try {
            File(context.applicationContext.filesDir, FILE_NAME).delete()
            File(context.applicationContext.filesDir, "$FILE_NAME.1").delete()
        } catch (_: Throwable) { }
    }

    /** 給設定頁顯示用：目前有幾行、多大。 */
    fun sizeInfo(context: Context): String {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (!file.exists()) return "尚無記錄"
        val kb = (file.length() + 1023) / 1024
        return "${kb} KB"
    }
}
