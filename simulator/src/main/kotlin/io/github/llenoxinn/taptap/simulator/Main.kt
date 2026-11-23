package io.github.llenoxinn.taptap.simulator

import io.github.llenoxinn.taptap.mesh.Priority
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

private const val USAGE = """Usage: --preset baseline|sparse|dense|lowbattery  --seeds 1-5  [--out out]  [--<param> value ...]
Params: nodes area range walk-min walk-max vehicle-share vehicle-min vehicle-max pause-max setup rate zones
        zone-radius uplink-every sos sos-window critical-pct urgent-pct update-share update-min update-max
        ack-min ack-max ack-info critical-only-share duration"""

fun main(args: Array<String>) {
    val opts = try {
        parseArgs(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("${e.message}\n$USAGE")
        exitProcess(2)
    }
    val preset = opts["preset"] ?: "baseline"
    val params = try {
        opts.filterKeys { it !in setOf("preset", "seeds", "out") }.entries
            .fold(Params.PRESETS[preset] ?: throw IllegalArgumentException("unknown preset $preset")) { p, (k, v) -> p.override(k, v) }
    } catch (e: IllegalArgumentException) {
        System.err.println("${e.message}\n$USAGE")
        exitProcess(2)
    }
    val seeds = parseSeeds(opts["seeds"] ?: "1")

    println("Taptap simulator: preset=$preset seeds=${seeds.first()}..${seeds.last()}")
    println(params)
    println(HEADER)
    val startNs = System.nanoTime()
    val runs = seeds.map { seed -> Simulation(params, seed).run().also { println(row(seed.toString(), it)) } }
    val total = runs.reduce(RunMetrics::plus)
    println(row("all", total))
    val wallS = (System.nanoTime() - startNs) / 1e9
    println(
        "delivery by priority: " +
            Priority.entries.joinToString("  ") { "%s %.1f%% (%d)".format(it, 100 * total.deliveryRate(it), total.sent[it] ?: 0) },
    )
    println("duplicate transfers: ${total.duplicates} (protocol), ${total.uplinkOverlapDuplicates} (uplink during session)")
    println("rejects: ${total.rejects}  envelopes tx: ${total.envelopesTx}  bytes tx: ${total.bytesTx}")
    println(
        "sessions: started ${total.sessionsStarted}, completed ${total.sessionsCompleted}, timed out ${total.sessionsTimedOut}, " +
            "disconnected ${total.sessionsDisconnected}, failed ${total.sessionsFailed}",
    )
    println("wall time: %.1f s".format(wallS))

    val outDir = File(opts["out"] ?: "out").apply { mkdirs() }
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val file = File(outDir, "$preset-$stamp.json")
    file.writeText(
        json(
            linkedMapOf(
                "preset" to preset, "params" to params.toString(), "wallTimeS" to wallS,
                "runs" to runs.map { it.toMap() }, "total" to total.toMap(),
            ),
        ) + "\n",
    )
    println("wrote ${file.path}")
}

private const val HEADER = "seed   SOS  deliv%  lat-med  lat-p90  lat-max  ACK-ret%  ACK-med   dup  sessions  maxStore   (latencies in min)"

private fun row(label: String, m: RunMetrics): String {
    fun min(s: Long?): String = s?.let { "%.1f".format(it / 60.0) } ?: "-"
    return "%-5s %4d  %6.1f  %7s  %7s  %7s  %8.1f  %7s  %4d  %8d  %8d".format(
        label, m.sosSent, 100 * m.deliveryRate, min(percentile(m.latenciesS, 0.5)), min(percentile(m.latenciesS, 0.9)),
        min(m.latenciesS.maxOrNull()), 100 * m.ackReturnRate, min(percentile(m.ackLatenciesS, 0.5)), m.duplicates,
        m.sessionsStarted, m.maxStore,
    )
}

/** `--key value` pairs; no CLI library (YAGNI). */
internal fun parseArgs(args: Array<String>): Map<String, String> {
    require(args.size % 2 == 0) { "expected --key value pairs" }
    return args.toList().chunked(2).associate { (k, v) ->
        require(k.startsWith("--")) { "expected --key, got $k" }
        k.removePrefix("--") to v
    }
}

/** "3" or "1-5". */
internal fun parseSeeds(s: String): List<Long> {
    val parts = s.split("-").map { it.trim().toLong() }
    return if (parts.size == 1) parts else (parts[0]..parts[1]).toList()
}
