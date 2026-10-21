package com.vic.inkflow.sync

import com.vic.inkflow.data.DatabaseManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The announcement matcher is the only thing between a spoofed UDP packet and a
 * sync pass, so "unknown sender" and "instance mismatch" must both provably
 * ignore — and a genuine announcement from the known tablet must match.
 *
 * The device list is seeded through reflection: it is populated in production
 * solely by the discovery flow, and driving real UDP in a unit test would trade a
 * deterministic assertion for socket timing. What matters here is the matching
 * rule, not the transport that feeds it.
 */
class EventTargetTest {

    private val dirs = mutableListOf<java.io.File>()
    private val managers = mutableListOf<LocalSyncManager>()

    private fun manager(): LocalSyncManager {
        val dir = java.nio.file.Files.createTempDirectory("inkflow-event").toFile()
        dirs += dir
        val db = DatabaseManager(dir.resolve("e.db").absolutePath)
        db.connect()
        return LocalSyncManager(db, dir.absolutePath).also { managers += it }
    }

    private fun seed(mgr: LocalSyncManager, device: DiscoveredDevice) {
        val field = LocalSyncManager::class.java.getDeclaredField("devices")
        field.isAccessible = true
        field.set(mgr, listOf(device))
    }

    private fun known() = DiscoveredDevice(
        deviceId = "tab-aaa", deviceName = "Tab", ip = "192.168.1.42",
        transferPort = 53531, lastSeenMillis = System.currentTimeMillis(),
        instanceId = "inst-a"
    )

    @AfterTest
    fun cleanup() {
        managers.forEach { runCatching { it.stopListening() } }
        dirs.forEach { runCatching { it.deleteRecursively() } }
    }

    @Test
    fun `an announcement from nobody known is ignored`() {
        val mgr = manager()
        assertNull(
            mgr.findEventTarget("tab-ghost", "192.168.1.99", "inst-x"),
            "unknown senders must never trigger a sync pass"
        )
    }

    @Test
    fun `a matching device id matches`() {
        val mgr = manager()
        seed(mgr, known())
        assertEquals("tab-aaa", mgr.findEventTarget("tab-aaa", "192.168.1.99", "inst-a")!!.deviceId)
    }

    @Test
    fun `a matching ip matches when the id rotated`() {
        // Device ids are stable per install, but matching by sender IP as well means
        // an announcement still lands when either side restarted and re-registered.
        val mgr = manager()
        seed(mgr, known())
        assertEquals(
            "tab-aaa",
            mgr.findEventTarget("tab-something-else", "192.168.1.42", "inst-a")!!.deviceId
        )
    }

    @Test
    fun `an instance mismatch is ignored`() {
        // Same IP, different install: a reinstalled tablet reuses the address, and
        // its announcements describe a generation we must wipe-and-repull, never
        // event-sync against.
        val mgr = manager()
        seed(mgr, known())
        assertNull(
            mgr.findEventTarget("tab-aaa", "192.168.1.42", "inst-b"),
            "a reinstall must not trigger an event sync"
        )
    }

    @Test
    fun `a null announced instance skips the generation check`() {
        // Older tablets do not send one. Treating null as "different" would mute
        // every announcement from a client that simply predates the field.
        val mgr = manager()
        seed(mgr, known())
        assertEquals(
            "tab-aaa",
            mgr.findEventTarget("tab-aaa", "192.168.1.42", null)!!.deviceId
        )
    }

    @Test
    fun `a device with unknown local instance still matches on id`() {
        // Our side has not completed a handshake yet (instanceId null): there is
        // nothing to compare against, so the announcement is honored and the real
        // sync pass sorts out the generation.
        val mgr = manager()
        seed(mgr, known().copy(instanceId = null))
        assertEquals(
            "tab-aaa",
            mgr.findEventTarget("tab-aaa", "192.168.1.42", "inst-a")!!.deviceId
        )
    }

    @Test
    fun `the throttle constant is sane`() {
        // Five seconds between event syncs: bursts collapse, polling covers the rest.
        // Zero would sync per stroke; a minute would make announcements pointless.
        assertTrue(
            LocalSyncManager.EVENT_SYNC_MIN_INTERVAL_MS in 1_000L..30_000L,
            "throttle out of sane range: ${LocalSyncManager.EVENT_SYNC_MIN_INTERVAL_MS}"
        )
    }
}
