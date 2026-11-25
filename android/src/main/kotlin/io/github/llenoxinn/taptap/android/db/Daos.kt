package io.github.llenoxinn.taptap.android.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface EnvelopeDao {
    @Query("SELECT * FROM envelopes WHERE msgIdHex = :msgIdHex")
    fun get(msgIdHex: String): EnvelopeEntity?

    @Query("SELECT * FROM envelopes ORDER BY priority ASC, createdAt ASC")
    fun all(): List<EnvelopeEntity>

    @Query("SELECT * FROM envelopes ORDER BY priority ASC, createdAt DESC")
    fun observeAll(): Flow<List<EnvelopeEntity>>

    @Query("SELECT COUNT(*) FROM envelopes")
    fun observeCount(): Flow<Int>

    @Query("SELECT prefix FROM envelopes")
    fun allPrefixes(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: EnvelopeEntity)

    @Query("DELETE FROM envelopes WHERE msgIdHex = :msgIdHex")
    fun delete(msgIdHex: String)

    @Query("DELETE FROM envelopes WHERE expiresAt <= :now")
    fun deleteExpired(now: Long)
}

@Dao
interface TombstoneDao {
    @Query("SELECT EXISTS(SELECT 1 FROM tombstones WHERE msgIdHex = :msgIdHex)")
    fun exists(msgIdHex: String): Boolean

    @Query("SELECT * FROM tombstones")
    fun all(): List<TombstoneEntity>

    @Query("SELECT prefix FROM tombstones")
    fun allPrefixes(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: TombstoneEntity)

    @Query("DELETE FROM tombstones WHERE msgIdHex = :msgIdHex")
    fun delete(msgIdHex: String)

    @Query("DELETE FROM tombstones WHERE until <= :now")
    fun deleteExpired(now: Long)
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(entity: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE msgIdHex = :msgIdHex")
    fun get(msgIdHex: String): OutboxEntity?

    @Query("SELECT * FROM outbox ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<OutboxEntity>>

    @Query("SELECT * FROM outbox WHERE msgIdHex = :msgIdHex")
    fun observeById(msgIdHex: String): Flow<OutboxEntity?>

    @Query("SELECT * FROM outbox WHERE expiresAt > :now AND ackAt IS NULL")
    fun activeNeedingLocation(now: Long): List<OutboxEntity>

    @Query("UPDATE outbox SET lat = :lat, lon = :lon, accuracyM = :bestAccuracyM, bestAccuracyM = :bestAccuracyM WHERE msgIdHex = :msgIdHex")
    fun updateLocation(msgIdHex: String, lat: Int, lon: Int, bestAccuracyM: Int)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertPeer(peer: OutboxPeerEntity): Long

    @Query("UPDATE outbox SET passedCount = passedCount + 1, passedAt = COALESCE(passedAt, :now) WHERE msgIdHex = :msgIdHex")
    fun incrementPassed(msgIdHex: String, now: Long)

    @Transaction
    fun recordPass(msgIdHex: String, peerToken: Long, now: Long) {
        if (get(msgIdHex) != null && insertPeer(OutboxPeerEntity(msgIdHex, peerToken)) != -1L) {
            incrementPassed(msgIdHex, now)
        }
    }

    @Query("UPDATE outbox SET receiptAt = COALESCE(receiptAt, :at) WHERE msgIdHex = :msgIdHex")
    fun markReceipt(msgIdHex: String, at: Long)

    @Query("UPDATE outbox SET ackAt = :at, ackStatus = :status WHERE msgIdHex = :msgIdHex")
    fun markAck(msgIdHex: String, at: Long, status: Int)
}
