package com.vic.inkflow.data

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * SQLite database manager for Windows desktop app.
 * Schema is 100% compatible with the Android Room database (AppDatabase v23):
 *   - documents(uri PK, displayName, lastOpenedAt, lastPageIndex, isFavorite, folderId)
 *   - strokes(id PK, documentUri, pageIndex, color, strokeWidth,
 *             boundsLeft/Top/Right/Bottom, isHighlighter, shapeType)
 *   - points(id AUTOINCREMENT PK, strokeId FK->strokes ON DELETE CASCADE, x, y, width)
 *   - folders(id PK, name, parentFolderId, sortOrder, createdAt, updatedAt)
 * Column names use Room's camelCase convention so a dump from either device
 * can be opened by the other without migration.
 */
class DatabaseManager(private val dbPath: String) {

    private var connection: Connection? = null

    fun connect() {
        try {
            Class.forName("org.sqlite.JDBC")
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
            // Create documents table (matches Room "documents")
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

            // Create folders table (matches Room "folders")
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

            // Create strokes table (matches Room "strokes")
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS strokes (
                    id TEXT PRIMARY KEY,
                    documentUri TEXT NOT NULL,
                    pageIndex INTEGER NOT NULL,
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

            // Create points table (matches Room "points")
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

            // Create indexes for performance
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_strokes_documentUri ON strokes(documentUri, pageIndex)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_points_strokeId ON points(strokeId)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_documents_folderId ON documents(folderId)")

            logger.info { "Database initialized" }
        }
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

    fun saveStroke(stroke: StrokeEntity, points: List<PointEntity>) {
        connection?.autoCommit = false
        try {
            connection?.prepareStatement("""
                INSERT OR REPLACE INTO strokes
                (id, documentUri, pageIndex, color, strokeWidth, boundsLeft, boundsTop, boundsRight, boundsBottom, isHighlighter, shapeType)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)?.use { stmt ->
                stmt.setString(1, stroke.id)
                stmt.setString(2, stroke.documentUri)
                stmt.setInt(3, stroke.pageIndex)
                stmt.setInt(4, stroke.color)
                stmt.setFloat(5, stroke.strokeWidth)
                stmt.setFloat(6, stroke.boundsLeft)
                stmt.setFloat(7, stroke.boundsTop)
                stmt.setFloat(8, stroke.boundsRight)
                stmt.setFloat(9, stroke.boundsBottom)
                stmt.setInt(10, if (stroke.isHighlighter) 1 else 0)
                stmt.setString(11, stroke.shapeType)
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

    fun getStrokesForPage(documentUri: String, pageIndex: Int): List<StrokeWithPoints> {
        return queryStrokes(
            "SELECT * FROM strokes WHERE documentUri = ? AND pageIndex = ?",
            documentUri, pageIndex
        )
    }

    fun getAllStrokesForDocument(documentUri: String): List<StrokeWithPoints> {
        return queryStrokes("SELECT * FROM strokes WHERE documentUri = ?", documentUri)
    }

    /** Stroke ids currently stored for a document (used by delta sync). */
    fun getStrokeIdsForDocument(documentUri: String): Set<String> {
        val ids = mutableSetOf<String>()
        connection?.prepareStatement("SELECT id FROM strokes WHERE documentUri = ?")?.use { stmt ->
            stmt.setString(1, documentUri)
            stmt.executeQuery().use { rs ->
                while (rs.next()) ids.add(rs.getString("id"))
            }
        }
        return ids
    }

    fun strokeCountForDocument(documentUri: String): Int {
        connection?.prepareStatement("SELECT COUNT(*) FROM strokes WHERE documentUri = ?")?.use { stmt ->
            stmt.setString(1, documentUri)
            stmt.executeQuery().use { rs ->
                if (rs.next()) return rs.getInt(1)
            }
        }
        return 0
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
