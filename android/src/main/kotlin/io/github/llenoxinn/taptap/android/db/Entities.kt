package io.github.llenoxinn.taptap.android.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.llenoxinn.taptap.mesh.AckStatus

@Entity(tableName = "envelopes")
data class EnvelopeEntity(
    @PrimaryKey val msgIdHex: String,
    val prefix: Long,
    val kind: Int,
    val priority: Int,
    val createdAt: Long,
    val expiresAt: Long,
    val hops: Int,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EnvelopeEntity) return false
        return msgIdHex == other.msgIdHex &&
            prefix == other.prefix &&
            kind == other.kind &&
            priority == other.priority &&
            createdAt == other.createdAt &&
            expiresAt == other.expiresAt &&
            hops == other.hops &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = msgIdHex.hashCode()
        result = 31 * result + prefix.hashCode()
        result = 31 * result + kind
        result = 31 * result + priority
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + expiresAt.hashCode()
        result = 31 * result + hops
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

@Entity(tableName = "tombstones")
data class TombstoneEntity(
    @PrimaryKey val msgIdHex: String,
    val prefix: Long,
    val until: Long,
)

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val msgIdHex: String,
    val priority: Int,
    val people: Int,
    val injured: Int,
    val flags: Int,
    val note: String,
    val lat: Int?,
    val lon: Int?,
    val accuracyM: Int,
    val bestAccuracyM: Int?,
    val createdAt: Long,
    val expiresAt: Long,
    val envelopeBytes: ByteArray,
    val passedCount: Int = 0,
    val passedAt: Long? = null,
    val receiptAt: Long? = null,
    val ackAt: Long? = null,
    val ackStatus: Int? = null,
) {
    val isTest: Boolean get() = (flags and TEST_FLAG) != 0
    val isDelivered: Boolean get() = receiptAt != null || ackAt != null
    val isAcknowledged: Boolean get() = ackAt != null && ackStatus == AckStatus.ACKNOWLEDGED.ordinal

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OutboxEntity) return false
        return msgIdHex == other.msgIdHex &&
            priority == other.priority &&
            people == other.people &&
            injured == other.injured &&
            flags == other.flags &&
            note == other.note &&
            lat == other.lat &&
            lon == other.lon &&
            accuracyM == other.accuracyM &&
            bestAccuracyM == other.bestAccuracyM &&
            createdAt == other.createdAt &&
            expiresAt == other.expiresAt &&
            envelopeBytes.contentEquals(other.envelopeBytes) &&
            passedCount == other.passedCount &&
            passedAt == other.passedAt &&
            receiptAt == other.receiptAt &&
            ackAt == other.ackAt &&
            ackStatus == other.ackStatus
    }

    override fun hashCode(): Int {
        var result = msgIdHex.hashCode()
        result = 31 * result + priority
        result = 31 * result + people
        result = 31 * result + injured
        result = 31 * result + flags
        result = 31 * result + note.hashCode()
        result = 31 * result + (lat ?: 0)
        result = 31 * result + (lon ?: 0)
        result = 31 * result + accuracyM
        result = 31 * result + (bestAccuracyM ?: -1)
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + expiresAt.hashCode()
        result = 31 * result + envelopeBytes.contentHashCode()
        result = 31 * result + passedCount
        result = 31 * result + (passedAt?.hashCode() ?: 0)
        result = 31 * result + (receiptAt?.hashCode() ?: 0)
        result = 31 * result + (ackAt?.hashCode() ?: 0)
        result = 31 * result + (ackStatus ?: -1)
        return result
    }

    companion object {
        const val FLAG_FLOODING = 1 shl 0
        const val FLAG_ELDERLY = 1 shl 1
        const val FLAG_CHILD = 1 shl 2
        const val FLAG_PREGNANT = 1 shl 3
        const val FLAG_MEDICINE = 1 shl 4
        const val TEST_FLAG = 1 shl 15
    }
}

@Entity(tableName = "outbox_peers", primaryKeys = ["msgIdHex", "peerToken"])
data class OutboxPeerEntity(
    val msgIdHex: String,
    val peerToken: Long,
)
