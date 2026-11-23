package io.github.llenoxinn.taptap.mesh

/** libsodium primitives. Implemented outside mesh-core (crypto-jvm, Android) to keep it dependency-free. */
public interface Crypto {
    public fun signKeypair(seed: ByteArray? = null): SignKeypair

    /** Ed25519 detached signature, 64 bytes. */
    public fun sign(message: ByteArray, secretKey: ByteArray): ByteArray
    public fun verify(sig: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean
    public fun boxKeypair(seed: ByteArray? = null): BoxKeypair

    /** `crypto_box_seal`: output is 48 bytes longer than [plaintext]. */
    public fun seal(plaintext: ByteArray, recipientPub: ByteArray): ByteArray

    /** Null if the ciphertext doesn't open with this keypair. */
    public fun openSealed(ciphertext: ByteArray, pub: ByteArray, secret: ByteArray): ByteArray?
}

/** Ed25519: 32-byte public key, 64-byte secret key. */
public class SignKeypair(public val publicKey: ByteArray, public val secretKey: ByteArray)

/** X25519: 32-byte public key, 32-byte secret key. */
public class BoxKeypair(public val publicKey: ByteArray, public val secretKey: ByteArray)

/** One pinned server key pair (public halves only). */
public class ServerKeys(public val signPub: ByteArray, public val boxPub: ByteArray)

/** The server public keys a device trusts, by `keyId`. New SOS are sealed to [currentKeyId]. */
public class ServerKeyring(public val keys: Map<Int, ServerKeys>, public val currentKeyId: Int) {
    init {
        require(currentKeyId in keys) { "currentKeyId $currentKeyId not in keyring" }
    }

    public val current: ServerKeys get() = keys.getValue(currentKeyId)
}

/** Encodes an envelope and signs it with [secretKey] (hops excluded from the signature). */
internal fun signedEnvelope(
    crypto: Crypto,
    secretKey: ByteArray,
    kind: Kind,
    msgId: MsgId,
    priority: Priority,
    createdAt: Long,
    expiresAt: Long,
    senderKey: ByteArray,
    keyId: Int,
    body: ByteArray,
    hops: Int = 0,
): Envelope {
    val bytes = EnvelopeCodec.encode(kind, msgId, priority, createdAt, expiresAt, hops, senderKey, keyId, body)
    crypto.sign(EnvelopeCodec.signedPart(bytes), secretKey).copyInto(bytes, bytes.size - EnvelopeCodec.SIG_SIZE)
    return checkNotNull(EnvelopeCodec.decode(bytes)) { "built an envelope that does not decode" }
}

/** Checks the envelope's signature against its own `senderKey`. */
internal fun verifySignature(crypto: Crypto, env: Envelope): Boolean =
    crypto.verify(env.sig, EnvelopeCodec.signedPart(env.bytes), env.senderKey)
