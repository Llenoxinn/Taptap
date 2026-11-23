package io.github.llenoxinn.taptap.mesh

import io.github.llenoxinn.taptap.mesh.ReceiveOutcome.Accepted
import io.github.llenoxinn.taptap.mesh.ReceiveOutcome.Rejected
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorePolicyTest {
    private val clock = TestClock()
    private val server = serverCodec()
    private var nextSender = 10

    /** A fresh sender per message, so the rate limit never interferes. */
    private fun sos(priority: Priority = Priority.CRITICAL, at: Long = clock.t): Envelope =
        factory(TestClock(at), deviceSeed = nextSender++).sos(sosRequest(priority = priority))

    @Test
    fun `evicts expired, then orphan UPDATE, then oldest Info, then oldest Urgent, never Critical or ACK`() {
        val node = node(clock, config = MeshConfig(storeCapacity = 5))
        val store = node.store
        val expiring = sos(Priority.INFO, at = T0 - 24 * 3600 + 10) // expires at T0 + 10
        val orphan = factory(clock, deviceSeed = 99).update(sos(), Location(1, 1, 1, 1)) // parent not held
        val info = sos(Priority.INFO)
        val urgent = sos(Priority.URGENT)
        val ack = server.ack(sos(), AckStatus.ACKNOWLEDGED, T0)
        listOf(expiring, orphan, info, urgent, ack).forEach(store::put)
        clock.t = T0 + 10

        for (expectedVictim in listOf(expiring, orphan, info, urgent)) {
            assertIs<Accepted>(node.receive(sos().bytes))
            assertNull(store.get(expectedVictim.msgId), "should have evicted ${expectedVictim.kind}/${expectedVictim.priority}")
            assertEquals(5, store.all().size)
        }
        assertEquals(Rejected(RejectReason.STORE_FULL), node.receive(sos().bytes))
        assertNotNull(store.get(ack.msgId))
        assertTrue(store.all().all { it.kind.fromServer || it.priority == Priority.CRITICAL }, "only Critical and ACK survive")
    }

    @Test
    fun `oldest Info goes first`() {
        val node = node(clock, config = MeshConfig(storeCapacity = 2))
        val older = sos(Priority.INFO, at = T0 - 100)
        val newer = sos(Priority.INFO, at = T0 - 50)
        listOf(newer, older).forEach(node.store::put)
        assertIs<Accepted>(node.receive(sos(Priority.INFO).bytes))
        assertNull(node.store.get(older.msgId))
        assertNotNull(node.store.get(newer.msgId))
    }

    @Test
    fun `a lower priority never evicts a higher one, but an ACK may`() {
        val node = node(clock, config = MeshConfig(storeCapacity = 2))
        val (older, newer) = listOf(T0 - 100, T0 - 50).map { sos(Priority.URGENT, at = it) }
        listOf(older, newer).forEach(node.store::put)
        assertEquals(Rejected(RejectReason.STORE_FULL), node.receive(sos(Priority.INFO).bytes))
        assertIs<Accepted>(node.receive(sos(Priority.URGENT).bytes))
        assertNull(node.store.get(older.msgId), "same priority: oldest goes")
        assertIs<Accepted>(node.receive(server.receipt(sos(Priority.INFO), T0).bytes))
        assertNull(node.store.get(newer.msgId), "ACK/RECEIPT outranks Urgent")
    }

    @Test
    fun `a RECEIPT removes the SOS and its UPDATEs, tombstones the SOS, and is kept`() {
        val node = node(clock)
        val sosFactory = factory(clock, deviceSeed = 3)
        val target = sosFactory.sos(sosRequest())
        val updates = List(2) { sosFactory.update(target, Location(it, it, 1, 1)) }
        val unrelated = sos()
        (listOf(target, unrelated) + updates).forEach { assertIs<Accepted>(node.receive(it.bytes)) }

        val receipt = server.receipt(target, T0 + 5)
        assertIs<Accepted>(node.receive(receipt.bytes))
        assertNull(node.store.get(target.msgId))
        updates.forEach { assertNull(node.store.get(it.msgId)) }
        assertNotNull(node.store.get(unrelated.msgId))
        assertNotNull(node.store.get(receipt.msgId))
        assertEquals(target.expiresAt, node.store.tombstones()[target.msgId])
        assertEquals(Rejected(RejectReason.TOMBSTONED), node.receive(target.bytes))
        assertTrue(target.msgId.prefix in node.store.summary(), "summary includes tombstones")
    }

    @Test
    fun `an ACK is accepted into a store full of Critical when it clears its own SOS`() {
        val node = node(clock, config = MeshConfig(storeCapacity = 2))
        val a = sos()
        listOf(a, sos()).forEach(node.store::put)
        assertIs<Accepted>(node.receive(server.ack(a, AckStatus.ACKNOWLEDGED, T0).bytes))
        assertEquals(2, node.store.all().size)
    }

    @Test
    fun `tombstone overflow drops the one expiring soonest`() {
        val node = node(clock, config = MeshConfig(tombstoneCapacity = 2))
        val (critical, urgent, info) = listOf(Priority.CRITICAL, Priority.URGENT, Priority.INFO).map { sos(it) }
        listOf(critical, urgent, info).forEach { assertIs<Accepted>(node.receive(server.receipt(it, T0).bytes)) }
        // When Info's tombstone arrives, the two held are Critical (72 h) and Urgent (48 h): Urgent goes.
        assertEquals(setOf(critical.msgId, info.msgId), node.store.tombstones().keys)
    }

    @Test
    fun `purgeExpired drops expired envelopes and tombstones`() {
        val store = InMemoryMessageStore()
        val short = sos(Priority.INFO)
        val long = sos(Priority.CRITICAL)
        listOf(short, long).forEach(store::put)
        store.addTombstone(id(1), T0 + 10)
        store.addTombstone(id(2), T0 + 100 * 3600)
        store.purgeExpired(short.expiresAt)
        assertNull(store.get(short.msgId))
        assertNotNull(store.get(long.msgId))
        assertFalse(store.isTombstoned(id(1)))
        assertTrue(store.isTombstoned(id(2)))
    }
}
