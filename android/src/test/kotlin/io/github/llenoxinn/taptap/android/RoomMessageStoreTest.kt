package io.github.llenoxinn.taptap.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.llenoxinn.taptap.android.crypto.PinnedServerKeys
import io.github.llenoxinn.taptap.android.db.RoomMessageStore
import io.github.llenoxinn.taptap.android.db.TaptapDatabase
import io.github.llenoxinn.taptap.android.util.hexToBytes
import io.github.llenoxinn.taptap.crypto.SodiumJvmCrypto
import io.github.llenoxinn.taptap.mesh.Clock
import io.github.llenoxinn.taptap.mesh.Location
import io.github.llenoxinn.taptap.mesh.Node
import io.github.llenoxinn.taptap.mesh.Priority
import io.github.llenoxinn.taptap.mesh.ServerCodec
import io.github.llenoxinn.taptap.mesh.ServerSecret
import io.github.llenoxinn.taptap.mesh.SessionState
import io.github.llenoxinn.taptap.mesh.SosRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
class RoomMessageStoreTest {
    private lateinit var dbA: TaptapDatabase
    private lateinit var dbB: TaptapDatabase
    private val crypto = SodiumJvmCrypto()

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        dbA = Room.inMemoryDatabaseBuilder(ctx, TaptapDatabase::class.java).allowMainThreadQueries().build()
        dbB = Room.inMemoryDatabaseBuilder(ctx, TaptapDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        dbA.close()
        dbB.close()
    }

    @Test
    fun syncPersistsSosAcrossDbReopenAndClearsOnReceipt() {
        val server = ServerCodec(
            crypto = crypto,
            secrets = mapOf(
                1 to ServerSecret(
                    sign = crypto.signKeypair("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f".hexToBytes()),
                    box = crypto.boxKeypair("404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f".hexToBytes()),
                ),
            ),
            currentKeyId = 1,
            random = Random(99),
        )
        var now = 1_800_000_000L
        val clock = object : Clock {
            override fun now(): Long = now
        }
        val storeA = RoomMessageStore(dbA)
        val storeB = RoomMessageStore(dbB)

        val nodeA = Node(
            crypto = crypto,
            keys = crypto.signKeypair(ByteArray(32) { 1 }),
            keyring = PinnedServerKeys.keyring(),
            clock = clock,
            random = Random(1),
            store = storeA,
        )
        val nodeB = Node(
            crypto = crypto,
            keys = crypto.signKeypair(ByteArray(32) { 2 }),
            keyring = PinnedServerKeys.keyring(),
            clock = clock,
            random = Random(2),
            store = storeB,
        )

        val sos = nodeA.sendSos(
            SosRequest(
                priority = Priority.CRITICAL,
                lat = 103157000,
                lon = 1238854000,
                accuracyM = 40,
                fixAgeS = 5,
                people = 4,
                injured = 1,
                flags = 1,
                note = "Roof",
            ),
        )
        val update = nodeA.sendUpdate(sos, Location(103157010, 123885410, 10, 1))

        val sessionA = nodeA.session()
        val sessionB = nodeB.session()
        val qA = ArrayDeque(sessionA.start())
        val qB = ArrayDeque(sessionB.start())
        while (qA.isNotEmpty() || qB.isNotEmpty()) {
            qA.removeFirstOrNull()?.let { qB.addAll(sessionB.onFrame(it)) }
            qB.removeFirstOrNull()?.let { qA.addAll(sessionA.onFrame(it)) }
        }
        assertEquals(SessionState.DONE, sessionA.state)
        assertEquals(SessionState.DONE, sessionB.state)

        // Fresh RoomMessageStore & Node over the same SQLite DB B must still hold SOS + UPDATE
        val reopenedB = RoomMessageStore(dbB)
        val storedSos = reopenedB.get(sos.msgId)
        assertNotNull(storedSos)
        assertEquals(1, storedSos!!.hops)
        assertNotNull(reopenedB.get(update.msgId))
        assertTrue(sos.msgId.prefix in reopenedB.summary())

        // Server receipt clears SOS + UPDATE on B and tombstones the SOS
        val receipt = server.receipt(storedSos, now + 60)
        nodeB.receive(receipt.bytes)
        assertNull(reopenedB.get(sos.msgId))
        assertNull(reopenedB.get(update.msgId))
        assertTrue(reopenedB.isTombstoned(sos.msgId))
        assertNotNull(reopenedB.get(receipt.msgId))

        // Expiry purge removes expired tombstones and envelopes
        now = sos.expiresAt + 10
        reopenedB.purgeExpired(now)
        assertFalse(reopenedB.isTombstoned(sos.msgId))
        assertTrue(reopenedB.all().isEmpty())
    }
}
