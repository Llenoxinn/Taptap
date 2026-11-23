package io.github.llenoxinn.taptap.mesh

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvelopeCodecTest {
    private val key = ByteArray(32) { 7 }
    private val sig = ByteArray(64) { 9 }

    private fun encode(kind: Kind, body: ByteArray, createdAt: Long = T0): ByteArray =
        EnvelopeCodec.encode(kind, id(1), Priority.URGENT, createdAt, createdAt + 3600, 3, key, 2, body, sig)

    private fun serverBody(status: Int): ByteArray = id(5).bytes + byteArrayOf(status.toByte(), 1, 2, 3, 4)

    private val bodies = mapOf(
        Kind.SOS to ByteArray(48 + 16) { it.toByte() },
        Kind.UPDATE to id(5).bytes + ByteArray(60) { it.toByte() },
        Kind.ACK to serverBody(2),
        Kind.RECEIPT to serverBody(0),
    )

    @Test
    fun `round-trips every kind`() {
        for ((kind, body) in bodies) {
            val bytes = encode(kind, body)
            val env = assertNotNull(EnvelopeCodec.decode(bytes), "$kind")
            assertEquals(kind, env.kind)
            assertEquals(id(1), env.msgId)
            assertEquals(Priority.URGENT, env.priority)
            assertEquals(T0, env.createdAt)
            assertEquals(T0 + 3600, env.expiresAt)
            assertEquals(3, env.hops)
            assertContentEquals(key, env.senderKey)
            assertEquals(2, env.keyId)
            assertContentEquals(body, env.body)
            assertContentEquals(sig, env.sig)
            assertContentEquals(bytes, env.bytes)
            assertEquals(if (kind == Kind.SOS) null else id(5), env.refMsgId)
        }
    }

    @Test
    fun `u32 timestamps above 2^31 round-trip`() {
        val env = assertNotNull(EnvelopeCodec.decode(encode(Kind.SOS, bodies.getValue(Kind.SOS), 3_000_000_000L)))
        assertEquals(3_000_000_000L, env.createdAt)
    }

    @Test
    fun `max SOS envelope is 269 bytes and every kind is under 300`() {
        val max = encode(Kind.SOS, ByteArray(48 + 96))
        assertEquals(269, max.size)
        assertNotNull(EnvelopeCodec.decode(max))
        assertNull(EnvelopeCodec.decode(encode(Kind.SOS, ByteArray(48 + 97))), "payload over 96 bytes")
        for ((kind, body) in bodies + (Kind.SOS to ByteArray(48 + 96))) {
            assertTrue(encode(kind, body).size < EnvelopeCodec.MAX_SIZE, "$kind too big")
        }
        assertEquals(201, encode(Kind.UPDATE, bodies.getValue(Kind.UPDATE)).size)
        assertEquals(146, encode(Kind.ACK, bodies.getValue(Kind.ACK)).size)
    }

    @Test
    fun `longest note gives the 96-byte max payload`() {
        assertEquals(96, encodeSosPayload(sosRequest(note = "x".repeat(500))).size)
    }

    @Test
    fun `note is truncated on a code point boundary`() {
        // 1 + 30×3 bytes; 80 bytes falls inside the 27th euro sign, so keep 26.
        val euro = decodeSosPayload(Priority.URGENT, encodeSosPayload(sosRequest(note = "a" + "€".repeat(30))))
        assertEquals("a" + "€".repeat(26), euro?.note)
        val emoji = decodeSosPayload(Priority.URGENT, encodeSosPayload(sosRequest(note = "😀".repeat(21))))
        assertEquals("😀".repeat(20), emoji?.note)
    }

    @Test
    fun `payload round-trips and saturates u16 fields`() {
        val req = sosRequest(note = "baha").copy(accuracyM = 70_000, fixAgeS = -5, flags = 1 shl 15)
        val back = decodeSosPayload(Priority.CRITICAL, encodeSosPayload(req))
        assertEquals(req.copy(accuracyM = 65_535, fixAgeS = 0), back)
    }

    @Test
    fun `no fix is encoded as Int MIN_VALUE`() {
        val payload = encodeSosPayload(sosRequest(lat = null))
        assertEquals(Int.MIN_VALUE, le(payload).int)
        val back = assertNotNull(decodeSosPayload(Priority.CRITICAL, payload))
        assertNull(back.lat)
        assertNull(back.lon)
    }

    @Test
    fun `rejects truncated input`() {
        for ((kind, body) in bodies) {
            val bytes = encode(kind, body)
            assertNull(EnvelopeCodec.decode(bytes.copyOf(bytes.size - 1)), "$kind minus one byte")
        }
        assertEquals(RejectReason.SIZE, EnvelopeCodec.formatError(ByteArray(124)))
        assertEquals(RejectReason.SIZE, EnvelopeCodec.formatError(ByteArray(300)))
    }

    @Test
    fun `unknown version, kind or priority is UNKNOWN_FORMAT`() {
        val good = encode(Kind.SOS, bodies.getValue(Kind.SOS))
        for ((offset, value) in listOf(0 to 2, 1 to 4, 18 to 3)) {
            val bad = good.copyOf().also { it[offset] = value.toByte() }
            assertEquals(RejectReason.UNKNOWN_FORMAT, EnvelopeCodec.formatError(bad), "offset $offset")
        }
    }

    @Test
    fun `bad ACK or RECEIPT status is MALFORMED`() {
        assertEquals(RejectReason.MALFORMED, EnvelopeCodec.formatError(encode(Kind.ACK, serverBody(3))))
        assertEquals(RejectReason.MALFORMED, EnvelopeCodec.formatError(encode(Kind.RECEIPT, serverBody(1))))
    }
}
