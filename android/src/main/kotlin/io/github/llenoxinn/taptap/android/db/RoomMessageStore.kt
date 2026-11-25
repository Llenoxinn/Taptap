package io.github.llenoxinn.taptap.android.db

import io.github.llenoxinn.taptap.android.util.hexToBytes
import io.github.llenoxinn.taptap.android.util.toHexString
import io.github.llenoxinn.taptap.mesh.Envelope
import io.github.llenoxinn.taptap.mesh.EnvelopeCodec
import io.github.llenoxinn.taptap.mesh.IdPrefix
import io.github.llenoxinn.taptap.mesh.MessageStore
import io.github.llenoxinn.taptap.mesh.MsgId

/** Room-backed [MessageStore]. Call only from the serialized mesh dispatcher. */
class RoomMessageStore(private val db: TaptapDatabase) : MessageStore {
    private val envelopeDao = db.envelopeDao()
    private val tombstoneDao = db.tombstoneDao()

    override fun get(msgId: MsgId): Envelope? =
        envelopeDao.get(msgId.bytes.toHexString())?.let { EnvelopeCodec.decode(it.bytes) }

    override fun put(env: Envelope) {
        envelopeDao.upsert(
            EnvelopeEntity(
                msgIdHex = env.msgId.bytes.toHexString(),
                prefix = env.msgId.prefix.value,
                kind = env.kind.ordinal,
                priority = env.priority.ordinal,
                createdAt = env.createdAt,
                expiresAt = env.expiresAt,
                hops = env.hops,
                bytes = env.bytes,
            ),
        )
    }

    override fun remove(msgId: MsgId) {
        envelopeDao.delete(msgId.bytes.toHexString())
    }

    override fun all(): List<Envelope> =
        envelopeDao.all().mapNotNull { EnvelopeCodec.decode(it.bytes) }

    override fun addTombstone(msgId: MsgId, until: Long) {
        tombstoneDao.upsert(
            TombstoneEntity(
                msgIdHex = msgId.bytes.toHexString(),
                prefix = msgId.prefix.value,
                until = until,
            ),
        )
    }

    override fun isTombstoned(msgId: MsgId): Boolean =
        tombstoneDao.exists(msgId.bytes.toHexString())

    override fun removeTombstone(msgId: MsgId) {
        tombstoneDao.delete(msgId.bytes.toHexString())
    }

    override fun tombstones(): Map<MsgId, Long> =
        tombstoneDao.all().associate { MsgId(it.msgIdHex.hexToBytes()) to it.until }

    override fun purgeExpired(now: Long) {
        db.runInTransaction {
            envelopeDao.deleteExpired(now)
            tombstoneDao.deleteExpired(now)
        }
    }

    override fun summary(): Set<IdPrefix> {
        val prefixes = HashSet<IdPrefix>()
        envelopeDao.allPrefixes().forEach { prefixes += IdPrefix(it) }
        tombstoneDao.allPrefixes().forEach { prefixes += IdPrefix(it) }
        return prefixes
    }
}
