package io.github.llenoxinn.taptap.android.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        EnvelopeEntity::class,
        TombstoneEntity::class,
        OutboxEntity::class,
        OutboxPeerEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class TaptapDatabase : RoomDatabase() {
    abstract fun envelopeDao(): EnvelopeDao
    abstract fun tombstoneDao(): TombstoneDao
    abstract fun outboxDao(): OutboxDao

    companion object {
        fun open(context: Context): TaptapDatabase =
            Room.databaseBuilder(context.applicationContext, TaptapDatabase::class.java, "taptap.db")
                .build()
    }
}
