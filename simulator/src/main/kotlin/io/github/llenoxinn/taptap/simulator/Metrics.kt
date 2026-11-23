package io.github.llenoxinn.taptap.simulator

import io.github.llenoxinn.taptap.mesh.Priority
import io.github.llenoxinn.taptap.mesh.RejectReason
import kotlin.math.ceil

/** One run's results (or several runs added together). Contains nothing time- or machine-dependent. */
data class RunMetrics(
    val seeds: List<Long>,
    val sent: Map<Priority, Int>,
    /** SOS the server received before their `expiresAt`. */
    val delivered: Map<Priority, Int>,
    /** SOS created → server, seconds, delivered SOS only. */
    val latenciesS: List<Long>,
    val acksIssued: Int,
    val acksReturned: Int,
    /** Server ACK → origin holds it, seconds. */
    val ackLatenciesS: List<Long>,
    /** Envelopes received that the receiver already held or had tombstoned (not via a mid-session uplink). Must be 0. */
    val duplicates: Int,
    /**
     * Duplicates of an envelope the receiver got by uplink *during* the session, after its SUMMARY was
     * sent, so the peer still sent it. Wasted airtime, not a protocol fault.
     */
    val uplinkOverlapDuplicates: Int,
    val envelopesTx: Long,
    val bytesTx: Long,
    val sessionsStarted: Int,
    val sessionsCompleted: Int,
    val sessionsTimedOut: Int,
    val sessionsDisconnected: Int,
    val sessionsFailed: Int,
    val rejects: Map<RejectReason, Int>,
    val maxStore: Int,
) {
    val sosSent: Int get() = sent.values.sum()
    val sosDelivered: Int get() = delivered.values.sum()
    val deliveryRate: Double get() = ratio(sosDelivered, sosSent)
    val ackReturnRate: Double get() = ratio(acksReturned, acksIssued)
    fun deliveryRate(p: Priority): Double = ratio(delivered[p] ?: 0, sent[p] ?: 0)

    operator fun plus(o: RunMetrics): RunMetrics = RunMetrics(
        seeds + o.seeds, sent.addCounts(o.sent), delivered.addCounts(o.delivered), latenciesS + o.latenciesS,
        acksIssued + o.acksIssued, acksReturned + o.acksReturned, ackLatenciesS + o.ackLatenciesS,
        duplicates + o.duplicates, uplinkOverlapDuplicates + o.uplinkOverlapDuplicates, envelopesTx + o.envelopesTx, bytesTx + o.bytesTx,
        sessionsStarted + o.sessionsStarted, sessionsCompleted + o.sessionsCompleted,
        sessionsTimedOut + o.sessionsTimedOut, sessionsDisconnected + o.sessionsDisconnected,
        sessionsFailed + o.sessionsFailed, rejects.addCounts(o.rejects), maxOf(maxStore, o.maxStore),
    )

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "seeds" to seeds,
        "sosSent" to sosSent,
        "sosDelivered" to sosDelivered,
        "deliveryRate" to deliveryRate,
        "deliveryRateByPriority" to Priority.entries.associateWith { deliveryRate(it) },
        "sentByPriority" to Priority.entries.associateWith { sent[it] ?: 0 },
        "latencyS" to stats(latenciesS),
        "acksIssued" to acksIssued,
        "acksReturned" to acksReturned,
        "ackReturnRate" to ackReturnRate,
        "ackLatencyS" to stats(ackLatenciesS),
        "duplicateTransfers" to duplicates,
        "uplinkOverlapDuplicates" to uplinkOverlapDuplicates,
        "envelopesTransmitted" to envelopesTx,
        "bytesTransmitted" to bytesTx,
        "sessions" to linkedMapOf(
            "started" to sessionsStarted, "completed" to sessionsCompleted, "timedOut" to sessionsTimedOut,
            "disconnected" to sessionsDisconnected, "failed" to sessionsFailed,
        ),
        "rejects" to RejectReason.entries.filter { it in rejects }.associateWith { rejects[it] },
        "maxStoreSize" to maxStore,
    )

    fun toJson(): String = json(toMap())

    private companion object {
        fun ratio(a: Int, b: Int): Double = if (b == 0) 0.0 else a.toDouble() / b
        fun <K> Map<K, Int>.addCounts(o: Map<K, Int>): Map<K, Int> = (keys + o.keys).associateWith { (this[it] ?: 0) + (o[it] ?: 0) }
        fun stats(xs: List<Long>): Map<String, Long?> =
            linkedMapOf("median" to percentile(xs, 0.5), "p90" to percentile(xs, 0.9), "max" to xs.maxOrNull())
    }
}

/** Nearest-rank percentile; null for an empty list. */
fun percentile(xs: List<Long>, q: Double): Long? {
    if (xs.isEmpty()) return null
    val sorted = xs.sorted()
    return sorted[(ceil(q * sorted.size).toInt()).coerceIn(1, sorted.size) - 1]
}

/** Minimal JSON writer: maps, lists, strings, numbers, booleans, enums, null. */
fun json(v: Any?): String = when (v) {
    null -> "null"
    is String -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    is Number, is Boolean -> v.toString()
    is Enum<*> -> json(v.name)
    is Map<*, *> -> v.entries.joinToString(",", "{", "}") { json(it.key.toString()) + ":" + json(it.value) }
    is Iterable<*> -> v.joinToString(",", "[", "]") { json(it) }
    else -> json(v.toString())
}
