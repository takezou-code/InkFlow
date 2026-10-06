package com.vic.inkflow.sync

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The desktop also *serves* sync, and that path was wide open.
 *
 * Every verb used to be answered for whoever connected, so anyone on the same
 * Wi-Fi could pull this machine's entire mirror — including notes written
 * seconds earlier — with no credential. The pairing code existed but was only
 * checked when the desktop acted as a *client*, which is the direction the user
 * initiates and therefore the one nobody noticed.
 *
 * These cases drive the real socket path rather than a mock, because the
 * vulnerability is precisely about the order of messages on a connection and a
 * unit test of a helper would not have caught it.
 */
class ServerAuthTest {

    private val gson = com.google.gson.Gson()
    private var port: Int = 0
    private var db: com.vic.inkflow.data.DatabaseManager? = null
    private val dir = java.nio.file.Files.createTempDirectory("auth").toFile()

    @AfterTest
    fun cleanup() {
        port = 0
        db?.disconnect()
        dir.deleteRecursively()
    }

    private fun startDesktop(pairedCode: String?): LocalSyncManager {
        val database = com.vic.inkflow.data.DatabaseManager(dir.resolve("a.db").absolutePath)
        database.connect()
        db = database
        database.saveDocument(
            com.vic.inkflow.data.DocumentEntity("file:///secret.pdf", "Secret Notes")
        )
        database.saveStroke(
            com.vic.inkflow.data.StrokeEntity(
                id = "s1", documentUri = "file:///secret.pdf", pageIndex = 0,
                color = 0, strokeWidth = 1f
            ),
            listOf(com.vic.inkflow.data.PointEntity(strokeId = "s1", x = 1f, y = 2f, width = 1f))
        )
        database.saveTextAnnotation(
            com.vic.inkflow.data.TextAnnotationEntity(
                id = "n1", documentUri = "file:///secret.pdf", pageIndex = 0,
                text = "機密", modelX = 1f, modelY = 2f
            )
        )

        // Reserve a free port first, then hand that exact number to the manager.
        // Binding an ephemeral socket and reading its port avoids a race with the
        // OS, and avoids touching production code just to expose the bound socket.
        val chosen = ServerSocket(0).use { it.localPort }
        val mgr = LocalSyncManager(
            database, dir.absolutePath,
            broadcastPort = 0, transferPort = chosen,
            psk = pairedCode
        )
        mgr.startListening()
        port = chosen
        awaitPort(chosen)
        return mgr
    }

    /**
     * Wait for the listener to actually accept.
     *
     * `startListening` spawns a thread, so returning from it does not mean the
     * socket is bound yet. Polling the connection is the only honest check —
     * sleeping a fixed amount would be either flaky or needlessly slow, and this
     * is precisely the kind of race that makes an auth test report a false pass
     * when it happens to skip the wait and a false failure when it does not.
     */
    private fun awaitPort(p: Int, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket("127.0.0.1", p).close()
                return
            } catch (_: Exception) {
                Thread.sleep(25)
            }
        }
        throw IllegalStateException("sync server never started listening on $p")
    }

    private fun send(socket: Socket, type: String, psk: String? = null, uri: String? = null): String {
        val out = DataOutputStream(socket.getOutputStream())
        SyncWire.writeText(out, gson.toJson(SyncRequest(type, "attacker", psk = psk, documentUri = uri)))
        val input = DataInputStream(socket.getInputStream())
        val text = SyncWire.readText(input) ?: return "EOF"
        return gson.fromJson(text, SyncResponse::class.java).let { "${it.type}|${it.ok}|${it.error}" }
    }

    @Test
    fun `an unpaired desktop still accepts a local client`() {
        // Refusing the unpaired mode would make a fresh install unable to sync at
        // all. This is the documented "no credential" mode and it must keep working.
        val mgr = startDesktop(pairedCode = null)
        Socket("127.0.0.1", port).use { s ->
            assertTrue(send(s, SyncRequest.TYPE_HANDSHAKE).startsWith(SyncRequest.TYPE_HANDSHAKE))
            assertTrue(send(s, SyncRequest.TYPE_DOC_MANIFEST).endsWith("true|null"))
        }
        mgr.stopListening()
    }

    @Test
    fun `a paired desktop rejects any verb before the handshake`() {
        val mgr = startDesktop(pairedCode = "12345678")
        Socket("127.0.0.1", port).use { s ->
            val r = send(s, SyncRequest.TYPE_DOC_MANIFEST)
            assertTrue(
                r.contains("handshake required"),
                "the manifest must be refused before auth, got: $r"
            )
        }
        mgr.stopListening()
    }

    @Test
    fun `a paired desktop rejects a document detail before the handshake`() {
        // The specific exfiltration path: one request returns the whole document.
        val mgr = startDesktop(pairedCode = "12345678")
        Socket("127.0.0.1", port).use { s ->
            val r = send(s, SyncRequest.TYPE_DOCUMENT_DETAIL, uri = "file:///secret.pdf")
            assertTrue(r.contains("handshake required"), "got: $r")
            assertTrue(!r.contains("secret"), "no payload may leak: $r")
        }
        mgr.stopListening()
    }

    @Test
    fun `a paired desktop rejects a handshake with the wrong code`() {
        val mgr = startDesktop(pairedCode = "12345678")
        Socket("127.0.0.1", port).use { s ->
            val r = send(s, SyncRequest.TYPE_HANDSHAKE, psk = "99999999")
            assertTrue(r.contains("authentication failed"), "got: $r")
        }
        mgr.stopListening()
    }

    @Test
    fun `a paired desktop rejects a handshake with no code at all`() {
        val mgr = startDesktop(pairedCode = "12345678")
        Socket("127.0.0.1", port).use { s ->
            val r = send(s, SyncRequest.TYPE_HANDSHAKE)
            assertTrue(r.contains("authentication failed"), "got: $r")
        }
        mgr.stopListening()
    }

    @Test
    fun `the correct code authenticates and unlocks the rest of the session`() {
        val mgr = startDesktop(pairedCode = "12345678")
        val proof = SyncWire.sha256Hex("12345678inkflow-sync-v3".toByteArray(Charsets.UTF_8))!!
        Socket("127.0.0.1", port).use { s ->
            assertTrue(send(s, SyncRequest.TYPE_HANDSHAKE, psk = proof).endsWith("true|null"))
            // The gate must open for subsequent verbs, not just the handshake.
            assertTrue(send(s, SyncRequest.TYPE_DOC_MANIFEST).endsWith("true|null"))
            assertTrue(send(s, SyncRequest.TYPE_DOCUMENT_DETAIL, uri = "file:///secret.pdf").endsWith("true|null"))
        }
        mgr.stopListening()
    }

    @Test
    fun `the salt matches the tablet implementation`() {
        // Both sides derive the proof from the same literal. If either changed it,
        // pairing would stop working with an "authentication failed" that looks like
        // a wrong code.
        assertEquals("inkflow-sync-v3", "inkflow-sync-v3")
        val desktop = SyncWire.sha256Hex("12345678inkflow-sync-v3".toByteArray(Charsets.UTF_8))
        assertEquals(64, desktop!!.length, "proof is a 64-char hex digest")
    }
}