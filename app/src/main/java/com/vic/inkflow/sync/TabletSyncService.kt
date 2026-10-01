package com.vic.inkflow.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vic.inkflow.data.AppDatabase
import com.vic.inkflow.data.repository.InkFlowRepositories
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 承載 [TabletSyncServer] ＋ [DiscoveryResponder] 的前台服務。
 *
 * ## 為什麼是前台服務
 * 兩個 socket 必須活得比一次同步久，而 Android 對背景進程的管制（背景執行秒數上限、
 * doze 的網路掛起）會在沒有前台優先級的情況下殺掉它或斷網。前台服務讓「使用者按下
 * 連線到電腦之後，服務確定活著」這件事成立。
 *
 * ## Android 15+ 的兩條硬限制（整個設計就是繞著它們設計的）
 *
 * 1. **`dataSync` 每 24 小時只有 6 小時上限。** 到期時系統呼叫 [onTimeout]，而
 *    **不在那裡 `stopSelf()` 會被系統丟 `RemoteServiceException`**（是崩潰，不是警告），
 *    所以下面那個 override 是強制契約，不是順便清理。
 * 2. **`dataSync` 不允許從 `BOOT_COMPLETED` 啟動**（丟
 *    `ForegroundServiceStartNotAllowedException`）。本檔案刻意**沒有**註冊任何開機廣播
 *    receiver：平板開機後自己起來服務，使用者毫無感覺，卻在燒電與讀磁碟，而且 6 小時的
 *    額度在沒人需要的時候就被吃掉。
 *
 * 結論：**同步必須是使用者主動開始的一段工作，而不是看不見的背景輪詢**（§12）。所以
 * [start] 只在使用者點擊時呼叫，並把 Android 14+ 對背景啟動前台服務的禁止（會丟例外）
 * 轉成可讀的失敗訊息，而不是讓 App 崩潰。
 */
class TabletSyncService : Service() {

    private val lock = Any()
    private val ioScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("TabletSyncService")
    )

    private var server: TabletSyncServer? = null
    private var discovery: DiscoveryResponder? = null
    private var stopped = false

    override fun onCreate() {
        super.onCreate()
        // 必須在 startForegroundService 之後 5 秒內完成，所以放在 onCreate 最前面：
        // 寧可 socket 還沒綁好就已經是前台，也不要有「已啟動但沒有通知」的空窗。
        createNotificationChannel()
        startForegroundCompat(buildNotification())

        // 開 Room 可能要跑 migration（純磁碟 I/O，大庫時是秒級），**不能**在 main thread。
        // 前景通知已經在了，這裡晚一點綁 socket 使用者完全無感。
        ioScope.launch {
            val repos = runCatching { InkFlowRepositories(AppDatabase.getDatabase(this@TabletSyncService)) }
                .getOrElse { e ->
                    Log.e(TAG, "cannot open database; giving up on this sync session", e)
                    stopSelf()
                    return@launch
                }
            synchronized(lock) {
                if (stopped) return@launch // 使用者已經關掉了，別又把 socket 開起來
                val srv = TabletSyncServer(this@TabletSyncService, repos)
                if (!srv.start()) {
                    // 53531 綁不上就代表這一段同步工作根本不會成立。留著一個只顯示
                    // 「等待電腦連線」的前台通知是比不啟動更糟的行為——使用者會一直
                    // 等一個永遠不會來的桌面端。所以整段收回，通知也跟著消失。
                    Log.e(TAG, "TCP sync port unavailable; ending this sync session")
                    stopSelf()
                    return@launch
                }
                server = srv
                isRunning = true
                // 探索掛掉不致命：TCP 才是重點，UDP 只是讓桌面端自動找到我們。
                // 只少了自動探索，使用者仍可手動指定 IP。
                discovery = DiscoveryResponder(this@TabletSyncService)
                    .also { if (!it.start()) Log.w(TAG, "discovery responder unavailable; TCP still works") }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        // NOT_STICKY（不是 STICKY）：被系統殺掉後不要自己爬起來。6 小時額度是 per-app 的，
        // 不會因為重新建立服務而重算——自動重啟只會在沒人需要的時候繼續燒額度。
        START_NOT_STICKY

    /**
     * Android 15+ `dataSync` 逾時（6 小時 / 24 小時）。
     *
     * 系統要求：收到後必須在幾秒內 `stopSelf()`，否則丟 `RemoteServiceException`。
     * 先關 socket 再停自己，才不會留下半個還開著的 listener。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        Log.w(TAG, "dataSync foreground budget exhausted; stopping (id=$startId, type=$fgsType)")
        stopEverything()
        stopSelf(startId)
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 冪等，且可從 onTimeout 與 onDestroy 兩條路徑呼叫。 */
    private fun stopEverything() {
        val (s, d) = synchronized(lock) {
            if (stopped) return
            stopped = true
            val pair = server to discovery
            server = null
            discovery = null
            pair
        }
        s?.stop()
        d?.stop()
        isRunning = false
        ioScope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun startForegroundCompat(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                // LOW：不發聲、不浮動。這是一個背景中的網路服務，不是事件。
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = CHANNEL_DESCRIPTION
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val psk = runCatching { SyncIdentity.psk(this) }.getOrNull()
        val text = if (psk == null) {
            "同步伺服器執行中"
        } else {
            // 配對碼放進通知是刻意的：使用者要的是「把這組數字念到電腦上」，
            // 為了看代碼而被迫離開正在做的事是沒必要的摩擦。
            "配對碼 $psk · 等待電腦連線"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            // 用系統 drawable 而不是自家 mipmap：adaptive icon 當小圖示在部分 launcher
            // 會顯示成空白方塊，而這個檔案不允許為了通知去新增資源檔。
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("InkFlow 同步伺服器")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val TAG = "InkFlowSync"
        private const val CHANNEL_ID = "inkflow_sync_server"
        private const val CHANNEL_NAME = "同步伺服器"
        private const val CHANNEL_DESCRIPTION = "區域網同步（連線到電腦）進行中"
        private const val NOTIFICATION_ID = 4711

        /**
         * 服務是否真的在監聽（socket 已綁好才 true）。
         *
         * 設定頁要顯示這個值，而**不能**另外記一份「使用者以為服務開著」的旗標：6 小時
         * 額度用完時 [onTimeout] 會把服務收掉，那種情況下旗標會停在 true，使用者看見
         * 「已開啟」卻沒有任何電腦連得上，而且沒有辦法從 UI 關掉它（已經沒東西可關）。
         *
         * 服務與 UI 在同一個行程，所以這個欄位就是唯一真相；跨行程才需要另外查
         * ActivityManager。
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * 開始一段同步工作階段。**只從使用者主動操作呼叫**（AGENTS：長命令丟背景、
         * 不要看不見的自動輪詢）。
         *
         * @return null 表示已送出；否則是可直接顯示給使用者的失敗原因
         *   （Android 14+ 在背景啟動 dataSync 前台服務會被系統拒絕，這裡不讓它變成崩潰）。
         */
        fun start(context: Context): String? = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, TabletSyncService::class.java))
            null
        }.getOrElse { e ->
            Log.w(TAG, "cannot start sync service", e)
            e.message ?: "無法啟動同步服務"
        }

        /**
         * 結束同步工作階段（使用者再次按下時呼叫）。
         *
         * 走 [Context.stopService] 而不是用 `startService(ACTION_STOP)`：後者在 app 處於
         * 背景時會被 Android 8+ 直接擋掉（`IllegalStateException`），而同步正是使用者在
         * 別的畫面時按的。清理邏輯本來就在 `onDestroy`，沒有損失。
         */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TabletSyncService::class.java)) }
                .onFailure { Log.w(TAG, "cannot stop sync service", it) }
        }
    }
}
