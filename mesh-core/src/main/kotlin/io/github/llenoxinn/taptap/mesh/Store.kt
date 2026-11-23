package io.github.llenoxinn.taptap.mesh

/** Envelope + tombstone storage. In memory here; Room on Android (M2). Policy lives in [StorePolicy]. */
public interface MessageStore {
    public fun get(msgId: MsgId): Envelope?

    /** Stores as given (hops already incremented). */
    public fun put(env: Envelope)
    public fun remove(msgId: MsgId)
    public fun all(): List<Envelope>
    public fun addTombstone(msgId: MsgId, until: Long)
    public fun isTombstoned(msgId: MsgId): Boolean
    public fun removeTombstone(msgId: MsgId)

    /** Tombstoned id → time it can be forgotten. */
    public fun tombstones(): Map<MsgId, Long>

    /** Removes expired envelopes and tombstones. */
    public fun purgeExpired(now: Long)

    /** 8-byte prefixes of every held envelope and tombstone. */
    public fun summary(): Set<IdPrefix>
}

public class InMemoryMessageStore : MessageStore {
    private val envelopes = LinkedHashMap<MsgId, Envelope>()
    private val tombs = LinkedHashMap<MsgId, Long>()

    override fun get(msgId: MsgId): Envelope? = envelopes[msgId]
    override fun put(env: Envelope) {
        envelopes[env.msgId] = env
    }

    override fun remove(msgId: MsgId) {
        envelopes.remove(msgId)
    }

    override fun all(): List<Envelope> = envelopes.values.toList()
    override fun addTombstone(msgId: MsgId, until: Long) {
        tombs[msgId] = until
    }

    override fun isTombstoned(msgId: MsgId): Boolean = msgId in tombs
    override fun removeTombstone(msgId: MsgId) {
        tombs.remove(msgId)
    }

    override fun tombstones(): Map<MsgId, Long> = tombs.toMap()
    override fun purgeExpired(now: Long) {
        envelopes.values.removeIf { it.expiresAt <= now }
        tombs.values.removeIf { it <= now }
    }

    override fun summary(): Set<IdPrefix> = (envelopes.keys + tombs.keys).mapTo(HashSet()) { it.prefix }
}

/** Oldest first; msgId breaks ties so the order never depends on hash iteration. */
internal val byAge: Comparator<Envelope> =
    compareBy<Envelope> { it.createdAt }.thenComparator { a, b -> compareUnsigned(a.msgId.bytes, b.msgId.bytes) }

/** Capacity, eviction and ACK clean-up rules (M1 spec §7). Kept out of the store implementations. */
internal object StorePolicy {
    fun hasRoom(store: MessageStore, incoming: Envelope, now: Long, config: MeshConfig): Boolean {
        val all = store.all()
        if (all.size < config.storeCapacity || victim(all, now, rank(incoming)) != null) return true
        // An ACK/RECEIPT frees its own room by clearing the SOS it answers.
        return incoming.kind.fromServer && incoming.refMsgId?.let(store::get) != null
    }

    /** [incoming] is the rank of what needs the slot; the node's own messages pass CRITICAL. */
    fun makeRoom(store: MessageStore, now: Long, config: MeshConfig, incoming: Priority = Priority.CRITICAL) {
        while (store.all().size >= config.storeCapacity) {
            val v = victim(store.all(), now, incoming) ?: return
            store.remove(v.msgId)
        }
    }

    /** ACK/RECEIPT rank as Critical: they may push out Info/Urgent. */
    fun rank(env: Envelope): Priority = if (env.kind.fromServer) Priority.CRITICAL else env.priority

    /**
     * Eviction order: expired → UPDATE without its parent → oldest Info → oldest Urgent. Never Critical or
     * ACK/RECEIPT, and never a higher priority than [incoming] (an Info can't push out an Urgent).
     */
    private fun victim(all: List<Envelope>, now: Long, incoming: Priority): Envelope? {
        all.firstOrNull { it.expiresAt <= now }?.let { return it }
        val held = all.mapTo(HashSet()) { it.msgId }
        all.filter { it.kind == Kind.UPDATE && it.refMsgId !in held }.minWithOrNull(byAge)?.let { return it }
        for (p in listOf(Priority.INFO, Priority.URGENT).filter { it >= incoming }) {
            all.filter { !it.kind.fromServer && it.priority == p }.minWithOrNull(byAge)?.let { return it }
        }
        return null
    }

    /** An ACK/RECEIPT removes its SOS and that SOS's UPDATEs and tombstones the SOS. The ACK itself is kept by the caller. */
    fun clear(store: MessageStore, ack: Envelope, config: MeshConfig) {
        val ref = ack.refMsgId ?: return
        store.all().filter { it.msgId == ref || (it.kind == Kind.UPDATE && it.refMsgId == ref) }
            .forEach { store.remove(it.msgId) }
        val tombs = store.tombstones()
        if (ref !in tombs && tombs.size >= config.tombstoneCapacity) {
            tombs.minByOrNull { it.value }?.let { store.removeTombstone(it.key) }
        }
        store.addTombstone(ref, ack.expiresAt)
    }
}
