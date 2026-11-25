package io.github.llenoxinn.taptap.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.llenoxinn.taptap.android.crypto.PinnedServerKeys
import io.github.llenoxinn.taptap.android.db.TaptapDatabase
import io.github.llenoxinn.taptap.android.mesh.MeshRuntime
import io.github.llenoxinn.taptap.android.ui.SosDraft
import io.github.llenoxinn.taptap.android.util.hexToBytes
import io.github.llenoxinn.taptap.crypto.SodiumJvmCrypto
import io.github.llenoxinn.taptap.mesh.AckStatus
import io.github.llenoxinn.taptap.mesh.Clock
import io.github.llenoxinn.taptap.mesh.Priority
import io.github.llenoxinn.taptap.mesh.ReceiveOutcome
import io.github.llenoxinn.taptap.mesh.RejectReason
import io.github.llenoxinn.taptap.mesh.ServerCodec
import io.github.llenoxinn.taptap.mesh.ServerSecret
import io.github.llenoxinn.taptap.mesh.SosRequest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
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
class OutboxStatusTest {
    private lateinit var db: TaptapDatabase
    private val crypto = SodiumJvmCrypto()

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, TaptapDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun outboxNeverShowsDeliveredOrAckWithoutVerifiedServerEnvelope() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var now = 1_800_000_000L
        val runtime = MeshRuntime(
            db = db,
            crypto = crypto,
            identityKeys = crypto.signKeypair(ByteArray(32) { 7 }),
            keyring = PinnedServerKeys.keyring(),
            clock = object : Clock {
                override fun now(): Long = now
            },
            random = Random(7),
            meshDispatcher = dispatcher,
        )

        val outbox = runtime.sendSos(
            SosRequest(Priority.CRITICAL, null, null, 0, 0, 2, 1, 0, "Help"),
        )
        assertEquals(0, outbox.passedCount)
        assertFalse(outbox.isDelivered)
        assertFalse(outbox.isAcknowledged)

        val sentEnv = runtime.runSerialized { it.store.all().single() }

        // Passing to two unique peers increments passedCount; repeating a peer does not.
        runtime.runSerialized {
            runtime.recordSentToPeer(sentEnv.msgId, 111L)
            runtime.recordSentToPeer(sentEnv.msgId, 111L)
            runtime.recordSentToPeer(sentEnv.msgId, 222L)
        }
        val afterPass = db.outboxDao().get(outbox.msgIdHex)!!
        assertEquals(2, afterPass.passedCount)
        assertFalse(afterPass.isDelivered)
        assertFalse(afterPass.isAcknowledged)

        // Rogue / untrusted server ACK must be rejected and leave Delivered/Acknowledged false (Rule P4).
        val rogueServer = ServerCodec(
            crypto = crypto,
            secrets = mapOf(
                1 to ServerSecret(
                    sign = crypto.signKeypair(ByteArray(32) { 99 }),
                    box = crypto.boxKeypair(ByteArray(32) { 98 }),
                ),
            ),
            currentKeyId = 1,
            random = Random(8),
        )
        val fakeAck = rogueServer.ack(sentEnv, AckStatus.ACKNOWLEDGED, now + 10)
        val rejected = runtime.runSerialized { it.receive(fakeAck.bytes) }
        assertEquals(ReceiveOutcome.Rejected(RejectReason.UNTRUSTED_SERVER), rejected)

        val afterFake = db.outboxDao().get(outbox.msgIdHex)!!
        assertNull(afterFake.receiptAt)
        assertNull(afterFake.ackAt)
        assertFalse(afterFake.isDelivered)
        assertFalse(afterFake.isAcknowledged)

        // Valid pinned-key server RECEIPT sets Delivered only.
        val realServer = ServerCodec(
            crypto = crypto,
            secrets = mapOf(
                1 to ServerSecret(
                    sign = crypto.signKeypair("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f".hexToBytes()),
                    box = crypto.boxKeypair("404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f".hexToBytes()),
                ),
            ),
            currentKeyId = 1,
            random = Random(9),
        )
        val realReceipt = realServer.receipt(sentEnv, now + 30)
        assertTrue(runtime.runSerialized { it.receive(realReceipt.bytes) } is ReceiveOutcome.Accepted)
        val afterReceipt = db.outboxDao().get(outbox.msgIdHex)!!
        assertNotNull(afterReceipt.receiptAt)
        assertTrue(afterReceipt.isDelivered)
        assertFalse(afterReceipt.isAcknowledged)

        // Valid pinned-key server ACK sets Acknowledged.
        val realAck = realServer.ack(sentEnv, AckStatus.ACKNOWLEDGED, now + 90)
        assertTrue(runtime.runSerialized { it.receive(realAck.bytes) } is ReceiveOutcome.Accepted)
        val afterAck = db.outboxDao().get(outbox.msgIdHex)!!
        assertTrue(afterAck.isAcknowledged)
    }

    @Test
    fun sosDraftClampsPeopleAndInjuredBounds() {
        val raised = SosDraft(people = 1, injured = 0).withInjuredSelection(3)
        assertEquals(3, raised.people)
        assertEquals(3, raised.injured)

        val lowered = raised.withPeople(1)
        assertEquals(1, lowered.people)
        assertEquals(1, lowered.injured)
    }
}
