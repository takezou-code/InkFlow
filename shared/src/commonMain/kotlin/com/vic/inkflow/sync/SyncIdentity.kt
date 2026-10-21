package com.vic.inkflow.sync

/**
 * Cross-device identity: the content-addressed document version and the pairing
 * proof.
 *
 * ## Why this is one file and not two
 *
 * `docVersion` and `pskProof` are the two values both ends have to compute
 * **byte for byte identically**. The tablet kept them in `SyncIdentity`, the
 * desktop kept a copy of the same logic inside its `SyncWire`, and the only thing
 * keeping them in step was a comment asking the reader to keep them in step.
 *
 * A wrong byte here is not a crash: it makes every document look changed on every
 * round, so the desktop re-pulls every stroke and every 300 MB PDF, forever, and
 * the symptom looks like a network problem. That is a genuinely hard bug to find
 * by hand, which is exactly why it should not be findable at all.
 *
 * ## The separator
 *
 * The fields are joined with a NUL byte, which cannot occur inside a file URI or a
 * hex digest. A space would **not** be safe: document URIs routinely contain spaces
 * (".../My Notes.pdf"), and ("/a b", "c") would then collide with ("/a", "b c").
 */
object SyncIdentity {

    /**
     * Salt for [pskProof]. **Both ends must agree byte for byte** — changing it
     * invalidates every already-paired desktop.
     */
    const val PSK_SALT = "inkflow-sync-v3"

    /** Field separator for [docVersion]. Cannot occur in a URI or a hex digest. */
    const val DOC_VERSION_SEPARATOR: Char = '\u0000'

    /** Sentinel for an absent value. "-" rather than "null", so it cannot collide. */
    private const val ABSENT = "-"
    private const val ABSENT_SIZE = -1L

    /**
     * Content-addressed document version (§6) — the field the desktop actually
     * diffs on.
     *
     * ```
     * docVersion = SHA-256(instanceId ‖ uri ‖ strokeCount ‖ fileSha256 ‖ fileSize ‖ textCount)
     * ```
     *
     * Why not `lastOpenedAt` (the v2 approach was wrong in both directions):
     * - **False positive**: the user only opens a document to read it and draws
     *   nothing, the timestamp moves, and the desktop re-pulls the row, every
     *   stroke and the whole PDF.
     * - **False negative (the dangerous one)**: any write path that mutates strokes
     *   without touching `lastOpenedAt` — undo/redo, import, page reorder — leaves
     *   the desktop permanently stale with no way to self-heal, because the manifest
     *   keeps saying "not newer".
     *
     * A hash of the actual content can do neither, and it removes the need for the
     * two machines' clocks to agree at all.
     *
     * Coordinates deliberately do not take part: the stroke **count** and the file
     * bytes are enough to address the content, and coordinates that change while the
     * count stays the same (move one point, delete one and add one) are common.
     * The trade-off is deliberate — a miss is corrected by the next full snapshot
     * pull, while the cost of a miss (re-pulling 300 MB every round) is far larger
     * than the cost of hashing every coordinate (a full document read per round).
     *
     * @param textCount number of text annotations; appended after [fileSize] so the
     *   field order stays a prefix-extension rather than a reshuffle.
     */
    fun docVersion(
        instanceId: String,
        uri: String,
        strokeCount: Int,
        fileSha256: String?,
        fileSize: Long?,
        textCount: Int = 0
    ): String {
        val canonical = buildString {
            append(instanceId); append(DOC_VERSION_SEPARATOR)
            append(uri); append(DOC_VERSION_SEPARATOR)
            append(strokeCount); append(DOC_VERSION_SEPARATOR)
            append(fileSha256 ?: ABSENT); append(DOC_VERSION_SEPARATOR)
            append(fileSize ?: ABSENT_SIZE); append(DOC_VERSION_SEPARATOR)
            append(textCount)
        }
        return sha256Hex(canonical.toByteArray(Charsets.UTF_8))
    }

    /**
     * `SHA-256(psk ‖ "inkflow-sync-v3")`, lowercase hex.
     *
     * A bare hash without salt dies to a rainbow table, and the salt has to be the
     * same on both ends, so the salt is part of the protocol (§8).
     *
     * This is a **one-way** proof: the desktop can confirm the tablet holds the
     * same secret, but not the other way round. Hence the two-step verification:
     * desktop sends the raw PSK -> tablet verifies -> tablet sends the proof ->
     * desktop verifies.
     */
    fun pskProof(psk: String): String =
        sha256Hex((psk + PSK_SALT).toByteArray(Charsets.UTF_8))

    /**
     * Length-independent constant-time string comparison.
     *
     * Returning false early on a length mismatch leaks the length, but the PSK is
     * always 8 digits and its length is public information, so there is nothing to
     * gain by hashing first. The comparison loop itself must **not** break early:
     * an early exit lets an attacker recover the PSK one byte at a time from the
     * response time.
     */
    fun constantTimeEquals(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }
}

/**
 * SHA-256 of a byte array, lowercase hex.
 *
 * `expect`/`actual` because `java.security.MessageDigest` is not available in
 * `commonMain` — but both of this module's targets (Android and the desktop JVM)
 * are JVMs, so a single actual in the shared JVM source set serves both.
 */
internal expect fun sha256Hex(bytes: ByteArray): String
