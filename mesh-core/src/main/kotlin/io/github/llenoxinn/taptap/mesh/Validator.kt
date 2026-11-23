package io.github.llenoxinn.taptap.mesh

import io.github.llenoxinn.taptap.mesh.ReceiveOutcome.Accepted
import io.github.llenoxinn.taptap.mesh.ReceiveOutcome.Rejected
import io.github.llenoxinn.taptap.mesh.RejectReason.*

/**
 * Receive rules (M1 spec §6), checked in order; the first failure wins. Pure check: it doesn't
 * store anything ([Node.receive] does). Relays can't open bodies, so contents are not checked here.
 */
public class Validator(private val node: Node) {
    public fun check(bytes: ByteArray): ReceiveOutcome {
        EnvelopeCodec.formatError(bytes)?.let { return Rejected(it) } // rules 1–3
        val env = EnvelopeCodec.decode(bytes) ?: return Rejected(MALFORMED)
        val now = node.clock.now()
        val config = node.config
        if (env.kind.fromServer) { // rule 4b: only pinned server keys may sign ACK/RECEIPT
            val pinned = node.keyring.keys[env.keyId]
            if (pinned == null || !pinned.signPub.contentEquals(env.senderKey)) return Rejected(UNTRUSTED_SERVER)
        }
        return when {
            !verifySignature(node.crypto, env) -> Rejected(BAD_SIGNATURE)
            env.expiresAt <= now -> Rejected(EXPIRED)
            env.createdAt > now + config.clockSkewS -> Rejected(FUTURE_DATED)
            env.expiresAt - env.createdAt > env.priority.ttlS -> Rejected(TTL_TOO_LONG)
            env.hops + 1 > config.maxHops -> Rejected(HOP_LIMIT)
            node.store.get(env.msgId) != null -> Rejected(DUPLICATE)
            node.store.isTombstoned(env.msgId) -> Rejected(TOMBSTONED)
            node.rateLimited(env, now) -> Rejected(RATE_LIMITED)
            !StorePolicy.hasRoom(node.store, env, now, config) -> Rejected(STORE_FULL)
            else -> Accepted(env)
        }
    }
}
