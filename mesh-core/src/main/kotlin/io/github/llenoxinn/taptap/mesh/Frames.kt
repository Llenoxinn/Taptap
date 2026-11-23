package io.github.llenoxinn.taptap.mesh

/** Sync session wire frames: `[type u8][length u16 LE][payload]` (M1 spec §8.1). */
public sealed interface Frame {
    public data class Hello(public val protocolVersion: Int, public val nodeToken: Long, public val criticalOnly: Boolean) : Frame

    /** Envelope + tombstone prefixes; `more` = another SUMMARY frame follows. */
    public data class Summary(public val more: Boolean, public val prefixes: List<IdPrefix>) : Frame

    public class EnvelopeFrame(public val bytes: ByteArray) : Frame

    public data object Done : Frame
}

public object FrameCodec {
    public const val PROTOCOL_VERSION: Int = 1

    /** Prefixes per SUMMARY frame: 3 + 2,000 × 8 = 16,003 B, under Nearby's 32 KB payload limit. */
    public const val MAX_SUMMARY: Int = 2000

    private const val HELLO = 1
    private const val SUMMARY = 2
    private const val ENVELOPE = 3
    private const val DONE = 4

    public fun encode(frame: Frame): ByteArray {
        val (type, payload) = when (frame) {
            is Frame.Hello -> HELLO to le(ByteArray(10))
                .put(frame.protocolVersion.toByte())
                .putLong(frame.nodeToken)
                .put(if (frame.criticalOnly) 1 else 0)
                .array()
            is Frame.Summary -> {
                require(frame.prefixes.size <= MAX_SUMMARY) { "too many prefixes for one SUMMARY frame" }
                val buf = le(ByteArray(3 + 8 * frame.prefixes.size))
                    .put(if (frame.more) 1 else 0)
                    .putShort(frame.prefixes.size.toShort())
                frame.prefixes.forEach { buf.putLong(it.value) }
                SUMMARY to buf.array()
            }
            is Frame.EnvelopeFrame -> ENVELOPE to frame.bytes
            Frame.Done -> DONE to ByteArray(0)
        }
        return le(ByteArray(3 + payload.size)).put(type.toByte()).putShort(payload.size.toShort()).put(payload).array()
    }

    /** Null for anything malformed; the session then ends as FAILED. */
    public fun decode(bytes: ByteArray): Frame? {
        if (bytes.size < 3) return null
        val b = le(bytes)
        val type = b.get().u8
        val len = b.short.toInt() and 0xFFFF
        if (len != bytes.size - 3) return null
        return when (type) {
            HELLO -> if (len != 10) null else Frame.Hello(b.get().u8, b.long, (b.get().u8 and 1) == 1)
            SUMMARY -> {
                if (len < 3) return null
                val more = b.get().u8
                val count = b.short.toInt() and 0xFFFF
                if (more > 1 || count > MAX_SUMMARY || len != 3 + 8 * count) null
                else Frame.Summary(more == 1, List(count) { IdPrefix(b.long) })
            }
            ENVELOPE -> Frame.EnvelopeFrame(bytes.copyOfRange(3, bytes.size))
            DONE -> if (len == 0) Frame.Done else null
            else -> null
        }
    }
}
