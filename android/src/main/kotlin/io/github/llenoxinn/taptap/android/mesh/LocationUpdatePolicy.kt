package io.github.llenoxinn.taptap.android.mesh

import io.github.llenoxinn.taptap.mesh.Location

/**
 * Rule from ARCHITECTURE.md §6: send an UPDATE if the initial SOS had no fix,
 * or once accuracy improves strictly by more than 50%.
 */
object LocationUpdatePolicy {
    fun shouldSendUpdate(bestAccuracyM: Int?, fix: Location): Boolean {
        if (fix.lat == null || fix.lon == null) return false
        if (bestAccuracyM == null) return true
        return fix.accuracyM > 0 && fix.accuracyM * 2 < bestAccuracyM
    }
}
