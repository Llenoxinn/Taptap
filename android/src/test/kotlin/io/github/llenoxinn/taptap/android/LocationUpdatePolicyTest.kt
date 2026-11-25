package io.github.llenoxinn.taptap.android

import io.github.llenoxinn.taptap.android.mesh.LocationUpdatePolicy
import io.github.llenoxinn.taptap.mesh.Location
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationUpdatePolicyTest {
    @Test
    fun triggersUpdateOnFirstFixOrOverFiftyPercentAccuracyImprovement() {
        val noFix = Location(null, null, 0, 0)
        assertFalse(LocationUpdatePolicy.shouldSendUpdate(null, noFix))
        assertFalse(LocationUpdatePolicy.shouldSendUpdate(100, noFix))

        val firstFix = Location(103157000, 1238854000, 100, 2)
        assertTrue(LocationUpdatePolicy.shouldSendUpdate(null, firstFix))

        // 40% better (100 -> 60) does not trigger
        assertFalse(LocationUpdatePolicy.shouldSendUpdate(100, firstFix.copy(accuracyM = 60)))
        // Exactly 50% (100 -> 50) is not strictly > 50%
        assertFalse(LocationUpdatePolicy.shouldSendUpdate(100, firstFix.copy(accuracyM = 50)))
        // > 50% better (100 -> 49) triggers
        assertTrue(LocationUpdatePolicy.shouldSendUpdate(100, firstFix.copy(accuracyM = 49)))
    }
}
