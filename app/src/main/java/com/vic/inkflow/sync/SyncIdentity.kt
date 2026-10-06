package com.vic.inkflow.sync

import android.content.Context
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

/**
 * 跨裝置身分：`instanceId`（世代）與 `psk`（配對碼）。
 *
 * **不動任何 schema**（§5）：兩者都是 app 自己產生、自己存在 SharedPreferences 的值。
 * Room 的 `@Entity` / `@Database(version=)` / `Migration` 一行都不用碰。
 *
 * @see <a href="file:../../../../../../../../InkFlow-Windows/desktop-app/SYNC_PROTOCOL.md">SYNC_PROTOCOL.md</a>
 */
object SyncIdentity {

    private const val PREFS_NAME = "inkflow_sync_identity"
    private const val KEY_INSTANCE_ID = "instance_id"
    private const val KEY_PSK = "psk"

    private const val PSK_DIGITS = 8
    private const val PSK_MODULUS = 100_000_000

    /**
     * pskProof 的鹽（§8）。**兩端必須逐位元組一致**，改了等於所有已配對的桌面端全部失效。
     */
    private const val PSK_SALT = "inkflow-sync-v3"

    /**
     * [docVersion] 的欄位分隔符（§6）。URI 與 hex digest 都不可能含 NUL，所以它能消除
     * `("ab","c")` 與 `("a","bc")` 這種串接歧義。**桌面端 `SyncWire.NUL` 就是這個字元。**
     */
    const val DOC_VERSION_SEPARATOR: Char = '\u0000'

    /** 缺席值的哨兵。[docVersion] 用它而不是 "null"，避免 "null" 與真實字串撞雜湊。 */
    private const val ABSENT = "-"
    private const val ABSENT_SIZE = -1L

    private val random = SecureRandom()
    private val lock = Any()

    /**
     * 這台平板「這一次安裝」的身分，首次呼叫時產生並永久保存。
     *
     * 為什麼整個 v3 設計都掛在這個欄位上（§5）：`documents.uri` 是 app 私有儲存裡的
     * `file://` 路徑，檔名是隨機 UUID。單次安裝內它是穩定的 join key，但 **Android 解除
     * 安裝會清空 app 私有儲存，於是所有 uri 全換新**。沒有 instanceId，桌面端分不出
     * 「使用者刪了文件」／「平板重灌」／「App 崩潰遺失狀態」——三者的共同徵兆都是
     * 「該 uri 不再出現在 manifest 裡」。加上它之後：
     *
     *  - instanceId 變了 → 視為重灌 → 桌面端清空鏡像從乾淨狀態重來
     *  - instanceId 沒變 → 同一世代，完整 manifest 裡缺席才代表刪除（§7）
     *
     * 這是**唯一一個不能事後補**的協定欄位：已經出貨、卻沒存它的桌面端，
     * 磁碟上會留下永久無解的孤兒。所以 `commit()` 而不是 `apply()`——寧可慢一次，
     * 也不要因為進程在半路上被殺而讓世代在同一次安裝內悄悄換掉。
     */
    fun instanceId(context: Context): String {
        val prefs = prefs(context)
        synchronized(lock) {
            prefs.getString(KEY_INSTANCE_ID, null)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
            val generated = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_INSTANCE_ID, generated).commit()
            return generated
        }
    }

    /**
     * 8 位數字配對碼，首次呼叫時產生並永久保存；設定頁直接顯示它讓使用者輸入到桌面端。
     *
     * 為什麼要它（§8）：威脅模型裡最強的一項**不是區域網路，而是平板本身**。平板上任何
     * 擁有 INTERNET 權限的 App 都能連進 `localhost:53531` 讀走全部文件與 PDF——
     * Android 的沙箱不會保護你 App 的 inbound socket。
     */
    fun psk(context: Context): String {
        val prefs = prefs(context)
        synchronized(lock) {
            prefs.getString(KEY_PSK, null)
                ?.takeIf { it.length == PSK_DIGITS && it.all(Char::isDigit) }
                ?.let { return it }
            val generated = String.format(Locale.US, "%0${PSK_DIGITS}d", random.nextInt(PSK_MODULUS))
            prefs.edit().putString(KEY_PSK, generated).commit()
            return generated
        }
    }

    /**
     * `SHA-256(psk ‖ "inkflow-sync-v3")`，回 64 字元小寫 hex。
     *
     * 純雜湊不帶鹽就會被彩虹表秒殺，鹽又必須兩端一致，所以鹽寫死在協定裡（§8）。
     * 這是**單向**證明：桌面端拿到它可以確認「平板持有同一份秘密」，反之不行，
     * 所以雙向驗證必須是：桌面送原始 PSK → 平板驗 → 平板送 proof → 桌面驗。
     */
    fun pskProof(psk: String): String =
        SyncWire.sha256Hex((psk + PSK_SALT).toByteArray(Charsets.UTF_8))

    /**
     * 內容定址的文件版本（§6），桌面端實際 diff 的欄位。
     *
     * ```
     * docVersion = SHA-256(instanceId ‖ uri ‖ strokeCount ‖ fileSha256 ‖ fileSize)
     * ```
     * 五個欄位各以 NUL（[DOC_VERSION_SEPARATOR]）隔開，缺席值是 `"-"` 與 `-1L`。
     *
     * 為什麼不是 `lastOpenedAt`（舊的 v2 做法兩個方向都錯）：
     *  - **假陽性**：只是打開文件閱讀、沒畫任何東西 → 時間戳前進 → 桌面端重拉整列 ＋
     *    全部筆跡 ＋ 整份 PDF。搭配無條件輪詢，這會持續發生。
     *  - **假陰性（危險）**：任何沒有同步更新 `lastOpenedAt` 的寫入路徑（undo/redo、
     *    匯入、頁面重排）會讓文件在桌面端**永久停留在舊狀態且無法自癒**，因為 manifest
     *    會一直說「不是較新的」。
     *
     * 雜湊在建構上就不可能假陽性或假陰性，也**完全不需要兩台機器的時鐘一致**。
     *
     * ⚠️ **兩端必須位元組級一致。** 對應桌面端：
     * `InkFlow-Windows/desktop-app/src/main/kotlin/com/vic/inkflow/sync/SyncProtocol.kt`
     * 的 `SyncWire.docVersion`（含 `SyncWire.NUL`）。任一邊改了分隔符、缺席哨兵或欄位
     * 順序，兩邊就會算出不同雜湊，結果是**每一份文件每輪都被判成「有更新」而整份重拉**，
     * 而且看起來像網路問題——極難診斷。改這裡之前先改那邊。
     *
     * 注意 float 不參與：筆跡的**筆數**與**檔案位元組**足以定址，座標再怎麼改而筆數
     * 不變是常見的（改一個點、刪一個點加一個點），那種變更不會改變 docVersion。
     * 這是刻意的取捨：真的漏掉時桌面端下一次拉整份筆跡快照時會一起修正，而漏判的代價
     * （每輪重拉 300 MB）遠大於偶發的漏判。
     */
    fun docVersion(
        instanceId: String,
        uri: String,
        strokeCount: Int,
        fileSha256: String?,
        fileSize: Long?,
        /**
         * v4：文字註解筆數，附加在 [fileSize] 之後（prefix-extension，不重排欄位）。
         *
         * 為什麼只算筆數：註解座標改變而筆數不變會漏判，這和「改一個筆跡點、
         * 刪一個點又加一個點」是完全相同的取捨，而且代價相同——下次整份快照
         * 拉取時會順便修正。反過來把每個座標都雜湊進去，會讓每一輪 manifest 都
         * 變成一次整份文件讀取。
         */
        textCount: Int = 0
    ): String {
        val canonical = buildString {
            append(instanceId); append(DOC_VERSION_SEPARATOR)
            append(uri); append(DOC_VERSION_SEPARATOR)
            append(strokeCount); append(DOC_VERSION_SEPARATOR)
            append(fileSha256 ?: ABSENT); append(DOC_VERSION_SEPARATOR)
            append(fileSize ?: ABSENT_SIZE); append(DOC_VERSION_SEPARATOR)
            append(textCount)
        }
        return SyncWire.sha256Hex(canonical.toByteArray(Charsets.UTF_8))
    }

    /**
     * 長度無關洩漏的 constant-time 字串比對。
     *
     * 長度不同直接回 false 會洩漏長度，但 PSK 恆為 8 碼、長度是公開資訊，所以這裡沒浪費
     * 力懂做雜湊後比較。真正的比對迴圈**不能**提早跳出（early-exit 讓攻擊者用回應時間
     * 逐位元組試出 PSK）。
     */
    fun constantTimeEquals(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
