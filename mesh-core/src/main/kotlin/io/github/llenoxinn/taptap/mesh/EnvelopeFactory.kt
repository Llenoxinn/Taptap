package io.github.llenoxinn.taptap.mesh

import kotlin.random.Random

/** Builds signed, sealed SOS and UPDATE envelopes for one device. */
public class EnvelopeFactory(
    private val crypto: Crypto,
    private val keys: SignKeypair,
    private val keyring: ServerKeyring,
    private val clock: Clock,
    /** Source of msgIds: `SecureRandom().asKotlinRandom()` in the app, seeded in tests. */
    private val random: Random,
) {
    public fun sos(req: SosRequest): Envelope {
        require(req.people in 1..255 && req.injured in 0..req.people) { "people 1–255, injured 0–people" }
        val now = clock.now()
        val body = crypto.seal(encodeSosPayload(req), keyring.current.boxPub)
        return signedEnvelope(
            crypto, keys.secretKey, Kind.SOS, newId(), req.priority, now, now + req.priority.ttlS,
            keys.publicKey, keyring.currentKeyId, body,
        )
    }

    /** A better location for [parent]; same priority and expiry as the parent. */
    public fun update(parent: Envelope, location: Location): Envelope {
        require(parent.kind == Kind.SOS) { "UPDATE must refer to an SOS" }
        val body = parent.msgId.bytes + crypto.seal(encodeLocation(location), keyring.current.boxPub)
        return signedEnvelope(
            crypto, keys.secretKey, Kind.UPDATE, newId(), parent.priority, clock.now(), parent.expiresAt,
            keys.publicKey, keyring.currentKeyId, body,
        )
    }

    private fun newId(): MsgId = MsgId(random.nextBytes(MsgId.SIZE))
}
