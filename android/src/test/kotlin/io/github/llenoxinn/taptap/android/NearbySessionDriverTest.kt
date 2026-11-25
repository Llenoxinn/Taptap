package io.github.llenoxinn.taptap.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.llenoxinn.taptap.android.crypto.PinnedServerKeys
import io.github.llenoxinn.taptap.android.db.TaptapDatabase
import io.github.llenoxinn.taptap.android.mesh.MeshRuntime
import io.github.llenoxinn.taptap.android.nearby.NearbyCallbacks
import io.github.llenoxinn.taptap.android.nearby.NearbyClient
import io.github.llenoxinn.taptap.android.nearby.NearbySessionDriver
import io.github.llenoxinn.taptap.crypto.SodiumJvmCrypto
import io.github.llenoxinn.taptap.mesh.Clock
import io.github.llenoxinn.taptap.mesh.Location
import io.github.llenoxinn.taptap.mesh.Priority
import io.github.llenoxinn.taptap.mesh.SosRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NearbySessionDriverTest {
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
    fun twoPhonesExchangeSosOverSimulatedNearbyAndEnforceSingleSessionAndCooldown() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = object : Clock {
            override fun now(): Long = 1_800_000_000L + testScheduler.currentTime / 1000L
        }

        val runtimeA = MeshRuntime(
            db = dbA,
            crypto = crypto,
            identityKeys = crypto.signKeypair(ByteArray(32) { 10 }),
            keyring = PinnedServerKeys.keyring(),
            clock = clock,
            random = Random(10),
            meshDispatcher = dispatcher,
        )
        val runtimeB = MeshRuntime(
            db = dbB,
            crypto = crypto,
            identityKeys = crypto.signKeypair(ByteArray(32) { 20 }),
            keyring = PinnedServerKeys.keyring(),
            clock = clock,
            random = Random(20),
            meshDispatcher = dispatcher,
        )

        val outboxA = runtimeA.sendSos(
            SosRequest(Priority.CRITICAL, 103157000, 1238854000, 100, 3, 2, 1, 0, "Flood"),
        )
        assertTrue(runtimeA.maybeSendLocationUpdate(outboxA.msgIdHex, Location(103157005, 1238854005, 15, 1)))

        val wire = FakeNearbyWire()
        val clientA = wire.endpoint("ep-A")
        val clientB = wire.endpoint("ep-B")
        val scopeA = CoroutineScope(dispatcher + SupervisorJob())
        val scopeB = CoroutineScope(dispatcher + SupervisorJob())

        val driverA = NearbySessionDriver(runtimeA, clientA, scopeA)
        val driverB = NearbySessionDriver(runtimeB, clientB, scopeB)
        try {
            driverA.start()
            driverB.start()
            runCurrent()

            // Mutual discovery: tie-break decides initiator cleanly with no collision.
            val nameA = clientA.advertisedName!!
            val nameB = clientB.advertisedName!!
            clientA.callbacks!!.onEndpointFound("ep-B", nameB)
            clientB.callbacks!!.onEndpointFound("ep-A", nameA)
            advanceTimeBy(2_000)
            runCurrent()

            val storedOnB = runtimeB.runSerialized { it.store.all() }
            assertEquals(2, storedOnB.size) // SOS + >50% UPDATE
            assertTrue(storedOnB.all { it.hops == 1 })
            assertEquals(1, dbA.outboxDao().get(outboxA.msgIdHex)!!.passedCount)

            // Cooldown: rediscovering B within 120s must not trigger a new connection request.
            val requestsBefore = clientA.requestCount
            clientA.callbacks!!.onEndpointFound("ep-B", nameB)
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(requestsBefore, clientA.requestCount)

            // Reject 3rd peer when already busy with a fresh (non-cooldown) peer D.
            clientA.callbacks!!.onConnectionInitiated("ep-D", NearbySessionDriver.formatEndpointName(888L))
            runCurrent()
            clientA.callbacks!!.onConnectionInitiated("ep-C", NearbySessionDriver.formatEndpointName(999L))
            runCurrent()
            assertTrue("ep-C" in clientA.rejected)
        } finally {
            driverA.stop()
            driverB.stop()
            runCurrent()
            scopeA.cancel()
            scopeB.cancel()
        }
    }

    private class FakeNearbyWire {
        private val endpoints = HashMap<String, FakeNearbyClient>()
        private var nextPayloadId = 1L

        fun endpoint(id: String): FakeNearbyClient =
            FakeNearbyClient(id, this).also { endpoints[id] = it }

        fun request(fromId: String, localName: String, targetId: String) {
            val target = endpoints[targetId] ?: return
            target.callbacks?.onConnectionInitiated(fromId, localName)
        }

        fun accept(fromId: String, peerId: String) {
            val from = endpoints[fromId] ?: return
            val peer = endpoints[peerId] ?: return
            peer.acceptedPeers += fromId
            if (fromId in peer.acceptedPeers && peerId in from.acceptedPeers) {
                from.callbacks?.onConnectionResult(peerId, true)
                peer.callbacks?.onConnectionResult(fromId, true)
            } else {
                // Initiator awaits target acceptConnection
                peer.callbacks?.onConnectionInitiated(fromId, from.advertisedName ?: "")
            }
        }

        fun send(fromId: String, targetId: String, bytes: ByteArray): Long {
            val id = nextPayloadId++
            val target = endpoints[targetId]
            val from = endpoints[fromId]
            target?.callbacks?.onBytesReceived(fromId, bytes)
            from?.callbacks?.onPayloadSent(targetId, id, true)
            return id
        }

        fun disconnect(fromId: String, targetId: String) {
            endpoints[targetId]?.callbacks?.onDisconnected(fromId)
        }
    }

    private class FakeNearbyClient(
        private val selfId: String,
        private val wire: FakeNearbyWire,
    ) : NearbyClient {
        var advertisedName: String? = null
        var callbacks: NearbyCallbacks? = null
        val acceptedPeers = HashSet<String>()
        val rejected = HashSet<String>()
        var requestCount = 0

        override fun startAdvertising(endpointName: String, serviceId: String, callbacks: NearbyCallbacks) {
            advertisedName = endpointName
            this.callbacks = callbacks
        }

        override fun stopAdvertising() {}

        override fun startDiscovery(serviceId: String, callbacks: NearbyCallbacks) {
            this.callbacks = callbacks
        }

        override fun stopDiscovery() {}

        override fun requestConnection(localName: String, endpointId: String, callbacks: NearbyCallbacks) {
            requestCount++
            this.callbacks = callbacks
            acceptedPeers += endpointId
            wire.request(selfId, localName, endpointId)
        }

        override fun acceptConnection(endpointId: String, callbacks: NearbyCallbacks) {
            this.callbacks = callbacks
            acceptedPeers += endpointId
            wire.accept(selfId, endpointId)
        }

        override fun rejectConnection(endpointId: String) {
            rejected += endpointId
        }

        override fun sendBytes(endpointId: String, bytes: ByteArray): Long =
            wire.send(selfId, endpointId, bytes)

        override fun disconnect(endpointId: String) {
            acceptedPeers -= endpointId
            wire.disconnect(selfId, endpointId)
        }

        override fun stopAll() {
            acceptedPeers.clear()
        }
    }
}
