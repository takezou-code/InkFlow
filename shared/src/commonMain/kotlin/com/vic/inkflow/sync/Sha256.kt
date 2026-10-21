package com.vic.inkflow.sync

/**
 * SHA-256 (FIPS 180-4), in plain Kotlin.
 *
 * ## Why not `java.security.MessageDigest`
 *
 * Because the whole point of this module is that the **same code** runs on every
 * platform — Android, the desktop, and (from the iOS work) the phone. A JVM
 * digest is not available on a non-JVM target, and an `expect`/`actual` for the
 * digest would mean a hand-written second implementation that has to agree with
 * the first one byte for byte, forever.
 *
 * ## Why not a third-party library
 *
 * A dependency would be fine, but the arithmetic is small, self-contained, and
 * — this is the part that matters — **it is verifiable against the JDK right
 * now**:
 *
 *  - `commonTest/Sha256Test.kt` pins the published NIST/RFC test vectors, so the
 *    implementation is correct on every platform.
 *  - `desktopTest/Sha256JvmTest.kt` compares this implementation against
 *    `java.security.MessageDigest` over a spread of input lengths (empty, one
 *    byte, block boundaries, multi-megabyte). The JDK is the reference; this is
 *    the copy. If they ever disagree, the test says so.
 *
 * That differential check is why this is safe to run on iOS, Android and desktop
 * at once: the algorithm was pinned against a third implementation (the JDK)
 * before the second platform ever ran it.
 *
 * ## The failure mode this protects against
 *
 * A wrong digest does not crash. It makes `docVersion` differ between two
 * devices, which makes every document look changed on every sync round, which
 * makes the desktop re-download every stroke and every 300 MB PDF, forever. The
 * symptom looks like a network problem, so it is genuinely hard to find by
 * hand — which is why it must not be findable at all.
 */
internal class Sha256 {

    private val state = intArrayOf(
        0x6a09e667.toInt(), 0xbb67ae85.toInt(), 0x3c6ef372.toInt(), 0xa54ff53a.toInt(),
        0x510e527f.toInt(), 0x9b05688c.toInt(), 0x1f83d9ab.toInt(), 0x5be0cd19.toInt()
    )

    private val buffer = ByteArray(BLOCK_SIZE)
    private var buffered = 0
    private var totalBytes = 0L

    /** Feeds more bytes into the digest. Call [digest] when done. */
    fun update(data: ByteArray, offset: Int = 0, size: Int = data.size - offset) {
        var pos = offset
        var remaining = size
        totalBytes += remaining.toLong()
        while (remaining > 0) {
            val n = minOf(BLOCK_SIZE - buffered, remaining)
            data.copyInto(buffer, buffered, pos, pos + n)
            buffered += n
            pos += n
            remaining -= n
            if (buffered == BLOCK_SIZE) {
                compress(state, buffer, 0)
                buffered = 0
            }
        }
    }

    /**
     * The digest of everything fed in so far.
     *
     * Does not modify this instance's state, so [digest] may be called more than
     * once — but only the first result is meaningful if more bytes arrive after.
     */
    fun digest(): ByteArray {
        val s = state.copyOf()
        val block = buffer.copyOf()
        var len = buffered

        block[len++] = 0x80.toByte()
        if (len > 56) {
            while (len < BLOCK_SIZE) block[len++] = 0
            compress(s, block, 0)
            len = 0
        }
        while (len < 56) block[len++] = 0

        val bitLength = totalBytes * 8
        block[56] = (bitLength ushr 56).toByte()
        block[57] = (bitLength ushr 48).toByte()
        block[58] = (bitLength ushr 40).toByte()
        block[59] = (bitLength ushr 32).toByte()
        block[60] = (bitLength ushr 24).toByte()
        block[61] = (bitLength ushr 16).toByte()
        block[62] = (bitLength ushr 8).toByte()
        block[63] = bitLength.toByte()
        compress(s, block, 0)

        val out = ByteArray(DIGEST_SIZE)
        for (i in 0 until 8) {
            out[i * 4] = (s[i] ushr 24).toByte()
            out[i * 4 + 1] = (s[i] ushr 16).toByte()
            out[i * 4 + 2] = (s[i] ushr 8).toByte()
            out[i * 4 + 3] = s[i].toByte()
        }
        return out
    }

    private fun compress(s: IntArray, block: ByteArray, off: Int) {
        val w = IntArray(64)
        for (i in 0 until 16) {
            val b = off + i * 4
            w[i] = ((block[b].toInt() and 0xFF) shl 24) or
                ((block[b + 1].toInt() and 0xFF) shl 16) or
                ((block[b + 2].toInt() and 0xFF) shl 8) or
                (block[b + 3].toInt() and 0xFF)
        }
        for (i in 16 until 64) {
            val s0 = rotr(w[i - 15], 7) xor rotr(w[i - 15], 18) xor (w[i - 15] ushr 3)
            val s1 = rotr(w[i - 2], 17) xor rotr(w[i - 2], 19) xor (w[i - 2] ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }

        var a = s[0]; var b = s[1]; var c = s[2]; var d = s[3]
        var e = s[4]; var f = s[5]; var g = s[6]; var h = s[7]

        for (i in 0 until 64) {
            val bigS1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
            val ch = (e and f) xor (e.inv() and g)
            val temp1 = h + bigS1 + ch + K[i] + w[i]
            val bigS0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val temp2 = bigS0 + maj

            h = g; g = f; f = e; e = d + temp1
            d = c; c = b; b = a; a = temp1 + temp2
        }

        s[0] += a; s[1] += b; s[2] += c; s[3] += d
        s[4] += e; s[5] += f; s[6] += g; s[7] += h
    }

    private fun rotr(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))

    private companion object {
        const val BLOCK_SIZE = 64
        const val DIGEST_SIZE = 32

        /** Round constants: the first 32 bits of the fractional parts of the cube roots of the first 64 primes. */
        val K = intArrayOf(
            0x428a2f98.toInt(), 0x71374491.toInt(), 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
            0x3956c25b.toInt(), 0x59f111f1.toInt(), 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
            0xd807aa98.toInt(), 0x12835b01.toInt(), 0x243185be.toInt(), 0x550c7dc3.toInt(),
            0x72be5d74.toInt(), 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
            0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6.toInt(), 0x240ca1cc.toInt(),
            0x2de92c6f.toInt(), 0x4a7484aa.toInt(), 0x5cb0a9dc.toInt(), 0x76f988da.toInt(),
            0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
            0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351.toInt(), 0x14292967.toInt(),
            0x27b70a85.toInt(), 0x2e1b2138.toInt(), 0x4d2c6dfc.toInt(), 0x53380d13.toInt(),
            0x650a7354.toInt(), 0x766a0abb.toInt(), 0x81c2c92e.toInt(), 0x92722c85.toInt(),
            0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
            0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070.toInt(),
            0x19a4c116.toInt(), 0x1e376c08.toInt(), 0x2748774c.toInt(), 0x34b0bcb5.toInt(),
            0x391c0cb3.toInt(), 0x4ed8aa4a.toInt(), 0x5b9cca4f.toInt(), 0x682e6ff3.toInt(),
            0x748f82ee.toInt(), 0x78a5636f.toInt(), 0x84c87814.toInt(), 0x8cc70208.toInt(),
            0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt()
        )
    }
}

/** SHA-256 of a byte array, lowercase hex. */
internal fun sha256Hex(bytes: ByteArray): String =
    Sha256().apply { update(bytes) }.digest().toHex()

/** SHA-256 of a sequence of chunks, lowercase hex — for payloads too big to hold twice. */
internal fun sha256Hex(chunks: Sequence<ByteArray>): String {
    val hasher = Sha256()
    for (chunk in chunks) hasher.update(chunk)
    return hasher.digest().toHex()
}

internal fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        out[i * 2] = HEX[v ushr 4]
        out[i * 2 + 1] = HEX[v and 0x0F]
    }
    return out.concatToString()
}

private val HEX = "0123456789abcdef".toCharArray()
