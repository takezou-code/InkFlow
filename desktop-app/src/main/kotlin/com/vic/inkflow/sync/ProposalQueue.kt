package com.vic.inkflow.sync

import com.google.gson.Gson
import com.vic.inkflow.data.DatabaseManager
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * The desktop's outbox for v5 proposals.
 *
 * ## Why this exists
 *
 * The mirror tables mix pulled content and local edits indistinguishably, so at
 * sync time there is no way to reconstruct "what did I change". The queue records
 * each local edit as an id-level op at write time; at sync time the pending ops for
 * a document become one proposal carrying the base version they were made against.
 *
 * ## The base-version rule
 *
 * An op is only recorded when the document has a stored version to arbitrate
 * against (i.e. it came from the tablet at some point). A never-synced local file
 * has no base, so its edits stay local-only — queueing them would produce proposals
 * the tablet must reject as "unknown document", every pass, forever.
 *
 * If the stored base moved since the queued ops were recorded (a pull overwrote
 * them), the old ops are dropped and reported: they describe edits to a state that
 * no longer exists, and sending them would resurrect content the tablet already
 * replaced. Dropping is correct; dropping *silently* is not, hence [drainNotices].
 *
 * ## Threading
 *
 * All public methods serialize on [lock]. The database serializes on its own lock
 * too, but check-then-act sequences (read base, then append) need the wider
 * critical section, otherwise a pull landing between the two leaves an op filed
 * under the wrong base.
 */
class ProposalQueue(
    private val db: DatabaseManager,
    private val gson: Gson = Gson()
) {
    companion object {
        /**
         * Ops kept per document.
         *
         * Bounded because an unbounded queue is a disk leak with a sync-shaped
         * excuse: a user who draws for a month offline would otherwise accumulate
         * every stroke ever made. Past the cap the oldest ops are dropped and the
         * proposal is flagged, so the loss is visible instead of silent.
         */
        const val MAX_OPS_PER_DOC = 1000
    }

    enum class RecordResult {
        /** Filed under the current base. */
        RECORDED,
        /** No stored version for this document; the edit stays local-only. */
        SKIPPED_NO_BASE,
        /** The base moved (a pull overwrote these edits); old ops dropped. */
        STALE_DROPPED
    }

    /** One document's unsent proposal, with ops in send order. */
    data class Pending(
        val documentUri: String,
        val proposalId: String,
        val baseDocVersion: String,
        val baseInstanceId: String?,
        val overflow: Boolean,
        /** (seq, opJson). The seq range is what a send consumes. */
        val ops: List<Pair<Long, String>>
    )

    private val lock = Any()
    private val notices = mutableListOf<String>()

    fun record(documentUri: String, op: ProposalOp): RecordResult = synchronized(lock) {
        val instance = db.getKnownInstanceId()
            ?: return RecordResult.SKIPPED_NO_BASE
        val base = db.getDocVersion(instance, documentUri)
            ?: return RecordResult.SKIPPED_NO_BASE

        val existing = db.getProposalRow(documentUri)
        // The edit that triggers a base move must still be filed — only the ops from
        // the dead base are dropped. Returning early here would discard the current
        // edit too, which is exactly the silent loss this queue exists to prevent.
        var staleDropped = 0
        if (existing == null || existing.baseDocVersion != base || existing.baseInstanceId != instance) {
            staleDropped = if (existing != null) db.countOps(documentUri) else 0
            db.deleteAllOps(documentUri)
            db.upsertProposalRow(
                DatabaseManager.ProposalRow(
                    documentUri = documentUri,
                    proposalId = java.util.UUID.randomUUID().toString(),
                    baseDocVersion = base,
                    baseInstanceId = instance,
                    overflow = false
                )
            )
            if (staleDropped > 0) {
                notices.add("「${shortUri(documentUri)}」的 $staleDropped 筆桌面修改已被平板新內容覆蓋")
                logger.info { "Proposal base moved for $documentUri; dropped $staleDropped stale ops" }
            }
        }

        db.appendOp(documentUri, gson.toJson(op))
        val n = db.countOps(documentUri)
        if (n > MAX_OPS_PER_DOC) {
            db.deleteOldestOps(documentUri, MAX_OPS_PER_DOC)
            db.setProposalOverflow(documentUri, true)
            notices.add("「${shortUri(documentUri)}」的修改太多，最舊的已無法送出")
        }
        return if (staleDropped > 0) RecordResult.STALE_DROPPED else RecordResult.RECORDED
    }

    fun takeForSend(documentUri: String): Pending? = synchronized(lock) {
        val row = db.getProposalRow(documentUri) ?: return null
        val ops = db.getOps(documentUri)
        if (ops.isEmpty()) return null
        return Pending(
            documentUri = documentUri,
            proposalId = row.proposalId,
            baseDocVersion = row.baseDocVersion,
            baseInstanceId = row.baseInstanceId,
            overflow = row.overflow,
            ops = ops
        )
    }

    /**
     * Resolve a send: on accept, consume exactly the seqs that were sent.
     *
     * Consuming by seq range rather than "delete all" matters: ops recorded while
     * the send was in flight must survive, and they keep the proposal row (with its
     * base updated to the winner) so the next pass can send them.
     */
    fun onAccepted(documentUri: String, sentMaxSeq: Long, winnerVersion: String, winnerInstance: String) =
        synchronized(lock) {
            db.deleteOpsUpTo(documentUri, sentMaxSeq)
            if (db.countOps(documentUri) == 0) {
                db.deleteProposalRow(documentUri)
            } else {
                val row = db.getProposalRow(documentUri)
                if (row != null) {
                    db.upsertProposalRow(row.copy(baseDocVersion = winnerVersion, baseInstanceId = winnerInstance))
                }
            }
        }

    /** On conflict or rejection the queued ops describe a dead base; drop them loudly. */
    fun onConflict(documentUri: String, opCount: Int, reason: String) = synchronized(lock) {
        db.deleteAllOps(documentUri)
        db.deleteProposalRow(documentUri)
        notices.add("「${shortUri(documentUri)}」有 $opCount 筆桌面修改未能送出（$reason）")
    }

    /** Human-readable conflict/overflow notes since the last drain. Empties the list. */
    fun drainNotices(): List<String> = synchronized(lock) {
        val out = notices.toList()
        notices.clear()
        return out
    }

    fun pendingOpCount(): Int = db.totalPendingOps()

    fun pendingDocuments(): List<String> = db.pendingProposalDocuments()

    private fun shortUri(uri: String): String {
        val name = uri.substringAfterLast('/').substringBefore('?')
        return if (name.length > 28) "…" + name.takeLast(27) else name
    }
}