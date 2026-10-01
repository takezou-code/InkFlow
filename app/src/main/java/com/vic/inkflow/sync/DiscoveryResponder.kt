package com.vic.inkflow.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * UDP 探索回應者（§3）。
 *
 * 桌面端每輪把 `discover` 廣播到 53530；平板**只回應、不同廣播**——兩個平板之間沒有理由
 * 對話，而且兩邊都回應會讓區網裡的封包量無謂翻倍。
 *
 * 回應是**單播回 sender**（不是再廣播一次），並且必須帶自己的區網 IP：桌面端就是靠這個
 * IP 來連 TCP 53531。廣播封包的來源位址只在同網段內有效，所以不能用它。
 *
 * 這個機制在 v4 會被 `NsdManager` 取代（§11.1）：`255.255.255.255` 不會穿過路由器，
 * 客用網路／IoT VLAN／mesh WiFi 的 client isolation 會直接讓它失效；而且 Android 16
 * 開始 local network access 需要使用者授權（API 37 強制 `ACCESS_LOCAL_NETWORK`）。
 * **但 v3 的邏輯（哪些欄位、role 規則、protocolVersion 相容性）不受影響**，所以現在就照
 * v3 實作不會白做——換的只是「怎麼被找到」。
 */
class DiscoveryResponder(
    context: Context,
    private val port: Int = SyncPorts.DISCOVERY_PORT
) {

    private val appContext: Context = context.applicationContext
    private val gson = Gson()
    private val running = AtomicBoolean(false)

    @Volatile
    private var socket: DatagramSocket? = null

    private var thread: Thread? = null

    /**
     * 回應節流。UDP 是無連線的，沒有任何機制阻止別人用假來源位址把我當成放大器
     * （反射流量）。限制自己每秒最多回幾封就足以淹掉這種玩法，代價是零。
     */
    private val lastReplyAt = AtomicLong(0L)

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        // 為什麼要先把 port 取出來：`DatagramSocket.apply { ... }` 裡的隱含 receiver 是
        // DatagramSocket，而它有 public 的 `getPort()`。在 apply 內部寫 `port` 會綁到那個
        // getter（未綁定的 socket 回 -1），**不是**本類別的屬性，於是變成
        // `InetSocketAddress(-1)` → "port out of range: -1"，而 catch 區塊在 apply 之外，
        // 印出來卻又是 53530，看起來自相矛盾。這是實機跑起來才抓得到的隱含 receiver 陷阱。
        val listenPort = port
        if (listenPort !in 1..65535) {
            running.set(false)
            Log.e(TAG, "discovery port $listenPort is not a valid UDP port")
            return false
        }
        // 建立 socket 與綁定分開：原本寫在同一個 apply 裡，選項設定失敗會被誤報成
        // 「綁定失敗」，除錯時會指向錯誤的位置。
        val sock = try {
            DatagramSocket(null as SocketAddress?).apply {
                reuseAddress = true
                // 部分 ROM 需要這個才收得到 255.255.255.255；對單播回應無害。
                broadcast = true
                soTimeout = RECEIVE_TIMEOUT_MS
            }
        } catch (e: Exception) {
            running.set(false)
            Log.e(TAG, "cannot create discovery socket", e)
            return false
        }
        try {
            sock.bind(InetSocketAddress(listenPort))
        } catch (e: Exception) {
            running.set(false)
            sock.close()
            Log.e(TAG, "cannot bind UDP $listenPort", e)
            return false
        }
        socket = sock
        thread = Thread({ receiveLoop(sock) }, "inkflow-sync-discovery").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "UDP discovery responder listening on $listenPort")
        return true
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { socket?.close() } // 讓阻塞中的 receive() 立刻回來
        socket = null
        runCatching { thread?.join(STOP_JOIN_MS) }
        thread = null
        Log.i(TAG, "UDP discovery responder stopped")
    }

    /** 廣播封包內含的 LAN IP；桌面端會直接拿它連 TCP。取不到就回 null（不廣播 loopback）。 */
    fun localIpAddress(): String? = selectLocalIpv4()

    private fun receiveLoop(sock: DatagramSocket) {
        val buf = ByteArray(MAX_DATAGRAM_BYTES)
        while (running.get() && !sock.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                sock.receive(packet)
                handleDatagram(String(packet.data, 0, packet.length, Charsets.UTF_8), packet)
            } catch (e: SocketTimeoutException) {
                // 只是讓迴圈有週期性地回來看 running 旗標，並讓 stop() 的時限有保證。
            } catch (e: SocketException) {
                if (running.get()) Log.w(TAG, "UDP socket error", e)
            } catch (e: Exception) {
                // 壞掉的封包不該讓整個 responder 死掉（任何人都能往 53530 丟垃圾）。
                Log.d(TAG, "ignored malformed datagram: ${e.message}")
            }
        }
        Log.i(TAG, "discovery receive loop finished")
    }

    private fun handleDatagram(message: String, packet: DatagramPacket) {
        val json = runCatching { JsonParser.parseString(message) as? JsonObject }.getOrNull() ?: return
        if (json.get("app")?.asString != SyncConstants.APP_TAG) return
        if (json.get("type")?.asString != "discover") return

        // §3 規則 1：只回應不同 role。兩個平板互相忽略。
        val role = runCatching { json.get("requesterRole")?.asString }.getOrNull()
        if (role == null || role == SyncRoles.TABLET) return

        if (!claimReplySlot()) return

        val ip = localIpAddress() ?: run {
            // 沒有可用的 LAN 位址（例如 WiFi 剛斷開）就別亂報 127.0.0.1，那只會讓桌面端
            // 嘗試連自己然後神祕地失敗。靜默不回應是正確行為，桌面端 90 秒後會把它標離線。
            Log.d(TAG, "no LAN IPv4 available; not answering discovery from ${packet.address.hostAddress}")
            return
        }

        val instanceId = SyncIdentity.instanceId(appContext)
        val response = DiscoveryResponse(
            deviceId = deviceId(instanceId),
            deviceName = deviceName(),
            ip = ip,
            instanceId = instanceId
        )
        val bytes = runCatching { gson.toJson(response).toByteArray(Charsets.UTF_8) }.getOrNull() ?: return
        runCatching {
            // 回 sender 的來源 port：桌面端綁在 53530，但用 packet.port 才是通則，
            // 綁在 ephemeral port 的 client 也能收到。
            val dest = InetSocketAddress(packet.address, packet.port)
            socket?.send(DatagramPacket(bytes, bytes.size, dest))
            Log.d(TAG, "answered discovery from ${dest} role=$role")
        }.onFailure { Log.d(TAG, "discovery reply failed: ${it.message}") }
    }

    private fun claimReplySlot(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val last = lastReplyAt.get()
        if (now - last < MIN_REPLY_INTERVAL_MS) return false
        return lastReplyAt.compareAndSet(last, now)
    }

    private fun deviceId(instanceId: String): String =
        "tab-" + instanceId.replace("-", "").take(8)

    private fun deviceName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        val maker = Build.MANUFACTURER?.trim().orEmpty()
        return if (maker.isNotEmpty() && !model.startsWith(maker, ignoreCase = true)) "$maker $model" else model
    }

    /**
     * 挑一個桌面端真的連得上 的 IPv4。
     *
     * **不能用 `InetAddress.getLocalHost()`**：在 Android 上它通常丟
     * `UnknownHostException`，就算成功也經常回 127.0.0.1 或一個根本不對的介面。踩過。
     * 正確做法是走訪網路介面清單並篩掉 loopback 與 link-local（169.254/16 只能同鏈路
     * 通，桌面端在別的機器上）。
     *
     * 有連線能力資訊時優先挑 WiFi：平板在「有線優先 + cellular fallback」的家用網路上
     * 可能同時拿到好幾個 IPv4，桌面端只認得其中一個（它就在那個 subnet 上）。
     * 這裡的取捨是「只要沒有明確更好的就回第一個」，因為多回一次廣播的成本遠小於
     * 桌面端連錯網段後神祕逾時。
     */
    private fun selectLocalIpv4(): String? {
        val preferWifi = isWifiTransport()
        var fallback: String? = null
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return null
        while (interfaces.hasMoreElements()) {
            val nif = interfaces.nextElement()
            if (nif.isLoopback || !nif.isUp) continue
            val addresses = nif.inetAddresses ?: continue
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement() as? Inet4Address ?: continue
                if (address.isLoopbackAddress || address.isLinkLocalAddress) continue
                val host = address.hostAddress ?: continue
                if (preferWifi && isWifiInterface(nif.name)) return host
                if (fallback == null) fallback = host
            }
        }
        return fallback
    }

    private fun isWifiInterface(name: String?): Boolean =
        name != null && (name.startsWith("wlan") || name.startsWith("swlan"))

    private fun isWifiTransport(): Boolean = runCatching {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }.getOrDefault(false)

    private companion object {
        const val TAG = "InkFlowSync"
        const val MAX_DATAGRAM_BYTES = 8192
        const val RECEIVE_TIMEOUT_MS = 1_000
        const val STOP_JOIN_MS = 2_000L
        const val MIN_REPLY_INTERVAL_MS = 200L
    }
}
