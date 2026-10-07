package com.vic.inkflow.sync

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.room.InvalidationTracker
import androidx.room.RoomDatabase
import com.google.gson.Gson
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Tells desktops "something changed, come sync" the moment it happens.
 *
 * ## Why this exists
 *
 * Without it the desktop only learns about tablet edits on its next poll — tens of
 * seconds later. Polling is a fallback, not a sync strategy: a user who draws on the
 * tablet and glances at the desktop expects the ink within a couple of seconds, the
 * way every shared editing surface behaves.
 *
 * ## How it knows
 *
 * Room's [InvalidationTracker] fires on every committed transaction touching the
 * observed tables — strokes, points and text annotations. No repository, DAO or
 * ViewModel changes needed: every write path (hand drawing, undo/redo, AI import,
 * proposal application, page ops) funnels through these three tables, so observing
 * them catches everything with zero invasion of the data layer.
 *
 * What it deliberately does NOT say is *what* changed: no uri, no version. The
 * desktop re-syncs and its manifest diff finds out. Sending the uri would require
 * resolving it at write time (a DB read inside every write transaction), while the
 * cost of being imprecise is one manifest pass — the cheapest request there is.
 *
 * ## Throttling
 *
 * A fast stroke produces dozens of transactions per second. Announcements are
 * coalesced to at most one per [ANNOUNCE_INTERVAL_MS]: the desktop only needs to
 * know "dirty since you last looked", and every announcement carries the same
 * content-free meaning, so dropping intermediate ones loses nothing.
 *
 * Spoofing this packet only triggers a manifest diff over an authenticated
 * connection — the worst case is a wasted sync pass, not data exposure. That is
 * why announcements carry no auth while the TCP verbs behind them do.
 */
class DirtyAnnouncer(
    context: Context,
    private val port: Int = SyncPorts.DISCOVERY_PORT
) {
    companion object {
        const val TYPE_CONTENT_CHANGED = "content-changed"

        /** At most one announcement per window; bursts collapse into one. */
        const val ANNOUNCE_INTERVAL_MS = 2_000L

        const val TAG = "InkFlowSync"
    }

    private val appContext: Context = context.applicationContext
    private val gson = Gson()
    private val running = AtomicBoolean(false)
    private val lastSentAt = AtomicLong(0L)

    @Volatile
    private var sendSocket: DatagramSocket? = null

    @Volatile
    private var watchedDb: RoomDatabase? = null

    private val observer = object : InvalidationTracker.Observer("strokes", "points", "text_annotations") {
        override fun onInvalidated(tables: Set<String>) {
            announce()
        }
    }

    /** Start observing [db] and allow announcements. Idempotent. */
    fun start(db: RoomDatabase): Boolean {
        if (!running.compareAndSet(false, true)) return true
        return try {
            sendSocket = DatagramSocket(null as java.net.SocketAddress?).apply {
                broadcast = true
            }
            db.invalidationTracker.addObserver(observer)
            watchedDb = db
            Log.i(TAG, "dirty announcer started")
            true
        } catch (e: Exception) {
            running.set(false)
            runCatching { sendSocket?.close() }
            sendSocket = null
            Log.w(TAG, "dirty announcer unavailable; polling still works", e)
            false
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        // Detach first so a restarted service does not accumulate observers on a
        // database that may already be closed. The db reference may be stale; both
        // calls are guarded.
        watchedDb?.let { runCatching { it.invalidationTracker.removeObserver(observer) } }
        watchedDb = null
        runCatching { sendSocket?.close() }
        sendSocket = null
    }

    /**
     * Broadcast one announcement, unless the last one was too recent.
     *
     * Public so proposal application can announce directly (it already knows a
     * write happened; waiting for the tracker round-trip would just add latency).
     * The throttle makes double-reporting harmless.
     */
    fun announce() {
        if (!running.get()) return
        val now = SystemClock.elapsedRealtime()
        val last = lastSentAt.get()
        if (now - last < ANNOUNCE_INTERVAL_MS) return
        if (!lastSentAt.compareAndSet(last, now)) return
        try {
            val instanceId = SyncIdentity.instanceId(appContext)
            val msg = gson.toJson(
                mapOf(
                    "app" to SyncConstants.APP_TAG,
                    "type" to TYPE_CONTENT_CHANGED,
                    "deviceId" to ("tab-" + instanceId.replace("-", "").take(8)),
                    "instanceId" to instanceId
                )
            )
            val bytes = msg.toByteArray(Charsets.UTF_8)
            val sock = sendSocket ?: return
            sock.send(
                DatagramPacket(
                    bytes, bytes.size,
                    InetAddress.getByName("255.255.255.255"), port
                )
            )
        } catch (e: Exception) {
            Log.d(TAG, "dirty announcement failed (polling covers it)", e)
        }
    }
}
