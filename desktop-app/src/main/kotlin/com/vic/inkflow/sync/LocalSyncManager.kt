package com.vic.inkflow.sync

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vic.inkflow.data.DatabaseManager
import com.vic.inkflow.data.DocumentEntity
import com.vic.inkflow.data.StrokeWithPoints
import com.vic.inkflow.data.TextAnnotationEntity
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
    /** v4: text annotations received. Reported separately from strokes because
     *  they are a different table and a user needs to be able to tell "my notes
     *  did not arrive" from "my ink did not arrive". */
    val textsPulled: Int = 0,
    /**
     * v5: proposals the tablet accepted. This is the number that tells the user
     * "your desktop edits reached the tablet" — without it a sync can report all
     * green while every local edit silently stayed local.
     */
    val proposalsAccepted: Int = 0,
    /**
     * v5: queued proposals dropped because the tablet had moved on. Each one means
     * local edits were overwritten; the detail lives in the queue's notices, which
     * the UI drains into the sync summary.
     */
    val proposalConflicts: Int = 0,
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
     *
     * Mutable because the user types the tablet's code in Settings after the app
     * has already started listening — recreating the whole manager (and its two
     * sockets) just to change one secret would drop any connection in flight.
     */
    psk: String? = null,
    /**
     * v5 proposal outbox, shared with the editor. Null disables pushing: the
     * manager stays pull-only and local edits are never sent. Passed in rather
     * than constructed here so the editor and the sync loop share one queue —
     * two queues would each believe they own the ops.
     */
    val proposalQueue: ProposalQueue? = null
) {

    @Volatile
    private var pairingCode: String? = psk?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Set (or clear, with null/blank) the pairing code at runtime. Takes effect on
     * the next handshake; an already-open session keeps using the code it
     * authenticated with, which is correct — re-authenticating mid-transfer would
     * only risk aborting a sync for no security gain.
     */
    fun setPairingCode(code: String?) {
        pairingCode = code?.trim()?.takeIf { it.isNotEmpty() }
        logger.info {
            if (pairingCode == null) "pairing code cleared; handshakes will be sent unauthenticated"
            else "pairing code updated; it takes effect on the next handshake"
        }
    }

    private val gson = Gson()
    private var udpSocket: DatagramSocket? = null
    private var tcpServer: ServerSocket? = null
    private val running = AtomicBoolean(false)

    /**
     * One worker for DB-touching work. Sync is lock-step by nature, so a pool
     * buys nothing and only creates concurrency bugs against a single JDBC
     * connection.
     */
    /**
     * Retained for compatibility with existing callers, but no longer used for the
     * listeners — see [startListening]. Sync work runs lock-step against a single
     * JDBC connection, so the DB lock is what guarantees safety, not pool width.
     */
    private val executor = Executors.newFixedThreadPool(2)

    /**
     * Separate pool for inbound sessions.
     *
     * This used to share [executor], which meant the server could never actually
     * serve anyone: `startUdpListener` and `startTcpServer` each block forever on
     * their own thread, filling both slots, so `serveClient` submissions queued
     * and never ran. The TCP socket still accepted the connection, so the client
     * hung waiting for a response that no thread was ever going to write — which
     * looks like a network fault rather than a dead pool.
     *
     * DB access is still serialised by `DatabaseManager`'s own lock, so a wider
     * pool here does not introduce concurrency against the single connection.
     */
    private val sessionExecutor = Executors.newCachedThreadPool()

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

        // Own threads, not [executor]. Both listeners block for the lifetime of the app,
        // so submitting them to a 2-slot pool would fill it completely and leave
        // [discoveryLoop] queued forever — which silently disables auto-sync, since
        // nothing else ever drives it. It would also starve [sessionExecutor]'s
        // callers if they shared the pool.
        Thread({ runCatching { startUdpListener() } }, "inkflow-sync-udp").apply { isDaemon = true }.start()
        Thread({ runCatching { startTcpServer() } }, "inkflow-sync-tcp").apply { isDaemon = true }.start()
        Thread({ runCatching { discoveryLoop() } }, "inkflow-sync-discovery").apply { isDaemon = true }.start()

        logger.info { "Local sync manager started (discovery=$broadcastPort, transfer=$transferPort, protocol v${SyncConstants.PROTOCOL_VERSION})" }
    }

    fun stopListening() {
        running.set(false)
        try { udpSocket?.close() } catch (_: Exception) {}
        try { tcpServer?.close() } catch (_: Exception) {}
        executor.shutdownNow()
        sessionExecutor.shutdownNow()
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
        textsPulled = a.textsPulled + b.textsPulled,
        proposalsAccepted = a.proposalsAccepted + b.proposalsAccepted,
        proposalConflicts = a.proposalConflicts + b.proposalConflicts,
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
        var texts = 0
        var proposalsAccepted = 0; var proposalConflicts = 0
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
                                entry.fileSha256, entry.fileSize, entry.textCount
                            )
                        val localVersion = if (instanceId != null)
                            databaseManager.getDocVersion(instanceId, entry.uri) else null
                        val changed = local == null || localVersion == null || localVersion != remoteVersion

                        // v5: push before pull. When our queued ops' base matches what the
                        // tablet reports, the tablet has not moved under us: sending first
                        // means the pull below brings back a state that already includes
                        // our edits, instead of wiping them and calling it a conflict.
                        // When the base does NOT match, the outcome is already decided —
                        // sending would only waste a round trip learning it.
                        val push = tryPushProposal(out, input, entry, instanceId, remoteVersion, pass)
                        when (push) {
                            // Accepted: the tablet now holds our edits. The stored version
                            // jumps straight to the winner, so the pull below would be a
                            // no-op fetch of content we already have — skip it.
                            PushOutcome.SENT_ACCEPTED -> {
                                proposalsAccepted++
                                continue
                            }
                            // The tablet moved first: our edits are already gone from the
                            // local rows (the pull below replaces them), so drop the dead
                            // ops loudly and let the pull proceed.
                            PushOutcome.CONFLICT -> {
                                proposalConflicts++
                            }
                            // Transport died mid-proposal: the tablet may or may not have
                            // applied it. Keep the ops (same proposalId, so a retry is
                            // idempotent) and skip the pull for this document — pulling
                            // now could overwrite edits we are about to successfully send.
                            PushOutcome.TRANSPORT_FAILED -> {
                                continue
                            }
                            PushOutcome.NOTHING_QUEUED -> Unit
                        }

                        if (!changed) {
                            // Content identical: only make sure the file body exists.
                            if (entry.filePresent && needsFileDownload(local, entry)) {
                                if (downloadFile(out, input, entry.uri, instanceId)) files++
                            }
                            skipped++
                            continue
                        }

                        // Content changed -> pull the row and the whole stroke snapshot.
                        val (doc, docStrokes, docTexts) =
                            fetchDocumentDetail(out, input, entry.uri, instanceId)
                        databaseManager.saveDocument(doc)
                        databaseManager.replaceStrokesForDocument(entry.uri, docStrokes)
                        // Notes are replaced wholesale for the same reason strokes are:
                        // the manifest is complete and the tablet is the only writer,
                        // so anything we still hold that is not in the payload is gone
                        // over there. A merge would keep deleted notes alive forever.
                        databaseManager.replaceTextAnnotationsForDocument(entry.uri, docTexts)
                        if (instanceId != null) {
                            databaseManager.setDocVersion(instanceId, entry.uri, remoteVersion, pass)
                        }
                        updated++
                        strokes += docStrokes.size
                        texts += docTexts.size

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
            textsPulled = texts,
            proposalsAccepted = proposalsAccepted,
            proposalConflicts = proposalConflicts,
            errors = errors
        )
    }

    /**
     * v5 push step for one manifest entry. Returns what the pull step should do.
     *
     * The handshake already guarantees a v5 peer, so reaching here means the tablet
     * understands proposals — no version check needed per document.
     */
    private enum class PushOutcome {
        /** Nothing queued for this document; proceed with the pull as usual. */
        NOTHING_QUEUED,
        /** Accepted; stored version already advanced, pull is a no-op — skip it. */
        SENT_ACCEPTED,
        /** Tablet had moved on; ops dropped loudly, let the pull proceed. */
        CONFLICT,
        /** Transport died; ops kept for retry, skip the pull to protect them. */
        TRANSPORT_FAILED
    }

    private fun tryPushProposal(
        out: DataOutputStream,
        input: DataInputStream,
        entry: DocumentManifestEntry,
        instanceId: String?,
        remoteVersion: String,
        pass: Long
    ): PushOutcome {
        val queue = proposalQueue ?: return PushOutcome.NOTHING_QUEUED
        val pending = queue.takeForSend(entry.uri) ?: return PushOutcome.NOTHING_QUEUED
        if (pending.ops.isEmpty()) return PushOutcome.NOTHING_QUEUED

        if (pending.baseDocVersion != remoteVersion) {
            // Known stale before a single byte is sent: the tablet's manifest already
            // says it moved on. Sending would only waste the round trip to learn that.
            queue.onConflict(
                entry.uri, pending.ops.size,
                "平板已有新內容（${shortVersion(remoteVersion)}）"
            )
            logger.info { "Proposal for ${entry.uri} known stale; dropping ${pending.ops.size} ops" }
            return PushOutcome.CONFLICT
        }

        val status = try {
            submitProposal(out, input, pending, instanceId)
        } catch (e: Exception) {
            logger.error(e) { "Proposal submit failed for ${entry.uri}; keeping ${pending.ops.size} ops for retry" }
            return PushOutcome.TRANSPORT_FAILED
        }

        return when (status.status) {
            ProposalStatusPayload.ACCEPTED -> {
                val winner = status.winnerDocVersion ?: remoteVersion
                queue.onAccepted(entry.uri, pending.ops.maxOf { it.first }, winner, instanceId ?: "")
                if (instanceId != null) {
                    databaseManager.setDocVersion(instanceId, entry.uri, winner, pass)
                }
                logger.info { "Proposal ${pending.proposalId} accepted for ${entry.uri}" }
                PushOutcome.SENT_ACCEPTED
            }
            else -> {
                // conflict_stale (lost a race after the manifest), rejected (the tablet
                // refused, e.g. generation changed), or unknown: the ops describe a dead
                // base either way, so keeping them only retries a known failure.
                val reason = status.message ?: status.status
                queue.onConflict(entry.uri, pending.ops.size, reason)
                logger.info { "Proposal ${pending.proposalId} for ${entry.uri} not applied: $reason" }
                PushOutcome.CONFLICT
            }
        }
    }

    private fun submitProposal(
        out: DataOutputStream,
        input: DataInputStream,
        pending: ProposalQueue.Pending,
        instanceId: String?
    ): ProposalStatusPayload {
        val type = object : TypeToken<ProposalOp>() {}.type
        val payload = ProposalSubmitPayload(
            proposalId = pending.proposalId,
            documentUri = pending.documentUri,
            baseDocVersion = pending.baseDocVersion,
            baseInstanceId = pending.baseInstanceId,
            actorDeviceId = deviceId,
            ops = pending.ops.map { (_, json) ->
                gson.fromJson<ProposalOp>(json, type)
            }
        )
        SyncWire.writeText(
            out,
            gson.toJson(
                SyncRequest(
                    SyncRequest.TYPE_PROPOSAL_SUBMIT, deviceId,
                    instanceId = instanceId, documentUri = pending.documentUri,
                    proposal = payload
                )
            )
        )
        val resp = readResponse(input) ?: throw IOException("No proposal response")
        if (!resp.ok) throw IOException("Proposal error: ${resp.error}")
        return gson.fromJson(resp.payload, ProposalStatusPayload::class.java)
            ?: throw IOException("Null proposal status")
    }

    private fun shortVersion(v: String): String = v.take(8)

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
            val configured = pairingCode
            if (configured != null && !constantTimeEquals(pskProof(configured), peer.pskProof)) {
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

    private fun pskOrNull(): String? = pairingCode

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
    ): Triple<DocumentEntity, List<StrokeWithPoints>, List<TextAnnotationEntity>> {
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
        // v4. `payload.texts` is `List<Any>` because the payload is decoded before the
        // concrete entity types are known; re-serializing and re-parsing is how the
        // strokes are handled too, so notes follow the same path rather than
        // introducing a second decoding strategy for one payload.
        val textType = object : TypeToken<List<TextAnnotationEntity>>() {}.type
        val texts = gson.fromJson<List<TextAnnotationEntity>>(gson.toJson(payload.texts), textType)
            ?: emptyList()
        return Triple(doc, strokes, texts)
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
                    sessionExecutor.submit { serveClient(client) }
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
            // Authentication gate. Previously every verb was served to whoever
            // connected, which meant anyone on the same Wi-Fi could pull this
            // machine's whole mirror — including notes the user just wrote — with no
            // credential at all. The gate is per connection because the protocol has
            // no notion of a session token; requiring the handshake first means a
            // client that cannot prove it holds the PSK gets nothing at all.
            var authenticated = false
            try {
                while (running.get()) {
                    val reqText = SyncWire.readText(input) ?: break
                    val req = gson.fromJson(reqText, SyncRequest::class.java)
                    val isHandshake = req.type == SyncRequest.TYPE_HANDSHAKE
                    if (!isHandshake && !authenticated) {
                        respondError(out, req.type ?: "unknown", "handshake required before ${req.type ?: "unknown"}")
                        break
                    }
                    handleServerRequest(req, input, out)
                    if (isHandshake) authenticated = true
                }
            } catch (e: Exception) {
                logger.debug(e) { "Client session ended" }
            }
        }
    }

    /**
     * Whether a handshake from [req] may proceed.
     *
     * With a pairing code configured, the client must prove it holds the PSK — the
     * same `pskProof` the tablet requires of us. Without a code configured, any
     * local client is allowed: that is the documented "unpaired" mode, and
     * refusing it would make a fresh install unable to sync at all. The settings
     * screen says so in as many words, because this is the one place where leaving
     * the field blank is a real security decision.
     */
    private fun handshakeAuthorised(req: SyncRequest): Boolean {
        val configured = pskOrNull() ?: return true
        val presented = req.psk ?: return false
        return constantTimeEquals(pskProof(configured), presented)
    }

    private fun handleServerRequest(req: SyncRequest, input: DataInputStream, out: DataOutputStream) {
        try {
            when (req.type) {
                SyncRequest.TYPE_HANDSHAKE -> {
                    if (!handshakeAuthorised(req)) {
                        respondError(out, req.type, "authentication failed: wrong pairing code")
                        return
                    }
                    ack(out, req.type)
                }

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
                            fileSize = file?.length(),
                            // v4：文字數與 docVersion 一起送出，否則對方無法判斷
                            // 「只有註解變了」。這裡沒有 instanceId，所以 docVersion
                            // 留空讓對方自己算——與 v3 的行為一致。
                            textCount = databaseManager.textAnnotationCountForDocument(doc.uri)
                        )
                    }
                    respondJson(out, req.type, manifest)
                }

                SyncRequest.TYPE_DOCUMENT_DETAIL -> {
                    val uri = req.documentUri ?: throw IllegalArgumentException("missing documentUri")
                    val doc = databaseManager.getDocument(uri)
                        ?: throw IllegalArgumentException("unknown document $uri")
                    val strokes = databaseManager.getAllStrokesForDocument(uri)
                    val texts = databaseManager.getAllTextAnnotationsForDocument(uri)
                    respondJson(out, req.type, DocumentDetailPayload(doc, strokes, texts))
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
