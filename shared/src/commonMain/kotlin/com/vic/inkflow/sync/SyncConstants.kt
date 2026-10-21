package com.vic.inkflow.sync

/**
 * Constants shared by every participant of the LAN sync protocol.
 *
 * ## Why these live in `:shared`
 *
 * They used to be declared twice — once in `app/.../sync/SyncProtocol.kt` and once
 * in `desktopApp/.../sync/SyncProtocol.kt` — and the two copies had already drifted:
 * the desktop had gained `DOCUMENTS_SUBDIR` and a `DeviceRole` enum that the tablet
 * did not have. Gson matches fields by name with no compile-time check, so a constant
 * that differs on one side is not a compile error, it is a silent behaviour
 * difference at run time.
 *
 * A file that exists once cannot drift. This is the rule `docs/specs/sync.md` calls
 * "shared module first", and this file is that rule applied to the protocol header.
 */

/** UDP discovery port and TCP transfer port. */
object SyncPorts {
    const val DISCOVERY_PORT = 53530
    const val TRANSFER_PORT = 53531
}

object SyncConstants {
    /**
     * Protocol version. Both ends must agree.
     *
     * v6 = proposals travel desktop -> tablet, and the tablet arbitrates per op
     * (§15.2) instead of rejecting the whole proposal. The two semantics produce
     * structurally identical but opposite answers for the same proposal, so mixing a
     * v5 tablet with a v6 desktop is not a graceful degradation — it makes the
     * desktop discard work the tablet already applied. The handshake rejects a
     * version mismatch outright, so the version number is what turns that silent
     * misreading into one clear error.
     */
    const val PROTOCOL_VERSION = 6

    /** Application tag in discovery packets. Non-InkFlow packets are ignored (§3). */
    const val APP_TAG = "InkFlow"

    /** Sub-directory of the desktop's data dir that mirrors the tablet's documents. */
    const val DOCUMENTS_SUBDIR = "documents"
}

/**
 * Role strings used in discovery messages.
 *
 * Strings rather than an enum: Gson binds enums by name or by ordinal, and an
 * ordinal shift would silently re-label every device.
 */
object SyncRoles {
    const val TABLET = "tablet"
    const val DESKTOP = "desktop"
}

/**
 * Role of a sync participant, for code that prefers an enum over a raw string.
 *
 * [SyncRoles] is the wire form; this is the local spelling of the same two values.
 */
enum class DeviceRole { TABLET, DESKTOP }
