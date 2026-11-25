package io.github.llenoxinn.taptap.android.mesh

import io.github.llenoxinn.taptap.android.db.EnvelopeEntity
import io.github.llenoxinn.taptap.android.db.OutboxEntity
import io.github.llenoxinn.taptap.android.db.RoomMessageStore
import io.github.llenoxinn.taptap.android.db.TaptapDatabase
import io.github.llenoxinn.taptap.android.util.toHexString
import io.github.llenoxinn.taptap.mesh.AckStatus
import io.github.llenoxinn.taptap.mesh.Clock
import io.github.llenoxinn.taptap.mesh.Crypto
import io.github.llenoxinn.taptap.mesh.Envelope
import io.github.llenoxinn.taptap.mesh.EnvelopeCodec
import io.github.llenoxinn.taptap.mesh.Kind
import io.github.llenoxinn.taptap.mesh.Location
import io.github.llenoxinn.taptap.mesh.MsgId
import io.github.llenoxinn.taptap.mesh.Node
import io.github.llenoxinn.taptap.mesh.ServerKeyring
import io.github.llenoxinn.taptap.mesh.SignKeypair
import io.github.llenoxinn.taptap.mesh.SosRequest
import io.github.llenoxinn.taptap.mesh.SyncSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Single serialized coordinator around [Node] and [TaptapDatabase].
 * Every mesh operation runs on [meshDispatcher] so neither [Node] nor [SyncSession] ever races.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshRuntime(
    private val db: TaptapDatabase,
    crypto: Crypto,
    identityKeys: SignKeypair,
    keyring: ServerKeyring,
    val clock: Clock,
    random: Random,
    private val locationProvider: LocationProvider? = null,
    val meshDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) {
    private val roomStore = RoomMessageStore(db)
    private val outboxDao = db.outboxDao()
    private val envelopeDao = db.envelopeDao()
    private val scope = CoroutineScope(meshDispatcher + SupervisorJob())
    private var locationWatchJob: Job? = null

    val node: Node = Node(
        crypto = crypto,
        keys = identityKeys,
        keyring = keyring,
        clock = clock,
        random = random,
        store = roomStore,
        onAccept = ::onEnvelopeAccepted,
    )

    val carriedCount: Flow<Int> = envelopeDao.observeCount()
    val carriedEnvelopes: Flow<List<EnvelopeEntity>> = envelopeDao.observeAll()
    val outboxEntries: Flow<List<OutboxEntity>> = outboxDao.observeAll()

    fun observeOutbox(msgIdHex: String): Flow<OutboxEntity?> = outboxDao.observeById(msgIdHex)

    suspend fun <T> runSerialized(block: (Node) -> T): T =
        withContext(meshDispatcher) { block(node) }

    suspend fun sendSos(req: SosRequest): OutboxEntity = withContext(meshDispatcher) {
        val env = node.sendSos(req)
        val hex = env.msgId.bytes.toHexString()
        val entry = OutboxEntity(
            msgIdHex = hex,
            priority = req.priority.ordinal,
            people = req.people,
            injured = req.injured,
            flags = req.flags,
            note = req.note,
            lat = req.lat,
            lon = req.lon,
            accuracyM = req.accuracyM,
            bestAccuracyM = if (req.lat != null && req.lon != null) req.accuracyM else null,
            createdAt = env.createdAt,
            expiresAt = env.expiresAt,
            envelopeBytes = env.bytes,
        )
        outboxDao.insert(entry)
        startLocationWatch()
        entry
    }

    /** Sends an UPDATE envelope if [fix] improves location accuracy by > 50% (or supplies first fix). */
    suspend fun maybeSendLocationUpdate(msgIdHex: String, fix: Location): Boolean =
        withContext(meshDispatcher) {
            val outbox = outboxDao.get(msgIdHex) ?: return@withContext false
            if (!LocationUpdatePolicy.shouldSendUpdate(outbox.bestAccuracyM, fix)) return@withContext false
            val parent = EnvelopeCodec.decode(outbox.envelopeBytes) ?: return@withContext false
            node.sendUpdate(parent, fix)
            val lat = fix.lat ?: return@withContext false
            val lon = fix.lon ?: return@withContext false
            outboxDao.updateLocation(msgIdHex, lat, lon, fix.accuracyM)
            true
        }

    fun recordSentToPeer(msgId: MsgId, peerToken: Long) {
        outboxDao.recordPass(msgId.bytes.toHexString(), peerToken, clock.now())
    }

    /**
     * Rule P4: ONLY called by [Node.receive] AFTER [Validator] checked the pinned server signature.
     * Nothing else in the app may mark Delivered (`receiptAt`) or Acknowledged (`ackAt`).
     */
    private fun onEnvelopeAccepted(env: Envelope) {
        val refHex = env.refMsgId?.bytes?.toHexString() ?: return
        when (env.kind) {
            Kind.RECEIPT -> outboxDao.markReceipt(refHex, env.createdAt)
            Kind.ACK -> {
                val rawStatus = env.body[MsgId.SIZE].toInt() and 0xFF
                if (rawStatus in AckStatus.entries.indices) {
                    outboxDao.markAck(refHex, env.createdAt, rawStatus)
                }
            }
            Kind.SOS, Kind.UPDATE -> Unit
        }
    }

    private fun startLocationWatch() {
        val provider = locationProvider ?: return
        if (locationWatchJob?.isActive == true) return
        locationWatchJob = scope.launch {
            provider.watchFixes { clock.now() }.collect { fix ->
                val active = outboxDao.activeNeedingLocation(clock.now())
                if (active.isEmpty()) {
                    locationWatchJob?.cancel()
                    return@collect
                }
                active.forEach { entry ->
                    if (LocationUpdatePolicy.shouldSendUpdate(entry.bestAccuracyM, fix)) {
                        EnvelopeCodec.decode(entry.envelopeBytes)?.let { parent ->
                            node.sendUpdate(parent, fix)
                            val lat = fix.lat
                            val lon = fix.lon
                            if (lat != null && lon != null) {
                                outboxDao.updateLocation(entry.msgIdHex, lat, lon, fix.accuracyM)
                            }
                        }
                    }
                }
            }
        }
    }
}
