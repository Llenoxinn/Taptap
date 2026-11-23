package io.github.llenoxinn.taptap.mesh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SigningTest {
    private val keys = crypto.signKeypair(seed(1))
    private val env = signedEnvelope(
        crypto, keys.secretKey, Kind.SOS, id(1), Priority.CRITICAL, T0, T0 + 3600,
        keys.publicKey, 0, ByteArray(64) { it.toByte() },
    )

    @Test
    fun `a fresh envelope verifies`() = assertTrue(verifySignature(crypto, env))

    @Test
    fun `changing hops keeps the signature valid`() {
        val relayed = env.withHops(9)
        assertEquals(9, relayed.hops)
        assertTrue(verifySignature(crypto, relayed))
        val raw = env.bytes.copyOf().also { it[EnvelopeCodec.HOPS_OFFSET] = 15 }
        assertTrue(verifySignature(crypto, assertNotNull(EnvelopeCodec.decode(raw))))
    }

    @Test
    fun `changing any other byte breaks the envelope`() {
        for (i in env.bytes.indices) {
            if (i == EnvelopeCodec.HOPS_OFFSET) continue
            val bytes = env.bytes.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            // Either the format check rejects it, or the signature does.
            val decoded = EnvelopeCodec.decode(bytes) ?: continue
            assertFalse(verifySignature(crypto, decoded), "flipping byte $i still verifies")
        }
    }
}
