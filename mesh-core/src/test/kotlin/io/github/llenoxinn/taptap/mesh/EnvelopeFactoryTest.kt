package io.github.llenoxinn.taptap.mesh

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvelopeFactoryTest {
    private val clock = TestClock()
    private val server = serverCodec()
    private val factory = factory(clock)

    @Test
    fun `SOS is signed, sealed, and opens to the request`() {
        val req = sosRequest(priority = Priority.URGENT, note = "naa sa atop")
        val env = factory.sos(req)
        assertEquals(Kind.SOS, env.kind)
        assertEquals(T0, env.createdAt)
        assertEquals(T0 + 48 * 3600, env.expiresAt)
        assertEquals(SERVER_KEY_ID, env.keyId)
        assertTrue(server.verify(env))
        assertEquals(req, server.openSos(env))
        assertEquals(125 + 48 + 16 + "naa sa atop".length, env.bytes.size)
    }

    @Test
    fun `relays can't read the body`() {
        val env = factory.sos(sosRequest(note = "secret note"))
        assertTrue("secret note".encodeToByteArray().toHex() !in env.bytes.toHex())
    }

    @Test
    fun `msgIds come from the injected random`() {
        val a = factory(TestClock(), random = kotlin.random.Random(42)).sos(sosRequest())
        val b = factory(TestClock(), random = kotlin.random.Random(42)).sos(sosRequest())
        assertEquals(a.msgId, b.msgId)
        assertNotEquals(a.msgId, factory.sos(sosRequest()).msgId)
    }

    @Test
    fun `invalid headcount is refused at the source`() {
        assertFailsWith<IllegalArgumentException> { factory.sos(sosRequest(people = 0, injured = 0)) }
        assertFailsWith<IllegalArgumentException> { factory.sos(sosRequest(people = 1, injured = 2)) }
    }

    @Test
    fun `UPDATE carries the parent id in cleartext and copies priority and expiry`() {
        val sos = factory.sos(sosRequest(priority = Priority.INFO))
        clock.t += 600
        val loc = Location(103_000_123, 1_239_000_456, 5, 2)
        val upd = factory.update(sos, loc)
        assertEquals(sos.msgId, upd.refMsgId)
        assertEquals(Priority.INFO, upd.priority)
        assertEquals(sos.expiresAt, upd.expiresAt)
        assertEquals(T0 + 600, upd.createdAt)
        assertEquals(201, upd.bytes.size)
        assertTrue(server.verify(upd))
        assertEquals(loc, server.openUpdate(upd))
    }

    @Test
    fun `RECEIPT and ACK are server-signed and point at the SOS`() {
        val sos = factory.sos(sosRequest())
        val receipt = server.receipt(sos, T0 + 60)
        val ack = server.ack(sos, AckStatus.RESOLVED, T0 + 120)
        for ((env, kind) in listOf(receipt to Kind.RECEIPT, ack to Kind.ACK)) {
            assertEquals(kind, env.kind)
            assertEquals(sos.msgId, env.refMsgId)
            assertEquals(sos.priority, env.priority)
            assertEquals(sos.expiresAt, env.expiresAt)
            assertContentEquals(server.keyring.current.signPub, env.senderKey)
            assertTrue(server.verify(env))
            assertEquals(146, env.bytes.size)
        }
        assertEquals(AckStatus.RESOLVED.ordinal, ack.body[16].toInt())
        assertEquals(T0 + 120, le(ack.body).getInt(17).toLong())
        assertEquals(ack.createdAt, T0 + 120)
    }

    @Test
    fun `server rejects contents it can't trust`() {
        val keys = crypto.signKeypair(seed(9))
        fun sosWith(payload: ByteArray, keyId: Int = SERVER_KEY_ID) = signedEnvelope(
            crypto, keys.secretKey, Kind.SOS, id(3), Priority.URGENT, T0, T0 + 60, keys.publicKey, keyId,
            crypto.seal(payload, server.keyring.current.boxPub),
        )
        assertNull(server.openSos(sosWith(encodeSosPayload(sosRequest(people = 0, injured = 0)))))
        assertNull(server.openSos(sosWith(encodeSosPayload(sosRequest(people = 1).copy(injured = 3)))))
        assertNull(server.openSos(sosWith(encodeSosPayload(sosRequest()), keyId = 99)), "unknown keyId")
    }
}
