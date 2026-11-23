package io.github.llenoxinn.taptap.simulator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SimulatorScenarioTest {
    private val baseline = Params.PRESETS.getValue("baseline")

    @Test
    fun `baseline over 5 seeds delivers at least 95 percent with no duplicate transfers`() {
        val total = (1L..5L).map { Simulation(baseline, it).run() }.reduce(RunMetrics::plus)
        assertEquals(250, total.sosSent)
        assertTrue(total.deliveryRate >= 0.95, "delivery ${total.deliveryRate} (PLAN.md §5.1 target 0.95)")
        assertEquals(0, total.duplicates, "protocol duplicate transfers")
    }

    @Test
    fun `same seed gives identical metrics JSON`() {
        val small = baseline.copy(durationS = 3600, sosWindowS = 1800)
        assertEquals(Simulation(small, 7).run().toJson(), Simulation(small, 7).run().toJson())
    }

    @Test
    fun `CLI args, seeds and overrides parse`() {
        assertEquals(mapOf("preset" to "dense", "range" to "20"), parseArgs(arrayOf("--preset", "dense", "--range", "20")))
        assertEquals(listOf(1L, 2L, 3L), parseSeeds("1-3"))
        assertEquals(listOf(4L), parseSeeds("4"))
        assertEquals(20.0, baseline.override("range", "20").rangeM)
        assertFailsWith<IllegalArgumentException> { baseline.override("nope", "1") }
        assertFailsWith<IllegalArgumentException> { parseArgs(arrayOf("--preset")) }
    }

    @Test
    fun `nearest-rank percentile`() {
        assertEquals(null, percentile(emptyList(), 0.5))
        assertEquals(3L, percentile(listOf(5L, 1L, 3L, 2L, 4L), 0.5))
        assertEquals(5L, percentile(listOf(5L, 1L, 3L, 2L, 4L), 0.9))
    }
}
