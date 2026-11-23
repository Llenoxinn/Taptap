package io.github.llenoxinn.taptap.mesh

import io.github.llenoxinn.taptap.mesh.ReceiveOutcome.Accepted
import io.github.llenoxinn.taptap.mesh.ReceiveOutcome.Rejected
import io.github.llenoxinn.taptap.mesh.RejectReason.*
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ValidatorTest {
    private val clock = TestClock()
    private val server = serverCodec()
    private val sender = factory(clock)
    private val node = node(clock)

    private fun assertRejected(reason: RejectReason, bytes: ByteArray) =
        assertEquals(Rejected(reason), node.receive(bytes))

    private fun accepted(bytes: ByteArray): Envelope = assertIs<Accepted>(node.receive(bytes)).envelope

    @Test
    fun `accepts each kind and stores it with hops + 1`() {
        val sos = sender.sos(sosRequest())
        val upd = sender.update(sos, Location(1, 2, 3, 4))
        val other = sender.sos(sosRequest(priority = Priority.INFO))
        val all = listOf(sos, upd, server.receipt(other, T0), server.ack(other, AckStatus.ACKNOWLEDGED, T0))
        for (env in all) {
            val stored = accepted(env.withHops(2).bytes)
            assertEquals(3, stored.hops)
            assertEquals(3, node.store.get(env.msgId)?.hops, "${env.kind}")
        }
    }

    @Test
    fun `SIZE outside 125 until 300`() {
        assertRejected(SIZE, ByteArray(124))
        assertRejected(SIZE, ByteArray(300))
    }

    @Test
    fun `UNKNOWN_FORMAT for another version`() {
        assertRejected(UNKNOWN_FORMAT, sender.sos(sosRequest()).bytes.also { it[0] = 2 })
    }

    @Test
    fun `MALFORMED body length`() {
        val bytes = sender.sos(sosRequest()).bytes
        assertRejected(MALFORMED, bytes.copyOf(bytes.size - 1))
    }

    @Test
    fun `BAD_SIGNATURE for a tampered byte`() {
        val bytes = sender.sos(sosRequest()).bytes
        bytes[70] = (bytes[70].toInt() xor 1).toByte() // inside the sealed body
        assertRejected(BAD_SIGNATURE, bytes)
    }

    @Test
    fun `BAD_SIGNATURE for a forged sender`() {
        val victim = crypto.signKeypair(seed(2))
        val forger = crypto.signKeypair(seed(3))
        val forged = signedEnvelope(
            crypto, forger.secretKey, Kind.SOS, id(9), Priority.CRITICAL, T0, T0 + 60, victim.publicKey, SERVER_KEY_ID, ByteArray(64),
        )
        assertRejected(BAD_SIGNATURE, forged.bytes)
    }

    @Test
    fun `UNTRUSTED_SERVER for an ACK from an unpinned key or unknown keyId`() {
        val sos = sender.sos(sosRequest())
        val impostor = ServerCodec(crypto, mapOf(SERVER_KEY_ID to serverSecret(200)), SERVER_KEY_ID, Random(1))
        assertRejected(UNTRUSTED_SERVER, impostor.ack(sos, AckStatus.ACKNOWLEDGED, T0).bytes)
        val unknownKeyId = ServerCodec(crypto, mapOf(7 to serverSecret()), 7, Random(1))
        assertRejected(UNTRUSTED_SERVER, unknownKeyId.ack(sos, AckStatus.ACKNOWLEDGED, T0).bytes)
    }

    @Test
    fun `BAD_SIGNATURE for a tampered ACK from the pinned key`() {
        val ack = server.ack(sender.sos(sosRequest()), AckStatus.ACKNOWLEDGED, T0).bytes
        ack[EnvelopeCodec.HEADER_SIZE + 16] = AckStatus.RESOLVED.ordinal.toByte()
        assertRejected(BAD_SIGNATURE, ack)
    }

    @Test
    fun `EXPIRED at expiresAt`() {
        val sos = sender.sos(sosRequest())
        clock.t = sos.expiresAt
        assertRejected(EXPIRED, sos.bytes)
    }

    @Test
    fun `FUTURE_DATED beyond the 10 minute skew`() {
        val ahead = TestClock(T0 + 601)
        assertRejected(FUTURE_DATED, factory(ahead).sos(sosRequest()).bytes)
        ahead.t = T0 + 600
        accepted(factory(ahead, deviceSeed = 2).sos(sosRequest()).bytes)
    }

    @Test
    fun `TTL_TOO_LONG when expiry exceeds the priority's TTL`() {
        val keys = crypto.signKeypair(seed(4))
        val tooLong = signedEnvelope(
            crypto, keys.secretKey, Kind.SOS, id(8), Priority.INFO, T0, T0 + 24 * 3600 + 1, keys.publicKey, SERVER_KEY_ID, ByteArray(64),
        )
        assertRejected(TTL_TOO_LONG, tooLong.bytes)
    }

    @Test
    fun `HOP_LIMIT past 16 hops`() {
        val sos = sender.sos(sosRequest())
        assertRejected(HOP_LIMIT, sos.withHops(16).bytes)
        assertEquals(16, accepted(sos.withHops(15).bytes).hops)
    }

    @Test
    fun `DUPLICATE when already held`() {
        val sos = sender.sos(sosRequest())
        accepted(sos.bytes)
        assertRejected(DUPLICATE, sos.withHops(4).bytes)
    }

    @Test
    fun `TOMBSTONED when cleared earlier`() {
        val sos = sender.sos(sosRequest())
        node.store.addTombstone(sos.msgId, sos.expiresAt)
        assertRejected(TOMBSTONED, sos.bytes)
    }

    @Test
    fun `rules run in order - a tampered expired envelope is BAD_SIGNATURE`() {
        val bytes = sender.sos(sosRequest()).bytes
        bytes[70] = (bytes[70].toInt() xor 1).toByte()
        clock.t += 400 * 3600
        assertRejected(BAD_SIGNATURE, bytes)
    }
}
