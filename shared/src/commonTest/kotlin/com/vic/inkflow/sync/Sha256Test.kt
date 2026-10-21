package com.vic.inkflow.sync

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shared SHA-256, pinned to the published test vectors.
 *
 * These vectors are the ones every SHA-256 implementation is checked against
 * (FIPS 180-2 / NIST). They run on **every** platform, including the ones this
 * module has not shipped to yet — which is the point: a digest that is wrong
 * only on one platform produces a `docVersion` mismatch, and the symptom of
 * that is "sync re-downloads everything, forever" rather than a crash.
 *
 * The companion differential test against the JDK lives in `desktopTest`
 * (`Sha256JvmTest`), because `commonTest` cannot use `java.security`.
 */
class Sha256Test {

    private fun hex(input: ByteArray): String = sha256Hex(input)

    @Test
    fun `empty input`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            hex(ByteArray(0))
        )
    }

    @Test
    fun `abc`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hex("abc".toByteArray(Charsets.UTF_8))
        )
    }

    @Test
    fun `two block message`() {
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".toByteArray(Charsets.UTF_8))
        )
    }

    @Test
    fun `one million a`() {
        val million = ByteArray(1_000_000) { 'a'.code.toByte() }
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            hex(million)
        )
    }

    @Test
    fun `streaming a digest equals a one shot digest`() {
        val data = ByteArray(200_000) { (it * 31).toByte() }
        val oneShot = sha256Hex(data)
        val chunked = sha256Hex(data.toList().chunked(7777).asSequence().map { it.toByteArray() })
        assertEquals(oneShot, chunked)
    }

    @Test
    fun `digest does not disturb the state`() {
        val data = "abc".toByteArray(Charsets.UTF_8)
        val hasher = Sha256()
        hasher.update(data)
        val first = hasher.digest().toHex()
        val second = hasher.digest().toHex()
        assertEquals(first, second)
    }
}
