package io.github.llenoxinn.taptap.mesh

import kotlin.random.Random

/** One server key pair, secret halves included. Never given to a [Node]. */
public class ServerSecret(public val sign: SignKeypair, public val box: BoxKeypair)

/**
 * The server side of the envelope format: opens sealed bodies and issues signed RECEIPT/ACK.
 * Used by the simulator's fake server now; the M3 Deno function mirrors it.
 */
public class ServerCodec(
    private val crypto: Crypto,
    private val secrets: Map<Int, ServerSecret>,
    private val currentKeyId: Int,
    private val random: Random,
) {
    init {
        require(currentKeyId in secrets) { "currentKeyId $currentKeyId not in secrets" }
    }

    /** The public keyring devices pin. */
    public val keyring: ServerKeyring =
        ServerKeyring(secrets.mapValues { (_, s) -> ServerKeys(s.sign.publicKey, s.box.publicKey) }, currentKeyId)

    public fun verify(env: Envelope): Boolean = verifySignature(crypto, env)

    /** Opens an SOS body; null if it doesn't open or its contents are invalid (people ≥ 1, injured ≤ people). */
    public fun openSos(env: Envelope): SosRequest? {
        if (env.kind != Kind.SOS) return null
        val plain = open(env.keyId, env.body) ?: return null
        return decodeSosPayload(env.priority, plain)?.takeIf { it.people >= 1 && it.injured <= it.people }
    }

    public fun openUpdate(env: Envelope): Location? {
        if (env.kind != Kind.UPDATE) return null
        return open(env.keyId, env.body.copyOfRange(MsgId.SIZE, env.body.size))?.let(::decodeLocation)
    }

    public fun receipt(ref: Envelope, now: Long): Envelope = reply(ref, Kind.RECEIPT, 0, now)

    public fun ack(ref: Envelope, status: AckStatus, now: Long): Envelope = reply(ref, Kind.ACK, status.ordinal, now)

    private fun open(keyId: Int, sealed: ByteArray): ByteArray? {
        val box = secrets[keyId]?.box ?: return null
        return crypto.openSealed(sealed, box.publicKey, box.secretKey)
    }

    private fun reply(ref: Envelope, kind: Kind, status: Int, now: Long): Envelope {
        val sign = secrets.getValue(currentKeyId).sign
        val body = le(ByteArray(EnvelopeCodec.SERVER_BODY)).put(ref.msgId.bytes).put(status.toByte()).putInt(now.toInt()).array()
        return signedEnvelope(
            crypto, sign.secretKey, kind, MsgId(random.nextBytes(MsgId.SIZE)), ref.priority, now, ref.expiresAt,
            sign.publicKey, currentKeyId, body,
        )
    }
}
