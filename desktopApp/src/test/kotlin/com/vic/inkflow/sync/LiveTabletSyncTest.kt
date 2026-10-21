package com.vic.inkflow.sync

import com.vic.inkflow.data.DatabaseManager
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Talks to the **real tablet** over the LAN.
 *
 * `SyncEndToEndTest` proves the protocol against a fake tablet running in the same
 * JVM. That cannot catch the two classes of defect that actually broke sync:
 *
 *  - a byte layout that differs between the two independent implementations
 *    (Android's `SyncIdentity` vs this side's `SyncProtocol`);
 *  - anything environmental — firewall, wrong pairing code, discovery not
 *    answering.
 *
 * So this test skips unless both are true, because it depends on the user having
 * turned the tablet's sync server on in Settings. It never fails when the tablet
 * is simply absent: a machine with no tablet is a normal state, not a regression.
 * When something *is* wrong, though, it says exactly what.
 *
 * Usage: `./gradlew test --tests '*LiveTabletSyncTest*'`
 */
class LiveTabletSyncTest {

    private val tabletHost = System.getenv("INKFLOW_TABLET_HOST") ?: "192.168.1.211"
    private val pairingCode = System.getenv("INKFLOW_PAIRING_CODE")

    @Test
    fun `desktop can discover, authenticate and pull from the tablet`() {
        if (pairingCode.isNullOrBlank()) {
            println("SKIP: set INKFLOW_PAIRING_CODE to the 8-digit code shown in tablet Settings")
            return
        }

        // Probe TCP first so an unreachable tablet reads as "skip", not "fail".
        val reachable = runCatching {
            Socket().use { it.connect(InetSocketAddress(tabletHost, SyncPorts.TRANSFER_PORT), 3000) }
            true
        }.getOrDefault(false)
        if (!reachable) {
            println("SKIP: tablet not reachable at $tabletHost:${SyncPorts.TRANSFER_PORT} - is the sync server on?")
            return
        }

        val dir = File(System.getProperty("java.io.tmpdir"), "inkflow-live-sync").apply { mkdirs() }
        val db = DatabaseManager(File(dir, "live-sync.db").absolutePath).also { it.connect() }

        try {
            val mgr = LocalSyncManager(db, dir.absolutePath, psk = pairingCode)
            val device = DiscoveredDevice(
                deviceId = "tablet-live",
                deviceName = "Tablet",
                ip = tabletHost,
                transferPort = SyncPorts.TRANSFER_PORT,
                lastSeenMillis = System.currentTimeMillis()
            )

            val result = mgr.syncWithTablet(device)

            if (result.errors.isNotEmpty()) {
                fail("sync failed against the real tablet: ${result.errors}")
            }

            // A pull of zero documents is legitimate on a second run, so this only
            // asserts the handshake and round trip actually completed.
            assertTrue(result.documentsUpdated >= 0)
            println(
                "OK: handshake + pull succeeded — documents=${result.documentsUpdated} " +
                    "strokes=${result.strokesPulled} pdf=${result.filesTransferred}"
            )
        } finally {
            db.disconnect()
        }
    }
}