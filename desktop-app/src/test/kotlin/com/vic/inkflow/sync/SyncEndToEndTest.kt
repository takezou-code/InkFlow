package com.vic.inkflow.sync

import com.google.gson.Gson
import com.vic.inkflow.data.DatabaseManager
import com.vic.inkflow.data.DocumentEntity
import com.vic.inkflow.data.PointEntity
import com.vic.inkflow.data.StrokeEntity
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end sync test over real TCP sockets on localhost:
 *  - Starts a fake "tablet" server that speaks protocol v2 (manifest, detail, file meta/data).
 *  - Runs the desktop's [LocalSyncManager.syncWithTablet] against it.
 *  - Verifies: newer-wins conflict resolution, stroke snapshot replacement,
 *    PDF body download with SHA-256 verification into documentsDir mirror,
 *    and that the document `uri` primary key is NOT rewritten (sync idempotency).
 */
class SyncEndToEndTest {

    private val gson = Gson()

    @Test
    fun `pull sync transfers document strokes and pdf body`() {
        // ── tablet-side fixture: a real PDF file + DB row ──
        val tabletDir = createTempDir("tablet", "")
        val tabletDocs = tabletDir.resolve("Documents").apply { mkdirs() }
        val pdfBytes = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() } // ~3 MiB, multi-chunk
        val tabletPdf = tabletDocs.resolve("lecture-notes.pdf").apply { writeBytes(pdfBytes) }
        val tabletUri = "file://" + tabletPdf.absolutePath
        val sha = SyncWire.sha256Hex(tabletPdf)!!

        val stroke = StrokeEntity(
            id = "stroke-1", documentUri = tabletUri, pageIndex = 2,
            color = 0xFF00FF00.toInt(), strokeWidth = 3f,
            boundsLeft = 1f, boundsTop = 2f, boundsRight = 90f, boundsBottom = 80f
        )
        val points = listOf(PointEntity(strokeId = "stroke-1", x = 1f, y = 2f, width = 3f))

        // ── fake tablet server (accept loop: one session per connection) ──
        val srv = ServerSocket(0)
        val acceptLoop = Thread {
            while (!srv.isClosed) {
                try {
                    srv.accept().use { s -> serveFakeTablet(s, tabletUri, tabletPdf, sha, stroke, points) }
                } catch (_: Exception) { break }
            }
        }
        acceptLoop.isDaemon = true
        acceptLoop.start()
        run {
            // ── desktop side ──
            val desktopDir = createTempDir("desktop", "")
            val db = DatabaseManager(desktopDir.resolve("inkflow.db").absolutePath).also { it.connect() }
            try {
                // Pre-existing LOCAL-NEWER document: must be skipped (conflict resolution).
                val localOnlyUri = "file:///local/stale.pdf"
                db.saveDocument(DocumentEntity(localOnlyUri, "stale.pdf", lastOpenedAt = Long.MAX_VALUE / 2))

                val mgr = LocalSyncManager(db, desktopDir.absolutePath, transferPort = srv.localPort)
                val device = DiscoveredDevice("tab-e2e", "FakeTab", "127.0.0.1", srv.localPort, System.currentTimeMillis())

                val result = mgr.syncWithTablet(device)

                println("E2E-RESULT >>> $result")
                assertEquals(0, result.errors.size, "errors: ${result.errors}")
                assertEquals(1, result.documentsUpdated)
                assertEquals(1, result.strokesPulled)
                assertEquals(1, result.filesTransferred)

                // Document pulled with uri intact and remote timestamp.
                val doc = db.getDocument(tabletUri)!!
                assertEquals(tabletUri, doc.uri)
                assertEquals("Lecture Notes", doc.displayName)
                assertEquals(2_000_000_000_000L, doc.lastOpenedAt)

                // Strokes replaced exactly.
                val synced = db.getAllStrokesForDocument(tabletUri)
                assertEquals(1, synced.size)
                assertEquals(stroke, synced[0].stroke)
                assertEquals(points.map { listOf(it.x, it.y, it.width) },
                             synced[0].points.map { listOf(it.x, it.y, it.width) })

                // PDF body downloaded to documents/<filename> mirror, checksum matches.
                val mirrored = desktopDir.resolve("documents/lecture-notes.pdf")
                assertTrue(mirrored.exists(), "mirrored file missing: $mirrored")
                assertEquals(pdfBytes.size.toLong(), mirrored.length())
                assertEquals(sha, SyncWire.sha256Hex(mirrored))

                // Idempotency: second pass must not re-pull anything.
                val result2 = mgr.syncWithTablet(device)
                assertEquals(0, result2.documentsUpdated, "second pass should skip up-to-date doc")
                assertEquals(0, result2.filesTransferred, "second pass should not re-download identical file")

                // Local-newer document untouched.
                assertEquals(Long.MAX_VALUE / 2, db.getDocument(localOnlyUri)!!.lastOpenedAt)

                mgr.stopListening()
            } finally {
                db.disconnect()
                srv.close()
            }
        }
    }

    /** Minimal protocol-v2 tablet server serving one document + its PDF body. */
    private fun serveFakeTablet(
        socket: Socket, uri: String, pdf: java.io.File, sha: String,
        stroke: StrokeEntity, points: List<PointEntity>
    ) {
        socket.soTimeout = 30_000
        val input = DataInputStream(socket.getInputStream())
        val out = DataOutputStream(socket.getOutputStream())
        while (true) {
            val reqText = SyncWire.readText(input) ?: return
            val req = gson.fromJson(reqText, SyncRequest::class.java)
            fun respond(type: String, payload: Any?) =
                SyncWire.writeText(out, gson.toJson(SyncResponse(type, payload = gson.toJson(payload))))

            when (req.type) {
                SyncRequest.TYPE_HANDSHAKE -> SyncWire.writeText(out, gson.toJson(SyncResponse(req.type)))
                SyncRequest.TYPE_DOC_MANIFEST -> respond(req.type, listOf(
                    DocumentManifestEntry(uri, "Lecture Notes", 2_000_000_000_000L, 1, true, sha, pdf.length()),
                    // A document the desktop has locally with a NEWER timestamp -> must be skipped.
                    DocumentManifestEntry("file:///local/stale.pdf", "stale.pdf", 1_000L, 0, false, null, null)
                ))
                SyncRequest.TYPE_DOCUMENT_DETAIL -> respond(req.type, DocumentDetailPayload(
                    DocumentEntity(uri, "Lecture Notes", 2_000_000_000_000L, lastPageIndex = 2),
                    listOf(com.vic.inkflow.data.StrokeWithPoints(stroke, points))
                ))
                SyncRequest.TYPE_FILE_META -> respond(req.type, FileMetaPayload(true, pdf.length(), sha))
                SyncRequest.TYPE_FILE_DATA -> {
                    val chunk = RandomAccessFileAccessor.readChunk(pdf, req.offset ?: 0L, req.limit ?: (1 shl 20))
                    SyncWire.writeFrame(out, chunk)
                }
                else -> SyncWire.writeText(out, gson.toJson(SyncResponse(req.type, ok = false, error = "unknown")))
            }
        }
    }
}
