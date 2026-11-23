package io.github.llenoxinn.taptap.mesh

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 128-bit message id. A class, not a value class, so equality compares the bytes. */
public class MsgId(public val bytes: ByteArray) {
    init {
        require(bytes.size == SIZE) { "MsgId must be $SIZE bytes, got ${bytes.size}" }
    }

    /** First 8 bytes, little-endian: what SUMMARY frames carry. */
    public val prefix: IdPrefix get() = IdPrefix(le(bytes).long)

    override fun equals(other: Any?): Boolean = other is MsgId && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
    override fun toString(): String = bytes.toHex()

    public companion object {
        public const val SIZE: Int = 16
    }
}

@JvmInline
public value class IdPrefix(public val value: Long)

public enum class Kind {
    SOS, UPDATE, ACK, RECEIPT;

    /** ACK and RECEIPT are signed by the server, not by a device. */
    public val fromServer: Boolean get() = this == ACK || this == RECEIPT
}

public enum class Priority(public val ttlS: Long) {
    CRITICAL(72 * 3600L), URGENT(48 * 3600L), INFO(24 * 3600L)
}

public enum class AckStatus { ACKNOWLEDGED, RESOLVED, FALSE }

public data class SosRequest(
    public val priority: Priority,
    public val lat: Int?,
    public val lon: Int?,
    public val accuracyM: Int,
    public val fixAgeS: Int,
    public val people: Int,
    public val injured: Int,
    public val flags: Int,
    public val note: String,
)

/** A location fix, as carried (sealed) in an UPDATE. `lat`/`lon` are degrees × 1e7; null = no fix. */
public data class Location(
    public val lat: Int?,
    public val lon: Int?,
    public val accuracyM: Int,
    public val fixAgeS: Int,
)

public interface Clock {
    /** Unix seconds. */
    public fun now(): Long
}

public data class MeshConfig(
    public val storeCapacity: Int = 2000,
    public val tombstoneCapacity: Int = 4000,
    public val maxHops: Int = 16,
    public val stallTimeoutS: Long = 10,
    public val sessionCapS: Long = 120,
    public val peerCooldownS: Long = 120,
    public val clockSkewS: Long = 600,
    public val sosPerHour: Int = 5,
    public val updatesPerHour: Int = 10,
)

public enum class RejectReason {
    SIZE, UNKNOWN_FORMAT, MALFORMED, BAD_SIGNATURE, UNTRUSTED_SERVER,
    EXPIRED, FUTURE_DATED, TTL_TOO_LONG, HOP_LIMIT, DUPLICATE, TOMBSTONED, RATE_LIMITED, STORE_FULL,
}

public sealed interface ReceiveOutcome {
    public data class Accepted(public val envelope: Envelope) : ReceiveOutcome
    public data class Rejected(public val reason: RejectReason) : ReceiveOutcome
}

internal fun le(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

internal val Byte.u8: Int get() = toInt() and 0xFF

// Not java.util.HexFormat: it needs Android API 34, and mesh-core must run on minSdk 26.
internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Unsigned lexicographic order (java.util.Arrays.compareUnsigned needs Android API 33). */
internal fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
    for (i in 0 until minOf(a.size, b.size)) {
        val c = a[i].u8 - b[i].u8
        if (c != 0) return c
    }
    return a.size - b.size
}
