package io.github.llenoxinn.taptap.simulator

/** World parameters (M1 spec §10.1). Defaults = the `baseline` preset. */
data class Params(
    val nodes: Int = 100,
    val areaM: Double = 1000.0,
    val rangeM: Double = 30.0,
    val walkMinMps: Double = 0.5,
    val walkMaxMps: Double = 1.5,
    val vehicleShare: Double = 0.1,
    val vehicleMinMps: Double = 5.0,
    val vehicleMaxMps: Double = 10.0,
    val pauseMaxS: Int = 600,
    val setupS: Int = 5,
    val envelopesPerS: Int = 20,
    val zones: Int = 2,
    val zoneRadiusM: Double = 50.0,
    val uplinkEveryS: Int = 30,
    val sosEvents: Int = 50,
    val sosWindowS: Int = 2 * 3600,
    val criticalPct: Int = 40,
    val urgentPct: Int = 40,
    val updateShare: Double = 0.2,
    val updateMinS: Int = 5 * 60,
    val updateMaxS: Int = 15 * 60,
    val ackMinS: Int = 2 * 60,
    val ackMaxS: Int = 10 * 60,
    val ackInfo: Boolean = false,
    val criticalOnlyShare: Double = 0.0,
    val durationS: Int = 6 * 3600,
) {
    init {
        require(nodes >= 2 && areaM > 0 && rangeM > 0 && sosWindowS > 0 && durationS > 0) { "sizes and durations must be positive" }
        require(walkMinMps < walkMaxMps && vehicleMinMps < vehicleMaxMps) { "speed min must be below max" }
        require(updateMinS <= updateMaxS && ackMinS <= ackMaxS && pauseMaxS >= 0) { "min must not exceed max" }
        require(criticalPct + urgentPct <= 100 && envelopesPerS >= 1 && zones >= 0) { "bad priority mix, rate or zones" }
    }

    /** `--name value` overrides; every field is overridable. */
    fun override(name: String, v: String): Params = when (name) {
        "nodes" -> copy(nodes = v.toInt())
        "area" -> copy(areaM = v.toDouble())
        "range" -> copy(rangeM = v.toDouble())
        "walk-min" -> copy(walkMinMps = v.toDouble())
        "walk-max" -> copy(walkMaxMps = v.toDouble())
        "vehicle-share" -> copy(vehicleShare = v.toDouble())
        "vehicle-min" -> copy(vehicleMinMps = v.toDouble())
        "vehicle-max" -> copy(vehicleMaxMps = v.toDouble())
        "pause-max" -> copy(pauseMaxS = v.toInt())
        "setup" -> copy(setupS = v.toInt())
        "rate" -> copy(envelopesPerS = v.toInt())
        "zones" -> copy(zones = v.toInt())
        "zone-radius" -> copy(zoneRadiusM = v.toDouble())
        "uplink-every" -> copy(uplinkEveryS = v.toInt())
        "sos" -> copy(sosEvents = v.toInt())
        "sos-window" -> copy(sosWindowS = v.toInt())
        "critical-pct" -> copy(criticalPct = v.toInt())
        "urgent-pct" -> copy(urgentPct = v.toInt())
        "update-share" -> copy(updateShare = v.toDouble())
        "update-min" -> copy(updateMinS = v.toInt())
        "update-max" -> copy(updateMaxS = v.toInt())
        "ack-min" -> copy(ackMinS = v.toInt())
        "ack-max" -> copy(ackMaxS = v.toInt())
        "ack-info" -> copy(ackInfo = v.toBooleanStrict())
        "critical-only-share" -> copy(criticalOnlyShare = v.toDouble())
        "duration" -> copy(durationS = v.toInt())
        else -> throw IllegalArgumentException("unknown option --$name")
    }

    companion object {
        val PRESETS: Map<String, Params> = mapOf(
            "baseline" to Params(),
            "sparse" to Params(nodes = 30),
            "dense" to Params(nodes = 300),
            "lowbattery" to Params(criticalOnlyShare = 0.3),
        )
    }
}
