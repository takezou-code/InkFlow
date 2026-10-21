package com.vic.inkflow.sync

/**
 * The wire DTOs of the LAN sync protocol.
 *
 * ## Why these live in `:shared`
 *
 * Every field name here is part of the wire contract: Gson matches by name and there
 * is no compile-time check, so a renamed field on one side is silent data loss, not
 * a build error. The two hand-copied versions of this file had already drifted —
 * the tablet's request fields were nullable (Gson assigns by reflection and never
 * runs Kotlin's null checks, so a missing field can only be observed as `null`),
 * while the desktop's were not, and the two ends disagreed about
 * `PageCountsPayload.counts` and `PageCountEntry`'s defaults.
 *
 * One file, one shape, both ends.
 *
 * ## Nullability
 *
 * Request-side fields are nullable with defaults on purpose: Gson constructs
 * instances reflectively, so a field missing from the wire arrives as `null` even
 * when Kotlin says otherwise. Every reader must therefore check the value itself
 * rather than trusting the type. Response-side fields the sender always fills
 * (`DiscoveryResponse.deviceId`, `.deviceName`, `.ip`) stay non-null, because
 * both ends always populate them and the receiving side uses them directly.
 */
data class DiscoveryRequest(
    val app: String = SyncConstants.APP_TAG,
    val type: String = "discover",
    /** "tablet" | "desktop" (§3 rule 1: only a different role is answered). */
    val requesterRole: String? = null,
    val deviceId: String? = null,
    val deviceName: String? = null,
    /**
     * Identity of the SENDER's install of the tablet app.
     *
     * A random UUID generated once per install. The tablet's `documents.uri` is a
     * `file://` path with a random UUID filename inside app-private storage, which
     * is destroyed by a reinstall — so the URI alone cannot distinguish "the user
     * deleted this" from "new install". With `instanceId` the desktop can detect a
     * reinstall and drop the previous generation wholesale, and within a generation,
     * absence from a complete manifest means deletion.
     *
     * This is the one protocol field that cannot be added retroactively: a desktop
     * that already shipped without it keeps unrecoverable orphans on disk forever.
     */
    val instanceId: String? = null
)

/** Tablet -> unicast reply: "I am here, connect to this IP". */
data class DiscoveryResponse(
    val app: String = SyncConstants.APP_TAG,
    val type: String = "response",
    val deviceId: String,
    val deviceName: String,
    val role: String = SyncRoles.TABLET,
    /** LAN IP. Never `InetAddress.getLocalHost()` — that returns the wrong one. */
    val ip: String,
    val transferPort: Int = SyncPorts.TRANSFER_PORT,
    /** Incompatible versions make the desktop refuse to open the TCP connection (§3). */
    val protocolVersion: Int = SyncConstants.PROTOCOL_VERSION,
    /** Lets the desktop record the generation (§5). */
    val instanceId: String? = null
)

/**
 * Client -> Server request. Exactly one verb per connection phase; the server
 * answers with a single [SyncResponse] (or a binary frame for file bodies).
 *
 * `type` and `deviceId` are nullable **by design**: Gson assigns by reflection and
 * never runs Kotlin's null checks, so a field missing from the wire can only be
 * observed as `null`. Readers must guard.
 */
data class SyncRequest(
    val type: String? = null,
    val deviceId: String? = null,
    val instanceId: String? = null,
    /** Required by document_* / stroke_* / file_*. */
    val documentUri: String? = null,
    /** stroke_page only. */
    val pageIndex: Int? = null,
    /** file_data only. */
    val offset: Long? = null,
    /** file_data only. */
    val limit: Int? = null,
    /** handshake only: the raw PSK, not a hash of it. */
    val psk: String? = null,
    /** proposal_submit only: the proposal body. */
    val proposal: ProposalSubmitPayload? = null,
    /** proposal_status only: which proposal is being asked about. */
    val proposalId: String? = null
) {
    companion object {
        const val TYPE_HANDSHAKE = "handshake"
        const val TYPE_DOC_MANIFEST = "doc_manifest"
        const val TYPE_DOCUMENT_DETAIL = "document_detail"
        const val TYPE_STROKE_DELTA = "stroke_delta"
        const val TYPE_STROKE_PAGE = "stroke_page"
        const val TYPE_FILE_META = "file_meta"
        const val TYPE_FILE_DATA = "file_data"
        /** desktop -> tablet, "here is what I changed". */
        const val TYPE_PROPOSAL_SUBMIT = "proposal_submit"
        /** "what happened to my proposal". */
        const val TYPE_PROPOSAL_STATUS = "proposal_status"
        /**
         * Per-page counts. Additive verb, no version bump: an old peer answers
         * "unknown request type" and the client falls back to a full pull — slower,
         * but correct. Incremental pull is an optimization, never a correctness
         * requirement.
         */
        const val TYPE_PAGE_COUNTS = "page_counts"
        /** Every text annotation on one page. The text twin of `stroke_page`. */
        const val TYPE_TEXT_PAGE = "text_page"
        /**
         * Folder list. Additive verb, no version bump: an old peer answers
         * "unknown request type" and the client skips it. Folders are display-only
         * categorization; missing them loses no content.
         */
        const val TYPE_FOLDER_LIST = "folder_list"
    }
}

/**
 * Server -> Client response.
 *
 * `payload` is a **JSON string**, not a nested object: the client decodes it with a
 * second `gson.fromJson(resp.payload, X::class.java)`, so the sender must have
 * written `gson.toJson(payload)`.
 */
data class SyncResponse(
    val type: String = "",
    val ok: Boolean = true,
    val error: String? = null,
    val payload: String? = null
)

/**
 * One row of `doc_manifest`. The **complete** list is what makes deletion
 * propagation possible: the desktop pulls the whole list every round, and only
 * absence from it means "deleted".
 *
 * [docVersion] is the field the desktop actually diffs on — a content-addressed
 * hash, not a timestamp (§6).
 */
data class DocumentManifestEntry(
    val uri: String,
    val displayName: String,
    /** Display only ("last opened"); it does not take part in the sync decision. */
    val lastOpenedAt: Long,
    val strokeCount: Int,
    val filePresent: Boolean,
    val fileSha256: String? = null,
    val fileSize: Long? = null,
    /** SHA-256(instanceId ‖ uri ‖ strokeCount ‖ fileSha256 ‖ fileSize ‖ textCount). */
    val docVersion: String? = null,
    /**
     * Number of text annotations; part of [docVersion]. Without it, "somebody added
     * one note" looks like no change at all and the note never arrives — that is the
     * only reason this field exists.
     *
     * Defaults to 0 so an older payload still decodes. It must **not** be -1: a
     * negative count also reads as "changed" and would re-pull the whole document
     * on every round.
     */
    val textCount: Int = 0
)

/**
 * Answer to `document_detail`: the whole row, all strokes and all text
 * annotations.
 *
 * Notes travel in the same payload as strokes rather than in a verb of their own:
 * the deletion model is "complete manifest, tablet is the only writer, desktop
 * replaces the document wholesale", and the two tables are always fetched together.
 */
data class DocumentDetailPayload(
    val document: Any?,
    val strokes: List<Any>,
    /** List<TextAnnotationEntity>. Defaults to empty so an older payload decodes. */
    val texts: List<Any> = emptyList()
)

/** Answer to `stroke_delta` / `stroke_page`. */
data class StrokeDeltaPayload(
    val documentUri: String,
    /** Order matters: the array order of `points` is the polyline order (§9). */
    val strokes: List<Any>,
    val deletedStrokeIds: List<String> = emptyList(),
    val hasMore: Boolean = false,
    val nextOffset: Int = 0
)

/** Answer to `text_page`: every text annotation on one page. */
data class TextDeltaPayload(
    val documentUri: String,
    val texts: List<Any>
)

/**
 * Answer to `page_counts`: per-page stroke and note counts for one document.
 *
 * The client diffs these against its own per-page counts and pulls only the pages
 * that differ. Two indexed GROUP BY queries on the tablet replace a full document
 * load — the transfer saving is the visible half, the tablet-side CPU/RAM saving is
 * the other half.
 */
data class PageCountsPayload(
    val documentUri: String,
    val counts: List<PageCountEntry> = emptyList(),
    /** The document row, so a delta pull also refreshes metadata. */
    val document: Any? = null
)

/** Stroke and note counts for one page. */
data class PageCountEntry(
    val pageIndex: Int,
    val strokes: Int = 0,
    val texts: Int = 0
)

/** Answer to `file_meta`: the PDF body's size and checksum. */
data class FileMetaPayload(
    val exists: Boolean,
    val size: Long = 0,
    val sha256: String? = null
)

/**
 * One id-level change inside a proposal.
 *
 * Ops are per object id, never whole-document snapshots. A whole snapshot as a
 * proposal would reintroduce the exact failure whole-snapshot pull avoids: a stale
 * desktop overwriting newer tablet content in one move. Id-level ops let the tablet
 * arbitrate each object against what it currently holds.
 *
 * Upserts carry the full object; deletes carry only the id. Both are idempotent by
 * construction — applying the same op twice lands in the same state — which is what
 * makes retried proposals safe without a persistent dedup store.
 */
data class ProposalOp(
    /** "upsert_stroke" | "delete_stroke" | "upsert_text" | "delete_text". */
    val op: String,
    /** Present when op == "upsert_stroke". Serialized StrokeWithPoints. */
    val stroke: Any? = null,
    /** Present when op == "upsert_text". Serialized TextAnnotationEntity. */
    val text: Any? = null,
    /** Present when op starts with "delete_". */
    val id: String? = null,
    /**
     * This object's version as the desktop saw it when the edit was made.
     *
     * Without it the tablet cannot tell "you changed this since I last sent it"
     * (a merge) from "you changed something I never had" (not a merge). Deletes
     * carry no payload, so this field is the only place their base can live; it is
     * on every op type for that reason.
     */
    val baseVersion: Int? = null,
    /** The nonce counterpart of [baseVersion]. */
    val baseNonce: Int? = null
) {
    companion object {
        const val UPSERT_STROKE = "upsert_stroke"
        const val DELETE_STROKE = "delete_stroke"
        const val UPSERT_TEXT = "upsert_text"
        const val DELETE_TEXT = "delete_text"
    }
}

/**
 * Body of `proposal_submit`.
 *
 * [baseDocVersion] is the optimistic-locking token: the version the desktop saw
 * when it made these edits. If the tablet has moved on since, the proposal is stale
 * and comes back as a conflict instead of silently overwriting newer content. That
 * single comparison is the whole difference between "merge" and "last writer wins by
 * accident".
 */
data class ProposalSubmitPayload(
    /** UUID idempotency key. Resending the same proposal never applies it twice. */
    val proposalId: String,
    val documentUri: String,
    val baseDocVersion: String,
    val baseInstanceId: String?,
    val actorDeviceId: String,
    val ops: List<ProposalOp>
)

/** Answer to `proposal_submit` and `proposal_status`. */
data class ProposalStatusPayload(
    val proposalId: String,
    /** "accepted" | "conflict_stale" | "rejected" | "unknown". */
    val status: String,
    /** The tablet's current version — what the desktop must rebase onto. */
    val winnerDocVersion: String? = null,
    /** Object ids the proposal touched that no longer match. Empty when accepted. */
    val conflictIds: List<String> = emptyList(),
    /**
     * Ids of conflict copies the tablet kept for us (§15.3).
     *
     * The same id edited on both devices means one copy has to lose; the tablet
     * keeps its own and stores ours under a fresh id so nothing disappears. The
     * desktop treats this as a successful send — the ops are consumed, not dropped.
     */
    val conflictCopies: List<String> = emptyList(),
    val message: String? = null
) {
    companion object {
        const val ACCEPTED = "accepted"
        const val CONFLICT_STALE = "conflict_stale"
        const val REJECTED = "rejected"
        const val UNKNOWN = "unknown"
    }
}

/**
 * Answer payload of `handshake` (§8).
 *
 * [pskProof] = SHA-256(psk ‖ "inkflow-sync-v3"). Both directions are verified: the
 * desktop sends the raw PSK first, the tablet compares it in constant time and
 * answers with a proof so the desktop can confirm the tablet holds the same secret.
 */
data class HandshakeAck(
    val protocolVersion: Int = SyncConstants.PROTOCOL_VERSION,
    val instanceId: String? = null,
    val pskProof: String? = null
)

/** Request-level error (empty documentUri, unknown verb, unknown document…). */
class SyncRequestException(message: String) : Exception(message)
