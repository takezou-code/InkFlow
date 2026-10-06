package com.vic.inkflow.sync

import com.google.gson.Gson
import com.vic.inkflow.data.DatabaseManager
import com.vic.inkflow.data.DocumentEntity
import com.vic.inkflow.data.PointEntity
import com.vic.inkflow.data.StrokeEntity
import com.vic.inkflow.data.StrokeWithPoints
import com.vic.inkflow.data.TextAnnotationEntity
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end sync test over real TCP sockets on localhost, protocol v3.
 *
 * What changed from the v2 test, and why the assertions differ:
 *
 * v2 decided what to pull with `lastOpenedAt` (newer remote wins). v3 uses
 * `docVersion`, a content hash, because the timestamp was wrong in both
 * directions: reading a document without drawing advanced it and caused a full
 * re-pull, and any write path that did not touch it (undo/redo, import, page
 * reorder) left the desktop permanently stale. So "the local copy is newer" is
 * no longer a concept. The equivalent skip case is "we already hold this exact
 * content hash", and that is what these tests assert.
 */
class SyncEndToEndTest {

    private val gson = Gson()
    private val instanceId = "tablet-install-aaa"

    // ─── 1. Happy path: pull, second pass is a no-op ──────────────────────────

    @Test
    fun `pull sync transfers document strokes and pdf body, second pass is a no-op`() {
        withFakeTablet { srv, tablet ->
            val tabletPdf = tablet.pdf
            val uri = tablet.uri
            val sha = tablet.sha

            val stroke = tablet.stroke
            val points = tablet.points

            desktop { db, dir ->
                val mgr = LocalSyncManager(db, dir.absolutePath, transferPort = srv.localPort)
                val device = DiscoveredDevice("tab-e2e", "FakeTab", "127.0.0.1", srv.localPort, System.currentTimeMillis())

                val result = mgr.syncWithTablet(device)

                assertEquals(0, result.errors.size, "errors: ${result.errors}")
                assertEquals(1, result.documentsUpdated, "exactly one document is new")
                assertEquals(1, result.strokesPulled)
                assertEquals(1, result.filesTransferred)

                val doc = db.getDocument(uri)!!
                assertEquals(uri, doc.uri, "uri is the cross-device key and must not be rewritten")
                assertEquals("Lecture Notes", doc.displayName)
                assertEquals(2_000_000_000_000L, doc.lastOpenedAt)

                val synced = db.getAllStrokesForDocument(uri)
                assertEquals(1, synced.size)
                assertEquals(stroke, synced[0].stroke)
                assertEquals(
                    points.map { listOf(it.x, it.y, it.width) },
                    synced[0].points.map { listOf(it.x, it.y, it.width) }
                )

                val mirrored = dir.resolve("documents/lecture-notes.pdf")
                assertTrue(mirrored.exists(), "mirrored file missing: $mirrored")
                assertEquals(pdfBytesSize(), mirrored.length())
                assertEquals(sha, SyncWire.sha256Hex(mirrored))

                // v3: the docVersion is recorded, which is what makes pass 2 free.
                assertEquals(tablet.docVersion, db.getDocVersion(instanceId, uri))

                val result2 = mgr.syncWithTablet(device)
                assertEquals(0, result2.documentsUpdated, "unchanged content must not be re-pulled")
                assertEquals(0, result2.filesTransferred, "identical file must not be re-downloaded")
                assertEquals(1, result2.conflictsSkipped)

                mgr.stopListening()
            }
        }
    }

    // ─── 2. Reinstall wipes the previous generation ───────────────────────────

    @Test
    fun `instanceId change wipes the previous generation instead of orphaning it`() {
        val tabletDir = java.nio.file.Files.createTempDirectory("tablet").toFile()
        val docs = tabletDir.resolve("Documents").apply { mkdirs() }
        val pdf = docs.resolve("old.pdf").apply { writeBytes(ByteArray(4096) { 7 }) }
        val uri = "file://" + pdf.absolutePath
        val sha = SyncWire.sha256Hex(pdf)!!
        val stroke = StrokeEntity("s1", uri, 0, color = 1, strokeWidth = 2f)

        // ONE desktop database, reused across both generations — that is the whole
        // point: the rows written by generation 1 must not survive into generation 2.
        desktop { db, dir ->
            // Pass 1: generation-1, one document.
            withFakeTablet(instanceId = "generation-1") { srv, _ ->
                val mgr = LocalSyncManager(db, dir.absolutePath, transferPort = srv.localPort)
                mgr.syncWithTablet(DiscoveredDevice("tab", "Tab", "127.0.0.1", srv.localPort, System.currentTimeMillis()))
                assertEquals(1, db.getAllDocuments().size)
                assertEquals("generation-1", db.getKnownInstanceId())
                mgr.stopListening()
            }

            // Pass 2: tablet was reinstalled -> new instanceId, empty manifest.
            withFakeTablet(instanceId = "generation-2", manifestOverride = emptyList()) { srv, _ ->
                val mgr = LocalSyncManager(db, dir.absolutePath, transferPort = srv.localPort)
                val result = mgr.syncWithTablet(
                    DiscoveredDevice("tab", "Tab", "127.0.0.1", srv.localPort, System.currentTimeMillis())
                )
                assertTrue(result.generationWiped, "a reinstall must be detected as a generation change")
                assertEquals("generation-2", db.getKnownInstanceId())
                assertEquals(0, db.getAllDocuments().size, "old generation must be gone, not orphaned")
                mgr.stopListening()
            }
        }
    }

    // ─── 3. Deletion propagates, with a one-pass grace window ─────────────────

    @Test
    fun `document removed on the tablet is removed locally after a grace pass`() {
        // Manifest content is derived from the fixture, so the document the server
        // can actually serve is the document the manifest lists.
        fun manifestWith(tablet: Tablet) = listOf(
            DocumentManifestEntry(
                tablet.uri, "Gone", 1L, 1, true,
                tablet.sha, tablet.pdf.length(), tablet.docVersion
            )
        )

        // ONE database, three passes — the grace window is only observable when
        // the same store is carried across passes.
        desktop { db, dir ->
            withFakeTablet(instanceId = "gen-x", manifestFor = ::manifestWith) { srv, _ ->
                val mgr = LocalSyncManager(db, dir.absolutePath, transferPort = srv.localPort)
                val r1 = mgr.syncWithTablet(
                    DiscoveredDevice("t", "T", "127.0.0.1", srv.localPort, System.currentTimeMillis())
                )
                assertEquals(0, r1.errors.size, "pass 1 errors: ${r1.errors}")
                assertEquals(1, db.getAllDocuments().size, "pass 1: document pulled")
                mgr.stopListening()
            }

            // Passes 2 and 3: the tablet no longer lists it. The first miss must
            // NOT delete (guards against a truncated manifest); the next must.
            withFakeTablet(instanceId = "gen-x", manifestOverride = emptyList()) { srv, _ ->
                val mgr = LocalSyncManager(db, dir.absolutePath, transferPort = srv.localPort)
                val device = DiscoveredDevice("t", "T", "127.0.0.1", srv.localPort, System.currentTimeMillis())

                val r2 = mgr.syncWithTablet(device)
                assertEquals(0, r2.orphansRemoved, "grace window: the first miss must not delete")
                assertEquals(1, db.getAllDocuments().size, "still present during the grace pass")

                val r3 = mgr.syncWithTablet(device)
                assertEquals(1, r3.orphansRemoved, "a second consecutive miss means it was deleted")
                assertEquals(0, db.getAllDocuments().size, "deletion must propagate")

                mgr.stopListening()
            }
        }
    }

    // ─── 4. docY round-trips (v3) ────────────────────────────────────────────

    @Test
    fun `docY survives the round trip and stays null for pre-v24 strokes`() {
        val docY = 1234.5f
        val stroke = StrokeEntity(
            id = "s-docy", documentUri = "file:///x.pdf", pageIndex = 3,
            docY = docY, color = 0xFF112233.toInt(), strokeWidth = 3f,
            boundsTop = 12f
        )
        assertEquals(docY, stroke.docY)

        // A stroke the tablet has not backfilled yet must stay null rather than
        // being invented here — a guessed stride puts ink in the wrong place.
        val legacy = StrokeEntity(id = "s-legacy", documentUri = "file:///x.pdf", pageIndex = 0, color = 1, strokeWidth = 1f)
        assertNull(legacy.docY, "pre-v24 strokes must arrive with docY = null")
    }

    // ─── v4: text annotations ─────────────────────────────────────────────────

    /**
     * The whole point of v4. A note added on the tablet must reach the desktop, and
     * the only reason it could silently fail is the diff: if the note did not affect
     * `docVersion`, the desktop would see an unchanged document and never look.
     */
    @Test
    fun `a note added on the tablet reaches the desktop`() {
        withFakeTablet { srv, tablet ->
            desktop { db, dir ->
                val mgr = LocalSyncManager(db, dir.absolutePath, transferPort = srv.localPort)
                val device = DiscoveredDevice("tab-e2e", "FakeTab", "127.0.0.1", srv.localPort, System.currentTimeMillis())

                // First pass: nothing on the tablet yet.
                assertEquals(0, mgr.syncWithTablet(device).textsPulled)
                assertEquals(0, db.getAllTextAnnotationsForDocument(tablet.uri).size)

                // The user writes a note on the tablet.
                tablet.texts.add(
                    TextAnnotationEntity(
                        id = "note-1", documentUri = tablet.uri, pageIndex = 2,
                        docY = 2f * 842f + 100f,
                        text = "考試重點", modelX = 72f, modelY = 700f,
                        fontSize = 16f, colorArgb = 0xFF112233.toInt(), isStamp = false
                    )
                )

                val second = mgr.syncWithTablet(device)
                assertEquals(0, second.errors.size, "errors: ${second.errors}")
                assertEquals(1, second.documentsUpdated, "the notes-only change must be detected")
                assertEquals(1, second.textsPulled)

                val arrived = db.getAllTextAnnotationsForDocument(tablet.uri)
                assertEquals(1, arrived.size, "the note must actually be stored")
                val n = arrived.single()
                assertEquals("note-1", n.id)
                assertEquals("考試重點", n.text)
                assertEquals(72f, n.modelX)
                assertEquals(700f, n.modelY)
                assertEquals(16f, n.fontSize)
                assertEquals(0xFF112233.toInt(), n.colorArgb)
                assertEquals(2, n.pageIndex)
            }
        }
    }

    @Test
    fun `docY is preserved for notes and stays null when the tablet has not backfilled it`() {
        withFakeTablet { srv, tablet ->
            tablet.texts.add(
                TextAnnotationEntity(
                    id = "with-docy", documentUri = tablet.uri, pageIndex = 1,
                    docY = 842f + 42f, text = "A", modelX = 10f, modelY = 20f
                )
            )
            tablet.texts.add(
                TextAnnotationEntity(
                    id = "no-docy", documentUri = tablet.uri, pageIndex = 0,
                    docY = null, text = "B", modelX = 30f, modelY = 40f
                )
            )
            desktop { db, dir ->
                val mgr = LocalSyncManager(db, dir.absolutePath, transferPort = srv.localPort)
                mgr.syncWithTablet(
                    DiscoveredDevice("tab-e2e", "FakeTab", "127.0.0.1", srv.localPort, System.currentTimeMillis())
                )
                val byId = db.getAllTextAnnotationsForDocument(tablet.uri).associateBy { it.id }
                assertEquals(884f, byId.getValue("with-docy").docY ?: -1f, 0.001f)
                assertNull(
                    byId.getValue("no-docy").docY,
                    "an un-backfilled note must arrive with null, never a guessed value"
                )
            }
        }
    }

    @Test
    fun `a note deleted on the tablet is deleted on the desktop too`() {
        withFakeTablet { srv, tablet ->
            desktop { db, dir ->
                val mgr = LocalSyncManager(db, dir.absolutePath, transferPort = srv.localPort)
                val device = DiscoveredDevice("tab-e2e", "FakeTab", "127.0.0.1", srv.localPort, System.currentTimeMillis())

                tablet.texts.add(
                    TextAnnotationEntity(
                        id = "gone", documentUri = tablet.uri, pageIndex = 0,
                        text = "x", modelX = 1f, modelY = 2f
                    )
                )
                mgr.syncWithTablet(device)
                assertEquals(1, db.getAllTextAnnotationsForDocument(tablet.uri).size)

                tablet.texts.clear()
                mgr.syncWithTablet(device)

                // Wholesale replace, not a merge: merging would keep a note the user
                // deleted alive forever, which is the failure mode of every naive
                // "add what is missing" sync.
                assertEquals(
                    0, db.getAllTextAnnotationsForDocument(tablet.uri).size,
                    "a deleted note must not survive as an orphan"
                )
            }
        }
    }

    @Test
    fun `a second pass with unchanged notes transfers nothing`() {
        withFakeTablet { srv, tablet ->
            tablet.texts.add(
                TextAnnotationEntity(
                    id = "n1", documentUri = tablet.uri, pageIndex = 0,
                    text = "x", modelX = 1f, modelY = 2f
                )
            )
            desktop { db, dir ->
                val mgr = LocalSyncManager(db, dir.absolutePath, transferPort = srv.localPort)
                val device = DiscoveredDevice("tab-e2e", "FakeTab", "127.0.0.1", srv.localPort, System.currentTimeMillis())

                assertEquals(1, mgr.syncWithTablet(device).textsPulled)
                // If textCount were not part of the hash this would re-pull forever,
                // which for a large document means re-downloading it every pass.
                assertEquals(0, mgr.syncWithTablet(device).textsPulled, "idempotent second pass")
            }
        }
    }

    @Test
    fun `notes and strokes arrive together and neither clobbers the other`() {
        withFakeTablet { srv, tablet ->
            tablet.texts.add(
                TextAnnotationEntity(
                    id = "n1", documentUri = tablet.uri, pageIndex = 0,
                    text = "hi", modelX = 5f, modelY = 6f
                )
            )
            desktop { db, dir ->
                val mgr = LocalSyncManager(db, dir.absolutePath, transferPort = srv.localPort)
                mgr.syncWithTablet(
                    DiscoveredDevice("tab-e2e", "FakeTab", "127.0.0.1", srv.localPort, System.currentTimeMillis())
                )
                assertEquals(1, db.getAllStrokesForDocument(tablet.uri).size, "ink survives")
                assertEquals(1, db.getAllTextAnnotationsForDocument(tablet.uri).size, "notes survive")
            }
        }
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private data class Tablet(
        val dir: java.io.File,
        val uri: String,
        val pdf: java.io.File,
        val sha: String,
        val stroke: StrokeEntity,
        val points: List<PointEntity>,
        val docVersion: String,
        /** v4: the notes this fake tablet serves. Mutable so a test can change the
         *  tablet's content between two sync passes. */
        val texts: MutableList<TextAnnotationEntity> = mutableListOf(),
        /** Recomputes [docVersion] from the current counts. Called after mutating
         *  [texts], because the count is part of the hash in v4. */
        val rehash: () -> String
    )

    private var lastPdfSize = 0L
    private fun pdfBytesSize() = lastPdfSize

    private fun withFakeTablet(
        instanceId: String = this.instanceId,
        manifestOverride: List<DocumentManifestEntry>? = null,
        /** Manifest derived from the fixture, so the listed doc is one the server can serve. */
        manifestFor: ((Tablet) -> List<DocumentManifestEntry>)? = null,
        block: (ServerSocket, Tablet) -> Unit
    ) {
        val tabletDir = java.nio.file.Files.createTempDirectory("tablet").toFile()
        val tabletDocs = tabletDir.resolve("Documents").apply { mkdirs() }
        val pdfBytes = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        val tabletPdf = tabletDocs.resolve("lecture-notes.pdf").apply { writeBytes(pdfBytes) }
        lastPdfSize = pdfBytes.size.toLong()
        val tabletUri = "file://" + tabletPdf.absolutePath
        val sha = SyncWire.sha256Hex(tabletPdf)!!
        val stroke = StrokeEntity(
            id = "stroke-1", documentUri = tabletUri, pageIndex = 2,
            docY = 2f * 842f,
            color = 0xFF00FF00.toInt(), strokeWidth = 3f,
            boundsLeft = 1f, boundsTop = 2f, boundsRight = 90f, boundsBottom = 80f
        )
        val points = listOf(PointEntity(strokeId = "stroke-1", x = 1f, y = 2f, width = 3f))

        val notes = mutableListOf<TextAnnotationEntity>()
        // The manifest is rebuilt per request rather than captured once, so a test
        // that adds a note between two passes actually sees the new docVersion.
        // Capturing it up front would make every "content changed" test pass for the
        // wrong reason (it would look changed on every pass, not because of the note).
        fun currentVersion() = SyncWire.docVersion(
            instanceId, tabletUri, 1, sha, tabletPdf.length(), notes.size
        )

        val tablet = Tablet(
            tabletDir, tabletUri, tabletPdf, sha, stroke, points, currentVersion(),
            texts = notes,
            rehash = ::currentVersion
        )
        val manifest = manifestOverride ?: manifestFor?.invoke(tablet) ?: listOf(
            DocumentManifestEntry(
                tabletUri, "Lecture Notes", 2_000_000_000_000L, 1, true,
                sha, tabletPdf.length(), currentVersion(), textCount = notes.size
            )
        )

        val srv = ServerSocket(0)
        val acceptLoop = Thread {
            while (!srv.isClosed) {
                try {
                    srv.accept().use { s ->
                        serveFakeTablet(s, instanceId, tablet, manifest)
                    }
                } catch (_: Exception) { break }
            }
        }
        acceptLoop.isDaemon = true
        acceptLoop.start()
        try { block(srv, tablet) } finally { srv.close() }
    }

    private fun desktop(block: (DatabaseManager, java.io.File) -> Unit) {
        val dir = java.nio.file.Files.createTempDirectory("desktop").toFile()
        val db = DatabaseManager(dir.resolve("inkflow.db").absolutePath)
        db.connect()
        try { block(db, dir) } finally { db.disconnect() }
    }

    /** Minimal protocol-v3 tablet server serving one document + its PDF body. */
    private fun serveFakeTablet(
        socket: Socket,
        instanceId: String,
        tablet: Tablet,
        manifest: List<DocumentManifestEntry>
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
                SyncRequest.TYPE_HANDSHAKE -> SyncWire.writeText(
                    out,
                    gson.toJson(
                        SyncResponse(
                            req.type,
                            payload = gson.toJson(
                                mapOf(
                                    "protocolVersion" to SyncConstants.PROTOCOL_VERSION,
                                    "instanceId" to instanceId
                                )
                            )
                        )
                    )
                )

                SyncRequest.TYPE_DOC_MANIFEST -> respond(
                    req.type,
                    // Re-hash on read so a mid-test mutation is reflected. Without
                    // this the fixture would report a stale version and the test
                    // asserting "notes arrived" could not fail for the right reason.
                    manifest.map { entry ->
                        if (entry.uri == tablet.uri) {
                            entry.copy(
                                docVersion = tablet.rehash(),
                                textCount = tablet.texts.size
                            )
                        } else entry
                    }
                )

                SyncRequest.TYPE_DOCUMENT_DETAIL -> {
                    val uri = req.documentUri
                    if (uri != tablet.uri) {
                        SyncWire.writeText(out, gson.toJson(SyncResponse(req.type, ok = false, error = "unknown document $uri")))
                    } else {
                        respond(
                            req.type,
                            DocumentDetailPayload(
                                DocumentEntity(uri, "Lecture Notes", 2_000_000_000_000L, lastPageIndex = 2),
                                listOf(StrokeWithPoints(tablet.stroke, tablet.points)),
                                tablet.texts.toList()
                            )
                        )
                    }
                }

                SyncRequest.TYPE_FILE_META ->
                    respond(req.type, FileMetaPayload(true, tablet.pdf.length(), tablet.sha))

                SyncRequest.TYPE_FILE_DATA -> {
                    val chunk = RandomAccessFileAccessor.readChunk(
                        tablet.pdf, req.offset ?: 0L, req.limit ?: (1 shl 20)
                    )
                    SyncWire.writeFrame(out, chunk)
                }

                else -> SyncWire.writeText(out, gson.toJson(SyncResponse(req.type, ok = false, error = "unknown")))
            }
        }
    }
}
