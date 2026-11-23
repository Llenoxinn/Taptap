package io.github.llenoxinn.taptap.mesh

import io.github.llenoxinn.taptap.mesh.ReceiveOutcome.Accepted
import kotlin.random.Random

/** One phone's mesh engine: store + validator + policy + keys. Transport-agnostic. */
public class Node(
    public val crypto: Crypto,
    private val keys: SignKeypair,
    /** Pinned server public keys. */
    public val keyring: ServerKeyring,
    public val clock: Clock,
    /** msgIds and session tokens: `SecureRandom().asKotlinRandom()` in the app, seeded in tests. */
    private val random: Random,
    public val config: MeshConfig = MeshConfig(),
    public val store: MessageStore = InMemoryMessageStore(),
    /** Called after an incoming envelope is stored (hops already incremented). */
    private val onAccept: (Envelope) -> Unit = {},
) {
    /** Low battery: relay only ACK/RECEIPT and Critical. */
    public var criticalOnly: Boolean = false

    private val factory = EnvelopeFactory(crypto, keys, keyring, clock, random)
    private val mySos = LinkedHashSet<MsgId>()
    private val acceptLog = HashMap<String, ArrayDeque<Pair<Long, Kind>>>()

    /** Ids of SOS this node originated (M2 moves these into an app-level outbox). */
    public val mySosIds: Set<MsgId> get() = mySos

    public val senderKey: ByteArray get() = keys.publicKey

    public fun sendSos(req: SosRequest): Envelope = factory.sos(req).also {
        keep(it)
        mySos += it.msgId
    }

    public fun sendUpdate(parent: Envelope, location: Location): Envelope = factory.update(parent, location).also(::keep)

    /** Validates [bytes] and, if accepted, stores the envelope with `hops + 1`. */
    public fun receive(bytes: ByteArray): ReceiveOutcome {
        val outcome = Validator(this).check(bytes)
        if (outcome !is Accepted) return outcome
        val env = outcome.envelope.withHops(outcome.envelope.hops + 1)
        val now = clock.now()
        if (env.kind.fromServer) StorePolicy.clear(store, env, config)
        StorePolicy.makeRoom(store, now, config)
        store.put(env)
        if (!env.kind.fromServer) acceptLog.getOrPut(env.senderKey.toHex()) { ArrayDeque() }.addLast(now to env.kind)
        onAccept(env)
        return Accepted(env)
    }

    /**
     * Rule 10: ≤ N SOS / UPDATE per sender key per rolling hour, by *this* node's clock.
     * Never by `createdAt`, which the sender controls.
     */
    internal fun rateLimited(env: Envelope, now: Long): Boolean {
        val limit = when (env.kind) {
            Kind.SOS -> config.sosPerHour
            Kind.UPDATE -> config.updatesPerHour
            else -> return false
        }
        val log = acceptLog[env.senderKey.toHex()] ?: return false
        while (log.isNotEmpty() && log.first().first <= now - 3600) log.removeFirst()
        return log.count { it.second == env.kind } >= limit
    }

    private fun keep(env: Envelope) {
        StorePolicy.makeRoom(store, clock.now(), config)
        store.put(env)
    }
}
