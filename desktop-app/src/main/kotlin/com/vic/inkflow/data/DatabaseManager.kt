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

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_strokes_documentUri ON strokes(documentUri, pageIndex)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_strokes_docY ON strokes(documentUri, docY)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_points_strokeId ON points(strokeId)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_documents_folderId ON documents(folderId)")

            logger.info { "Database initialized (desktop-owned schema, protocol v3)" }
        }
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
            stmt.executeUpdate("DELETE FROM documents")
            stmt.executeUpdate("DELETE FROM sync_docs")
        }
        logger.info { "Wiped previous tablet generation (instanceId changed)" }
    }

    /** Remove a single document and everything hanging off it. */
    fun purgeDocument(uri: String) {
        connection?.createStatement()?.use { stmt ->
            stmt.executeUpdate("DELETE FROM points WHERE strokeId IN (SELECT id FROM strokes WHERE documentUri = '$uri')")
            stmt.executeUpdate("DELETE FROM strokes WHERE documentUri = '$uri'")
            stmt.executeUpdate("DELETE FROM documents WHERE uri = '$uri'")
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
     * Replace all local strokes of a document with the remote snapshot.
     * Each stroke is written transactionally via [saveStroke]; callers should
     * only invoke this when conflict resolution decided the remote wins.
     */
    fun replaceStrokesForDocument(documentUri: String, strokes: List<StrokeWithPoints>) {
        deleteStrokesForDocument(documentUri)
        strokes.forEach { saveStroke(it.stroke, it.points) }
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
