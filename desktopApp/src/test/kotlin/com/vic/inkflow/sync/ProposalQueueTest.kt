package com.vic.inkflow.sync

import com.vic.inkflow.data.DatabaseManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The outbox has to file edits under the right base, refuse the unfileable, and
 * drop the dead — loudly in every case.
 *
 * The failure these pin is silent divergence: ops recorded against a version the
 * tablet has already left, sent anyway, and "accepted" into a document that no
 * longer contains what the ops describe. Every rule here exists to make that
 * impossible, and every test asserts the rule, not the SQL.
 */
class ProposalQueueTest {

    private val dirs = mutableListOf<java.io.File>()

    private fun newDb(): DatabaseManager {
        val dir = java.nio.file.Files.createTempDirectory("inkflow-queue").toFile()
        dirs += dir
        return DatabaseManager(dir.resolve("q.db").absolutePath).also { it.connect() }
    }

    @AfterTest
    fun cleanup() {
        dirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun seedBase(db: DatabaseManager, uri: String, version: String, instance: String = "inst-1") {
        db.setKnownInstanceId(instance)
        db.saveDocument(com.vic.inkflow.data.DocumentEntity(uri, "d"))
        db.setDocVersion(instance, uri, version, 1L)
    }

    private fun upsert(id: String) = ProposalOp(op = ProposalOp.UPSERT_STROKE, id = id)

    @Test
    fun `an edit with no stored version is skipped, not queued`() {
        // A never-synced local file has no base to arbitrate against. Queueing it
        // would produce proposals the tablet must reject as "unknown document",
        // every pass, forever — a queue leak disguised as sync.
        val db = newDb()
        try {
            val q = ProposalQueue(db)
            val r = q.record("file:///local-only.pdf", ProposalOp(ProposalOp.DELETE_STROKE, id = "s1"))
            assertEquals(ProposalQueue.RecordResult.SKIPPED_NO_BASE, r)
            assertEquals(0, q.pendingOpCount())
            assertTrue(q.pendingDocuments().isEmpty())
        } finally {
            db.disconnect()
        }
    }

    @Test
    fun `ops accumulate under one proposal with a stable id`() {
        val db = newDb()
        try {
            seedBase(db, "file:///a.pdf", "v1")
            val q = ProposalQueue(db)

            assertEquals(ProposalQueue.RecordResult.RECORDED, q.record("file:///a.pdf", ProposalOp(ProposalOp.DELETE_STROKE, id = "s1")))
            assertEquals(ProposalQueue.RecordResult.RECORDED, q.record("file:///a.pdf", ProposalOp(ProposalOp.DELETE_TEXT, id = "n1")))

            val pending = q.takeForSend("file:///a.pdf")
            assertTrue(pending != null, "expected a pending proposal")
            assertEquals("v1", pending.baseDocVersion)
            assertEquals("inst-1", pending.baseInstanceId)
            assertEquals(2, pending.ops.size)
            assertEquals(listOf("s1", "n1"), pending.ops.map {
                com.google.gson.Gson().fromJson(it.second, ProposalOp::class.java).let { op -> op.id }
            })

            // Same proposal id across takes: a retry must be idempotent, and
            // idempotency needs a stable key.
            assertEquals(pending.proposalId, q.takeForSend("file:///a.pdf")!!.proposalId)
        } finally {
            db.disconnect()
        }
    }

    @Test
    fun `a moved base drops the dead ops and says so`() {
        val db = newDb()
        try {
            seedBase(db, "file:///a.pdf", "v1")
            val q = ProposalQueue(db)
            q.record("file:///a.pdf", ProposalOp(ProposalOp.DELETE_STROKE, id = "s1"))

            // A pull landed in between and moved the base: the queued op describes
            // edits to a state that no longer exists.
            db.setDocVersion("inst-1", "file:///a.pdf", "v2", 2L)
            val r = q.record("file:///a.pdf", ProposalOp(ProposalOp.DELETE_STROKE, id = "s2"))

            assertEquals(ProposalQueue.RecordResult.STALE_DROPPED, r)
            val pending = q.takeForSend("file:///a.pdf")!!
            assertEquals("v2", pending.baseDocVersion, "the new proposal starts from the new base")
            assertEquals(1, pending.ops.size, "only the new op survives")
            val notes = q.drainNotices()
            assertEquals(1, notes.size)
            assertTrue(notes.single().contains("1 筆"), "the notice must name the dropped count, got: ${notes.single()}")
        } finally {
            db.disconnect()
        }
    }

    @Test
    fun `accept consumes exactly the sent seqs and keeps the rest`() {
        val db = newDb()
        try {
            seedBase(db, "file:///a.pdf", "v1")
            val q = ProposalQueue(db)
            q.record("file:///a.pdf", ProposalOp(ProposalOp.DELETE_STROKE, id = "s1"))
            val sent = q.takeForSend("file:///a.pdf")!!
            val maxSeq = sent.ops.maxOf { it.first }

            // An edit lands while the send is in flight.
            q.record("file:///a.pdf", ProposalOp(ProposalOp.DELETE_STROKE, id = "s2"))

            // Accept consumes only what was sent; the in-flight op survives under
            // the winner base, so the next pass can send it.
            q.onAccepted("file:///a.pdf", maxSeq, "v2", "inst-1")
            val remaining = q.takeForSend("file:///a.pdf")!!
            assertEquals(1, remaining.ops.size)
            assertEquals("v2", remaining.baseDocVersion, "leftovers rebase onto the winner")
            assertEquals(
                "s2",
                com.google.gson.Gson().fromJson(remaining.ops.single().second, ProposalOp::class.java).id
            )

            q.onAccepted("file:///a.pdf", remaining.ops.maxOf { it.first }, "v3", "inst-1")
            assertNull(q.takeForSend("file:///a.pdf"), "empty queue leaves no row behind")
        } finally {
            db.disconnect()
        }
    }

    @Test
    fun `conflict drops everything loudly`() {
        val db = newDb()
        try {
            seedBase(db, "file:///a.pdf", "v1")
            val q = ProposalQueue(db)
            q.record("file:///a.pdf", ProposalOp(ProposalOp.DELETE_STROKE, id = "s1"))
            q.record("file:///a.pdf", ProposalOp(ProposalOp.DELETE_TEXT, id = "n1"))

            q.onConflict("file:///a.pdf", 2, "tablet had moved on")

            assertNull(q.takeForSend("file:///a.pdf"), "conflicted ops must not be retried blindly")
            assertEquals(0, q.pendingOpCount())
            val notes = q.drainNotices()
            assertEquals(1, notes.size)
            assertTrue(notes.single().contains("2 筆"), "got: ${notes.single()}")
            assertTrue(q.drainNotices().isEmpty(), "drain empties the list")
        } finally {
            db.disconnect()
        }
    }

    @Test
    fun `the cap drops the oldest and flags the overflow`() {
        val db = newDb()
        try {
            seedBase(db, "file:///a.pdf", "v1")
            val q = ProposalQueue(db)
            repeat(ProposalQueue.MAX_OPS_PER_DOC + 5) { i ->
                q.record("file:///a.pdf", ProposalOp(ProposalOp.DELETE_STROKE, id = "s$i"))
            }

            val pending = q.takeForSend("file:///a.pdf")!!
            assertEquals(ProposalQueue.MAX_OPS_PER_DOC, pending.ops.size, "the queue must stay bounded")
            assertTrue(pending.overflow, "the loss must be flagged, not silent")
            val ids = pending.ops.map {
                com.google.gson.Gson().fromJson(it.second, ProposalOp::class.java).id
            }
            assertFalse("s0" in ids, "oldest ops are the ones dropped")
            assertTrue("s${ProposalQueue.MAX_OPS_PER_DOC + 4}" in ids, "newest ops survive")
            assertTrue(q.drainNotices().any { it.contains("最舊") }, "overflow must be reported")
        } finally {
            db.disconnect()
        }
    }

    @Test
    fun `proposals for purged documents do not linger`() {
        val db = newDb()
        try {
            seedBase(db, "file:///a.pdf", "v1")
            seedBase(db, "file:///b.pdf", "v1")
            val q = ProposalQueue(db)
            q.record("file:///a.pdf", ProposalOp(ProposalOp.DELETE_STROKE, id = "s1"))
            q.record("file:///b.pdf", ProposalOp(ProposalOp.DELETE_STROKE, id = "s2"))

            db.purgeDocument("file:///a.pdf")

            assertNull(q.takeForSend("file:///a.pdf"), "purged docs must not keep proposals")
            assertEquals(listOf("file:///b.pdf"), q.pendingDocuments())
        } finally {
            db.disconnect()
        }
    }

    @Test
    fun `wipeGeneration clears the whole outbox`() {
        val db = newDb()
        try {
            seedBase(db, "file:///a.pdf", "v1")
            val q = ProposalQueue(db)
            q.record("file:///a.pdf", ProposalOp(ProposalOp.DELETE_STROKE, id = "s1"))

            db.wipeGeneration()

            assertEquals(0, q.pendingOpCount())
            assertTrue(q.pendingDocuments().isEmpty())
        } finally {
            db.disconnect()
        }
    }

    @Test
    fun `takeForSend on an empty queue returns null`() {
        val db = newDb()
        try {
            assertNull(ProposalQueue(db).takeForSend("file:///nothing.pdf"))
        } finally {
            db.disconnect()
        }
    }

    // ─── v6 resolveSend: per-op resolution, not per-proposal ───────────────

    @Test
    fun `resolveSend with a whole-send conflict is exactly onConflict`() {
        // What a v5 tablet always returns: every sent id named. The outbox must
        // end in the identical state as the old whole-queue drop — row gone,
        // everything (including in-flight) deleted, one loud notice.
        val db = newDb()
        try {
            seedBase(db, "file:///a.pdf", "v1")
            val q = ProposalQueue(db)
            q.record("file:///a.pdf", ProposalOp(ProposalOp.DELETE_STROKE, id = "s1"))
            q.record("file:///a.pdf", ProposalOp(ProposalOp.DELETE_STROKE, id = "s2"))
            val sent = q.takeForSend("file:///a.pdf")!!

            val r = q.resolveSend("file:///a.pdf", sent.ops, setOf("s1", "s2"), "v2", "inst-1", "stale")

            assertEquals(ProposalQueue.SendResolution(accepted = 0, dropped = 2, retained = 0), r)
            assertEquals(0, q.pendingOpCount())
            assertTrue(q.pendingDocuments().isEmpty())
            assertEquals(1, q.drainNotices().size)
        } finally {
            db.disconnect()
        }
    }

    @Test
    fun `resolveSend with a partial conflict keeps the rest and advances the base`() {
        // The v6 shape: only s2 truly collided. s1/s3 were applied — consumed,
        // not resent — and the two ops recorded while the send was in flight
        // stay queued under the winner base for the next pass.
        val db = newDb()
        try {
            seedBase(db, "file:///a.pdf", "v1")
            val q = ProposalQueue(db)
            q.record("file:///a.pdf", ProposalOp(ProposalOp.DELETE_STROKE, id = "s1"))
            q.record("file:///a.pdf", ProposalOp(ProposalOp.DELETE_STROKE, id = "s2"))
            q.record("file:///a.pdf", ProposalOp(ProposalOp.DELETE_STROKE, id = "s3"))
            val sent = q.takeForSend("file:///a.pdf")!!
            q.record("file:///a.pdf", ProposalOp(ProposalOp.DELETE_STROKE, id = "s4"))
            q.record("file:///a.pdf", ProposalOp(ProposalOp.DELETE_STROKE, id = "s5"))

            val r = q.resolveSend("file:///a.pdf", sent.ops, setOf("s2"), "v2", "inst-1", "stale")

            assertEquals(ProposalQueue.SendResolution(accepted = 2, dropped = 1, retained = 2), r)
            assertEquals(listOf("file:///a.pdf"), q.pendingDocuments())
            val next = q.takeForSend("file:///a.pdf")!!
            assertEquals("v2", next.baseDocVersion, "survivors travel under the winner base")
            assertEquals(2, next.ops.size)
            val notes = q.drainNotices()
            assertEquals(1, notes.size)
            assertTrue(notes.single().contains("1 筆"), "got: ${notes.single()}")
        } finally {
            db.disconnect()
        }
    }

    @Test
    fun `resolveSend drops an unparseable op instead of retrying it forever`() {
        // A corrupt row must not poison every future pass: unidentified ops
        // count as conflicted, so the queue drains instead of looping.
        val db = newDb()
        try {
            seedBase(db, "file:///a.pdf", "v1")
            val q = ProposalQueue(db)
            q.record("file:///a.pdf", ProposalOp(ProposalOp.DELETE_STROKE, id = "s1"))
            db.appendOp("file:///a.pdf", "{corrupt")
            val sent = q.takeForSend("file:///a.pdf")!!
            assertEquals(2, sent.ops.size)

            val r = q.resolveSend("file:///a.pdf", sent.ops, setOf("s1"), "v2", "inst-1", "stale")

            assertEquals(0, r.accepted)
            assertEquals(2, r.dropped)
            assertEquals(0, q.pendingOpCount())
        } finally {
            db.disconnect()
        }
    }

    @Test
    fun `opId reads nested stroke and text ids`() {
        val db = newDb()
        try {
            val q = ProposalQueue(db)
            val gson = com.google.gson.Gson()
            val strokeOp = gson.toJson(ProposalOp(ProposalOp.UPSERT_STROKE, stroke = mapOf("stroke" to mapOf("id" to "s9"))))
            val textOp = gson.toJson(ProposalOp(ProposalOp.UPSERT_TEXT, text = mapOf("id" to "t3")))
            val deleteOp = gson.toJson(ProposalOp(ProposalOp.DELETE_TEXT, id = "t4"))
            assertEquals("s9", q.opId(strokeOp))
            assertEquals("t3", q.opId(textOp))
            assertEquals("t4", q.opId(deleteOp))
            assertNull(q.opId("{corrupt"))
        } finally {
            db.disconnect()
        }
    }
}