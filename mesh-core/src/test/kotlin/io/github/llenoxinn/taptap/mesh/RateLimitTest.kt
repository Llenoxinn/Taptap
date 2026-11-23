package io.github.llenoxinn.taptap.mesh

import io.github.llenoxinn.taptap.mesh.ReceiveOutcome.Rejected
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RateLimitTest {
    private val clock = TestClock()
    private val senderClock = TestClock()
    private val sender = factory(senderClock)
    private val node = node(clock)
    private val server = serverCodec()

    private fun acceptFive() = repeat(5) { assertIs<ReceiveOutcome.Accepted>(node.receive(sender.sos(sosRequest()).bytes)) }

    @Test
    fun `6th SOS from one key within an hour is RATE_LIMITED`() {
        acceptFive()
        clock.t += 3599
        assertEquals(Rejected(RejectReason.RATE_LIMITED), node.receive(sender.sos(sosRequest()).bytes))
    }

    @Test
    fun `backdated createdAt doesn't bypass the limit`() {
        acceptFive()
        senderClock.t -= 7200
        assertEquals(Rejected(RejectReason.RATE_LIMITED), node.receive(sender.sos(sosRequest()).bytes))
    }

    @Test
    fun `accepted again an hour later by the receiver's clock`() {
        acceptFive()
        clock.t += 3600
        senderClock.t += 3600
        assertIs<ReceiveOutcome.Accepted>(node.receive(sender.sos(sosRequest()).bytes))
    }

    @Test
    fun `other senders, UPDATEs and ACKs are counted separately`() {
        acceptFive()
        assertIs<ReceiveOutcome.Accepted>(node.receive(factory(senderClock, deviceSeed = 2).sos(sosRequest()).bytes))
        val parent = sender.sos(sosRequest())
        repeat(10) { assertIs<ReceiveOutcome.Accepted>(node.receive(sender.update(parent, Location(1, 1, 1, 1)).bytes)) }
        assertEquals(Rejected(RejectReason.RATE_LIMITED), node.receive(sender.update(parent, Location(1, 1, 1, 1)).bytes))
        repeat(6) { assertIs<ReceiveOutcome.Accepted>(node.receive(server.receipt(sender.sos(sosRequest()), T0).bytes)) }
    }
}
