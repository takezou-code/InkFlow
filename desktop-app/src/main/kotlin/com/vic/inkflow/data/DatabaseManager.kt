package com.vic.inkflow.data

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * SQLite database manager for Windows desktop app.
 * Provides same data structure as Android Room database.
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
            // Create documents table
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
            
            // Create strokes table
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
            
            // Create points table
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
            
            logger.info { "Database initialized" }
        }
    }
    
    // Document operations
    fun getDocument(uri: String): DocumentEntity? {
        return connection?.prepareStatement("SELECT * FROM documents WHERE uri = ?")?.use { stmt ->
            stmt.setString(1, uri)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    DocumentEntity(
                        uri = rs.getString("uri"),
                        displayName = rs.getString("displayName"),
                        lastOpenedAt = rs.getLong("lastOpenedAt"),
                        lastPageIndex = rs.getInt("lastPageIndex"),
                        isFavorite = rs.getBoolean("isFavorite"),
                        folderId = rs.getString("folderId")
                    )
                } else null
            }
        }
    }
    
    fun getAllDocuments(): List<DocumentEntity> {
        val documents = mutableListOf<DocumentEntity>()
        connection?.createStatement()?.use { stmt ->
            stmt.executeQuery("SELECT * FROM documents ORDER BY lastOpenedAt DESC").use { rs ->
                while (rs.next()) {
                    documents.add(
                        DocumentEntity(
                            uri = rs.getString("uri"),
                            displayName = rs.getString("displayName"),
                            lastOpenedAt = rs.getLong("lastOpenedAt"),
                            lastPageIndex = rs.getInt("lastPageIndex"),
                            isFavorite = rs.getBoolean("isFavorite"),
                            folderId = rs.getString("folderId")
                        )
                    )
                }
            }
        }
        return documents
    }
    
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
    
    // Stroke operations
    fun saveStroke(stroke: StrokeEntity, points: List<PointEntity>) {
        connection?.autoCommit = false
        try {
            // Insert stroke
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
            
            // Delete existing points for this stroke
            connection?.prepareStatement("DELETE FROM points WHERE strokeId = ?")?.use { stmt ->
                stmt.setString(1, stroke.id)
                stmt.executeUpdate()
            }
            
            // Insert new points
            connection?.prepareStatement("INSERT INTO points (strokeId, x, y, width) VALUES (?, ?, ?, ?)")?.use { stmt ->
                for (point in points) {
                    stmt.setString(1, point.strokeId)
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
        val strokes = mutableListOf<StrokeWithPoints>()
        connection?.prepareStatement("SELECT * FROM strokes WHERE documentUri = ? AND pageIndex = ?")?.use { stmt ->
            stmt.setString(1, documentUri)
            stmt.setInt(2, pageIndex)
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
                    
                    val points = getPointsForStroke(stroke.id)
                    strokes.add(StrokeWithPoints(stroke, points))
                }
            }
        }
        return strokes
    }
    
    private fun getPointsForStroke(strokeId: String): List<PointEntity> {
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
