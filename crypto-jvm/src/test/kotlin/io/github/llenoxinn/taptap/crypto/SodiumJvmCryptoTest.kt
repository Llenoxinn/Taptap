package io.github.llenoxinn.taptap.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SodiumJvmCryptoTest {
    private val crypto = SodiumJvmCrypto()
    private val seed = ByteArray(32) { it.toByte() }
    private val plaintext = "lat lon people".encodeToByteArray()

    @Test
    fun `seeded keypairs are deterministic and the right size`() {
        val a = crypto.signKeypair(seed)
        assertContentEquals(a.publicKey, crypto.signKeypair(seed).publicKey)
        assertEquals(32, a.publicKey.size)
        assertEquals(64, a.secretKey.size)
        val b = crypto.boxKeypair(seed)
        assertContentEquals(b.publicKey, crypto.boxKeypair(seed).publicKey)
        assertEquals(32, b.secretKey.size)
    }

    @Test
    fun `sign then verify, and a changed message fails`() {
        val keys = crypto.signKeypair()
        val sig = crypto.sign(plaintext, keys.secretKey)
        assertEquals(64, sig.size)
        assertTrue(crypto.verify(sig, plaintext, keys.publicKey))
        assertFalse(crypto.verify(sig, plaintext + 0.toByte(),keys.publicKey))
        assertFalse(crypto.verify(sig, plaintext, crypto.signKeypair().publicKey))
    }

    @Test
    fun `sealed box opens with the right key and adds 48 bytes`() {
        val keys = crypto.boxKeypair()
        val sealed = crypto.seal(plaintext, keys.publicKey)
        assertEquals(plaintext.size + 48, sealed.size)
        assertContentEquals(plaintext, crypto.openSealed(sealed, keys.publicKey, keys.secretKey))
    }

    @Test
    fun `wrong key or tampered ciphertext returns null`() {
        val keys = crypto.boxKeypair()
        val other = crypto.boxKeypair()
        val sealed = crypto.seal(plaintext, keys.publicKey)
        assertNull(crypto.openSealed(sealed, other.publicKey, other.secretKey))
        sealed[sealed.size - 1] = (sealed.last().toInt() xor 1).toByte()
        assertNull(crypto.openSealed(sealed, keys.publicKey, keys.secretKey))
        assertNull(crypto.openSealed(ByteArray(10), keys.publicKey, keys.secretKey))
    }
}
