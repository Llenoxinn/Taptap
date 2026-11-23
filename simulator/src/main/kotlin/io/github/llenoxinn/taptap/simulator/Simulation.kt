package io.github.llenoxinn.taptap.simulator

import io.github.llenoxinn.taptap.crypto.SodiumJvmCrypto
import io.github.llenoxinn.taptap.mesh.AckStatus
import io.github.llenoxinn.taptap.mesh.Clock
import io.github.llenoxinn.taptap.mesh.Envelope
import io.github.llenoxinn.taptap.mesh.EnvelopeCodec
import io.github.llenoxinn.taptap.mesh.Frame
import io.github.llenoxinn.taptap.mesh.FrameCodec
import io.github.llenoxinn.taptap.mesh.Kind
import io.github.llenoxinn.taptap.mesh.Location
import io.github.llenoxinn.taptap.mesh.MeshConfig
import io.github.llenoxinn.taptap.mesh.MsgId
import io.github.llenoxinn.taptap.mesh.Node
import io.github.llenoxinn.taptap.mesh.Priority
import io.github.llenoxinn.taptap.mesh.RejectReason
import io.github.llenoxinn.taptap.mesh.ServerCodec
import io.github.llenoxinn.taptap.mesh.ServerSecret
import io.github.llenoxinn.taptap.mesh.SessionState
import io.github.llenoxinn.taptap.mesh.SosRequest
import io.github.llenoxinn.taptap.mesh.SyncSession
import io.github.llenoxinn.taptap.mesh.UplinkBatch
import io.github.llenoxinn.taptap.mesh.UplinkResult
import kotlin.math.hypot
import kotlin.random.Random

/** 2027-01-15: any realistic unix time works; envelopes store u32 seconds. */
private const val EPOCH = 1_800_000_000L

// Rough metres → degrees × 1e7 around northern Cebu; only the server ever sees these.
private const val BASE_LAT = 110_000_000
private const val BASE_LON = 1_239_000_000
private const val E7_PER_M = 90

private class SimClock(var now: Long = EPOCH) : Clock {
    override fun now(): Long = now
}

private class Phone(val index: Int, val vehicle: Boolean, var x: Double, var y: Double) {
    lateinit var node: Node
    var tx = x
    var ty = y
    var speed = 0.0
    var pauseUntil = 0
    var link: Link? = null
    var lastUplink = Int.MIN_VALUE / 2
}

/** One session between two phones; frames queue in each direction at the radio's rate. */
private class Link(val a: Phone, val b: Phone, val sa: SyncSession, val sb: SyncSession) {
    val toA = ArrayDeque<Frame>()
    val toB = ArrayDeque<Frame>()
    /** Set if either phone uploaded during the session: its store changed after its SUMMARY was sent. */
    var uplinked = false
}

private class SentSos(val env: Envelope, val origin: Phone)

/** One seeded run (M1 spec §10). Everything random comes from [seed], so a seed always gives the same metrics. */
class Simulation(private val p: Params, private val seed: Long) {
    private val rnd = Random(seed)
    private val crypto = SodiumJvmCrypto()
    private val clock = SimClock()
    private val serverCodec = ServerCodec(
        crypto, mapOf(1 to ServerSecret(crypto.signKeypair(rnd.nextBytes(32)), crypto.boxKeypair(rnd.nextBytes(32)))),
        1, Random(rnd.nextLong()),
    )
    private val server = FakeServer(serverCodec, p, Random(rnd.nextLong()))
    private val zones = List(p.zones) { rnd.inside(p.zoneRadiusM) to rnd.inside(p.zoneRadiusM) }
    private val phones = List(p.nodes) { i -> Phone(i, rnd.nextDouble() < p.vehicleShare, rnd.inside(0.0), rnd.inside(0.0)) }
    private val links = ArrayList<Link>()
    private val events = HashMap<Int, MutableList<() -> Unit>>()
    private val inRangeSince = IntArray(p.nodes * p.nodes) { -1 }
    private val sos = ArrayList<SentSos>()
    private val ackReturnedAt = HashMap<MsgId, Long>()

    private var duplicates = 0
    private var overlapDuplicates = 0
    private var envelopesTx = 0L
    private var bytesTx = 0L
    private var started = 0
    private var completed = 0
    private var timedOut = 0
    private var disconnected = 0
    private var failed = 0
    private val rejects = HashMap<RejectReason, Int>()
    private var maxStore = 0

    init {
        for (ph in phones) {
            ph.node = Node(crypto, crypto.signKeypair(rnd.nextBytes(32)), serverCodec.keyring, clock, Random(rnd.nextLong()), MeshConfig()) { env ->
                onAccept(ph, env)
            }
            ph.node.criticalOnly = rnd.nextDouble() < p.criticalOnlyShare
            newWaypoint(ph)
        }
        repeat(p.sosEvents) {
            val at = rnd.nextInt(p.sosWindowS)
            val roll = rnd.nextInt(100)
            val priority = when {
                roll < p.criticalPct -> Priority.CRITICAL
                roll < p.criticalPct + p.urgentPct -> Priority.URGENT
                else -> Priority.INFO
            }
            val updateAfter = if (rnd.nextDouble() < p.updateShare) rnd.nextInt(p.updateMinS, p.updateMaxS + 1) else null
            schedule(at) { fireSos(priority, at, updateAfter) }
        }
    }

    fun run(): RunMetrics {
        for (t in 0 until p.durationS) {
            clock.now = EPOCH + t
            phones.forEach { move(it, t) }
            events.remove(t)?.forEach { it() }
            server.release(clock.now)
            for (ph in phones) {
                if (inZone(ph) && t - ph.lastUplink >= p.uplinkEveryS) {
                    ph.lastUplink = t
                    ph.link?.uplinked = true
                    ph.node.applyUplinkResult(server.upload(ph.node.uplinkBatch(), clock.now))
                }
            }
            links.toList().forEach(::step)
            pair(t)
        }
        links.toList().forEach(::end)
        return metrics()
    }

    private fun schedule(t: Int, action: () -> Unit) {
        events.getOrPut(t) { ArrayList() } += action
    }

    private fun fireSos(priority: Priority, t: Int, updateAfter: Int?) {
        val candidates = phones.filter { !inZone(it) }
        if (candidates.isEmpty()) return
        val origin = candidates[rnd.nextInt(candidates.size)]
        val req = SosRequest(priority, lat(origin), lon(origin), 10, 0, rnd.nextInt(1, 6), 0, 0, "")
        val env = origin.node.sendSos(req)
        sos += SentSos(env, origin)
        if (updateAfter != null) {
            schedule(t + updateAfter) { origin.node.sendUpdate(env, Location(lat(origin), lon(origin), 5, 0)) }
        }
    }

    private fun onAccept(ph: Phone, env: Envelope) {
        maxStore = maxOf(maxStore, ph.node.store.all().size)
        val ref = env.refMsgId ?: return
        if (env.kind == Kind.ACK && ref in ph.node.mySosIds) ackReturnedAt.putIfAbsent(ref, clock.now)
    }

    // --- mobility: random waypoint ---

    private fun move(ph: Phone, t: Int) {
        if (t < ph.pauseUntil) return
        val dx = ph.tx - ph.x
        val dy = ph.ty - ph.y
        val d = hypot(dx, dy)
        if (d <= ph.speed) {
            ph.x = ph.tx
            ph.y = ph.ty
            ph.pauseUntil = t + rnd.nextInt(p.pauseMaxS + 1)
            newWaypoint(ph)
        } else {
            ph.x += dx / d * ph.speed
            ph.y += dy / d * ph.speed
        }
    }

    private fun newWaypoint(ph: Phone) {
        ph.tx = rnd.inside(0.0)
        ph.ty = rnd.inside(0.0)
        ph.speed = if (ph.vehicle) rnd.nextDouble(p.vehicleMinMps, p.vehicleMaxMps) else rnd.nextDouble(p.walkMinMps, p.walkMaxMps)
    }

    private fun Random.inside(margin: Double): Double = margin + nextDouble() * (p.areaM - 2 * margin)

    private fun inZone(ph: Phone): Boolean = zones.any { (zx, zy) -> hypot(ph.x - zx, ph.y - zy) <= p.zoneRadiusM }

    private fun inRange(a: Phone, b: Phone): Boolean {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy <= p.rangeM * p.rangeM
    }

    private fun lat(ph: Phone): Int = BASE_LAT + (ph.y * E7_PER_M).toInt()
    private fun lon(ph: Phone): Int = BASE_LON + (ph.x * E7_PER_M).toInt()

    // --- contacts and sessions ---

    /** Starts a session for each idle pair that has been in range for `setupS` (O(n²); fine up to ~300 nodes). */
    private fun pair(t: Int) {
        val n = phones.size
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val k = i * n + j
                val a = phones[i]
                val b = phones[j]
                if (!inRange(a, b)) {
                    inRangeSince[k] = -1
                    continue
                }
                if (inRangeSince[k] < 0) inRangeSince[k] = t
                if (t - inRangeSince[k] < p.setupS || a.link != null || b.link != null) continue
                // shortcut: the simulator reads the peer token directly; a real phone learns it from HELLO.
                if (a.node.inCooldown(b.node.nodeToken) || b.node.inCooldown(a.node.nodeToken)) continue
                val link = Link(a, b, a.node.session(), b.node.session())
                link.toB += link.sa.start()
                link.toA += link.sb.start()
                a.link = link
                b.link = link
                links += link
                started++
            }
        }
    }

    private fun step(link: Link) {
        if (!inRange(link.a, link.b)) return end(link)
        deliver(link.toB, link.sb, link.toA)
        deliver(link.toA, link.sa, link.toB)
        link.sa.onTick()
        link.sb.onTick()
        val bothDone = link.sa.state == SessionState.DONE && link.sb.state == SessionState.DONE &&
            link.toA.isEmpty() && link.toB.isEmpty()
        val broken = listOf(link.sa, link.sb).any { it.state.ended && it.state != SessionState.DONE }
        if (bothDone || broken) end(link)
    }

    /** Control frames are free; ENVELOPE frames go at `envelopesPerS` per tick (1 s). */
    private fun deliver(queue: ArrayDeque<Frame>, to: SyncSession, replies: ArrayDeque<Frame>) {
        var envelopes = 0
        while (queue.isNotEmpty()) {
            val frame = queue.first()
            if (frame is Frame.EnvelopeFrame) {
                if (envelopes == p.envelopesPerS) break
                envelopes++
                envelopesTx++
            }
            queue.removeFirst()
            val bytes = FrameCodec.encode(frame)
            bytesTx += bytes.size
            replies += to.onBytes(bytes)
        }
    }

    private fun end(link: Link) {
        val sessions = listOf(link.sa, link.sb)
        sessions.forEach { it.onDisconnect() } // no-op for a session that already ended
        for (s in sessions) {
            s.stats.rejected.forEach { (reason, count) -> rejects.merge(reason, count, Int::plus) }
            val d = (s.stats.rejected[RejectReason.DUPLICATE] ?: 0) + (s.stats.rejected[RejectReason.TOMBSTONED] ?: 0)
            if (link.uplinked) overlapDuplicates += d else duplicates += d
        }
        val states = sessions.map { it.state }
        when {
            states.all { it == SessionState.DONE } -> completed++
            states.any { it == SessionState.STALLED || it == SessionState.CAPPED } -> timedOut++
            states.any { it == SessionState.FAILED } -> failed++
            else -> disconnected++
        }
        link.a.link = null
        link.b.link = null
        links.remove(link)
    }

    private fun metrics(): RunMetrics {
        val delivered = sos.filter { it.env.msgId in server.receivedAt }
        return RunMetrics(
            seeds = listOf(seed),
            sent = sos.groupingBy { it.env.priority }.eachCount(),
            delivered = delivered.groupingBy { it.env.priority }.eachCount(),
            latenciesS = delivered.map { server.receivedAt.getValue(it.env.msgId) - it.env.createdAt },
            acksIssued = server.ackIssuedAt.size,
            acksReturned = ackReturnedAt.size,
            ackLatenciesS = ackReturnedAt.map { (ref, at) -> at - server.ackIssuedAt.getValue(ref) },
            duplicates = duplicates,
            uplinkOverlapDuplicates = overlapDuplicates,
            envelopesTx = envelopesTx,
            bytesTx = bytesTx,
            sessionsStarted = started,
            sessionsCompleted = completed,
            sessionsTimedOut = timedOut,
            sessionsDisconnected = disconnected,
            sessionsFailed = failed,
            rejects = rejects.toMap(),
            maxStore = maxStore,
        )
    }
}

/**
 * Stands in for the M3 intake function: verifies and opens uploads, returns a RECEIPT per accepted
 * envelope at once, and an ACK 2–10 min later (a responder's delay) for Critical/Urgent SOS.
 */
private class FakeServer(private val codec: ServerCodec, private val p: Params, private val rnd: Random) {
    /** SOS/UPDATE id → when the server first received it (only if before its expiry). */
    val receivedAt = LinkedHashMap<MsgId, Long>()
    val ackIssuedAt = LinkedHashMap<MsgId, Long>()
    private val receipts = HashMap<MsgId, Envelope>()
    private val pendingAcks = LinkedHashMap<MsgId, Pair<Long, Envelope>>()
    private val acks = LinkedHashMap<MsgId, Envelope>()

    fun release(now: Long) {
        val due = pendingAcks.entries.filter { it.value.first <= now }
        for ((ref, pending) in due) {
            acks[ref] = codec.ack(pending.second, AckStatus.ACKNOWLEDGED, now)
            ackIssuedAt[ref] = now
            pendingAcks.remove(ref)
        }
    }

    fun upload(batch: UplinkBatch, now: Long): UplinkResult {
        val out = ArrayList<ByteArray>()
        for (bytes in batch.envelopes) {
            val env = EnvelopeCodec.decode(bytes) ?: continue
            if (env.expiresAt <= now || !codec.verify(env)) continue
            val valid = when (env.kind) {
                Kind.SOS -> codec.openSos(env) != null
                Kind.UPDATE -> codec.openUpdate(env) != null
                else -> false
            }
            if (!valid) continue
            if (receivedAt.putIfAbsent(env.msgId, now) == null) {
                receipts[env.msgId] = codec.receipt(env, now)
                if (env.kind == Kind.SOS && (env.priority != Priority.INFO || p.ackInfo)) {
                    pendingAcks[env.msgId] = (now + rnd.nextInt(p.ackMinS, p.ackMaxS + 1)) to env
                }
            }
            receipts.getValue(env.msgId).takeIf { it.msgId.prefix !in batch.summary }?.let { out += it.bytes }
        }
        for ((ref, ack) in acks) {
            if (ref.prefix in batch.summary && ack.msgId.prefix !in batch.summary) out += ack.bytes
        }
        return UplinkResult(out)
    }
}
