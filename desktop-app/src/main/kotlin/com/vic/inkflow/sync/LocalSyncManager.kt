package com.vic.inkflow.sync

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vic.inkflow.data.DatabaseManager
import com.vic.inkflow.data.DocumentEntity
import com.vic.inkflow.data.StrokeWithPoints
import mu.KotlinLogging
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

/** A tablet (or peer) discovered on the LAN. */
data class DiscoveredDevice(
    val deviceId: String,
    val deviceName: String,
    val ip: String,
    val transferPort: Int,
    val lastSeenMillis: Long
)

/** Result summary of one sync run, surfaced to the UI. */
data class SyncResult(
    val documentsUpdated: Int = 0,
    val strokesPulled: Int = 0,
    val filesTransferred: Int = 0,
    val conflictsSkipped: Int = 0,
    val errors: List<String> = emptyList()
)

/**
 * Local LAN sync manager (protocol v2).
 *
 * Responsibilities:
 *  - Discovery: UDP broadcast "Who is InkFlow Tablet?" on [SyncPorts.DISCOVERY_PORT],
 *    answers with our identity; remembers responding tablets.
 *  - Pull sync over TCP: document manifest diff -> conflict resolution by
 *    lastOpenedAt (newer wins) -> stroke snapshot pull -> PDF file transfer
 *    when the body is missing locally or checksums differ.
 *  - Server side: serves our local DB rows and PDF bodies so a tablet can
 *    also pull from this desktop.
 */
class LocalSyncManager(
    private val databaseManager: DatabaseManager,
    private val appDataDir: String,
    private val broadcastPort: Int = SyncPorts.DISCOVERY_PORT,
    private val transferPort: Int = SyncPorts.TRANSFER_PORT
) {

    private val gson = Gson()
    private var udpSocket: DatagramSocket? = null
    private var tcpServer: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val executor = Executors.newFixedThreadPool(4)
    private val discoveryResponded = AtomicBoolean(false)

    /** Devices discovered in the last 90 seconds. */
    @Volatile
    var devices: List<DiscoveredDevice> = emptyList()
        private set

    @Volatile
    var lastSyncResult: SyncResult? = null
        private set

    /** True while a pull-sync pass is executing. */
    @Volatile
    var isSyncing: Boolean = false
        private set

    /** Connected == at least one live tablet peer. */
    val isConnected: Boolean
        get() = devices.any { System.currentTimeMillis() - it.lastSeenMillis < 90_000 }

    private val deviceId: String by lazy { computeDeviceId(appDataDir) }
    private val deviceName: String by lazy {
        try { InetAddress.getLocalHost().hostName } catch (e: Exception) { "InkFlow-Desktop" }
    }

    /** Directory holding synced PDF bodies, mirroring Android's app-private Documents dir. */
    val documentsDir: File
        get() = File(appDataDir, SyncConstants.DOCUMENTS_SUBDIR).also { it.mkdirs() }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    fun startListening() {
        if (!running.compareAndSet(false, true)) return

        executor.submit { startUdpListener() }
        executor.submit { startTcpServer() }
        executor.submit { discoveryLoop() }

        logger.info { "Local sync manager started (discovery=$broadcastPort, transfer=$transferPort)" }
    }

    fun stopListening() {
        running.set(false)
        try { udpSocket?.close() } catch (_: Exception) {}
        try { tcpServer?.close() } catch (_: Exception) {}
        executor.shutdownNow()
        logger.info { "Local sync manager stopped" }
    }

    // ─── Discovery (UDP) ─────────────────────────────────────────────────────

    /**
     * Periodically broadcast "Who is InkFlow Tablet?" and listen for responses.
     * Tablets answer with their IP + name; we remember them as sync targets.
     */
    private fun discoveryLoop() {
        // Give the UDP listener a moment to bind before first broadcast.
        try { Thread.sleep(1_500) } catch (_: InterruptedException) { return }
        while (running.get()) {
            broadcastDiscoveryRequest()
            // Wait briefly for responses to be collected by the UDP listener.
            try { Thread.sleep(3_000) } catch (_: InterruptedException) { return }
            pruneDevices()
            // Auto-pull whenever tablets are known to be online.
            if (devices.isNotEmpty() && !isSyncing) {
                syncAllTablets()
            }
            try { Thread.sleep(25_000) } catch (_: InterruptedException) { return }
        }
    }

    private fun broadcastDiscoveryRequest() {
        try {
            val req = DiscoveryRequest(
                requesterRole = DeviceRole.DESKTOP.name.lowercase(),
                deviceId = deviceId,
                deviceName = deviceName
            )
            val bytes = gson.toJson(req).toByteArray(Charsets.UTF_8)
            val packet = DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), broadcastPort)
            udpSocket?.send(packet)
            discoveryResponded.set(false)
            logger.debug { "Broadcast: Who is InkFlow Tablet?" }
        } catch (e: Exception) {
            logger.debug(e) { "Discovery broadcast failed" }
        }
    }

    private fun startUdpListener() {
        try {
            udpSocket = DatagramSocket(broadcastPort).apply { broadcast = true }
            logger.info { "UDP discovery listener on port $broadcastPort" }
            val buf = ByteArray(8192)
            while (running.get()) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    udpSocket?.receive(packet)
                    handleUdpMessage(String(packet.data, 0, packet.length, Charsets.UTF_8), packet.address)
                } catch (e: SocketException) {
                    if (running.get()) logger.error(e) { "UDP socket error" }
                } catch (e: Exception) {
                    logger.debug(e) { "Bad UDP packet ignored" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to start UDP listener" }
        }
    }

    private fun handleUdpMessage(message: String, sender: InetAddress) {
        try {
            val data = gson.fromJson(message, Map::class.java)
            if (data["app"] != SyncConstants.APP_TAG) return
            when (data["type"]) {
                // A tablet (or desktop) asking who is out there -> identify ourselves.
                "discover" -> replyToDiscovery(sender)
                // A tablet announcing itself -> register as sync target.
                "response" -> {
                    val id = data["deviceId"] as? String ?: return
                    if (id == deviceId) return // ignore our own broadcast echo
                    val role = data["role"] as? String ?: "tablet"
                    registerDevice(
                        DiscoveredDevice(
                            deviceId = id,
                            deviceName = (data["deviceName"] as? String) ?: sender.hostAddress,
                            ip = (data["ip"] as? String) ?: sender.hostAddress,
                            transferPort = (data["transferPort"] as? Double)?.toInt() ?: transferPort,
                            lastSeenMillis = System.currentTimeMillis()
                        ),
                        asTablet = role == DeviceRole.TABLET.name.lowercase()
                    )
                }
            }
        } catch (e: Exception) {
            logger.debug(e) { "Could not parse UDP message: $message" }
        }
    }

    private fun replyToDiscovery(sender: InetAddress) {
        try {
            val resp = DiscoveryResponse(
                deviceId = deviceId,
                deviceName = deviceName,
                role = DeviceRole.DESKTOP.name.lowercase(),
                ip = localIpAddress(),
                transferPort = transferPort
            )
            val bytes = gson.toJson(resp).toByteArray(Charsets.UTF_8)
            udpSocket?.send(DatagramPacket(bytes, bytes.size, sender, broadcastPort))
        } catch (e: Exception) {
            logger.debug(e) { "Discovery reply failed" }
        }
    }

    private fun registerDevice(dev: DiscoveredDevice, asTablet: Boolean) {
        synchronized(this) {
            val others = devices.filter { it.deviceId != dev.deviceId }
            devices = if (asTablet) listOf(dev) + others else others + dev
        }
        logger.info { "Discovered ${if (asTablet) "TABLET" else "peer"}: ${dev.deviceName} @ ${dev.ip}" }
    }

    private fun pruneDevices() {
        val cutoff = System.currentTimeMillis() - 90_000
        synchronized(this) {
            devices = devices.filter { it.lastSeenMillis >= cutoff }
        }
    }

    // ─── Pull sync (client side) ─────────────────────────────────────────────

    /** Pull from every known tablet; merges results into one summary. */
    fun syncAllTablets() {
        val targets = devices.toList()
        if (targets.isEmpty()) {
            logger.info { "No tablets discovered yet; skipping sync pass." }
            return
        }
        isSyncing = true
        try {
            var merged = SyncResult()
            for (t in targets) {
                merged = merge(merged, syncWithTablet(t))
            }
            lastSyncResult = merged
            logger.info { "Sync complete: $merged" }
        } finally {
            isSyncing = false
        }
    }

    /** Manual trigger used by the UI sync button. */
    fun requestSyncNow(onComplete: (SyncResult) -> Unit = {}) {
        executor.submit {
            syncAllTablets()
            onComplete(lastSyncResult ?: SyncResult())
        }
    }

    private fun merge(a: SyncResult, b: SyncResult) = SyncResult(
        documentsUpdated = a.documentsUpdated + b.documentsUpdated,
        strokesPulled = a.strokesPulled + b.strokesPulled,
        filesTransferred = a.filesTransferred + b.filesTransferred,
        conflictsSkipped = a.conflictsSkipped + b.conflictsSkipped,
        errors = a.errors + b.errors
    )

    /**
     * Full pull-sync against one tablet:
     *  1. Fetch remote document manifest.
     *  2. For each entry: newer remote OR missing local -> pull detail + strokes.
     *  3. If the PDF body is absent locally (or checksum mismatch) -> download it.
     * Conflict rule: lastOpenedAt newer wins; equal timestamps keep local copy.
     */
    fun syncWithTablet(device: DiscoveredDevice): SyncResult {
        var updated = 0; var strokes = 0; var files = 0; var skipped = 0
        val errors = mutableListOf<String>()
        try {
            Socket(device.ip, device.transferPort).use { socket ->
                socket.soTimeout = 60_000
                val out = DataOutputStream(socket.getOutputStream())
                val input = DataInputStream(socket.getInputStream())

                handshake(out, input, device)
                val manifest = fetchManifest(out, input)

                for (entry in manifest) {
                    try {
                        val local = databaseManager.getDocument(entry.uri)
                        val remoteNewer = local == null || entry.lastOpenedAt > local.lastOpenedAt

                        if (!remoteNewer) {
                            // Local copy is current: only make sure the file body exists.
                            if (!entry.filePresent) continue
                            if (needsFileDownload(local, entry)) {
                                if (downloadFile(out, input, entry.uri)) files++
                            }
                            skipped++
                            continue
                        }

                        // Remote wins -> pull full document + stroke snapshot.
                        val (doc, docStrokes) = fetchDocumentDetail(out, input, entry.uri)
                        databaseManager.saveDocument(doc)
                        databaseManager.replaceStrokesForDocument(entry.uri, docStrokes)
                        updated++
                        strokes += docStrokes.size

                        // Ensure the PDF body exists locally.
                        if (needsFileDownload(doc, entry)) {
                            if (downloadFile(out, input, entry.uri)) files++
                            else errors.add("File missing for ${entry.displayName}")
                        }
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to sync document ${entry.uri}" }
                        errors.add("${entry.uri}: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Sync with ${device.deviceName} failed" }
            errors.add("${device.deviceName}: ${e.message}")
        }
        return SyncResult(updated, strokes, files, skipped, errors)
    }

    private fun needsFileDownload(local: DocumentEntity?, entry: DocumentManifestEntry): Boolean {
        if (!entry.filePresent) return false
        // Resolve the body the desktop would actually OPEN (documentsDir mirror
        // first, then the raw path). If that file is missing or its checksum
        // differs from the tablet's, we must (re-)download it.
        val f = localBodyFile(local) ?: return false
        if (!f.exists()) return true
        // If we have the file but checksum differs, re-download (tablet edited the body).
        val remoteHash = entry.fileSha256 ?: return false
        val localHash = SyncWire.sha256Hex(f)
        return localHash != null && remoteHash != localHash
    }

    /**
     * The file the desktop will render for this document row.
     *
     * Mirror-first policy: synced PDF bodies always live at
     * `documentsDir/<fileName>` (that is where [downloadFile] writes them and
     * where the reader looks for them). We deliberately do NOT fall back to
     * the raw tablet-side absolute path when deciding whether a download is
     * needed — otherwise a path that happens to exist on this machine (shared
     * dev folders, or an on-disk tablet emulator) would suppress the download
     * and leave the desktop without its own copy.
     */
    private fun localBodyFile(doc: DocumentEntity?): File? {
        val path = doc?.localPath?.takeIf { it.isNotBlank() } ?: return null
        return mirrorFileFor(path)
    }

    /** Maps any (tablet-side) absolute path onto our local mirror layout. */
    private fun mirrorFileFor(path: String): File =
        File(documentsDir, File(path).name)

    /**
     * Resolve a path for serving/opening: prefer our mirror copy, fall back to
     * the raw path only when we have never downloaded the body ourselves
     * (e.g. the file genuinely lives on this machine).
     */
    private fun resolveLocalFile(path: String): File {
        val mirrored = mirrorFileFor(path)
        return if (mirrored.exists()) mirrored else File(path)
    }

    private fun handshake(out: DataOutputStream, input: DataInputStream, device: DiscoveredDevice) {
        SyncWire.writeText(out, gson.toJson(SyncRequest(SyncRequest.TYPE_HANDSHAKE, deviceId)))
        val resp = readResponse(input) ?: throw IOException("No handshake response")
        if (!resp.ok) throw IOException("Handshake rejected: ${resp.error}")
        // Refresh last-seen.
        registerDevice(device.copy(lastSeenMillis = System.currentTimeMillis()), asTablet = true)
    }

    private fun fetchManifest(out: DataOutputStream, input: DataInputStream): List<DocumentManifestEntry> {
        SyncWire.writeText(out, gson.toJson(SyncRequest(SyncRequest.TYPE_DOC_MANIFEST, deviceId)))
        val resp = readResponse(input) ?: throw IOException("No manifest response")
        if (!resp.ok) throw IOException("Manifest error: ${resp.error}")
        val type = object : TypeToken<List<DocumentManifestEntry>>() {}.type
        return gson.fromJson(resp.payload ?: "[]", type)
    }

    private fun fetchDocumentDetail(
        out: DataOutputStream, input: DataInputStream, uri: String
    ): Pair<DocumentEntity, List<StrokeWithPoints>> {
        SyncWire.writeText(
            out,
            gson.toJson(SyncRequest(SyncRequest.TYPE_DOCUMENT_DETAIL, deviceId, documentUri = uri))
        )
        val resp = readResponse(input) ?: throw IOException("No detail response")
        if (!resp.ok) throw IOException("Detail error: ${resp.error}")
        val payload = gson.fromJson(resp.payload, DocumentDetailPayload::class.java)
        val doc = gson.fromJson(gson.toJson(payload.document), DocumentEntity::class.java)
            ?: throw IOException("Null document in detail payload")
        val type = object : TypeToken<List<StrokeWithPoints>>() {}.type
        val strokes = gson.fromJson<List<StrokeWithPoints>>(gson.toJson(payload.strokes), type)
            ?: emptyList()
        return doc to strokes
    }

    /**
     * Download the PDF body for [uri] into documentsDir/<filename>.
     *
     * IMPORTANT: the document row's `uri` is the cross-device primary key and
     * MUST stay byte-identical to the tablet's value, otherwise the next
     * manifest diff would no longer match and every sync would re-pull the
     * document. Only the derived `localPath` column is rewritten to point at
     * our local mirror copy.
     */
    private fun downloadFile(out: DataOutputStream, input: DataInputStream, uri: String): Boolean {
        // 1. meta
        SyncWire.writeText(out, gson.toJson(SyncRequest(SyncRequest.TYPE_FILE_META, deviceId, documentUri = uri)))
        val metaResp = readResponse(input) ?: return false
        if (!metaResp.ok) return false
        val meta = gson.fromJson(metaResp.payload, FileMetaPayload::class.java)
        if (!meta.exists) {
            logger.warn { "Remote has no file body for $uri (annotations still synced)" }
            return false
        }

        // 2. stream binary in chunks to a temp file
        val fileName = File(uri.removePrefix("file://")).name.ifBlank { "document.pdf" }
        val dest = File(documentsDir, fileName)
        val tmp = File(documentsDir, "$fileName.part")
        var written = 0L
        tmp.outputStream().use { fos ->
            while (written < meta.size) {
                val want = minOf(1L shl 20, meta.size - written) // 1 MiB chunks
                SyncWire.writeText(
                    out,
                    gson.toJson(SyncRequest(SyncRequest.TYPE_FILE_DATA, deviceId, documentUri = uri, offset = written, limit = want.toInt()))
                )
                val chunk = SyncWire.readFrame(input) ?: break
                fos.write(chunk)
                written += chunk.size
                if (chunk.isEmpty()) break
            }
        }

        // 3. verify checksum
        val actual = SyncWire.sha256Hex(tmp)
        if (meta.sha256 != null && actual != meta.sha256) {
            tmp.delete()
            logger.error { "Checksum mismatch downloading $fileName ($actual != ${meta.sha256})" }
            return false
        }
        if (dest.exists()) dest.delete()
        tmp.renameTo(dest)

        // 4. uri (the cross-device primary key) is intentionally NOT rewritten.
        //    The desktop resolves the body via the documentsDir mirror by file
        //    name (see resolveLocalFile), so no DB row change is needed here.
        logger.info { "Downloaded $fileName (${dest.length()} bytes)" }
        return true
    }

    private fun readResponse(input: DataInputStream): SyncResponse? {
        val text = SyncWire.readText(input) ?: return null
        return gson.fromJson(text, SyncResponse::class.java)
    }

    // ─── Server side (tablet pulls from us / pushes to us) ───────────────────

    private fun startTcpServer() {
        try {
            tcpServer = ServerSocket(transferPort).apply { reuseAddress = true }
            logger.info { "TCP sync server on port $transferPort" }
            while (running.get()) {
                try {
                    val client = tcpServer?.accept() ?: break
                    executor.submit { serveClient(client) }
                } catch (e: SocketException) {
                    if (running.get()) logger.error(e) { "TCP accept error" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to start TCP server" }
        }
    }

    private fun serveClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 120_000
            val input = DataInputStream(s.getInputStream())
            val out = DataOutputStream(s.getOutputStream())
            try {
                while (running.get()) {
                    val reqText = SyncWire.readText(input) ?: break
                    val req = gson.fromJson(reqText, SyncRequest::class.java)
                    handleServerRequest(req, input, out)
                }
            } catch (e: Exception) {
                logger.debug(e) { "Client session ended" }
            }
        }
    }

    private fun handleServerRequest(req: SyncRequest, input: DataInputStream, out: DataOutputStream) {
        try {
            when (req.type) {
                SyncRequest.TYPE_HANDSHAKE ->
                    ack(out, req.type)

                SyncRequest.TYPE_DOC_MANIFEST -> {
                    val manifest = databaseManager.getAllDocuments().map { doc ->
                        val file = doc.localPath.takeIf { it.isNotBlank() }?.let { resolveLocalFile(it) }
                        DocumentManifestEntry(
                            uri = doc.uri,
                            displayName = doc.displayName,
                            lastOpenedAt = doc.lastOpenedAt,
                            strokeCount = databaseManager.strokeCountForDocument(doc.uri),
                            filePresent = file?.exists() == true,
                            fileSha256 = file?.let { SyncWire.sha256Hex(it) },
                            fileSize = file?.length()
                        )
                    }
                    respondJson(out, req.type, manifest)
                }

                SyncRequest.TYPE_DOCUMENT_DETAIL -> {
                    val uri = req.documentUri ?: throw IllegalArgumentException("missing documentUri")
                    val doc = databaseManager.getDocument(uri)
                        ?: throw IllegalArgumentException("unknown document $uri")
                    val strokes = databaseManager.getAllStrokesForDocument(uri)
                    respondJson(out, req.type, DocumentDetailPayload(doc, strokes))
                }

                SyncRequest.TYPE_STROKE_DELTA -> {
                    val uri = req.documentUri ?: throw IllegalArgumentException("missing documentUri")
                    respondJson(out, req.type, StrokeDeltaPayload(uri, databaseManager.getAllStrokesForDocument(uri)))
                }

                SyncRequest.TYPE_STROKE_PAGE -> {
                    val uri = req.documentUri ?: throw IllegalArgumentException("missing documentUri")
                    val page = req.pageIndex ?: 0
                    respondJson(
                        out, req.type,
                        StrokeDeltaPayload(uri, databaseManager.getStrokesForPage(uri, page))
                    )
                }

                SyncRequest.TYPE_FILE_META -> {
                    val uri = req.documentUri ?: throw IllegalArgumentException("missing documentUri")
                    val file = uri.removePrefix("file://").takeIf { it.isNotBlank() }?.let { resolveLocalFile(it) }
                    val present = file?.exists() == true
                    respondJson(
                        out, req.type,
                        FileMetaPayload(present, if (present) file!!.length() else 0, if (present) SyncWire.sha256Hex(file!!) else null)
                    )
                }

                SyncRequest.TYPE_FILE_DATA -> {
                    val uri = req.documentUri ?: throw IllegalArgumentException("missing documentUri")
                    val offset = req.offset ?: 0L
                    val limit = req.limit ?: (1 shl 20)
                    val file = resolveLocalFile(uri.removePrefix("file://"))
                    if (!file.exists()) {
                        SyncWire.writeFrame(out, ByteArray(0))
                    } else {
                        RandomAccessFileAccessor.readChunk(file, offset, limit).let { SyncWire.writeFrame(out, it) }
                    }
                }

                else -> respondError(out, req.type, "unknown request type ${req.type}")
            }
        } catch (e: Exception) {
            logger.error(e) { "Error serving ${req.type}" }
            try { respondError(out, req.type, e.message ?: "server error") } catch (_: Exception) {}
        }
    }

    private fun ack(out: DataOutputStream, type: String) = respondRaw(out, SyncResponse(type))

    private fun respondJson(out: DataOutputStream, type: String, payload: Any?) =
        respondRaw(out, SyncResponse(type, payload = gson.toJson(payload)))

    private fun respondError(out: DataOutputStream, type: String, msg: String) =
        respondRaw(out, SyncResponse(type, ok = false, error = msg))

    private fun respondRaw(out: DataOutputStream, resp: SyncResponse) =
        SyncWire.writeText(out, gson.toJson(resp))

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun localIpAddress(): String = try {
        InetAddress.getLocalHost().hostAddress
    } catch (e: Exception) {
        "127.0.0.1"
    }

    companion object {
        /** Stable per-install device id (persisted in the app data dir). */
        fun computeDeviceId(appDataDir: String? = null): String {
            if (appDataDir != null) {
                val f = File(appDataDir, "device.id")
                if (f.exists()) {
                    val stored = f.readText().trim()
                    if (stored.isNotEmpty()) return stored
                }
            }
            val hostname = try { InetAddress.getLocalHost().hostName } catch (_: Exception) { "desktop" }
            val id = "pc-" + java.util.UUID.randomUUID().toString().take(8)
            logger.info { "Generated device id $id for host $hostname" }
            return id
        }
    }

    init {
        // Persist device id once app data dir exists.
        executor.submit {
            try {
                val dir = File(appDataDir).apply { mkdirs() }
                val f = File(dir, "device.id")
                if (!f.exists()) f.writeText(deviceId)
            } catch (e: Exception) {
                logger.debug(e) { "Could not persist device id" }
            }
        }
    }
}

/** Small helper to stream file chunks without loading whole PDFs into memory. */
internal object RandomAccessFileAccessor {
    fun readChunk(file: File, offset: Long, maxBytes: Int): ByteArray {
        java.io.RandomAccessFile(file, "r").use { raf ->
            if (offset >= raf.length()) return ByteArray(0)
            raf.seek(offset)
            val size = minOf(maxBytes.toLong(), raf.length() - offset).toInt()
            val buf = ByteArray(size)
            raf.readFully(buf)
            return buf
        }
    }
}

private typealias IOException = java.io.IOException
