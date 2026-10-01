package com.vic.inkflow.sync

import com.vic.inkflow.data.DatabaseManager
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runs a real pull into the **app's own database** (`~/.inkflow/inkflow.db`).
 *
 * Why this exists alongside [LiveTabletSyncTest]:
 *
 * That test proved the protocol works, but into a throwaway database in TEMP. The
 * first run "succeeded" with 2 MB transferred while the actual app database stayed
 * empty — so the honest end-to-end question was still unanswered: does the desktop
 * app end up with the tablet's documents?
 *
 * This drives the same sync path against the real DB, so after it passes you can
 * open the app and the files are there. It is a developer tool, not something to
 * wire into the build: it needs the tablet's sync server turned on and it mutates
 * the user's data directory.
 *
 * Usage:
 *   set INKFLOW_PAIRING_CODE=42158219
 *   set INKFLOW_TABLET_HOST=192.168.1.211
 *   ./gradlew test --tests '*AppDatabaseSyncTest*'
 */
class AppDatabaseSyncTest {

    @Test
    fun `pull tablet documents into the app database`() {
        val host = System.getenv("INKFLOW_TABLET_HOST") ?: "192.168.1.211"
        val code = System.getenv("INKFLOW_PAIRING_CODE")
        check(!code.isNullOrBlank()) { "set INKFLOW_PAIRING_CODE to the code from tablet Settings" }

        val dir = File(System.getProperty("user.home"), ".inkflow")
        val db = DatabaseManager(File(dir, "inkflow.db").absolutePath).also { it.connect() }
        try {
            val mgr = LocalSyncManager(db, dir.absolutePath, psk = code)
            val device = DiscoveredDevice(
                deviceId = "tablet-live",
                deviceName = "Tablet",
                ip = host,
                transferPort = SyncPorts.TRANSFER_PORT,
                lastSeenMillis = System.currentTimeMillis()
            )

            val result = mgr.syncWithTablet(device)
            println(
                "sync result: documents=${result.documentsUpdated} strokes=${result.strokesPulled} " +
                    "pdf=${result.filesTransferred} errors=${result.errors}"
            )
            assertTrue(result.errors.isEmpty(), "sync errors: ${result.errors}")

            // The point of this test: the app's own DB must now hold documents.
            val docs = db.getAllDocuments()
            println("app database now holds ${docs.size} documents")
            docs.take(10).forEach { println("  - ${it.displayName}") }
            assertTrue(docs.isNotEmpty(), "app database is still empty after a successful sync")
        } finally {
            db.disconnect()
        }
    }
}