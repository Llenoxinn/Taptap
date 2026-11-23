package io.github.llenoxinn.taptap.mesh

/** A fixed "now" (2027-01-15) so every test is deterministic. */
internal const val T0: Long = 1_800_000_000L

internal class TestClock(var t: Long = T0) : Clock {
    override fun now(): Long = t
}

internal fun seed(n: Int): ByteArray = ByteArray(32) { (n + it).toByte() }

internal fun id(n: Int): MsgId = MsgId(ByteArray(16) { n.toByte() })

internal fun sosRequest(
    priority: Priority = Priority.CRITICAL,
    note: String = "",
    lat: Int? = 103_000_000,
    people: Int = 2,
    injured: Int = 1,
): SosRequest = SosRequest(priority, lat, lat?.let { 1_239_000_000 }, 15, 30, people, injured, 0b1, note)
