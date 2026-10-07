package com.vic.inkflow.data

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * SQLite store for the desktop mirror (v3).
 *
 * IMPORTANT — this schema is deliberately NOT a column-for-column copy of the
 * Android Room schema. The wire DTOs ([com.vic.inkflow.sync.SyncProtocol]) are
 * the contract; the SQL on each side is an implementation detail. The desktop
 * only reads, so it is free to keep whatever columns make its own queries fast,
 * and — more importantly — the Android side never has to be frozen. The old
 * "schema must stay identical" rule meant every future Android column was a
 * latent desktop bug waiting to happen.
 *
 * Threading: JDBC `Connection` is not thread-safe, and `LocalSyncManager` runs
 * its work on a 4-thread pool. One shared connection across those threads
 * interleaves transaction state (setAutoCommit/commit/rollback are
 * connection-global), which during a document-scoped "delete all strokes, then
 * insert all strokes" produces a silently torn commit: a document with some
 * strokes and none of their points. Every public method here is therefore
 * serialised on a single lock, and the pool is collapsed to one worker.
 */
class DatabaseManager(private val dbPath: String) {

    private var connection: Connection? = null

    /**
     * Serialises every DB operation. Sync is inherently serial anyway (one
     * document at a time, lock-step over the socket), so this costs nothing and
     * removes an entire class of corruption.
     */
    private val dbLock = Any()

    fun connect() {
        try {
            Class.forName("org.sqlite.JDBC")
            // The SQLite JDBC driver does NOT create intermediate directories, so a
            // first run against e.g. C:\Users\<name>\.inkflow\inkflow.db fails with
            // "path to ... does not exist" and the app dies on startup. Create it here.
            java.io.File(dbPath).parentFile?.let { dir ->
                if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
                    throw java.io.IOException("Could not create data directory: $dir")
                }
            }
            connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
            initializeDatabase()
            logger.info { "Database connected: $dbPath" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to connect to database" }
            throw e
        }
    }

    fun disconnect() {
        try {
            connection?.close()
            logger.info { "Database disconnected" }
        } catch (e: SQLException) {
            logger.error(e) { "Error closing database connection" }
        }
    }

    private fun initializeDatabase() {
        connection?.createStatement()?.use { stmt ->
            stmt.execute("PRAGMA journal_mode=WAL")
            stmt.execute("PRAGMA busy_timeout=5000")

            // Schema versioning. `CREATE TABLE IF NOT EXISTS` silently does nothing when the
            // table already exists, so adding a column to an existing table looks like it
            // worked and then blows up at the first query with "no such column". Every
            // shipped database has to be walked forward explicitly.
            migrateSchema(stmt)

            // documents: the mirror of what the tablet has. `uri` is the tablet's
            // file:// path and is only meaningful together with the tablet's
            // instanceId (see sync_state) — it is NOT a global identity.
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS documents (
                    uri TEXT PRIMARY KEY,
                    displayName TEXT NOT NULL,
                    lastOpenedAt INTEGER NOT NULL,
                    lastPageIndex INTEGER NOT NULL DEFAULT 0,
                    isFavorite INTEGER NOT NULL DEFAULT 0,
                    folderId TEXT
                )
            """)

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS folders (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    parentFolderId TEXT,
                    sortOrder INTEGER NOT NULL DEFAULT 0,
                    createdAt INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL DEFAULT 0
                )
            """)

            // strokes: `docY` (v3) is the tablet's S1 single-canvas document-space
            // anchor. Nullable exactly as the tablet stores it — NULL means
            // pre-v24 data the tablet backfills lazily on open. We must not
            // invent a value here; a guessed stride puts strokes in the wrong place.
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS strokes (
                    id TEXT PRIMARY KEY,
                    documentUri TEXT NOT NULL,
                    pageIndex INTEGER NOT NULL,
                    docY REAL,
                    color INTEGER NOT NULL,
                    strokeWidth REAL NOT NULL,
                    boundsLeft REAL NOT NULL,
                    boundsTop REAL NOT NULL,
                    boundsRight REAL NOT NULL,
                    boundsBottom REAL NOT NULL,
                    isHighlighter INTEGER NOT NULL,
                    shapeType TEXT
                )
            """)

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS points (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    strokeId TEXT NOT NULL,
                    x REAL NOT NULL,
                    y REAL NOT NULL,
                    width REAL NOT NULL DEFAULT 0,
                    FOREIGN KEY(strokeId) REFERENCES strokes(id) ON DELETE CASCADE
                )
            """)

            // v3: desktop-only sync bookkeeping. This is the table that makes the
            // content-hash diff work without touching the Android schema.
            //
            //   meta     — the instanceId we last saw, so a reinstall is detectable
            //   docs     — the docVersion we already hold for each document
            //
            // `lastSeenPass` records which sync pass last reported the row, which
            // is how deletion is detected: after a COMPLETE manifest pass, any
            // row not refreshed for one pass is genuinely gone (v2 never deleted,
            // which is what produced permanent orphans).
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS sync_meta (
                    key TEXT PRIMARY KEY,
                    value TEXT
                )
            """)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS sync_docs (
                    instanceId TEXT NOT NULL,
                    uri TEXT NOT NULL,
                    docVersion TEXT NOT NULL,
                    lastSeenPass INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (instanceId, uri)
                )
            """)

            // text_annotations: the tablet's text notes. Added here so notes sync
            // instead of being silently dropped — the previous schema only knew
            // about ink, so every note the user wrote on the tablet simply did
            // not exist on the desktop.
            //
            // `modelY` is the first line's BASELINE (see TextAnnotationEntity), and
            // `docY` is the continuous-canvas anchor with the same NULL semantics as
            // strokes: NULL means "the tablet has not backfilled this yet", and the
            // desktop must not guess it.
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS text_annotations (
                    id TEXT PRIMARY KEY,
                    documentUri TEXT NOT NULL,
                    pageIndex INTEGER NOT NULL,
                    docY REAL,
                    text TEXT NOT NULL,
                    modelX REAL NOT NULL,
                    modelY REAL NOT NULL,
                    fontSize REAL NOT NULL DEFAULT 16,
                    colorArgb INTEGER NOT NULL,
                    isStamp INTEGER NOT NULL DEFAULT 0
                )
            """)

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_text_documentUri ON text_annotations(documentUri, pageIndex)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_text_docY ON text_annotations(documentUri, docY)")

            // sync_proposals + sync_oplog (v5): the desktop's outbox for bidirectional sync.
            //
            // The mirror tables (strokes/texts) mix pulled content and local edits
            // indistinguishably, so at sync time there is no way to reconstruct "what
            // did I change". The oplog records each local edit as an id-level op at
            // write time; at sync time the pending ops for a document become one
            // proposal carrying the base version they were made against.
            //
            // One proposal row per document: ops accumulate until they are sent. The
            // proposalId survives retries so a resend is idempotent on the tablet.
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS sync_proposals (
                    documentUri TEXT PRIMARY KEY,
                    proposalId TEXT NOT NULL,
                    baseDocVersion TEXT NOT NULL,
                    baseInstanceId TEXT,
                    overflow INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL DEFAULT 0
                )
            """)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS sync_oplog (
                    seq INTEGER PRIMARY KEY AUTOINCREMENT,
                    documentUri TEXT NOT NULL,
                    opJson TEXT NOT NULL,
                    createdAt INTEGER NOT NULL DEFAULT 0
                )
            """)

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_oplog_documentUri ON sync_oplog(documentUri, seq)")

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_strokes_documentUri ON strokes(documentUri, pageIndex)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_strokes_docY ON strokes(documentUri, docY)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_points_strokeId ON points(strokeId)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_documents_folderId ON documents(folderId)")

            logger.info { "Database initialized (desktop-owned schema, protocol v3)" }
        }
    }

    /**
     * Walk an existing desktop database forward to the current shape.
     *
     * v1 = documents / folders / strokes / points, `strokes` WITHOUT `docY`, and no
     * sync bookkeeping.
     * v2 = adds `strokes.docY` and the `sync_meta` / `sync_docs` tables.
     * v3 = adds `text_annotations`.
     * v4 = adds `sync_proposals` / `sync_oplog` (the v5 proposal outbox).
     *
     * Additive only — the desktop is a read-only mirror, so there is nothing to
     * rewrite, and a column that the tablet sends as NULL stays NULL rather than
     * being invented here.
     */
    private fun migrateSchema(stmt: java.sql.Statement) {
        val version = stmt.executeQuery("PRAGMA user_version").use { rs ->
            if (rs.next()) rs.getInt(1) else 0
        }
        if (version >= SCHEMA_VERSION) return

        if (version < 2) {
            // `docY` is the tablet's S1 single-canvas document-space anchor. Only add it
            // when the table exists and lacks it, so this is safe to re-run.
            if (tableExists(stmt, "strokes") && !columnExists(stmt, "strokes", "docY")) {
                stmt.execute("ALTER TABLE strokes ADD COLUMN docY REAL")
                logger.info { "Schema v1 -> v2: added strokes.docY" }
            }
        }

        if (version < 3) {
            // text_annotations is created by initializeDatabase()'s CREATE TABLE IF NOT
            // EXISTS statement, so there is nothing to ALTER here. The guard below is
            // belt-and-braces: if that statement ever fails to run, this gives the
            // migration a second chance rather than letting the app start with notes
            // missing and no error.
            if (!tableExists(stmt, "text_annotations")) {
                logger.warn { "Schema v2 -> v3: text_annotations missing after init; check create statement" }
            } else {
                logger.info { "Schema v2 -> v3: text_annotations present" }
            }
        }

        if (version < 4) {
            // sync_proposals / sync_oplog are created by initializeDatabase()'s
            // CREATE TABLE IF NOT EXISTS statements, same pattern as v3.
            if (!tableExists(stmt, "sync_proposals") || !tableExists(stmt, "sync_oplog")) {
                logger.warn { "Schema v3 -> v4: proposal tables missing after init; check create statements" }
            } else {
                logger.info { "Schema v3 -> v4: proposal tables present" }
            }
        }

        // sync_meta / sync_docs are created by initializeDatabase()'s CREATE TABLE IF NOT
        // EXISTS statements, so nothing to do here for them.

        stmt.execute("PRAGMA user_version = $SCHEMA_VERSION")
        logger.info { "Desktop schema at version $SCHEMA_VERSION" }
    }

    private fun tableExists(stmt: java.sql.Statement, table: String): Boolean =
        stmt.executeQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='$table'"
        ).use { it.next() }

    private fun columnExists(stmt: java.sql.Statement, table: String, column: String): Boolean {
        var found = false
        stmt.executeQuery("PRAGMA table_info($table)").use { rs ->
            while (rs.next()) {
                // PRAGMA table_info columns: cid, name, type, notnull, dflt_value, pk
                if (rs.getString("name").equals(column, ignoreCase = true)) {
                    found = true
                    break
                }
            }
        }
        return found
    }

    private companion object {
        const val SCHEMA_VERSION = 4
    }

    // ─── Sync bookkeeping (v3) ──────────────────────────────────────────────

    /**
     * Monotonic sync-pass counter, persisted.
     *
     * It MUST live in the database rather than in memory: the grace window for
     * deletion compares "the pass in which this document was last reported"
     * against the current pass, and that comparison has to stay meaningful
     * across manager instances — the sync manager is recreated on every app
     * launch, so an in-memory counter would reset and orphan detection would
     * never fire.
     */
    fun nextPass(): Long = synchronized(dbLock) {
        val prev = (connection?.prepareStatement("SELECT value FROM sync_meta WHERE key = 'passCounter'")?.use { s ->
            s.executeQuery().use { if (it.next()) it.getString(1)?.toLongOrNull() else null }
        }) ?: 0L
        val next = prev + 1
        connection?.prepareStatement(
            "INSERT OR REPLACE INTO sync_meta (key, value) VALUES ('passCounter', ?)"
        )?.use { it.setString(1, next.toString()); it.executeUpdate() }
        next
    }

    /** The tablet instanceId recorded from the most recent successful sync. */
    fun getKnownInstanceId(): String? = queryOne("SELECT value FROM sync_meta WHERE key = 'instanceId'") as String?

    fun setKnownInstanceId(instanceId: String) =
        connection?.prepareStatement(
            "INSERT OR REPLACE INTO sync_meta (key, value) VALUES ('instanceId', ?)"
        )?.use { it.setString(1, instanceId); it.executeUpdate() }

    /** docVersion currently held for a document, or null if never synced. */
    fun getDocVersion(instanceId: String, uri: String): String? = connection?.prepareStatement(
        "SELECT docVersion FROM sync_docs WHERE instanceId = ? AND uri = ?"
    )?.use { s ->
        s.setString(1, instanceId); s.setString(2, uri)
        s.executeQuery().use { if (it.next()) it.getString(1) else null }
    }

    fun setDocVersion(instanceId: String, uri: String, docVersion: String, pass: Long) =
        connection?.prepareStatement(
            "INSERT OR REPLACE INTO sync_docs (instanceId, uri, docVersion, lastSeenPass) VALUES (?, ?, ?, ?)"
        )?.use {
            it.setString(1, instanceId); it.setString(2, uri)
            it.setString(3, docVersion); it.setLong(4, pass)
            it.executeUpdate()
        }

    /** Every uri we hold for a generation — the input to orphan detection. */
    fun getSyncedUris(instanceId: String): List<String> {
        val out = mutableListOf<String>()
        connection?.prepareStatement("SELECT uri FROM sync_docs WHERE instanceId = ?")?.use { s ->
            s.setString(1, instanceId)
            s.executeQuery().use { while (it.next()) out.add(it.getString(1)) }
        }
        return out
    }

    /**
     * Pass number in which this document was last reported by a complete
     * manifest. A value older than the previous pass means the tablet did not
     * mention it this time, i.e. it was deleted there.
     */
    fun getLastSeenPass(instanceId: String, uri: String): Long? =
        connection?.prepareStatement(
            "SELECT lastSeenPass FROM sync_docs WHERE instanceId = ? AND uri = ?"
        )?.use { s ->
            s.setString(1, instanceId); s.setString(2, uri)
            s.executeQuery().use { if (it.next()) it.getLong(1) else null }
        }

    fun forgetDocVersion(instanceId: String, uri: String) =
        connection?.prepareStatement("DELETE FROM sync_docs WHERE instanceId = ? AND uri = ?")?.use {
            it.setString(1, instanceId); it.setString(2, uri); it.executeUpdate()
        }

    /**
     * Drop everything belonging to a previous tablet generation.
     *
     * Called when the handshake reports a different `instanceId`: Android wiped
     * app-private storage on reinstall, so every URI from the old generation is
     * provably dead. Without this the desktop keeps a growing pile of orphans
     * that no manifest can ever match.
     */
    fun wipeGeneration() {
        connection?.createStatement()?.use { stmt ->
            stmt.executeUpdate("DELETE FROM points")
            stmt.executeUpdate("DELETE FROM strokes")
            stmt.executeUpdate("DELETE FROM text_annotations")
            stmt.executeUpdate("DELETE FROM documents")
            stmt.executeUpdate("DELETE FROM sync_docs")
            // Pending proposals belong to the old generation: their base versions are
            // from another install and can never arbitrate. Keeping them would send
            // proposals the tablet must reject, every pass, forever.
            stmt.executeUpdate("DELETE FROM sync_oplog")
            stmt.executeUpdate("DELETE FROM sync_proposals")
        }
        logger.info { "Wiped previous tablet generation (instanceId changed)" }
    }

    /** Remove a single document and everything hanging off it. */
    fun purgeDocument(uri: String) {
        connection?.createStatement()?.use { stmt ->
            stmt.executeUpdate("DELETE FROM points WHERE strokeId IN (SELECT id FROM strokes WHERE documentUri = '$uri')")
            stmt.executeUpdate("DELETE FROM strokes WHERE documentUri = '$uri'")
            stmt.executeUpdate("DELETE FROM text_annotations WHERE documentUri = '$uri'")
            stmt.executeUpdate("DELETE FROM documents WHERE uri = '$uri'")
            // A purged document is gone on the tablet; any queued proposal for it
            // would come back "unknown document". Drop it here rather than letting
            // every future pass waste a round trip learning that.
            stmt.executeUpdate("DELETE FROM sync_oplog WHERE documentUri = '$uri'")
            stmt.executeUpdate("DELETE FROM sync_proposals WHERE documentUri = '$uri'")
        }
    }

    private fun queryOne(sql: String): Any? = connection?.prepareStatement(sql)?.use { s ->
        s.executeQuery().use { if (it.next()) it.getString(1) else null }
    }

    // ─── Document operations ─────────────────────────────────────────────────

    fun getDocument(uri: String): DocumentEntity? {
        return connection?.prepareStatement("SELECT * FROM documents WHERE uri = ?")?.use { stmt ->
            stmt.setString(1, uri)
            stmt.executeQuery().use { rs ->
                if (rs.next()) mapDocument(rs) else null
            }
        }
    }

    /** Search by display name or folder name (case-insensitive substring). */
    fun searchDocuments(query: String): List<DocumentEntity> {
        if (query.isBlank()) return getAllDocuments()
        val like = "%${query.trim()}%"
        return try {
            val ps = connection?.prepareStatement("""
                SELECT d.* FROM documents d
                LEFT JOIN folders f ON d.folderId = f.id
                WHERE LOWER(d.displayName) LIKE LOWER(?) OR LOWER(IFNULL(f.name,'')) LIKE LOWER(?)
                ORDER BY d.lastOpenedAt DESC
            """) ?: return emptyList()
            ps.use {
                it.setString(1, like)
                it.setString(2, like)
                val rs = it.executeQuery()
                val list = mutableListOf<DocumentEntity>()
                while (rs.next()) list.add(mapDocument(rs))
                list
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to search documents" }
            emptyList()
        }
    }

    fun getAllDocuments(): List<DocumentEntity> {
        val documents = mutableListOf<DocumentEntity>()
        connection?.createStatement()?.use { stmt ->
            stmt.executeQuery("SELECT * FROM documents ORDER BY lastOpenedAt DESC").use { rs ->
                while (rs.next()) documents.add(mapDocument(rs))
            }
        }
        return documents
    }

    private fun mapDocument(rs: java.sql.ResultSet) = DocumentEntity(
        uri = rs.getString("uri"),
        displayName = rs.getString("displayName"),
        lastOpenedAt = rs.getLong("lastOpenedAt"),
        lastPageIndex = rs.getInt("lastPageIndex"),
        isFavorite = rs.getBoolean("isFavorite"),
        folderId = rs.getString("folderId")
    )

    fun saveDocument(document: DocumentEntity) {
        connection?.prepareStatement("""
            INSERT OR REPLACE INTO documents (uri, displayName, lastOpenedAt, lastPageIndex, isFavorite, folderId)
            VALUES (?, ?, ?, ?, ?, ?)
        """)?.use { stmt ->
            stmt.setString(1, document.uri)
            stmt.setString(2, document.displayName)
            stmt.setLong(3, document.lastOpenedAt)
            stmt.setInt(4, document.lastPageIndex)
            stmt.setInt(5, if (document.isFavorite) 1 else 0)
            stmt.setString(6, document.folderId)
            stmt.executeUpdate()
        }
    }

    /**
     * Sync-aware upsert with lastOpenedAt-based conflict resolution (newer wins).
     * @return true if the incoming row was applied, false if local data is newer.
     */
    fun upsertDocumentIfNewer(incoming: DocumentEntity): Boolean {
        val existing = getDocument(incoming.uri)
        if (existing != null && existing.lastOpenedAt >= incoming.lastOpenedAt) {
            return false
        }
        saveDocument(incoming)
        return true
    }

    fun deleteDocument(uri: String) {
        connection?.prepareStatement("DELETE FROM documents WHERE uri = ?")?.use { stmt ->
            stmt.setString(1, uri)
            stmt.executeUpdate()
        }
        deleteStrokesForDocument(uri)
        deleteTextAnnotationsForDocument(uri)
        deleteProposalForDocument(uri)
    }

    /** Drop a document's queued proposal and ops. Used by delete paths. */
    fun deleteProposalForDocument(documentUri: String) {
        connection?.prepareStatement("DELETE FROM sync_oplog WHERE documentUri = ?")?.use { stmt ->
            stmt.setString(1, documentUri)
            stmt.executeUpdate()
        }
        connection?.prepareStatement("DELETE FROM sync_proposals WHERE documentUri = ?")?.use { stmt ->
            stmt.setString(1, documentUri)
            stmt.executeUpdate()
        }
    }

    // ─── Proposal outbox (v5) ─────────────────────────────────────────────

    /** One document's pending proposal header. Null when nothing is queued. */
    data class ProposalRow(
        val documentUri: String,
        val proposalId: String,
        val baseDocVersion: String,
        val baseInstanceId: String?,
        val overflow: Boolean
    )

    fun getProposalRow(documentUri: String): ProposalRow? {
        var row: ProposalRow? = null
        connection?.prepareStatement("SELECT * FROM sync_proposals WHERE documentUri = ?")?.use { stmt ->
            stmt.setString(1, documentUri)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    row = ProposalRow(
                        documentUri = rs.getString("documentUri"),
                        proposalId = rs.getString("proposalId"),
                        baseDocVersion = rs.getString("baseDocVersion"),
                        baseInstanceId = rs.getString("baseInstanceId"),
                        overflow = rs.getBoolean("overflow")
                    )
                }
            }
        }
        return row
    }

    fun upsertProposalRow(row: ProposalRow) = synchronized(dbLock) {
        connection?.prepareStatement(
            """
            INSERT INTO sync_proposals (documentUri, proposalId, baseDocVersion, baseInstanceId, overflow, updatedAt)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(documentUri) DO UPDATE SET
                proposalId = excluded.proposalId,
                baseDocVersion = excluded.baseDocVersion,
                baseInstanceId = excluded.baseInstanceId,
                overflow = excluded.overflow,
                updatedAt = excluded.updatedAt
            """.trimIndent()
        )?.use { stmt ->
            stmt.setString(1, row.documentUri)
            stmt.setString(2, row.proposalId)
            stmt.setString(3, row.baseDocVersion)
            stmt.setString(4, row.baseInstanceId)
            stmt.setBoolean(5, row.overflow)
            stmt.setLong(6, System.currentTimeMillis())
            stmt.executeUpdate()
        }
        Unit
    }

    fun deleteProposalRow(documentUri: String) {
        connection?.prepareStatement("DELETE FROM sync_proposals WHERE documentUri = ?")?.use { stmt ->
            stmt.setString(1, documentUri)
            stmt.executeUpdate()
        }
    }

    fun setProposalOverflow(documentUri: String, overflow: Boolean) = synchronized(dbLock) {
        connection?.prepareStatement("UPDATE sync_proposals SET overflow = ? WHERE documentUri = ?")?.use { stmt ->
            stmt.setBoolean(1, overflow)
            stmt.setString(2, documentUri)
            stmt.executeUpdate()
        }
        Unit
    }

    fun appendOp(documentUri: String, opJson: String) = synchronized(dbLock) {
        connection?.prepareStatement(
            "INSERT INTO sync_oplog (documentUri, opJson, createdAt) VALUES (?, ?, ?)"
        )?.use { stmt ->
            stmt.setString(1, documentUri)
            stmt.setString(2, opJson)
            stmt.setLong(3, System.currentTimeMillis())
            stmt.executeUpdate()
        }
        Unit
    }

    /** (seq, opJson) in send order. */
    fun getOps(documentUri: String): List<Pair<Long, String>> {
        val out = mutableListOf<Pair<Long, String>>()
        connection?.prepareStatement(
            "SELECT seq, opJson FROM sync_oplog WHERE documentUri = ? ORDER BY seq"
        )?.use { stmt ->
            stmt.setString(1, documentUri)
            stmt.executeQuery().use { rs ->
                while (rs.next()) out.add(rs.getLong("seq") to rs.getString("opJson"))
            }
        }
        return out
    }

    fun countOps(documentUri: String): Int {
        var n = 0
        connection?.prepareStatement("SELECT COUNT(*) FROM sync_oplog WHERE documentUri = ?")?.use { stmt ->
            stmt.setString(1, documentUri)
            stmt.executeQuery().use { rs -> if (rs.next()) n = rs.getInt(1) }
        }
        return n
    }

    fun totalPendingOps(): Int {
        var n = 0
        connection?.createStatement()?.use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM sync_oplog").use { rs ->
                if (rs.next()) n = rs.getInt(1)
            }
        }
        return n
    }

    fun pendingProposalDocuments(): List<String> {
        val out = mutableListOf<String>()
        connection?.createStatement()?.use { stmt ->
            stmt.executeQuery("SELECT documentUri FROM sync_proposals").use { rs ->
                while (rs.next()) out.add(rs.getString(1))
            }
        }
        return out
    }

    /** Delete the oldest ops beyond [keepNewest], for the overflow cap. */
    fun deleteOldestOps(documentUri: String, keepNewest: Int) = synchronized(dbLock) {
        connection?.prepareStatement(
            """
            DELETE FROM sync_oplog WHERE documentUri = ? AND seq NOT IN (
                SELECT seq FROM sync_oplog WHERE documentUri = ? ORDER BY seq DESC LIMIT ?
            )
            """.trimIndent()
        )?.use { stmt ->
            stmt.setString(1, documentUri)
            stmt.setString(2, documentUri)
            stmt.setInt(3, keepNewest)
            stmt.executeUpdate()
        }
        Unit
    }

    /** Delete ops up to and including [maxSeq] — what a send consumed. */
    fun deleteOpsUpTo(documentUri: String, maxSeq: Long) {
        connection?.prepareStatement("DELETE FROM sync_oplog WHERE documentUri = ? AND seq <= ?")?.use { stmt ->
            stmt.setString(1, documentUri)
            stmt.setLong(2, maxSeq)
            stmt.executeUpdate()
        }
    }

    fun deleteAllOps(documentUri: String) {
        connection?.prepareStatement("DELETE FROM sync_oplog WHERE documentUri = ?")?.use { stmt ->
            stmt.setString(1, documentUri)
            stmt.executeUpdate()
        }
    }

    // ─── Folder operations (category support) ────────────────────────────────

    fun getAllFolders(): List<FolderEntity> {
        val folders = mutableListOf<FolderEntity>()
        connection?.createStatement()?.use { stmt ->
            stmt.executeQuery("SELECT * FROM folders ORDER BY sortOrder, name").use { rs ->
                while (rs.next()) {
                    folders.add(
                        FolderEntity(
                            id = rs.getString("id"),
                            name = rs.getString("name"),
                            parentFolderId = rs.getString("parentFolderId"),
                            sortOrder = rs.getInt("sortOrder"),
                            createdAt = rs.getLong("createdAt"),
                            updatedAt = rs.getLong("updatedAt")
                        )
                    )
                }
            }
        }
        return folders
    }

    fun saveFolder(folder: FolderEntity) {
        connection?.prepareStatement("""
            INSERT OR REPLACE INTO folders (id, name, parentFolderId, sortOrder, createdAt, updatedAt)
            VALUES (?, ?, ?, ?, ?, ?)
        """)?.use { stmt ->
            stmt.setString(1, folder.id)
            stmt.setString(2, folder.name)
            stmt.setString(3, folder.parentFolderId)
            stmt.setInt(4, folder.sortOrder)
            stmt.setLong(5, folder.createdAt)
            stmt.setLong(6, folder.updatedAt)
            stmt.executeUpdate()
        }
    }

    /**
     * Replace the whole folder list with the tablet's snapshot.
     *
     * Wholesale rather than merged: folders are display-only categorization with no
     * local writes on the desktop (nothing here creates folders), so there is no
     * local state a merge would need to preserve. A merge would instead keep folders
     * the user deleted on the tablet alive forever.
     *
     * Documents pointing at vanished folders are reset to uncategorized. Leaving the
     * dangling id would make those documents disappear from every filtered view while
     * still existing — visible in "all", invisible everywhere else, which reads as
     * data loss.
     */
    fun replaceAllFolders(folders: List<FolderEntity>) = synchronized(dbLock) {
        connection?.autoCommit = false
        try {
            connection?.createStatement()?.use { stmt ->
                stmt.executeUpdate("DELETE FROM folders")
            }
            folders.forEach { saveFolder(it) }
            val valid = folders.map { it.id }.toSet()
            if (valid.isEmpty()) {
                connection?.createStatement()?.use { stmt ->
                    stmt.executeUpdate("UPDATE documents SET folderId = NULL WHERE folderId IS NOT NULL")
                }
            } else {
                // Placeholders keep the statement valid for any size; an empty IN ()
                // is a syntax error, hence the branch above.
                val marks = valid.joinToString(",") { "?" }
                connection?.prepareStatement(
                    "UPDATE documents SET folderId = NULL WHERE folderId IS NOT NULL AND folderId NOT IN ($marks)"
                )?.use { stmt ->
                    valid.forEachIndexed { i, id -> stmt.setString(i + 1, id) }
                    stmt.executeUpdate()
                }
            }
            connection?.commit()
        } catch (e: Exception) {
            connection?.rollback()
            throw e
        } finally {
            connection?.autoCommit = true
        }
        Unit
    }

    // ─── Stroke operations ───────────────────────────────────────────────────

    fun saveStroke(stroke: StrokeEntity, points: List<PointEntity>) = synchronized(dbLock) {
        connection?.autoCommit = false
        try {
            connection?.prepareStatement("""
                INSERT OR REPLACE INTO strokes
                (id, documentUri, pageIndex, docY, color, strokeWidth, boundsLeft, boundsTop, boundsRight, boundsBottom, isHighlighter, shapeType)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)?.use { stmt ->
                stmt.setString(1, stroke.id)
                stmt.setString(2, stroke.documentUri)
                stmt.setInt(3, stroke.pageIndex)
                if (stroke.docY == null) stmt.setNull(4, java.sql.Types.REAL) else stmt.setFloat(4, stroke.docY)
                stmt.setInt(5, stroke.color)
                stmt.setFloat(6, stroke.strokeWidth)
                stmt.setFloat(7, stroke.boundsLeft)
                stmt.setFloat(8, stroke.boundsTop)
                stmt.setFloat(9, stroke.boundsRight)
                stmt.setFloat(10, stroke.boundsBottom)
                stmt.setInt(11, if (stroke.isHighlighter) 1 else 0)
                stmt.setString(12, stroke.shapeType)
                stmt.executeUpdate()
            }

            connection?.prepareStatement("DELETE FROM points WHERE strokeId = ?")?.use { stmt ->
                stmt.setString(1, stroke.id)
                stmt.executeUpdate()
            }

            connection?.prepareStatement("INSERT INTO points (strokeId, x, y, width) VALUES (?, ?, ?, ?)")?.use { stmt ->
                for (point in points) {
                    stmt.setString(1, stroke.id)
                    stmt.setFloat(2, point.x)
                    stmt.setFloat(3, point.y)
                    stmt.setFloat(4, point.width)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }

            connection?.commit()
        } catch (e: Exception) {
            connection?.rollback()
            throw e
        } finally {
            connection?.autoCommit = true
        }
    }

    fun getStrokesForPage(documentUri: String, pageIndex: Int): List<StrokeWithPoints> =
        synchronized(dbLock) {
            queryStrokes(
                "SELECT * FROM strokes WHERE documentUri = ? AND pageIndex = ?",
                documentUri, pageIndex
            )
        }

    fun getAllStrokesForDocument(documentUri: String): List<StrokeWithPoints> =
        synchronized(dbLock) { queryStrokes("SELECT * FROM strokes WHERE documentUri = ?", documentUri) }

    /** Stroke ids currently stored for a document (used by delta sync). */
    fun getStrokeIdsForDocument(documentUri: String): Set<String> = synchronized(dbLock) {
        val ids = mutableSetOf<String>()
        connection?.prepareStatement("SELECT id FROM strokes WHERE documentUri = ?")?.use { stmt ->
            stmt.setString(1, documentUri)
            stmt.executeQuery().use { rs ->
                while (rs.next()) ids.add(rs.getString("id"))
            }
        }
        ids
    }

    fun strokeCountForDocument(documentUri: String): Int = synchronized(dbLock) {
        var n = 0
        connection?.prepareStatement("SELECT COUNT(*) FROM strokes WHERE documentUri = ?")?.use { stmt ->
            stmt.setString(1, documentUri)
            stmt.executeQuery().use { rs -> if (rs.next()) n = rs.getInt(1) }
        }
        n
    }

    fun deleteStroke(strokeId: String) {
        connection?.prepareStatement("DELETE FROM strokes WHERE id = ?")?.use { stmt ->
            stmt.setString(1, strokeId)
            stmt.executeUpdate()
        }
        // Cascade manually in case PRAGMA foreign_keys is off on this driver/session.
        connection?.prepareStatement("DELETE FROM points WHERE strokeId = ?")?.use { stmt ->
            stmt.setString(1, strokeId)
            stmt.executeUpdate()
        }
    }

    fun deleteStrokesForDocument(documentUri: String) {
        for (id in getStrokeIdsForDocument(documentUri)) deleteStroke(id)
    }

    /**
     * Per-page stroke counts for incremental sync.
     *
     * A GROUP BY query, not a load: the whole point of the `page_counts` verb is to
     * decide *whether* to load a page, so counting must not itself load anything.
     */
    fun countStrokesByPage(documentUri: String): Map<Int, Int> {
        val out = mutableMapOf<Int, Int>()
        connection?.prepareStatement(
            "SELECT pageIndex, COUNT(*) FROM strokes WHERE documentUri = ? GROUP BY pageIndex"
        )?.use { stmt ->
            stmt.setString(1, documentUri)
            stmt.executeQuery().use { rs ->
                while (rs.next()) out[rs.getInt(1)] = rs.getInt(2)
            }
        }
        return out
    }

    /**
     * Delete one page's strokes (and their points) without touching other pages.
     *
     * Used by incremental pull: only the pages the tablet reports as changed are
     * replaced. A whole-document replace here would discard clean pages' local
     * edits along with the dirty ones.
     */
    fun deleteStrokesForPage(documentUri: String, pageIndex: Int) = synchronized(dbLock) {
        val ids = mutableListOf<String>()
        connection?.prepareStatement(
            "SELECT id FROM strokes WHERE documentUri = ? AND pageIndex = ?"
        )?.use { stmt ->
            stmt.setString(1, documentUri)
            stmt.setInt(2, pageIndex)
            stmt.executeQuery().use { rs -> while (rs.next()) ids.add(rs.getString(1)) }
        }
        ids.forEach { deleteStroke(it) }
        Unit
    }

    /**
     * Replace all local strokes of a document with the remote snapshot.
     * Each stroke is written transactionally via [saveStroke]; callers should
     * only invoke this when conflict resolution decided the remote wins.
     */
    fun replaceStrokesForDocument(documentUri: String, strokes: List<StrokeWithPoints>) {
        deleteStrokesForDocument(documentUri)
        strokes.forEach { saveStroke(it.stroke, it.points) }
    }

    // ─── Text annotations ────────────────────────────────────────────────────

    /**
     * Insert or update one text annotation.
     *
     * UPDATE-not-replace so the id survives: the tablet identifies a note by id,
     * and a new id would read as "deleted here, added there" on the next sync.
     */
    fun saveTextAnnotation(annotation: TextAnnotationEntity) = synchronized(dbLock) {
        connection?.prepareStatement(
            """
            INSERT INTO text_annotations
                (id, documentUri, pageIndex, docY, text, modelX, modelY, fontSize, colorArgb, isStamp)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                documentUri = excluded.documentUri,
                pageIndex = excluded.pageIndex,
                docY = excluded.docY,
                text = excluded.text,
                modelX = excluded.modelX,
                modelY = excluded.modelY,
                fontSize = excluded.fontSize,
                colorArgb = excluded.colorArgb,
                isStamp = excluded.isStamp
            """.trimIndent()
        )?.use { stmt ->
            stmt.setString(1, annotation.id)
            stmt.setString(2, annotation.documentUri)
            stmt.setInt(3, annotation.pageIndex)
            if (annotation.docY == null) stmt.setNull(4, java.sql.Types.REAL) else stmt.setFloat(4, annotation.docY)
            stmt.setString(5, annotation.text)
            stmt.setFloat(6, annotation.modelX)
            stmt.setFloat(7, annotation.modelY)
            stmt.setFloat(8, annotation.fontSize)
            stmt.setInt(9, annotation.colorArgb)
            stmt.setBoolean(10, annotation.isStamp)
            stmt.executeUpdate()
        }
        Unit
    }

    fun getTextAnnotationsForPage(documentUri: String, pageIndex: Int): List<TextAnnotationEntity> =
        queryText("SELECT * FROM text_annotations WHERE documentUri = ? AND pageIndex = ? ORDER BY rowid", documentUri, pageIndex)

    fun getAllTextAnnotationsForDocument(documentUri: String): List<TextAnnotationEntity> =
        queryText("SELECT * FROM text_annotations WHERE documentUri = ? ORDER BY rowid", documentUri)

    fun getTextAnnotationIdsForDocument(documentUri: String): Set<String> = synchronized(dbLock) {
        val ids = mutableSetOf<String>()
        connection?.prepareStatement("SELECT id FROM text_annotations WHERE documentUri = ?")?.use { stmt ->
            stmt.setString(1, documentUri)
            stmt.executeQuery().use { rs -> while (rs.next()) ids.add(rs.getString(1)) }
        }
        ids
    }

    fun textAnnotationCountForDocument(documentUri: String): Int {
        var n = 0
        connection?.prepareStatement("SELECT COUNT(*) FROM text_annotations WHERE documentUri = ?")?.use { stmt ->
            stmt.setString(1, documentUri)
            stmt.executeQuery().use { rs -> if (rs.next()) n = rs.getInt(1) }
        }
        return n
    }

    fun deleteTextAnnotation(id: String) {
        connection?.prepareStatement("DELETE FROM text_annotations WHERE id = ?")?.use { stmt ->
            stmt.setString(1, id)
            stmt.executeUpdate()
        }
    }

    fun deleteTextAnnotationsForDocument(documentUri: String) {
        connection?.prepareStatement("DELETE FROM text_annotations WHERE documentUri = ?")?.use { stmt ->
            stmt.setString(1, documentUri)
            stmt.executeUpdate()
        }
    }

    /** Per-page note counts for incremental sync. See [countStrokesByPage]. */
    fun countTextsByPage(documentUri: String): Map<Int, Int> {
        val out = mutableMapOf<Int, Int>()
        connection?.prepareStatement(
            "SELECT pageIndex, COUNT(*) FROM text_annotations WHERE documentUri = ? GROUP BY pageIndex"
        )?.use { stmt ->
            stmt.setString(1, documentUri)
            stmt.executeQuery().use { rs ->
                while (rs.next()) out[rs.getInt(1)] = rs.getInt(2)
            }
        }
        return out
    }

    /** Delete one page's notes. See [deleteStrokesForPage] for why per-page matters. */
    fun deleteTextAnnotationsForPage(documentUri: String, pageIndex: Int) {
        connection?.prepareStatement(
            "DELETE FROM text_annotations WHERE documentUri = ? AND pageIndex = ?"
        )?.use { stmt ->
            stmt.setString(1, documentUri)
            stmt.setInt(2, pageIndex)
            stmt.executeUpdate()
        }
    }

    fun replaceTextAnnotationsForDocument(documentUri: String, annotations: List<TextAnnotationEntity>) {
        deleteTextAnnotationsForDocument(documentUri)
        annotations.forEach { saveTextAnnotation(it) }
    }

    private fun queryText(sql: String, vararg args: Any?): List<TextAnnotationEntity> {
        val out = mutableListOf<TextAnnotationEntity>()
        connection?.prepareStatement(sql)?.use { stmt ->
            args.forEachIndexed { i, a ->
                when (a) {
                    is Int -> stmt.setInt(i + 1, a)
                    else -> stmt.setString(i + 1, a as String)
                }
            }
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    out.add(
                        TextAnnotationEntity(
                            id = rs.getString("id"),
                            documentUri = rs.getString("documentUri"),
                            pageIndex = rs.getInt("pageIndex"),
                            docY = rs.getObject("docY")?.let { (it as Number).toFloat() },
                            text = rs.getString("text"),
                            modelX = rs.getFloat("modelX"),
                            modelY = rs.getFloat("modelY"),
                            fontSize = rs.getFloat("fontSize"),
                            colorArgb = rs.getInt("colorArgb"),
                            isStamp = rs.getBoolean("isStamp")
                        )
                    )
                }
            }
        }
        return out
    }

    private fun queryStrokes(sql: String, vararg args: Any?): List<StrokeWithPoints> {
        val strokes = mutableListOf<StrokeWithPoints>()
        connection?.prepareStatement(sql)?.use { stmt ->
            args.forEachIndexed { i, a ->
                when (a) {
                    is Int -> stmt.setInt(i + 1, a)
                    else -> stmt.setString(i + 1, a as String)
                }
            }
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val stroke = StrokeEntity(
                        id = rs.getString("id"),
                        documentUri = rs.getString("documentUri"),
                        pageIndex = rs.getInt("pageIndex"),
                        docY = rs.getObject("docY")?.let { (it as Number).toFloat() },
                        color = rs.getInt("color"),
                        strokeWidth = rs.getFloat("strokeWidth"),
                        boundsLeft = rs.getFloat("boundsLeft"),
                        boundsTop = rs.getFloat("boundsTop"),
                        boundsRight = rs.getFloat("boundsRight"),
                        boundsBottom = rs.getFloat("boundsBottom"),
                        isHighlighter = rs.getBoolean("isHighlighter"),
                        shapeType = rs.getString("shapeType")
                    )
                    strokes.add(StrokeWithPoints(stroke, getPointsForStroke(stroke.id)))
                }
            }
        }
        return strokes
    }

    fun getPointsForStroke(strokeId: String): List<PointEntity> {
        val points = mutableListOf<PointEntity>()
        connection?.prepareStatement("SELECT * FROM points WHERE strokeId = ? ORDER BY id")?.use { stmt ->
            stmt.setString(1, strokeId)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    points.add(
                        PointEntity(
                            id = rs.getLong("id"),
                            strokeId = rs.getString("strokeId"),
                            x = rs.getFloat("x"),
                            y = rs.getFloat("y"),
                            width = rs.getFloat("width")
                        )
                    )
                }
            }
        }
        return points
    }
}
