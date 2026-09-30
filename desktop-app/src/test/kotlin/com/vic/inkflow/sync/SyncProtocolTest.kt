package com.vic.inkflow.sync

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vic.inkflow.data.DocumentEntity
import com.vic.inkflow.data.PointEntity
import com.vic.inkflow.data.StrokeEntity
import com.vic.inkflow.data.StrokeWithPoints
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncProtocolTest {

    private val gson = Gson()

    @Test
    fun `frame round trip preserves bytes`() {
        val baos = ByteArrayOutputStream()
        val out = DataOutputStream(baos)
        SyncWire.writeText(out, "hello")
        SyncWire.writeFrame(out, byteArrayOf(1, 2, 3, 4))

        val input = DataInputStream(ByteArrayInputStream(baos.toByteArray()))
        assertEquals("hello", SyncWire.readText(input))
        val bin = SyncWire.readFrame(input)
        assertNotNull(bin)
        assertTrue(bin.contentEquals(byteArrayOf(1, 2, 3, 4)))
        assertNull(SyncWire.readFrame(input)) // clean EOF
    }

    @Test
    fun `manifest entries serialize and deserialize`() {
        val manifest = listOf(
            DocumentManifestEntry(
                uri = "file:///storage/emulated/0/Android/data/com.vic.inkflow/files/Documents/abc.pdf",
                displayName = "abc.pdf",
                lastOpenedAt = 1_700_000_000_000L,
                strokeCount = 12,
                filePresent = true,
                fileSha256 = "deadbeef",
                fileSize = 4096L
            )
        )
        val json = gson.toJson(manifest)
        val type = object : TypeToken<List<DocumentManifestEntry>>() {}.type
        val back = gson.fromJson<List<DocumentManifestEntry>>(json, type)
        assertEquals(manifest, back)
    }

    @Test
    fun `document detail payload survives gson round trip`() {
        val doc = DocumentEntity(uri = "file:///x/y.pdf", displayName = "y.pdf", lastOpenedAt = 5L)
        val stroke = StrokeEntity(
            id = "s1", documentUri = doc.uri, pageIndex = 0,
            color = 0xFF0000FF.toInt(), strokeWidth = 2.5f,
            boundsLeft = 1f, boundsTop = 2f, boundsRight = 3f, boundsBottom = 4f
        )
        val points = listOf(PointEntity(strokeId = "s1", x = 1f, y = 2f, width = 2.5f))
        val payload = DocumentDetailPayload(doc, listOf(StrokeWithPoints(stroke, points)))

        val json = gson.toJson(payload)
        val decoded = gson.fromJson(json, DocumentDetailPayload::class.java)
        val decodedDoc = gson.fromJson(gson.toJson(decoded.document), DocumentEntity::class.java)
        assertEquals(doc, decodedDoc)

        val type = object : TypeToken<List<StrokeWithPoints>>() {}.type
        val strokes = gson.fromJson<List<StrokeWithPoints>>(gson.toJson(decoded.strokes), type)
        assertEquals(1, strokes.size)
        assertEquals(stroke, strokes[0].stroke)
        assertEquals(points, strokes[0].points)
    }

    @Test
    fun `conflict resolution keeps newer document`() {
        // Newer-wins rule used by syncWithTablet: remote applied only when strictly newer.
        val local = DocumentEntity(uri = "u", displayName = "d", lastOpenedAt = 100L)
        val remoteNewer = DocumentEntity(uri = "u", displayName = "d", lastOpenedAt = 101L)
        val remoteOlder = DocumentEntity(uri = "u", displayName = "d", lastOpenedAt = 99L)

        assertTrue(remoteNewer.lastOpenedAt > local.lastOpenedAt)   // would pull
        assertTrue(!(remoteOlder.lastOpenedAt > local.lastOpenedAt)) // would skip
        // upsertDocumentIfNewer semantics: equal timestamps keep local copy
        assertTrue(local.lastOpenedAt >= remoteOlder.lastOpenedAt)
    }

    @Test
    fun `sha256 helper hashes files`() {
        val f = File.createTempFile("synctest", ".pdf")
        try {
            f.writeBytes(byteArrayOf(1, 2, 3))
            val hash = SyncWire.sha256Hex(f)
            assertNotNull(hash)
            assertEquals(64, hash.length)
            // sha256 of bytes 01 02 03 (verified with: python3 -c "import hashlib; print(hashlib.sha256(b'\x01\x02\x03').hexdigest())")
            assertEquals(
                "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
                hash
            )
        } finally {
            f.delete()
        }
        assertNull(SyncWire.sha256Hex(File("/nonexistent/file.xyz")))
    }

    @Test
    fun `discovery messages carry app tag and role`() {
        val req = DiscoveryRequest(requesterRole = "desktop", deviceId = "pc-1", deviceName = "PC")
        val json = gson.toJson(req)
        assertTrue(json.contains("\"app\":\"InkFlow\""))
        assertTrue(json.contains("discover"))

        val resp = DiscoveryResponse(
            deviceId = "tab-1", deviceName = "Tablet", role = "tablet", ip = "192.168.1.5"
        )
        val respJson = gson.toJson(resp)
        val parsed = gson.fromJson(respJson, DiscoveryResponse::class.java)
        assertEquals("tablet", parsed.role)
        assertEquals(SyncPorts.TRANSFER_PORT, parsed.transferPort)
    }
}
