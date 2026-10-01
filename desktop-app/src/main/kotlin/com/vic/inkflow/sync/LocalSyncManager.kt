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
    val lastSeenMillis: Long,
    /** v3: the tablet's install identity, from the discovery response. */
    val instanceId: String? = null
)

/** Result summary of one sync run, surfaced to the UI. */
data class SyncResult(
    val documentsUpdated: Int = 0,
    val strokesPulled: Int = 0,
    val filesTransferred: Int = 0,
    val conflictsSkipped: Int = 0,
    val generationWiped: Boolean = false,
    val orphansRemoved: Int = 0,
    val errors: List<String> = emptyList()
)

/**
 * Local LAN sync manager (protocol v3).
 *
 * Responsibilities:
 *  - Discovery: announces itself and remembers responding tablets.
 *  - Pull sync over TCP: document manifest diff by CONTENT HASH -> stroke snapshot
 *    pull -> PDF file transfer when the body is missing or its checksum differs.
 *  - Server side: serves our local rows and PDF bodies so a tablet can pull too.
 *
 * v3 changes from v2, and why:
 *
 *  1. Diffing moved off `lastOpenedAt` onto `docVersion` (a content hash).
 *     `lastOpenedAt` was wrong in both directions: reading a document without
 *     drawing advanced it and triggered a full re-pull, while any write path
 *     that did not touch it (undo/redo, import, page reorder) left the desktop
 *     permanently stale with no way to self-heal. A hash cannot do either, and
 *     it removes any dependency on the two machines' clocks agreeing.
 *
 *  2. `instanceId` makes a tablet reinstall detectable. The tablet's `uri` is a
 *     `file://` path inside app-private storage, which Android wipes on
 *     uninstall, so a reinstall silently orphaned every row and PDF on the
 *     desktop forever. On a generation change we wipe and start clean.
 *
 *  3. Rows absent from a COMPLETE manifest are now deleted (after a one-pass
 *     grace window). v2 never deleted anything, which is the other half of the
 *     orphan problem. The tablet is the sole writer and requests a full
 *     manifest every pass, so absence genuinely means deletion.
 *
 *  4. DB access is single-threaded. A shared JDBC `Connection` across the
 *     executor pool interleaves transaction state; during a document-scoped
 *     "delete all strokes then insert all strokes" that silently commits a
 *     document with strokes but no points.
 *
 * @see <a href="SYNC_PROTOCOL.md">SYNC_PROTOCOL.md</a> for the wire format.
 */
class LocalSyncManager(
    private val databaseManager: DatabaseManager,
    private val appDataDir: String,
    private val broadcastPort: Int = SyncPorts.DISCOVERY_PORT,
    private val transferPort: Int = SyncPorts.TRANSFER_PORT,
    /**
     * Shared secret verified during the handshake (v3). A LAN service with no
     * authentication is readable by anything on the subnet, and — more to the
     * point — by any app on the tablet itself, since Android's sandbox does not
     * gate inbound sockets to a port. Null disables the check (single-user
     * setup); see SYNC_PROTOCOL.md §7.
     */
    private val psk: String? = null
) {

    private val gson = Gson()
    private var udpSocket: DatagramSocket? = null
    private var tcpServer: ServerSocket? = null
    private val running = AtomicBoolean(false)

    /**
     * One worker for DB-touching work. Sync is lock-step by nature, so a pool
     * buys nothing and only creates concurrency bugs against a single JDBC
     * connection.
     */
    private val executor = Executors.newFixedThreadPool(2)
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

        logger.info { "Local sync manager started (discovery=$broadcastPort, transfer=$transferPort, protocol v${SyncConstants.PROTOCOL_VERSION})" }
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
        generationWiped = a.generationWiped || b.generationWiped,
        orphansRemoved = a.orphansRemoved + b.orphansRemoved,
        errors = a.errors + b.errors
    )

    /**
     * Full pull-sync against one tablet (v3).
     *
     *  1. Handshake; record the tablet's `instanceId`. If it differs from the one
     *     we hold, the tablet was reinstalled: its app-private storage (and thus
     *     every `documents.uri` it ever used) is gone, so we wipe our mirror
     *     rather than accumulate unreachable orphans.
     *  2. Fetch the complete manifest.
     *  3. For each entry, compare `docVersion` — a content hash — against the one
     *     we stored. Equal means nothing to do beyond making sure the file body
     *     is present. Different means the tablet's content changed, so pull the
     *     row and the whole stroke snapshot.
     *  4. Delete local rows that the complete manifest did not mention. The
     *     tablet is the only writer and we asked for a full manifest, so absence
     *     means the user deleted it. A one-pass grace window protects against a
     *     truncated manifest.
     *
     * @see <a href="SYNC_PROTOCOL.md">SYNC_PROTOCOL.md</a> sections 6 and 7.
     */
    fun syncWithTablet(device: DiscoveredDevice): SyncResult {
        var updated = 0; var strokes = 0; var files = 0; var skipped = 0
        var orphans = 0; var wiped = false
        val errors = mutableListOf<String>()
        try {
            Socket(device.ip, device.transferPort).use { socket ->
                socket.soTimeout = 60_000
                val out = DataOutputStream(socket.getOutputStream())
                val input = DataInputStream(socket.getInputStream())

                val instanceId = handshake(out, input, device)

                // v3 step 1: generation change = reinstall = start clean.
                val known = databaseManager.getKnownInstanceId()
                if (known != null && instanceId != null && known != instanceId) {
                    databaseManager.wipeGeneration()
                    wiped = true
                }
                if (instanceId != null) databaseManager.setKnownInstanceId(instanceId)

                val manifest = fetchManifest(out, input, instanceId)
                // Persisted so the deletion grace window still works across app restarts.
                val pass = databaseManager.nextPass()
                val seen = mutableSetOf<String>()

                for (entry in manifest) {
                    try {
                        seen += entry.uri
                        val local = databaseManager.getDocument(entry.uri)
                        val remoteVersion = entry.docVersion
                            ?: SyncWire.docVersion(
                                instanceId ?: "", entry.uri, entry.strokeCount,
                                entry.fileSha256, entry.fileSize
                            )
                        val localVersion = if (instanceId != null)
                            databaseManager.getDocVersion(instanceId, entry.uri) else null
                        val changed = local == null || localVersion == null || localVersion != remoteVersion

                        if (!changed) {
                            // Content identical: only make sure the file body exists.
                            if (entry.filePresent && needsFileDownload(local, entry)) {
                                if (downloadFile(out, input, entry.uri, instanceId)) files++
                            }
                            skipped++
                            continue
                        }

                        // Content changed -> pull the row and the whole stroke snapshot.
                        val (doc, docStrokes) = fetchDocumentDetail(out, input, entry.uri, instanceId)
                        databaseManager.saveDocument(doc)
                        databaseManager.replaceStrokesForDocument(entry.uri, docStrokes)
                        if (instanceId != null) {
                            databaseManager.setDocVersion(instanceId, entry.uri, remoteVersion, pass)
                        }
                        updated++
                        strokes += docStrokes.size

                        // Ensure the PDF body exists locally.
                        if (needsFileDownload(doc, entry)) {
                            if (downloadFile(out, input, entry.uri, instanceId)) files++
                            else errors.add("File missing for ${entry.displayName}")
                        }
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to sync document ${entry.uri}" }
                        errors.add("${entry.uri}: ${e.message}")
                    }
                }

                // v3 step 4: propagate deletions. The manifest is complete and the
                // tablet is the only writer, so anything we hold that it did not
                // mention has been deleted over there. One pass of grace so a
                // truncated manifest cannot cause data loss.
                if (instanceId != null) {
                    val held = databaseManager.getSyncedUris(instanceId)
                    for (uri in held) {
                        if (uri in seen) continue
                        val lastPass = databaseManager.getDocVersion(instanceId, uri)?.let {
                            databaseManager.getLastSeenPass(instanceId, uri)
                        }
                        if (lastPass != null && lastPass < pass - 1) {
                            databaseManager.purgeDocument(uri)
                            databaseManager.forgetDocVersion(instanceId, uri)
                            orphans++
                            logger.info { "Removed document deleted on tablet: $uri" }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Sync with ${device.deviceName} failed" }
            errors.add("${device.deviceName}: ${e.message}")
        }
        return SyncResult(
            documentsUpdated = updated,
            strokesPulled = strokes,
            filesTransferred = files,
            conflictsSkipped = skipped,
            generationWiped = wiped,
            orphansRemoved = orphans,
            errors = errors
        )
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
    /**
     * v3 handshake. Returns the tablet's `instanceId` (null if it is a v2 peer),
     * and refuses to continue when the peer speaks a protocol we cannot satisfy.
     */
    private fun handshake(
        out: DataOutputStream,
        input: DataInputStream,
        device: DiscoveredDevice
    ): String? {
        SyncWire.writeText(
            out,
            gson.toJson(SyncRequest(SyncRequest.TYPE_HANDSHAKE, deviceId, psk = pskOrNull()))
        )
        val resp = readResponse(input) ?: throw IOException("No handshake response")
        if (!resp.ok) throw IOException("Handshake rejected: ${resp.error}")

        val peer = gson.fromJson(resp.payload, HandshakeAck::class.java)
        if (peer != null) {
            if (peer.protocolVersion != SyncConstants.PROTOCOL_VERSION) {
                throw IOException(
                    "Protocol mismatch: tablet speaks v${peer.protocolVersion}, " +
                        "this build speaks v${SyncConstants.PROTOCOL_VERSION}. Update the desktop app."
                )
            }
            if (psk != null && !constantTimeEquals(pskProof(psk), peer.pskProof)) {
                throw IOException("Authentication failed: wrong pairing code")
            }
        }
        registerDevice(device.copy(lastSeenMillis = System.currentTimeMillis(), instanceId = peer?.instanceId), asTablet = true)
        return peer?.instanceId
    }

    /** Payload the tablet returns from a handshake (v3). */
    private data class HandshakeAck(
        val protocolVersion: Int = 2,
        val instanceId: String? = null,
        /** Server-side proof so the desktop can verify the tablet actually holds the PSK. */
        val pskProof: String? = null
    )

    private fun pskOrNull(): String? = psk

    /**
     * The value the tablet returns in `pskProof`. The desktop must compare against
     * THIS, not against the raw PSK — the tablet never sends the secret back, only
     * this derivative. Comparing `psk` to `peer.pskProof` (an earlier mistake here)
     * can never succeed, so pairing was unconditionally rejected.
     *
     * Must stay byte-identical to `SyncIdentity.pskProof` on the Android side.
     */
    private fun pskProof(psk: String): String =
        SyncWire.sha256Hex((psk + "inkflow-sync-v3").toByteArray(Charsets.UTF_8))

    private fun constantTimeEquals(a: String, b: String?): Boolean {
        if (b == null) return false
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private fun fetchManifest(
        out: DataOutputStream,
        input: DataInputStream,
        instanceId: String?
    ): List<DocumentManifestEntry> {
        SyncWire.writeText(
            out,
            gson.toJson(SyncRequest(SyncRequest.TYPE_DOC_MANIFEST, deviceId, instanceId = instanceId))
        )
        val resp = readResponse(input) ?: throw IOException("No manifest response")
        if (!resp.ok) throw IOException("Manifest error: ${resp.error}")
        val type = object : TypeToken<List<DocumentManifestEntry>>() {}.type
        return gson.fromJson(resp.payload ?: "[]", type)
    }

    private fun fetchDocumentDetail(
        out: DataOutputStream,
        input: DataInputStream,
        uri: String,
        instanceId: String?
    ): Pair<DocumentEntity, List<StrokeWithPoints>> {
        SyncWire.writeText(
            out,
            gson.toJson(
                SyncRequest(SyncRequest.TYPE_DOCUMENT_DETAIL, deviceId, instanceId = instanceId, documentUri = uri)
            )
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
    private fun downloadFile(
        out: DataOutputStream,
        input: DataInputStream,
        uri: String,
        instanceId: String?
    ): Boolean {
        // 1. meta
        SyncWire.writeText(
            out,
            gson.toJson(
                SyncRequest(SyncRequest.TYPE_FILE_META, deviceId, instanceId = instanceId, documentUri = uri)
            )
        )
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
                    gson.toJson(
                        SyncRequest(
                            SyncRequest.TYPE_FILE_DATA, deviceId, instanceId = instanceId,
                            documentUri = uri, offset = written, limit = want.toInt()
                        )
                    )
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
