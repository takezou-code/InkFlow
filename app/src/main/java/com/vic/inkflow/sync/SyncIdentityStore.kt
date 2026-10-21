package com.vic.inkflow.sync

import android.content.Context
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

/**
 * 跨裝置身分的**本機儲存**：這台平板「這一次安裝」的 `instanceId` 與 `psk`。
 *
 * 與協定本身的分工（§5、§8）：
 *  - 需要兩端位元組級一致的東西（`docVersion`、`pskProof`、常數、DTO）都在
 *    `:shared` 的 `SyncIdentity` 裡，兩端共用一份實作，不會漂移。
 *  - 這個檔案只負責「把值存起來」：`instanceId` 與 `psk` 存在 app 私有的
 *    SharedPreferences，兩端各自的裝置狀態，沒有理由共用。
 *
 * **不動任何 schema**（§5）：兩個值都是 app 自己產生、自己保存的。Room 的
 * `@Entity` / `@Database(version=)` / `Migration` 一行都不用碰。
 *
 * @see <a href="file:../../../../../../../../InkFlow-Windows/desktopApp/SYNC_PROTOCOL.md">SYNC_PROTOCOL.md</a>
 */
object SyncIdentityStore {

    private const val PREFS_NAME = "inkflow_sync_identity"
    private const val KEY_INSTANCE_ID = "instance_id"
    private const val KEY_PSK = "psk"

    private const val PSK_DIGITS = 8
    private const val PSK_MODULUS = 100_000_000

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

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
