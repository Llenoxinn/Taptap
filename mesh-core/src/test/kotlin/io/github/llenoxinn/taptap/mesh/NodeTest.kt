package io.github.llenoxinn.taptap.mesh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeTest {
    private val clock = TestClock()
    private val server = serverCodec()

    @Test
    fun `3-node chain carries an SOS to C and its RECEIPT back to A, clearing B`() {
        val confirmedAtOrigin = mutableListOf<Envelope>()
        lateinit var a: Node
        a = node(clock, seedN = 70) { env -> if (env.kind.fromServer && env.refMsgId in a.mySosIds) confirmedAtOrigin += env }
        val b = node(clock, seedN = 71)
        val c = node(clock, seedN = 72)

        val sos = a.sendSos(sosRequest())
        val update = a.sendUpdate(sos, Location(1, 2, 3, 4))
        assertEquals(setOf(sos.msgId), a.mySosIds)

        sync(a, b)
        sync(b, c)
        assertEquals(2, c.store.get(sos.msgId)?.hops, "A→B→C is two hops")
        assertNotNull(c.store.get(update.msgId))

        // C reaches the internet: the server issues a RECEIPT per envelope it accepts.
        val batch = c.uplinkBatch()
        assertEquals(setOf(sos.msgId, update.msgId), batch.envelopes.map { EnvelopeCodec.decode(it)?.msgId }.toSet())
        assertTrue(sos.msgId.prefix in batch.summary)
        val receipts = batch.envelopes.map { server.receipt(assertNotNull(EnvelopeCodec.decode(it)), clock.t) }
        c.applyUplinkResult(UplinkResult(receipts.map { it.bytes })).forEach { assertIs<ReceiveOutcome.Accepted>(it) }
        assertNull(c.store.get(sos.msgId))
        assertTrue(c.store.isTombstoned(sos.msgId))

        clock.t += 200 // past the B–C cooldown
        sync(c, b)
        assertNull(b.store.get(sos.msgId), "RECEIPT clears the relay's SOS")
        assertNull(b.store.get(update.msgId), "…and its UPDATE")
        assertTrue(b.store.isTombstoned(sos.msgId))

        sync(b, a)
        assertTrue(confirmedAtOrigin.any { it.kind == Kind.RECEIPT && it.refMsgId == sos.msgId })
        assertNull(a.store.get(sos.msgId))
        assertTrue(a.uplinkBatch().envelopes.isEmpty(), "nothing left to upload once confirmed")
    }

    @Test
    fun `a forged RECEIPT from the uplink is rejected`() {
        val a = node(clock)
        val sos = a.sendSos(sosRequest())
        val impostor = ServerCodec(crypto, mapOf(SERVER_KEY_ID to serverSecret(300)), SERVER_KEY_ID, kotlin.random.Random(1))
        val outcomes = a.applyUplinkResult(UplinkResult(listOf(impostor.receipt(sos, T0).bytes)))
        assertEquals(listOf<ReceiveOutcome>(ReceiveOutcome.Rejected(RejectReason.UNTRUSTED_SERVER)), outcomes)
        assertNotNull(a.store.get(sos.msgId))
    }
}
