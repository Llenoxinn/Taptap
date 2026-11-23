package io.github.llenoxinn.taptap.mesh

import io.github.llenoxinn.taptap.mesh.ReceiveOutcome.Accepted
import io.github.llenoxinn.taptap.mesh.ReceiveOutcome.Rejected

public enum class SessionState {
    HANDSHAKE, EXCHANGING, DONE, STALLED, CAPPED, DISCONNECTED, FAILED;

    public val ended: Boolean get() = this != HANDSHAKE && this != EXCHANGING
}

/** `received` = envelopes accepted; `rejected` = envelopes refused, by reason. */
public data class SessionStats(public val sent: Int, public val received: Int, public val rejected: Map<RejectReason, Int>)

/**
 * One phone-to-phone exchange (M1 spec §8). Pure and deterministic: the transport feeds frames
 * in and sends the frames returned. A node runs at most one session at a time (the transport's job).
 * Whatever ends the session, envelopes already accepted stay in the store.
 */
public class SyncSession(private val node: Node) {
    public var state: SessionState = SessionState.HANDSHAKE
        private set

    private var sent = 0
    private var received = 0
    private val rejected = sortedMapOf<RejectReason, Int>()
    public val stats: SessionStats get() = SessionStats(sent, received, rejected.toMap())

    private var startedAt = 0L
    private var lastFrameAt = 0L
    private var peerToken: Long? = null
    private var peerCriticalOnly = false
    private val peerHas = HashSet<IdPrefix>()
    private var sentDone = false
    private var peerDone = false

    /** HELLO, then our SUMMARY frame(s). */
    public fun start(): List<Frame> {
        startedAt = node.clock.now()
        lastFrameAt = startedAt
        node.store.purgeExpired(startedAt)
        val chunks = node.store.summary().sortedBy { it.value }.chunked(FrameCodec.MAX_SUMMARY).ifEmpty { listOf(emptyList()) }
        return listOf(Frame.Hello(FrameCodec.PROTOCOL_VERSION, node.nodeToken, node.criticalOnly)) +
            chunks.mapIndexed { i, chunk -> Frame.Summary(more = i < chunks.lastIndex, prefixes = chunk) }
    }

    /** Raw frame from the transport; a malformed frame ends the session as FAILED. */
    public fun onBytes(bytes: ByteArray): List<Frame> = FrameCodec.decode(bytes)?.let(::onFrame) ?: fail()

    public fun onFrame(frame: Frame): List<Frame> {
        if (state.ended) return emptyList()
        lastFrameAt = node.clock.now()
        if (frame !is Frame.Hello && peerToken == null) return fail() // HELLO must come first
        return when (frame) {
            is Frame.Hello -> hello(frame)
            is Frame.Summary -> summary(frame)
            is Frame.EnvelopeFrame -> {
                when (val outcome = node.receive(frame.bytes)) {
                    is Accepted -> received++
                    is Rejected -> rejected.merge(outcome.reason, 1, Int::plus)
                }
                emptyList()
            }
            Frame.Done -> {
                peerDone = true
                if (sentDone) end(SessionState.DONE)
                emptyList()
            }
        }
    }

    /** Call regularly with the injected clock: ends the session on a stall or at the hard cap. */
    public fun onTick() {
        if (state.ended) return
        val now = node.clock.now()
        when {
            now - startedAt >= node.config.sessionCapS -> end(SessionState.CAPPED)
            now - lastFrameAt >= node.config.stallTimeoutS -> end(SessionState.STALLED)
        }
    }

    public fun onDisconnect() {
        if (!state.ended) end(SessionState.DISCONNECTED)
    }

    private fun hello(frame: Frame.Hello): List<Frame> {
        if (peerToken != null || frame.protocolVersion != FrameCodec.PROTOCOL_VERSION || node.inCooldown(frame.nodeToken)) {
            return fail()
        }
        peerToken = frame.nodeToken
        peerCriticalOnly = frame.criticalOnly
        return emptyList()
    }

    private fun summary(frame: Frame.Summary): List<Frame> {
        if (sentDone) return fail()
        peerHas += frame.prefixes
        if (frame.more) return emptyList()
        state = SessionState.EXCHANGING
        val criticalOnly = peerCriticalOnly || node.criticalOnly
        val toSend = node.store.all()
            .filter { it.msgId.prefix !in peerHas && (!criticalOnly || it.kind.fromServer || it.priority == Priority.CRITICAL) }
            .sortedWith(sendOrder)
            .map { Frame.EnvelopeFrame(it.bytes) }
        sent += toSend.size
        sentDone = true
        if (peerDone) end(SessionState.DONE)
        return toSend + Frame.Done
    }

    private fun fail(): List<Frame> {
        end(SessionState.FAILED)
        return emptyList()
    }

    private fun end(final: SessionState) {
        state = final
        peerToken?.let(node::sessionEnded)
    }

    private companion object {
        /** ACK/RECEIPT → Critical → Urgent → Info, oldest first, msgId as the tie-break. */
        val sendOrder: Comparator<Envelope> =
            compareBy<Envelope>({ !it.kind.fromServer }, { it.priority }).then(byAge)
    }
}
