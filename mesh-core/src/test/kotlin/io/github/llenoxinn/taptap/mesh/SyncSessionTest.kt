package io.github.llenoxinn.taptap.mesh

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Runs a full session between [a] and [b] over encoded frames, alternating one frame each way. */
internal fun sync(a: Node, b: Node): Pair<SyncSession, SyncSession> {
    val sa = a.session()
    val sb = b.session()
    val toB = ArrayDeque(sa.start())
    val toA = ArrayDeque(sb.start())
    while (toA.isNotEmpty() || toB.isNotEmpty()) {
        toB.removeFirstOrNull()?.let { toA += sb.onBytes(FrameCodec.encode(it)) }
        toA.removeFirstOrNull()?.let { toB += sa.onBytes(FrameCodec.encode(it)) }
    }
    return sa to sb
}

class SyncSessionTest {
    private val clock = TestClock()
    private val server = serverCodec()
    private val a = node(clock, seedN = 60)
    private val b = node(clock, seedN = 61)
    private var nextSender = 10

    private fun sos(priority: Priority, at: Long = T0): Envelope =
        factory(TestClock(at), deviceSeed = nextSender++).sos(sosRequest(priority = priority))

    /** Starts a session on [node] and feeds it a peer HELLO + one SUMMARY; returns the envelopes it sends. */
    private fun sendsTo(
        node: Node,
        peerHas: List<IdPrefix> = emptyList(),
        peerCriticalOnly: Boolean = false,
    ): List<Envelope> {
        val s = node.session()
        s.start()
        s.onFrame(Frame.Hello(1, 777L, peerCriticalOnly))
        val out = s.onFrame(Frame.Summary(false, peerHas))
        assertEquals(Frame.Done, out.last())
        return out.dropLast(1).map { assertNotNull(EnvelopeCodec.decode((it as Frame.EnvelopeFrame).bytes)) }
    }

    @Test
    fun `sends ACK-RECEIPT, then Critical, Urgent, Info, oldest first`() {
        val info = sos(Priority.INFO, T0 - 10)
        val urgent = sos(Priority.URGENT, T0 - 20)
        val critNew = sos(Priority.CRITICAL, T0 - 5)
        val critOld = sos(Priority.CRITICAL, T0 - 50)
        val receipt = server.receipt(sos(Priority.INFO), T0)
        listOf(info, urgent, critNew, critOld, receipt).forEach(a.store::put)
        assertEquals(listOf(receipt, critOld, critNew, urgent, info).map { it.msgId }, sendsTo(a).map { it.msgId })
    }

    @Test
    fun `never sends what the peer already has`() {
        val held = sos(Priority.CRITICAL)
        val missing = sos(Priority.CRITICAL)
        listOf(held, missing).forEach(a.store::put)
        assertEquals(listOf(missing.msgId), sendsTo(a, peerHas = listOf(held.msgId.prefix)).map { it.msgId })
    }

    @Test
    fun `criticalOnly on either side sends only ACK-RECEIPT and Critical`() {
        val crit = sos(Priority.CRITICAL)
        val receipt = server.receipt(sos(Priority.INFO), T0)
        listOf(crit, sos(Priority.URGENT), sos(Priority.INFO), receipt).forEach(a.store::put)
        val expected = setOf(crit.msgId, receipt.msgId)
        assertEquals(expected, sendsTo(a, peerCriticalOnly = true).map { it.msgId }.toSet())
        a.criticalOnly = true
        clock.t += 1000 // past the cooldown with the fake peer token
        assertEquals(expected, sendsTo(a).map { it.msgId }.toSet())
    }

    @Test
    fun `full exchange swaps everything and both end DONE with no duplicates`() {
        val forB = List(3) { sos(Priority.URGENT) }
        val forA = List(2) { sos(Priority.CRITICAL) }
        forB.forEach(a.store::put)
        forA.forEach(b.store::put)
        val (sa, sb) = sync(a, b)
        assertEquals(SessionState.DONE, sa.state)
        assertEquals(SessionState.DONE, sb.state)
        assertEquals(SessionStats(sent = 3, received = 2, rejected = emptyMap()), sa.stats)
        assertEquals(SessionStats(sent = 2, received = 3, rejected = emptyMap()), sb.stats)
        (forA + forB).forEach { assertNotNull(a.store.get(it.msgId)); assertNotNull(b.store.get(it.msgId)) }
    }

    @Test
    fun `SUMMARY splits over several frames and the peer waits for the last one`() {
        val known = sos(Priority.CRITICAL)
        val unknown = sos(Priority.CRITICAL)
        listOf(known, unknown).forEach(b.store::put)
        a.store.addTombstone(known.msgId, T0 + 3600)
        repeat(FrameCodec.MAX_SUMMARY) { a.store.addTombstone(MsgId(le(ByteArray(16)).putInt(it).array()), T0 + 3600) }

        val frames = a.session().start()
        val summaries = frames.filterIsInstance<Frame.Summary>()
        assertEquals(listOf(true to 2000, false to 1), summaries.map { it.more to it.prefixes.size })
        summaries.forEach { assertTrue(FrameCodec.encode(it).size - 3 <= 16_003, "payload over 16,003 B") }

        val s = b.session()
        s.start()
        s.onFrame(frames.first())
        assertEquals(emptyList(), s.onFrame(summaries[0]), "must wait for the last SUMMARY frame")
        val out = s.onFrame(summaries[1])
        assertEquals(listOf(unknown.msgId), out.filterIsInstance<Frame.EnvelopeFrame>().map { EnvelopeCodec.decode(it.bytes)?.msgId })
        assertEquals(Frame.Done, out.last())
    }

    @Test
    fun `stalls after 10 s without a frame and keeps what it accepted`() {
        val s = a.session()
        s.start()
        s.onFrame(Frame.Hello(1, 5L, false))
        val sos = sos(Priority.CRITICAL)
        s.onFrame(Frame.EnvelopeFrame(sos.bytes))
        clock.t += 9
        s.onTick()
        assertEquals(SessionState.HANDSHAKE, s.state)
        clock.t += 1
        s.onTick()
        assertEquals(SessionState.STALLED, s.state)
        assertNotNull(a.store.get(sos.msgId))
    }

    @Test
    fun `hard cap at 120 s even while frames keep coming`() {
        val s = a.session()
        s.start()
        s.onFrame(Frame.Hello(1, 5L, false))
        repeat(23) {
            clock.t += 5
            s.onFrame(Frame.EnvelopeFrame(ByteArray(10))) // junk envelope: rejected, but it is traffic
            s.onTick()
        }
        assertEquals(SessionState.HANDSHAKE, s.state)
        clock.t += 5
        s.onTick()
        assertEquals(SessionState.CAPPED, s.state)
        assertEquals(mapOf(RejectReason.SIZE to 23), s.stats.rejected)
    }

    @Test
    fun `unknown protocol version fails without sending anything`() {
        val s = a.session()
        s.start()
        assertEquals(emptyList(), s.onFrame(Frame.Hello(2, 5L, false)))
        assertEquals(SessionState.FAILED, s.state)
        assertEquals(emptyList(), s.onFrame(Frame.Summary(false, emptyList())))
    }

    @Test
    fun `malformed or out-of-order frames fail the session, never crash`() {
        for (bad in listOf(byteArrayOf(9, 0, 0), byteArrayOf(4, 1, 0), byteArrayOf(1), FrameCodec.encode(Frame.Done))) {
            val s = a.session()
            s.start()
            clock.t += 1000
            s.onBytes(bad)
            assertEquals(SessionState.FAILED, s.state, bad.toHex())
        }
    }

    @Test
    fun `same peer token is refused for 120 s after a session ends`() {
        sync(a, b)
        val bToken = b.nodeToken
        assertTrue(a.inCooldown(bToken))
        clock.t += 119
        val s = a.session()
        s.start()
        s.onFrame(Frame.Hello(1, bToken, false))
        assertEquals(SessionState.FAILED, s.state)
        clock.t += 1
        val (sa, _) = sync(a, b)
        assertEquals(SessionState.DONE, sa.state)
    }

    @Test
    fun `node token rotates every 24 h`() {
        val t = a.nodeToken
        clock.t += 24 * 3600 - 1
        assertEquals(t, a.nodeToken)
        clock.t += 1
        assertTrue(t != a.nodeToken)
    }

    @Test
    fun `frames round-trip through the codec`() {
        val env = sos(Priority.CRITICAL)
        val frames = listOf(
            Frame.Hello(1, -42L, true),
            Frame.Summary(true, listOf(IdPrefix(1), IdPrefix(Long.MIN_VALUE))),
            Frame.EnvelopeFrame(env.bytes),
            Frame.Done,
        )
        for (f in frames) {
            val back = FrameCodec.decode(FrameCodec.encode(f))
            if (f is Frame.EnvelopeFrame) assertContentEquals(f.bytes, assertIs<Frame.EnvelopeFrame>(back).bytes)
            else assertEquals(f, back)
        }
    }
}
