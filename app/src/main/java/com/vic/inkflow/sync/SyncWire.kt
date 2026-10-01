package com.vic.inkflow.sync

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * 線格式工具（§2）。與
 * `InkFlow-Windows/desktop-app/src/main/kotlin/com/vic/inkflow/sync/SyncProtocol.kt`
 * 的 `SyncWire` **逐行對應**，任何一邊改動都要同步。
 *
 * ```
 * [4-byte big-endian int: payload length][payload bytes]
 * ```
 *  - 文本幀：payload 是 UTF-8 JSON
 *  - 二進制幀：只有 `file_data` 應答是原始位元組
 *  - 長度 0 = EOF marker（文件沒剩餘資料了）
 *
 * 為什麼是長度前綴而不是換行分隔的 JSON：整份 PDF（這邊的實例是 300 MB 以上）要能安全
 * 帶過去，換行／跳脫會把位元組層級的東西變成雜訊。
 */
object SyncWire {

    /** 單幀上限 512 MiB（§2），超出即視為協定違規斷連。 */
    const val MAX_FRAME_BYTES = 512 * 1024 * 1024

    /** 雜湊 300 MB 級檔案時的讀取緩衝。 */
    private const val HASH_BUFFER_BYTES = 256 * 1024

    fun writeFrame(out: DataOutputStream, bytes: ByteArray) {
        if (bytes.size > MAX_FRAME_BYTES) {
            throw IOException("Refusing to write oversized frame: ${bytes.size} bytes")
        }
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    fun writeText(out: DataOutputStream, text: String) =
        writeFrame(out, text.toByteArray(Charsets.UTF_8))

    /** 讀一幀。**乾淨 EOF 回 null**；半截的長度前綴視為斷線（丟 IOException）。 */
    fun readFrame(input: DataInputStream): ByteArray? {
        val len = try {
            input.readInt()
        } catch (e: EOFException) {
            return null
        }
        if (len < 0 || len > MAX_FRAME_BYTES) {
            throw IOException("Invalid frame length $len (cap $MAX_FRAME_BYTES)")
        }
        if (len == 0) return ByteArray(0)
        val buf = ByteArray(len)
        input.readFully(buf)
        return buf
    }

    fun readText(input: DataInputStream): String? =
        readFrame(input)?.toString(Charsets.UTF_8)

    /** 檔案不存在回 null（與桌面端一致，manifest 靠它把 fileSha256 標成缺席）。 */
    fun sha256Hex(file: File): String? {
        if (!file.exists()) return null
        val digest = newSha256()
        file.inputStream().buffered(HASH_BUFFER_BYTES).use { fis ->
            val buf = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val n = fis.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().toHex()
    }

    fun sha256Hex(bytes: ByteArray): String =
        newSha256().digest(bytes).toHex()

    private fun newSha256(): MessageDigest = MessageDigest.getInstance("SHA-256")

    private fun ByteArray.toHex(): String {
        val out = CharArray(size * 2)
        for (i in indices) {
            val v = this[i].toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }

    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * `RandomAccessFile.seek(offset)` 讀最多 [limit] 位元組。
     *
     * 絕不整份讀進記憶體：桌面端以 1 MiB 為單位迴圈呼叫，300 MB 的檔案會是 300 次請求。
     * offset 已經 >= 長度時回空陣列 —— 空幀就是 §2 的 EOF marker，client 會據此收尾。
     */
    fun readChunk(file: File, offset: Long, limit: Int): ByteArray {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (offset < 0 || offset >= length) return ByteArray(0)
            raf.seek(offset)
            val size = minOf(limit.toLong(), length - offset).toInt()
            if (size <= 0) return ByteArray(0)
            val buf = ByteArray(size)
            raf.readFully(buf)
            return buf
        }
    }
}
