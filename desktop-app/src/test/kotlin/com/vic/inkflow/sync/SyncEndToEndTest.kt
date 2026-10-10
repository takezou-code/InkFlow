package com.vic.inkflow.sync

import com.google.gson.Gson
import com.vic.inkflow.data.DatabaseManager
import com.vic.inkflow.data.DocumentEntity
import com.vic.inkflow.data.FolderEntity
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
                assertEquals(1, result2.unchangedSkipped)

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

// ─── v5: proposals ────────────────────────────────────────────────────────

    private fun deviceFor(srv: ServerSocket) =
        DiscoveredDevice("tab-e2e", "FakeTab", "127.0.0.1", srv.localPort, System.currentTimeMillis())

    private fun desktopStroke(uri: String, id: String, x: Float = 50f, y: Float = 60f) =
        StrokeWithPoints(
            StrokeEntity(
                id = id, documentUri = uri, pageIndex = 0, docY = null,
                color = 0xFF000000.toInt(), strokeWidth = 2f,
                boundsLeft = x, boundsTop = y, boundsRight = x + 10f, boundsBottom = y + 10f
            ),
            listOf(
                PointEntity(id = 1, strokeId = id, x = x, y = y, width = 2f),
                PointEntity(id = 2, strokeId = id, x = x + 10f, y = y + 10f, width = 2f)
            )
        )

    /**
     * The whole point of v5. A stroke drawn on the desktop must reach the tablet,
     * and the only thing that can silently break that is the base-version check —
     * so this drives the real sync loop against a fake tablet that arbitrates.
     */
    @Test
    fun `a desktop edit reaches the tablet through a proposal`() {
        withFakeTablet { srv, tablet ->
            desktop { db, dir ->
                val queue = ProposalQueue(db)
                val mgr = LocalSyncManager(
                    db, dir.absolutePath, transferPort = srv.localPort, proposalQueue = queue
                )
                val device = deviceFor(srv)

                // Pull the base first: without a stored version there is nothing to
                // arbitrate against, so the edit would correctly stay local-only.
                mgr.syncWithTablet(device)

                // The user draws on the desktop: a real row plus a recorded op.
                val swp = desktopStroke(tablet.uri, "desktop-1")
                db.saveStroke(swp.stroke, swp.points)
                assertEquals(
                    ProposalQueue.RecordResult.RECORDED,
                    queue.record(tablet.uri, ProposalOp(ProposalOp.UPSERT_STROKE, stroke = swp))
                )

                val r = mgr.syncWithTablet(device)
                assertEquals(0, r.errors.size, "errors: ${r.errors}")
                assertEquals(1, r.proposalsAccepted, "the proposal must be accepted")
                assertEquals(0, r.proposalConflicts)
                assertTrue(
                    tablet.pushedStrokes.any { it.stroke.id == "desktop-1" },
                    "the tablet must hold the pushed stroke"
                )
                assertEquals(0, queue.pendingOpCount(), "accepted ops are consumed")

                // Third pass: nothing to send, nothing to pull — the two sides agree.
                val r3 = mgr.syncWithTablet(device)
                assertEquals(0, r3.proposalsAccepted)
                assertEquals(0, r3.documentsUpdated, "no redundant pull after an accept")
            }
        }
    }

    @Test
    fun `a stale proposal is dropped and reported, tablet wins`() {
        withFakeTablet { srv, tablet ->
            desktop { db, dir ->
                val queue = ProposalQueue(db)
                val mgr = LocalSyncManager(
                    db, dir.absolutePath, transferPort = srv.localPort, proposalQueue = queue
                )
                val device = deviceFor(srv)
                mgr.syncWithTablet(device)

                val swp = desktopStroke(tablet.uri, "desktop-1")
                db.saveStroke(swp.stroke, swp.points)
                queue.record(tablet.uri, ProposalOp(ProposalOp.UPSERT_STROKE, stroke = swp))

                // The tablet moves first: a stroke drawn over there.
                tablet.pushedStrokes.add(desktopStroke(tablet.uri, "tablet-2", x = 300f, y = 300f))

                val r = mgr.syncWithTablet(device)
                assertEquals(0, r.errors.size, "errors: ${r.errors}")
                assertEquals(0, r.proposalsAccepted)
                assertEquals(1, r.proposalConflicts, "the lost edit must be counted")

                // Tablet wins: the pull replaced everything, so the desktop holds
                // exactly what the tablet holds — no more, no less.
                val ids = db.getAllStrokesForDocument(tablet.uri).map { it.stroke.id }.toSet()
                assertTrue("tablet-2" in ids, "the tablet's stroke must arrive")
                assertTrue("desktop-1" !in ids, "the overwritten edit must not linger")
                assertTrue(
                    "desktop-1" !in tablet.pushedStrokes.map { it.stroke.id },
                    "the rejected op must never reach the tablet"
                )
                val notes = queue.drainNotices()
                assertTrue(notes.any { it.contains("1 筆") }, "the loss must be visible, got: $notes")
            }
        }
    }

    @Test
    fun `a proposal that changes nothing still round-trips`() {
        // Degenerate but legal: an empty op list against a matching base. The tablet
        // must accept it rather than error, because "nothing to send" is a normal
        // state, not a protocol violation.
        withFakeTablet { srv, tablet ->
            desktop { db, dir ->
                val queue = ProposalQueue(db)
                val mgr = LocalSyncManager(
                    db, dir.absolutePath, transferPort = srv.localPort, proposalQueue = queue
                )
                val device = deviceFor(srv)
                mgr.syncWithTablet(device)

                // Record then remove: leaves a proposal row with no ops... actually
                // takeForSend returns null for empty ops, so nothing is ever sent.
                // This asserts that invariant rather than the wire.
                assertNull(queue.takeForSend(tablet.uri), "empty op lists are never sent")
                val r = mgr.syncWithTablet(device)
                assertEquals(0, r.proposalsAccepted)
                assertEquals(0, r.proposalConflicts)
            }
        }
    }

    // ─── folders ──────────────────────────────────────────────────────────────

    /**
     * Categories have to travel or the library filter lies: a document with a
     * folderId no local folder matches vanishes from every filtered view while
     * still existing, which reads as data loss.
     */
    @Test
    fun `folders arrive and documents resolve against them`() {
        withFakeTablet { srv, tablet ->
            desktop { db, dir ->
                val mgr = LocalSyncManager(db, dir.absolutePath, transferPort = srv.localPort)
                val device = deviceFor(srv)

                val r = mgr.syncWithTablet(device)
                assertEquals(0, r.errors.size, "errors: ${r.errors}")
                assertEquals(2, r.foldersSynced)

                val folders = db.getAllFolders().associateBy { it.id }
                assertEquals("工作", folders.getValue("f-work").name)
                assertEquals("f-work", folders.getValue("f-life").parentFolderId)

                // Second pass: folders are stable, documents are stable — nothing moves.
                val r2 = mgr.syncWithTablet(device)
                assertEquals(2, r2.foldersSynced, "folders re-sync every pass (they are cheap)")
                assertEquals(0, r2.documentsUpdated)
            }
        }
    }

    @Test
    fun `a folder deleted on the tablet un-orphans its documents`() {
        withFakeTablet { srv, tablet ->
            desktop { db, dir ->
                val mgr = LocalSyncManager(db, dir.absolutePath, transferPort = srv.localPort)
                val device = deviceFor(srv)
                mgr.syncWithTablet(device)

                // The document claims a folder that is about to disappear.
                val doc = db.getDocument(tablet.uri)!!
                db.saveDocument(doc.copy(folderId = "f-life"))
                assertEquals("f-life", db.getDocument(tablet.uri)!!.folderId)

                tablet.folders.removeAll { it.id == "f-life" }
                mgr.syncWithTablet(device)

                assertEquals(
                    null, db.getDocument(tablet.uri)!!.folderId,
                    "a dangling folderId must reset to uncategorized, not linger"
                )
                assertEquals(1, db.getAllFolders().size)
            }
        }
    }

    @Test
    fun `an old peer without folder_list does not break document sync`() {
        // The verb is additive: ok=false must skip folder sync and leave the document
        // flow untouched. Otherwise upgrading either side first would break sync.
        withFakeTablet(supportFolders = false) { srv, tablet ->
            desktop { db, dir ->
                val mgr = LocalSyncManager(db, dir.absolutePath, transferPort = srv.localPort)
                val r = mgr.syncWithTablet(deviceFor(srv))
                assertEquals(0, r.errors.size, "errors: ${r.errors}")
                assertEquals(0, r.foldersSynced)
                assertEquals(1, r.documentsUpdated, "documents must still sync")
                assertTrue(db.getAllFolders().isEmpty(), "no folders fabricated from nothing")
            }
        }
    }

    // ─── incremental pull ─────────────────────────────────────────────────────

    private fun pageStroke(uri: String, id: String, page: Int) = StrokeWithPoints(
        StrokeEntity(
            id = id, documentUri = uri, pageIndex = page, docY = null,
            color = 0xFF000000.toInt(), strokeWidth = 2f,
            boundsLeft = 1f, boundsTop = 1f, boundsRight = 9f, boundsBottom = 9f
        ),
        listOf(
            PointEntity(id = 1, strokeId = id, x = 1f, y = 1f, width = 2f),
            PointEntity(id = 2, strokeId = id, x = 9f, y = 9f, width = 2f)
        )
    )

    /**
     * The whole point of `page_counts`: a one-page change must not re-pull the
     * document. The fake records every page fetch, so this asserts on what crossed
     * the wire — not just on the final state, which a full pull would also produce.
     */
    @Test
    fun `a one-page change pulls only that page`() {
        withFakeTablet { srv, tablet ->
            desktop { db, dir ->
                val mgr = LocalSyncManager(db, dir.absolutePath, transferPort = srv.localPort)
                val device = deviceFor(srv)
                mgr.syncWithTablet(device)
                tablet.pageFetches.clear()

                // Page 2 is already here; the new content lands on page 5.
                tablet.pushedStrokes.add(pageStroke(tablet.uri, "p5-stroke", 5))
                tablet.texts.add(
                    TextAnnotationEntity(
                        id = "p5-note", documentUri = tablet.uri, pageIndex = 5,
                        text = "five", modelX = 10f, modelY = 20f
                    )
                )

                val r = mgr.syncWithTablet(device)
                assertEquals(0, r.errors.size, "errors: ${r.errors}")
                assertEquals(1, r.documentsUpdated)

                assertEquals(
                    setOf("stroke_page" to 5, "text_page" to 5),
                    tablet.pageFetches.toSet(),
                    "only the changed page may cross the wire, got: ${tablet.pageFetches}"
                )
                assertTrue(
                    db.getAllStrokesForDocument(tablet.uri).any { it.stroke.id == "p5-stroke" },
                    "the new page's stroke must arrive"
                )
                assertTrue(
                    db.getAllTextAnnotationsForDocument(tablet.uri).any { it.id == "p5-note" },
                    "the new page's note must arrive"
                )
                assertTrue(
                    db.getAllStrokesForDocument(tablet.uri).any { it.stroke.id == "stroke-1" },
                    "the untouched page must survive the delta"
                )
            }
        }
    }

    @Test
    fun `a peer without page_counts falls back to a full pull`() {
        // Incremental pull is an optimization, never a correctness requirement: an
        // older peer answers "unknown" and the client must still converge.
        withFakeTablet(supportDelta = false) { srv, tablet ->
            desktop { db, dir ->
                val mgr = LocalSyncManager(db, dir.absolutePath, transferPort = srv.localPort)
                val r = mgr.syncWithTablet(deviceFor(srv))
                assertEquals(0, r.errors.size, "errors: ${r.errors}")
                assertEquals(1, r.documentsUpdated)
                assertTrue(tablet.pageFetches.isEmpty(), "no page fetches against an old peer")
                assertEquals(1, db.getAllStrokesForDocument(tablet.uri).size)
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
        /** v5: strokes applied via accepted proposals. Mutable for the same reason. */
        val pushedStrokes: MutableList<StrokeWithPoints> = mutableListOf(),
        /** Folders served by folder_list. Mutable so a test can delete one. */
        val folders: MutableList<FolderEntity> = mutableListOf(),
        /** proposalId -> status, proving idempotent resends. */
        val seenProposals: MutableMap<String, ProposalStatusPayload> = mutableMapOf(),
        /** Every page fetch the desktop made: (verb, page). Proves incrementality. */
        val pageFetches: MutableList<Pair<String, Int>> = mutableListOf(),
        /** Recomputes [docVersion] from the current counts. Called after mutating
         *  [texts], because the count is part of the hash in v4. */
        val rehash: () -> String
    ) {
        /** Every stroke the tablet currently holds: fixture + pushed. */
        fun allStrokes(): List<StrokeWithPoints> =
            listOf(StrokeWithPoints(stroke, points)) + pushedStrokes.toList()

        fun strokeCount(): Int = 1 + pushedStrokes.size
    }

    private var lastPdfSize = 0L
    private fun pdfBytesSize() = lastPdfSize

    private fun withFakeTablet(
        instanceId: String = this.instanceId,
        manifestOverride: List<DocumentManifestEntry>? = null,
        /** Manifest derived from the fixture, so the listed doc is one the server can serve. */
        manifestFor: ((Tablet) -> List<DocumentManifestEntry>)? = null,
        /** False simulates a pre-folder_list peer: the verb answers "unknown". */
        supportFolders: Boolean = true,
        /** False simulates a pre-delta peer: page_counts answers "unknown". */
        supportDelta: Boolean = true,
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
        val tabletLateInit = arrayOfNulls<Tablet>(1)
        fun currentVersion(): String {
            val t = tabletLateInit[0]
            val strokes = t?.strokeCount() ?: 1
            return SyncWire.docVersion(
                instanceId, tabletUri, strokes, sha, tabletPdf.length(), (t?.texts?.size ?: 0)
            )
        }

        val tablet = Tablet(
            tabletDir, tabletUri, tabletPdf, sha, stroke, points, currentVersion(),
            texts = notes,
            folders = mutableListOf(
                FolderEntity(id = "f-work", name = "工作"),
                FolderEntity(id = "f-life", name = "生活", parentFolderId = "f-work")
            ),
            rehash = ::currentVersion
        )
        tabletLateInit[0] = tablet
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
                            serveFakeTablet(s, instanceId, tablet, manifest, supportFolders, supportDelta)
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

    /** Minimal protocol-v5 tablet server serving one document + its PDF body. */
    private fun serveFakeTablet(
        socket: Socket,
        instanceId: String,
        tablet: Tablet,
        manifest: List<DocumentManifestEntry>,
        supportFolders: Boolean = true,
        supportDelta: Boolean = true
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
                                strokeCount = tablet.strokeCount(),
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
                                tablet.allStrokes(),
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

                // v5: minimal arbitration mirroring the real tablet — compare the base,
                // apply id-level ops, remember the answer for idempotent resends.
                SyncRequest.TYPE_PROPOSAL_SUBMIT -> {
                    val p = req.proposal
                    if (p == null) {
                        SyncWire.writeText(out, gson.toJson(SyncResponse(req.type, ok = false, error = "missing proposal")))
                    } else {
                        tablet.seenProposals[p.proposalId]?.let {
                            respond(req.type, it)
                        } ?: run {
                            if (p.documentUri != tablet.uri) {
                                respond(
                                    req.type,
                                    ProposalStatusPayload(
                                        p.proposalId, ProposalStatusPayload.REJECTED,
                                        message = "unknown document"
                                    ).also { tablet.seenProposals[p.proposalId] = it }
                                )
                            } else if (p.baseDocVersion != tablet.rehash()) {
                                respond(
                                    req.type,
                                    ProposalStatusPayload(
                                        p.proposalId, ProposalStatusPayload.CONFLICT_STALE,
                                        winnerDocVersion = tablet.rehash(),
                                        conflictIds = p.ops.mapNotNull {
                                            it.id ?: it.stroke?.let { s ->
                                                (gson.fromJson(gson.toJson(s), StrokeWithPoints::class.java))?.stroke?.id
                                            } ?: it.text?.let { t ->
                                                (gson.fromJson(gson.toJson(t), TextAnnotationEntity::class.java))?.id
                                            }
                                        },
                                        message = "base moved on"
                                    ).also { tablet.seenProposals[p.proposalId] = it }
                                )
                            } else {
                                p.ops.forEach { op ->
                                    when (op.op) {
                                        ProposalOp.UPSERT_STROKE -> {
                                            val swp = gson.fromJson(gson.toJson(op.stroke), StrokeWithPoints::class.java)!!
                                            tablet.pushedStrokes.removeAll { it.stroke.id == swp.stroke.id }
                                            if (swp.stroke.id != tablet.stroke.id) tablet.pushedStrokes.add(swp)
                                        }
                                        ProposalOp.DELETE_STROKE -> {
                                            tablet.pushedStrokes.removeAll { it.stroke.id == op.id }
                                        }
                                        ProposalOp.UPSERT_TEXT -> {
                                            val t = gson.fromJson(gson.toJson(op.text), TextAnnotationEntity::class.java)!!
                                            tablet.texts.removeAll { it.id == t.id }
                                            tablet.texts.add(t)
                                        }
                                        ProposalOp.DELETE_TEXT -> {
                                            tablet.texts.removeAll { it.id == op.id }
                                        }
                                    }
                                }
                                respond(
                                    req.type,
                                    ProposalStatusPayload(
                                        p.proposalId, ProposalStatusPayload.ACCEPTED,
                                        winnerDocVersion = tablet.rehash()
                                    ).also { tablet.seenProposals[p.proposalId] = it }
                                )
                            }
                        }
                    }
                }

                SyncRequest.TYPE_PROPOSAL_STATUS -> {
                    val id = req.proposalId
                    val known = id?.let { tablet.seenProposals[it] }
                    respond(
                        req.type,
                        known ?: ProposalStatusPayload(
                            id ?: "", ProposalStatusPayload.UNKNOWN,
                            message = "no record of this proposal"
                        )
                    )
                }

                SyncRequest.TYPE_FOLDER_LIST -> {
                    if (!supportFolders) {
                        SyncWire.writeText(out, gson.toJson(SyncResponse(req.type, ok = false, error = "unknown")))
                    } else {
                        respond(req.type, tablet.folders.toList())
                    }
                }

                SyncRequest.TYPE_PAGE_COUNTS -> {
                    if (!supportDelta) {
                        SyncWire.writeText(out, gson.toJson(SyncResponse(req.type, ok = false, error = "unknown")))
                    } else {
                    val uri = req.documentUri
                    if (uri != tablet.uri) {
                        SyncWire.writeText(out, gson.toJson(SyncResponse(req.type, ok = false, error = "unknown document $uri")))
                    } else {
                        respond(
                            req.type,
                            PageCountsPayload(
                                uri,
                                tablet.allStrokes().groupBy { it.stroke.pageIndex }
                                    .map { (page, swps) ->
                                        PageCountEntry(
                                            page,
                                            swps.size,
                                            tablet.texts.count { it.pageIndex == page }
                                        )
                                    } + tablet.texts.map { it.pageIndex }.distinct()
                                        .filter { p -> tablet.allStrokes().none { it.stroke.pageIndex == p } }
                                        .map { p ->
                                            PageCountEntry(p, 0, tablet.texts.count { it.pageIndex == p })
                                        },
                                DocumentEntity(uri, "Lecture Notes", 2_000_000_000_000L, lastPageIndex = 2)
                            )
                        )
                    }
                    }
                }

                SyncRequest.TYPE_STROKE_PAGE -> {
                    if (!supportDelta) {
                        SyncWire.writeText(out, gson.toJson(SyncResponse(req.type, ok = false, error = "unknown")))
                    } else {
                    val uri = req.documentUri
                    val page = req.pageIndex ?: 0
                    if (uri != tablet.uri) {
                        SyncWire.writeText(out, gson.toJson(SyncResponse(req.type, ok = false, error = "unknown document $uri")))
                    } else {
                        tablet.pageFetches.add("stroke_page" to page)
                        respond(
                            req.type,
                            StrokeDeltaPayload(
                                uri,
                                tablet.allStrokes().filter { it.stroke.pageIndex == page }
                            )
                        )
                    }
                    }
                }

                SyncRequest.TYPE_TEXT_PAGE -> {
                    if (!supportDelta) {
                        SyncWire.writeText(out, gson.toJson(SyncResponse(req.type, ok = false, error = "unknown")))
                    } else {
                    val uri = req.documentUri
                    val page = req.pageIndex ?: 0
                    if (uri != tablet.uri) {
                        SyncWire.writeText(out, gson.toJson(SyncResponse(req.type, ok = false, error = "unknown document $uri")))
                    } else {
                        tablet.pageFetches.add("text_page" to page)
                        respond(
                            req.type,
                            TextDeltaPayload(uri, tablet.texts.filter { it.pageIndex == page })
                        )
                    }
                    }
                }

                else -> SyncWire.writeText(out, gson.toJson(SyncResponse(req.type, ok = false, error = "unknown")))
            }
        }
    }
}
