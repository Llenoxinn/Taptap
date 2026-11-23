package io.github.llenoxinn.taptap.mesh

/** What an online phone uploads: every SOS/UPDATE it holds, plus its summary so the server can return matching ACKs. */
public data class UplinkBatch(public val envelopes: List<ByteArray>, public val summary: Set<IdPrefix>)

/** What the server returns: RECEIPTs and ACKs, re-validated by [Node.applyUplinkResult] like any envelope. */
public data class UplinkResult(public val envelopes: List<ByteArray>)
