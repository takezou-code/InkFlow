package com.vic.inkflow.sync

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
import com.vic.inkflow.data.DocumentEntity
import com.vic.inkflow.data.StrokeWithPoints
import com.vic.inkflow.data.TextAnnotationEntity
import com.vic.inkflow.data.repository.InkFlowRepositories
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 檔案層級（巢狀類別看得到外層 companion 的話會少一個 indirections，但 log tag 要統一）。 */
private const val TAG = "InkFlowSync"

/**
 * 平板端 LAN 同步 server（協定 v5，§4）。
 *
 * 角色：桌面端是 client，平板是 server。v4 以前是 pull-only（這個類別只讀不寫）；
 * v5 起接受桌面的提案寫入，但**仲裁權在平板**：提案帶 `baseDocVersion`，對不上
 * 當前版本就整筆駁回，絕不合併——合併是靜默覆寫的另一種寫法。
 *
 * 資料存取一律走 [InkFlowRepositories]（AGENTS：repository 是唯一入口，禁直呼 DAO），
 * 而且不新增任何 repository 方法：upsert 靠 `REPLACE` 語義的 insert，刪除靠既有
 * delete 方法。
 *
 * 兩個必須注意的效能陷阱：
 *  1. **不要在每次請求上重算 PDF 的 SHA-256。** 這裡的實例是 300 MB 以上，而桌面端
 *     每 30 秒輪詢一次 → 每輪重雜湊就是每 30 秒讀幾 GB 磁碟。[FileDigestCache] 以
 *     (路徑, 大小, mtime) 為鍵快取，檔案沒變就直接回舊值。
 *  2. **筆數不能用「載入全部筆跡再數」當作長期方案。** `StrokeRepository` 目前沒有
 *     count 查詢（見 [StrokeCountCache] 的說明），所以這裡加了有界的快取與背景刷新。
 *
 * @see <a href="file:../../../../../../../../InkFlow-Windows/desktop-app/SYNC_PROTOCOL.md">SYNC_PROTOCOL.md</a>
 */
class TabletSyncServer(
    context: Context,
    private val repos: InkFlowRepositories,
    private val port: Int = SyncPorts.TRANSFER_PORT,
    private val maxConcurrentClients: Int = 4
) {

    private val appContext: Context = context.applicationContext
    private val gson = Gson()
    private val running = AtomicBoolean(false)
    private val serverSocket = AtomicReference<ServerSocket?>(null)
    private val openClients = ConcurrentHashMap.newKeySet<Socket>()
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("TabletSyncServer")
    )
    private val clientSlots = Semaphore(maxConcurrentClients)
    private val digests = FileDigestCache()
    private val strokeCounts = StrokeCountCache(scope)

    /**
     * v5：最近的提案仲裁結果，key 是 proposalId。
     *
     * 只做重送去重，不做持久化——持久化不需要，因為 ops 按 id 冪等：同一提案重送
     * 落在同一狀態。重啟後 map 是空的，重送的提案若 base 已過期會被正常駁回，
     * 若 base 仍有效則冪等重應用，兩種結果都正確。
     *
     * 有界 64：提案結果只在重試窗口內有用，無限增長是記憶體洩漏。
     */
    private val recentProposals: MutableMap<String, ProposalStatusPayload> =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, ProposalStatusPayload>(64, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, ProposalStatusPayload>
                ): Boolean = size > 64
            }
        )

    /** v4：文字註解筆數快取，與 [strokeCounts] 同一套邏輯與同一套預熱理由。 */
    private val textCounts = StrokeCountCache(scope)

    /** 允許服務的實體檔根目錄；見 [requireServableFile] 的安全說明。 */
    private val servableRoot: File? = (
        appContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: appContext.filesDir
        )?.canonicalFileOrNull()

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    /** @return false 表示綁定失敗（埠被佔、權限不足…），呼叫端應該把錯誤顯示給使用者。 */
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        val socket = try {
            ServerSocket(port).apply { reuseAddress = true }
        } catch (e: IOException) {
            running.set(false)
            Log.e(TAG, "cannot bind TCP $port", e)
            return false
        }
        serverSocket.set(socket)
        Thread({ acceptLoop(socket) }, "inkflow-sync-accept").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "TCP sync server listening on $port (protocol v${SyncConstants.PROTOCOL_VERSION}, max $maxConcurrentClients clients)")
        scope.launch { warmUpCaches() }
        return true
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket.getAndSet(null)?.close() }
        // 先關掉所有進行中的連線，否則阻塞在 readFrame 的 client 會拖住關閉。
        openClients.forEach { runCatching { it.close() } }
        openClients.clear()
        scope.cancel()
        Log.i(TAG, "TCP sync server stopped")
    }

    // ─── Accept / session ────────────────────────────────────────────────────

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get() && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: SocketException) {
                break // stop() 關掉 ServerSocket 造成的，預期中的退出
            } catch (e: IOException) {
                if (running.get()) Log.w(TAG, "accept failed", e)
                continue
            }
            try {
                client.tcpNoDelay = true
                openClients += client
                // 用 launch 出去而不是直接在 accept 執行緒服務：同步是 lock-step 的慢操作，
                // 卡住 accept 會讓其他桌面端連不上。超過名額的連線在這裡排隊而不是被拒，
                // 因為 TCP 層的半開連線會讓 client 的 soTimeout 難看的失敗。
                scope.launch { clientSlots.withPermit { serveClient(client) } }
            } catch (e: Exception) {
                runCatching { client.close() }
                Log.w(TAG, "cannot hand off client", e)
            }
        }
        Log.i(TAG, "accept loop finished")
    }

    private suspend fun serveClient(socket: Socket) {
        try {
            socket.use {
                socket.soTimeout = SOCKET_TIMEOUT_MS
                val input = DataInputStream(BufferedInputStream(socket.getInputStream(), BUFFER_BYTES))
                val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), BUFFER_BYTES))
                // 同一條連線上 handshake 只需成功一次（§4 的流程是握手後才送資料）。
                var authenticated = false
                while (running.get() && !socket.isClosed) {
                    val text = SyncWire.readText(input) ?: break // 乾淨 EOF = client 收工
                    val req = runCatching { gson.fromJson(text, SyncRequest::class.java) }.getOrNull()
                    if (req == null) {
                        respondError(out, "", "malformed request frame")
                        break
                    }
                    authenticated = handleRequest(req, out, authenticated)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 連線層的錯誤（對端消失、readFully 中斷…）不值得堆疊，debug 級即可。
            Log.d(TAG, "client session ended: ${e.message}")
        } finally {
            openClients -= socket
        }
    }

    /**
     * @return 這條連線現在是否已通過認證（供下一輪請求判斷）。
     */
    private suspend fun handleRequest(
        req: SyncRequest,
        out: DataOutputStream,
        authenticated: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val type = req.type
        try {
            when (type) {
                SyncRequest.TYPE_HANDSHAKE -> handshake(req, out)
                else -> {
                    if (!authenticated) {
                        // 強制握手不是多餘的：若允許跳過，PSK 就形同虛設——攻略者只要不要
                        // 送 handshake，直接問 doc_manifest 就能讀走全部文件（§8 的威脅模型）。
                        respondError(out, type, "handshake required before $type")
                        false
                    } else {
                        serve(type, req, out)
                        true
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncRequestException) {
            // 可預期的請求層錯誤：回 ok=false + 人類可讀訊息，client 會跳過這份文件續跑。
            respondError(out, type, e.message ?: "bad request")
            authenticated
        } catch (e: Exception) {
            Log.w(TAG, "serving $type failed", e)
            respondError(out, type, e.message ?: "server error")
            authenticated
        }
    }

    /**
     * §8 認證。雙向：桌面送原始 PSK → 我們 constant-time 驗；我們回
     * `SHA-256(psk ‖ "inkflow-sync-v3")` 讓它確認我們也持有同一份秘密。
     */
    private fun handshake(req: SyncRequest, out: DataOutputStream): Boolean {
        val localPsk = SyncIdentity.psk(appContext)
        val presented = req.psk
        if (!SyncIdentity.constantTimeEquals(localPsk, presented)) {
            Log.w(TAG, "handshake rejected: bad pairing code from ${req.deviceId}")
            respondError(out, SyncRequest.TYPE_HANDSHAKE, "authentication failed: wrong pairing code")
            return false
        }
        val ack = HandshakeAck(
            protocolVersion = SyncConstants.PROTOCOL_VERSION,
            instanceId = SyncIdentity.instanceId(appContext),
            pskProof = SyncIdentity.pskProof(localPsk)
        )
        respondJson(out, SyncRequest.TYPE_HANDSHAKE, ack)
        return true
    }

    private suspend fun serve(type: String?, req: SyncRequest, out: DataOutputStream) {
        when (type) {
            SyncRequest.TYPE_DOC_MANIFEST -> respondJson(out, type, buildManifest())

            SyncRequest.TYPE_DOCUMENT_DETAIL -> {
                val doc = requireDocument(req.documentUri)
                val strokes = ordered(repos.strokes.getAllStrokesForDocument(doc.uri))
                // 這一趟本來就把全部筆跡讀出來了，順手餵回快取（免費的精確刷新）。
                strokeCounts.record(doc.uri, strokes.size)
                // v4：文字註解跟筆跡走同一個 payload。放在一起的理由是刪除模型——
                // manifest 是完整的且平板是唯一寫者，桌面端本來就是整份替換。
                // 分成獨立 verb 只會多一套「這一輪看過了」的簿記，沒有任何好處。
                val texts = repos.texts.getAllForDocument(doc.uri)
                textCounts.record(doc.uri, texts.size)
                respondJson(out, type, DocumentDetailPayload(doc, strokes, texts))
            }

            SyncRequest.TYPE_STROKE_DELTA -> {
                val doc = requireDocument(req.documentUri)
                val strokes = ordered(repos.strokes.getAllStrokesForDocument(doc.uri))
                strokeCounts.record(doc.uri, strokes.size)
                respondJson(out, type, StrokeDeltaPayload(doc.uri, strokes))
            }

            SyncRequest.TYPE_STROKE_PAGE -> {
                val doc = requireDocument(req.documentUri)
                val page = (req.pageIndex ?: 0).coerceAtLeast(0)
                val strokes = ordered(repos.strokes.getStrokesForPageSync(doc.uri, page))
                respondJson(out, type, StrokeDeltaPayload(doc.uri, strokes))
            }

            SyncRequest.TYPE_FILE_META -> {
                val file = requireServableFile(requireDocument(req.documentUri))
                val present = file.isFile
                respondJson(
                    out, type,
                    FileMetaPayload(
                        exists = present,
                        size = if (present) file.length() else 0L,
                        sha256 = if (present) digests.sha256Hex(file) else null
                    )
                )
            }

            SyncRequest.TYPE_FILE_DATA -> {
                val file = requireServableFile(requireDocument(req.documentUri))
                val offset = (req.offset ?: 0L).coerceAtLeast(0L)
                val limit = (req.limit ?: DEFAULT_CHUNK_BYTES).coerceIn(0, MAX_CHUNK_BYTES)
                if (!file.isFile) {
                    SyncWire.writeFrame(out, ByteArray(0)) // 缺席 = EOF marker（§2）
                } else {
                    SyncWire.writeFrame(out, SyncWire.readChunk(file, offset, limit))
                }
            }

            SyncRequest.TYPE_PROPOSAL_SUBMIT -> {
                val proposal = req.proposal
                    ?: throw SyncRequestException("missing proposal")
                respondJson(out, type, arbitrateProposal(proposal))
            }

            SyncRequest.TYPE_PROPOSAL_STATUS -> {
                val id = req.proposalId?.takeIf { it.isNotBlank() }
                    ?: throw SyncRequestException("missing proposalId")
                val known = recentProposals[id]
                respondJson(
                    out, type,
                    known ?: ProposalStatusPayload(
                        proposalId = id,
                        status = ProposalStatusPayload.UNKNOWN,
                        message = "no record of this proposal (restarted, or never received)"
                    )
                )
            }

            SyncRequest.TYPE_FOLDER_LIST -> {
                // 整批快照：資料夾是顯示屬性，沒有增量語義，客戶端整批替換。
                // Flow 取一次即走，不訂閱——同步是拉取，不是訂閱。
                respondJson(out, type, repos.folders.getAllFolders().first())
            }

            else -> respondError(out, type, "unknown request type $type")
        }
    }

    /**
     * v5 仲裁：接受或整筆駁回桌面的提案，沒有第三種結果。
     *
     * 沒有「部分接受」：一個提案要嘛全部落，要嘛全部不落。部分接受會讓桌面以為
     * 「送出去的就是生效的」，而實際上只有一半生效——下次拉取時另一半憑空消失，
     * 那是比明確駁回難十倍的故障。
     *
     * 冪等：同一個 proposalId 重送直接回上次的結果，不重新比對、不重新寫入。
     * 這讓客戶端的重試是安全的：超時後不知道對端到底寫了沒，直接重送即可。
     *
     * 併發寫入走 `repos.transaction`：提案的 ops 必須同生同死，否則一半 ops 落庫
     * 而另一半沒有，文件就處於桌面和提案都沒描述過的狀態。
     */
    private suspend fun arbitrateProposal(p: ProposalSubmitPayload): ProposalStatusPayload {
        recentProposals[p.proposalId]?.let { return it }

        val doc = try {
            requireDocument(p.documentUri)
        } catch (e: SyncRequestException) {
            return reject(p, "unknown document").also { recentProposals[p.proposalId] = it }
        }

        val instance = SyncIdentity.instanceId(appContext)
        val current = currentDocVersion(doc)
        // 決策是純函數（見 ProposalArbiter）：這裡只負責執行，不重新發明規則。
        // 規則若有兩份實現，遲早各改各的，然後在某個深夜以資料損毀的方式分叉。
        when (val decision = ProposalArbiter.decide(
            currentVersion = current,
            currentInstance = instance,
            baseVersion = p.baseDocVersion,
            baseInstance = p.baseInstanceId,
            opIds = p.ops.mapNotNull { it.id ?: strokeIdOf(it) }
        )) {
            is ProposalArbiter.Decision.Reject -> {
                return reject(p, decision.message).also { recentProposals[p.proposalId] = it }
            }
            is ProposalArbiter.Decision.ConflictStale -> {
                return ProposalStatusPayload(
                    proposalId = p.proposalId,
                    status = ProposalStatusPayload.CONFLICT_STALE,
                    winnerDocVersion = current,
                    conflictIds = decision.conflictIds,
                    message = "base version moved on; rebase onto $current and resubmit"
                ).also { recentProposals[p.proposalId] = it }
            }
            ProposalArbiter.Decision.Accept -> Unit
        }

        if (p.ops.isEmpty()) {
            return accept(p, current).also { recentProposals[p.proposalId] = it }
        }

        // 應用前先校驗每個 op 的目標屬於這份文件：否則一個提案可以把別的文件的
        // 物件寫進來，而 requireDocument 只檢查了文件本身。
        val decoded = p.ops.map { decodeOp(p.documentUri, it) }

        repos.transaction {
            decoded.forEach { applyOp(it) }
        }

        // 快取跟著寫入走，否則下一輪 manifest 會用舊筆數算出舊版本，
        // 把剛接受的提案判成「又變了」而讓桌面白拉一次。
        val strokes = repos.strokes.getAllStrokesForDocument(doc.uri)
        val texts = repos.texts.getAllForDocument(doc.uri)
        strokeCounts.record(doc.uri, strokes.size)
        textCounts.record(doc.uri, texts.size)
        val winner = currentDocVersion(doc)
        return accept(p, winner).also { recentProposals[p.proposalId] = it }
    }

    private fun reject(p: ProposalSubmitPayload, message: String) = ProposalStatusPayload(
        proposalId = p.proposalId,
        status = ProposalStatusPayload.REJECTED,
        message = message
    )

    private fun accept(p: ProposalSubmitPayload, winner: String) = ProposalStatusPayload(
        proposalId = p.proposalId,
        status = ProposalStatusPayload.ACCEPTED,
        winnerDocVersion = winner
    )

    /** 和 buildManifest 同一公式，但讀新鮮值而非快取：仲裁用的版本不能是舊的。 */
    private suspend fun currentDocVersion(doc: DocumentEntity): String {
        val instance = SyncIdentity.instanceId(appContext)
        val file = documentFile(doc.uri)
        val present = file?.exists() == true
        val sha = if (present) digests.sha256Hex(file!!) else null
        val size = file?.length()
        val strokes = repos.strokes.getAllStrokesForDocument(doc.uri).size
        val texts = repos.texts.getAllForDocument(doc.uri).size
        return SyncIdentity.docVersion(instance, doc.uri, strokes, sha, size, texts)
    }

    /** 解碼一個 op，並確認它操作的是提案聲稱的那份文件。 */
    private fun decodeOp(documentUri: String, op: ProposalOp): DecodedOp = when (op.op) {
        ProposalOp.UPSERT_STROKE -> {
            val swp = gson.fromJson(gson.toJson(op.stroke), StrokeWithPoints::class.java)
                ?: throw SyncRequestException("bad upsert_stroke payload")
            if (swp.stroke.documentUri != documentUri) {
                throw SyncRequestException("op targets a different document")
            }
            DecodedOp.UpsertStroke(swp)
        }
        ProposalOp.DELETE_STROKE -> {
            val id = op.id?.takeIf { it.isNotBlank() }
                ?: throw SyncRequestException("delete_stroke without id")
            DecodedOp.DeleteStroke(id)
        }
        ProposalOp.UPSERT_TEXT -> {
            val text = gson.fromJson(gson.toJson(op.text), TextAnnotationEntity::class.java)
                ?: throw SyncRequestException("bad upsert_text payload")
            if (text.documentUri != documentUri) {
                throw SyncRequestException("op targets a different document")
            }
            DecodedOp.UpsertText(text)
        }
        ProposalOp.DELETE_TEXT -> {
            val id = op.id?.takeIf { it.isNotBlank() }
                ?: throw SyncRequestException("delete_text without id")
            DecodedOp.DeleteText(id)
        }
        else -> throw SyncRequestException("unknown op ${op.op}")
    }

    private sealed interface DecodedOp {
        data class UpsertStroke(val swp: StrokeWithPoints) : DecodedOp
        data class DeleteStroke(val id: String) : DecodedOp
        data class UpsertText(val text: TextAnnotationEntity) : DecodedOp
        data class DeleteText(val id: String) : DecodedOp
    }

    /**
     * 在事務內應用一個已解碼的 op。
     *
     * upsert 靠 DAO 的 REPLACE 語義：同 id 存在即覆寫，不存在即插入，所以重送天然
     * 冪等。筆跡要先清舊點再插新點，否則改過的筆會同時留著新舊兩套點。
     */
    private suspend fun InkFlowRepositories.applyOp(op: DecodedOp) {
        when (op) {
            is DecodedOp.UpsertStroke -> {
                strokes.insertStroke(op.swp.stroke)
                strokes.deletePointsForStroke(op.swp.stroke.id)
                strokes.insertPoints(op.swp.points)
            }
            is DecodedOp.DeleteStroke -> {
                strokes.deletePointsForStroke(op.id)
                strokes.deleteStrokesByIds(listOf(op.id))
            }
            is DecodedOp.UpsertText -> texts.insert(op.text)
            is DecodedOp.DeleteText -> texts.deleteById(op.id)
        }
    }

    private fun strokeIdOf(op: ProposalOp): String? = try {
        when (op.op) {
            ProposalOp.UPSERT_STROKE ->
                gson.fromJson(gson.toJson(op.stroke), StrokeWithPoints::class.java)?.stroke?.id
            ProposalOp.UPSERT_TEXT ->
                gson.fromJson(gson.toJson(op.text), TextAnnotationEntity::class.java)?.id
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    // ─── doc_manifest ────────────────────────────────────────────────────────

    /**
     * 完整清單（§6/§7）。**必須是全量**：桌面端靠「全量裡缺席」判定刪除，所以漏列任何
     * 一份文件 = 使用者下次刪一份文件就被誤判成刪除兩份。
     */
    private suspend fun buildManifest(): List<DocumentManifestEntry> {
        val instance = SyncIdentity.instanceId(appContext)
        return repos.documents.getAllDocumentsSync().map { doc ->
            val file = documentFile(doc.uri)
            val present = file?.exists() == true
            val sha = if (present) digests.sha256Hex(file!!) else null
            val size = file?.length()
            val strokeCount = strokeCounts.count(doc.uri) {
                repos.strokes.getAllStrokesForDocument(doc.uri).size
            }
            // v4：文字數與筆數走同一套快取與預熱。理由完全相同——這兩個數字遲早都要
            // 算，而算在請求路徑上會讓桌面端的第一次 manifest 卡到撞上 60 秒
            // soTimeout，算在背景裡則幾乎是即時的。
            val textCount = textCounts.count(doc.uri) {
                repos.texts.getAllForDocument(doc.uri).size
            }
            DocumentManifestEntry(
                uri = doc.uri,
                displayName = doc.displayName,
                lastOpenedAt = doc.lastOpenedAt,
                strokeCount = strokeCount,
                filePresent = present,
                fileSha256 = sha,
                fileSize = size,
                textCount = textCount,
                // textCount 必須放進雜湊，否則「只加了一條註解」在桌面端看起來
                // 完全沒有變化，註解就永遠不會出現。這是 v4 存在的唯一理由。
                docVersion = SyncIdentity.docVersion(
                    instance, doc.uri, strokeCount, sha, size, textCount
                )
            )
        }
    }

    /**
     * 啟動後的背景預熱：把每份文件的 SHA-256 與筆數各算一次。
     *
     * 為什麼值得先付這筆成本：這兩件事遲早都要算，而**計算地點決定了誰在等**。
     * 算在請求路徑上，桌面端的第一次 manifest 會卡住好幾十秒然後撞上它的 60 秒
     * soTimeout；算在背景裡，manifest 幾乎是即時的，而平板的 UI 只是在讀自己的磁碟
     * （本來就要讀）。刻意**逐份**處理並在份與份之間讓出 I/O，避免長時間的連續讀取
     * 跟使用者的繪圖搶 flash 控制器。
     */
    private suspend fun warmUpCaches() {
        delay(WARMUP_START_DELAY_MS)
        while (scope.isActive) {
            val docs = runCatching { repos.documents.getAllDocumentsSync() }.getOrElse {
                Log.w(TAG, "warm-up could not list documents", it)
                return
            }
            for (doc in docs) {
                if (!scope.isActive) return
                documentFile(doc.uri)?.takeIf { it.isFile }?.let { digests.sha256Hex(it) }
                runCatching { repos.strokes.getAllStrokesForDocument(doc.uri) }
                    .onSuccess { strokeCounts.record(doc.uri, it.size) }
                    .onFailure { Log.d(TAG, "warm-up skipped ${doc.uri}: ${it.message}") }
                // v4：文字數一併預熱，否則第一次 manifest 會在請求路徑上等它。
                runCatching { repos.texts.getAllForDocument(doc.uri) }
                    .onSuccess { textCounts.record(doc.uri, it.size) }
                    .onFailure { Log.d(TAG, "warm-up skipped texts for ${doc.uri}: ${it.message}") }
                delay(WARMUP_GAP_MS)
            }
            return
        }
    }

    // ─── 查詢與安全閘 ─────────────────────────────────────────────────────────

    /**
     * 任何帶 `documentUri` 的動詞都必須先通過這道閘：**uri 必須真的存在於 documents 表**。
     *
     * 這不是潔癖，是必要的：[DocumentRepository] 沒有 by-uri 的單筆查詢，所以若直接拿
     * client 傳來的 uri 去組檔案路徑，它就能指定
     * `file:///data/data/com.vic.inkflow/shared_prefs/inkflow_sync_identity.xml`
     * 然後用 `file_data` 把配對碼讀出去。列在 documents 表裡的 uri 才是我們自己寫進去的
     * 值，因此可信任。
     */
    private suspend fun requireDocument(rawUri: String?): DocumentEntity {
        val uri = rawUri?.takeIf { it.isNotBlank() }
            ?: throw SyncRequestException("missing documentUri")
        return repos.documents.getAllDocumentsSync().firstOrNull { it.uri == uri }
            ?: throw SyncRequestException("unknown document")
    }

    /**
     * 縱深防禦：即使 uri 在表內，也只服務 app 私有 documents 目錄底下的實體檔。
     * 擋掉 restored row 被指向 symlink/外部路徑的情況。
     */
    private fun requireServableFile(doc: DocumentEntity): File {
        val file = documentFile(doc.uri)
            ?: throw SyncRequestException("document path is not a usable file uri")
        if (!file.isFile) return file // 缺席由呼叫端回 exists=false / EOF
        val root = servableRoot ?: throw SyncRequestException("app documents dir unavailable")
        val target = file.canonicalFileOrNull()
            ?: throw SyncRequestException("document path cannot be resolved")
        val inside = target.path == root.path ||
            target.path.startsWith(root.path + File.separator)
        if (!inside) throw SyncRequestException("refusing to serve a path outside the app documents dir")
        return target
    }

    /** `file://` URI → 實體檔。與 [com.vic.inkflow.util.BackupManager] 用同一套解析。 */
    private fun documentFile(uri: String): File? {
        val path = Uri.parse(uri).path?.takeIf { it.isNotBlank() }
            ?: uri.removePrefix("file://").takeIf { it.isNotBlank() }
            ?: return null
        return File(path)
    }

    private fun File.canonicalFileOrNull(): File? =
        runCatching { canonicalFile }.getOrNull()

    /**
     * **點的陣列順序就是 polyline 的順序**（§9），而 Room 的 `@Relation` **不保證**任何
     * 順序——它只保證「有一個順序」。不排序的話偶爾會畫出折返的筆跡，而且因為取決於
     * SQLite 的查詢計畫，這種 bug 只在某些裝置/某些資料量下出現，極難重現與回報。
     *
     * 筆劃之間的順序在協定上沒有意義（每一筆都以 id 獨立識別），但這裡仍然排序以讓
     * payload 具決定性：同一份文件重複請求要能產生位元組相同的回應，否則除錯時無法
     * 比較兩次結果。
     */
    private fun ordered(strokes: List<StrokeWithPoints>): List<StrokeWithPoints> =
        strokes
            .sortedWith(
                compareBy<StrokeWithPoints> { it.stroke.pageIndex }
                    .thenBy { it.stroke.docY ?: it.stroke.boundsTop }
                    .thenBy { it.stroke.id }
            )
            .map { it.copy(points = it.points.sortedBy { p -> p.id }) }

    // ─── 回應 ────────────────────────────────────────────────────────────────

    private fun respondJson(out: DataOutputStream, type: String?, payload: Any?) =
        SyncWire.writeText(out, gson.toJson(SyncResponse(type = type ?: "", payload = gson.toJson(payload))))

    private fun respondError(out: DataOutputStream, type: String?, message: String) =
        SyncWire.writeText(out, gson.toJson(SyncResponse(type = type ?: "", ok = false, error = message)))

    // ─── 快取 ────────────────────────────────────────────────────────────────

    /**
     * PDF SHA-256 快取，以 (路徑, 大小, mtime) 為鍵。
     *
     * 這是本檔案最重要的效能決定：一份真實文件是 300 MB 以上，雜湊一次要讀完整個檔案，
     * 而桌面端每 30 秒要一次 manifest。沒有這層快取就是「每 30 秒重讀幾 GB 磁碟」，
     * 而且檔案沒變時結果一模一樣，純粹是白燒電與 I/O。
     *
     * 失效條件是大小或 mtime 改變。`PdfManager.saveAtomically` 是 tmp + rename，所以
     * 檔案內容改變必然伴隨 (大小, mtime) 改變；檔案系統的 mtime 精度是奈秒級，
     * 「新舊檔案大小相同且 mtime 相同」的碰撞在實務上不可能。
     */
    private class FileDigestCache(private val maxEntries: Int = 512) {

        private class Entry(
            val size: Long,
            val lastModified: Long,
            val sha256: String?,
            val touchedAt: Long
        )

        private val lock = Any()
        private val cache = HashMap<String, Entry>()

        fun sha256Hex(file: File): String? {
            if (!file.exists()) return null
            val now = SystemClock.elapsedRealtime()
            val size = file.length()
            val mtime = file.lastModified()
            synchronized(lock) {
                val hit = cache[file.path]
                if (hit != null && hit.size == size && hit.lastModified == mtime) {
                    cache[file.path] = Entry(size, mtime, hit.sha256, now)
                    return hit.sha256
                }
            }
            // 慢路徑必須在鎖外跑：300 MB 的讀取不該擋住其他請求的快取查詢。
            // 兩個執行緒同時打同一個檔案會重算一次（浪費但正確），這裡不值得為它加鎖。
            val digest = SyncWire.sha256Hex(file)
            synchronized(lock) {
                prune(now)
                cache[file.path] = Entry(size, mtime, digest, now)
            }
            return digest
        }

        /** 順手清掉「檔案已不存在」與長時間沒被碰過的列，避免文件刪除後快取無界限成長。 */
        private fun prune(now: Long) {
            if (cache.size < maxEntries) return
            val iterator = cache.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (!File(entry.key).exists() || now - entry.value.touchedAt > STALE_ENTRY_MS) {
                    iterator.remove()
                }
            }
        }

        private companion object {
            const val STALE_ENTRY_MS = 10 * 60_000L
        }
    }

    /**
     * 每份文件的筆數快取。
     *
     * **這裡有個資料層的缺口值得記下來**：`StrokeRepository` 沒有
     * `suspend fun strokeCountForDocument(uri: String): Int`，而
     * `getAllStrokesForDocument` 會把**每一個點物件都配置出來**（Room 的 `@Relation`
     * 是每個父列一次子查詢，也就是 N+1）。要一個 COUNT 卻得載入整份筆跡，這是本檔案
     * 唯一一個明顯不對勁的地方。正解是補一行
     * `SELECT COUNT(*) FROM strokes WHERE documentUri = :documentUri`（見交付報告），
     * 補上之後本類別可以整個刪掉。
     *
     * 在那之前的策略是「寧可多算，不要算錯」：
     *  - [FRESH_WINDOW_MS] 內的快取直接回（吸收同一輪裡的重複請求與併發）。
     *  - 過期就**在背景重算**，並最多等 [FRESH_WAIT_MS]。通常不到 200 ms 就拿到精確值。
     *  - 真的超時（病態大文件）就回舊值——此時背景仍會跑完並更新快取，所以**最舊只差
     *    一次刷新**，下一輪 manifest 必定是準的。
     *
     * 為什麼不把視窗拉長到「超過輪詢間隔」來徹底省掉重算：那正是 §6 要消滅的假陰性。
     * 筆數是 docVersion 的輸入之一，回一個過期的筆數等於對桌面端說謊，而症狀是
     * 「使用者明明畫了東西，桌面端一直說沒變」——那種 bug 沒有任何自癒路徑。
     */
    private class StrokeCountCache(private val scope: CoroutineScope) {

        private class Entry(val count: Int, val loadedAt: Long)

        private val lock = Any()
        private val entries = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
            protected override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
                size > MAX_ENTRIES
        }
        private val inflight = HashMap<String, Deferred<Int>>()

        suspend fun count(uri: String, loader: suspend () -> Int): Int {
            val now = SystemClock.elapsedRealtime()
            var stale: Int? = null
            var pending: Deferred<Int>? = null
            var toStart: Deferred<Int>? = null
            synchronized(lock) {
                val hit = entries[uri]
                if (hit != null) {
                    if (now - hit.loadedAt <= FRESH_WINDOW_MS) return hit.count
                    stale = hit.count
                }
                val existing = inflight[uri]
                if (existing != null) {
                    pending = existing
                } else {
                    val created = scope.async(start = CoroutineStart.LAZY) { loader() }
                    created.invokeOnCompletion {
                        synchronized(lock) { if (inflight[uri] === created) inflight.remove(uri) }
                    }
                    inflight[uri] = created
                    pending = created
                    toStart = created
                }
            }
            toStart?.start() // 鎖外啟動，別讓慢查詢擋住別人的快取查詢
            val job = pending!!
            return try {
                if (stale != null) {
                    withTimeoutOrNull(FRESH_WAIT_MS) { job.await() } ?: stale
                } else {
                    job.await()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 有舊值就降級用它；沒有就讓這輪 manifest 誠實地失敗——寧可桌面端看到
                // 「Manifest error」再試一輪，也不要給它一個錯的 docVersion。
                Log.w(TAG, "stroke count failed for $uri: ${e.message}")
                stale ?: throw e
            }
        }

        /** 從筆跡 payload 取得精確筆數時順手回填。 */
        fun record(uri: String, count: Int) {
            synchronized(lock) { entries[uri] = Entry(count, SystemClock.elapsedRealtime()) }
        }

        private companion object {
            const val MAX_ENTRIES = 256
            const val FRESH_WINDOW_MS = 10_000L
            const val FRESH_WAIT_MS = 2_000L
        }
    }

    private companion object {
        /** 桌面端 soTimeout 是 60 s；我們給得比它寬，避免對端先放棄。 */
        const val SOCKET_TIMEOUT_MS = 120_000

        const val BUFFER_BYTES = 32 * 1024
        const val DEFAULT_CHUNK_BYTES = 1 shl 20

        /**
         * `file_data` 單次上限。協定只要求「不超過 client 要求的 limit」，所以回短一點
         * 完全合法（client 迴圈是 `while (written < size)` 並逐次累加 chunk 長度）。
         * 設上限是為了別讓一個惡意的 limit=2^30 逼我們配置 1 GB 陣列。
         */
        const val MAX_CHUNK_BYTES = 8 shl 20

        const val WARMUP_START_DELAY_MS = 3_000L
        const val WARMUP_GAP_MS = 250L
    }
}
