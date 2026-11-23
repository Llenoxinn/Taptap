package io.github.llenoxinn.taptap.mesh

/** A decoded envelope. Only [EnvelopeCodec] builds these, so fields always match [bytes]. */
public class Envelope internal constructor(
    public val kind: Kind,
    public val msgId: MsgId,
    public val priority: Priority,
    public val createdAt: Long,
    public val expiresAt: Long,
    public val hops: Int,
    public val senderKey: ByteArray,
    public val keyId: Int,
    public val body: ByteArray,
    public val sig: ByteArray,
    /** The encoded envelope, exactly as it goes on the wire. */
    public val bytes: ByteArray,
) {
    /** Parent id of an UPDATE / ACK / RECEIPT (first 16 body bytes, cleartext); null for SOS. */
    public val refMsgId: MsgId? get() = if (kind == Kind.SOS) null else MsgId(body.copyOfRange(0, MsgId.SIZE))

    /** Same envelope with a new hop count; `hops` is not signed, so the signature stays valid. */
    public fun withHops(newHops: Int): Envelope {
        val copy = bytes.copyOf()
        copy[EnvelopeCodec.HOPS_OFFSET] = newHops.toByte()
        return Envelope(kind, msgId, priority, createdAt, expiresAt, newHops, senderKey, keyId, body, sig, copy)
    }
}

/** Binary envelope format v1 (docs/milestones/M1-mesh-core.md §5). Little-endian. */
public object EnvelopeCodec {
    public const val VERSION: Int = 1
    public const val HEADER_SIZE: Int = 61
    public const val SIG_SIZE: Int = 64
    public const val OVERHEAD: Int = HEADER_SIZE + SIG_SIZE

    /** Exclusive upper bound on an encoded envelope (AGENTS.md P3). */
    public const val MAX_SIZE: Int = 300
    public const val HOPS_OFFSET: Int = 27
    public const val KEY_SIZE: Int = 32
    public const val SEAL_OVERHEAD: Int = 48
    public const val SOS_FIXED: Int = 16
    public const val MAX_NOTE: Int = 80
    public const val MAX_PAYLOAD: Int = SOS_FIXED + MAX_NOTE
    public const val LOCATION_SIZE: Int = 12
    public const val UPDATE_BODY: Int = MsgId.SIZE + SEAL_OVERHEAD + LOCATION_SIZE
    public const val SERVER_BODY: Int = MsgId.SIZE + 1 + 4

    public fun encode(
        kind: Kind,
        msgId: MsgId,
        priority: Priority,
        createdAt: Long,
        expiresAt: Long,
        hops: Int,
        senderKey: ByteArray,
        keyId: Int,
        body: ByteArray,
        sig: ByteArray = ByteArray(SIG_SIZE),
    ): ByteArray {
        require(senderKey.size == KEY_SIZE && sig.size == SIG_SIZE)
        return le(ByteArray(OVERHEAD + body.size))
            .put(VERSION.toByte())
            .put(kind.ordinal.toByte())
            .put(msgId.bytes)
            .put(priority.ordinal.toByte())
            .putInt(createdAt.toInt()) // u32: toInt keeps the low 32 bits
            .putInt(expiresAt.toInt())
            .put(hops.toByte())
            .put(senderKey)
            .put(keyId.toByte())
            .put(body)
            .put(sig)
            .array()
    }

    /** Null if [bytes] breaks receive rules 1–3 (size, format, body length). */
    public fun decode(bytes: ByteArray): Envelope? {
        if (formatError(bytes) != null) return null
        val b = le(bytes)
        b.get() // version, checked above
        val kind = Kind.entries[b.get().u8]
        val msgId = MsgId(ByteArray(MsgId.SIZE).also { b.get(it) })
        val priority = Priority.entries[b.get().u8]
        val createdAt = b.int.toLong() and 0xFFFFFFFFL
        val expiresAt = b.int.toLong() and 0xFFFFFFFFL
        val hops = b.get().u8
        val senderKey = ByteArray(KEY_SIZE).also { b.get(it) }
        val keyId = b.get().u8
        val body = ByteArray(bytes.size - OVERHEAD).also { b.get(it) }
        val sig = ByteArray(SIG_SIZE).also { b.get(it) }
        return Envelope(kind, msgId, priority, createdAt, expiresAt, hops, senderKey, keyId, body, sig, bytes.copyOf())
    }

    /** Receive rules 1–3 on raw bytes, or null if they pass. */
    internal fun formatError(bytes: ByteArray): RejectReason? {
        if (bytes.size !in OVERHEAD until MAX_SIZE) return RejectReason.SIZE
        val kind = Kind.entries.getOrNull(bytes[1].u8)
        if (bytes[0].u8 != VERSION || kind == null || bytes[18].u8 >= Priority.entries.size) {
            return RejectReason.UNKNOWN_FORMAT
        }
        val n = bytes.size - OVERHEAD
        val status = if (n > MsgId.SIZE) bytes[HEADER_SIZE + MsgId.SIZE].u8 else -1
        val ok = when (kind) {
            Kind.SOS -> n in (SEAL_OVERHEAD + SOS_FIXED)..(SEAL_OVERHEAD + MAX_PAYLOAD)
            Kind.UPDATE -> n == UPDATE_BODY
            Kind.RECEIPT -> n == SERVER_BODY && status == 0
            Kind.ACK -> n == SERVER_BODY && status in AckStatus.entries.indices
        }
        return if (ok) null else RejectReason.MALFORMED
    }

    /** The signed bytes: everything except `hops` (byte 27) and the signature itself. */
    public fun signedPart(bytes: ByteArray): ByteArray =
        bytes.copyOfRange(0, HOPS_OFFSET) + bytes.copyOfRange(HOPS_OFFSET + 1, bytes.size - SIG_SIZE)
}
