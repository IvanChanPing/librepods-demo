package me.kavishdevar.librepods.presentation.viewmodel

import me.kavishdevar.librepods.data.Capability
import me.kavishdevar.librepods.health.HealthConnectExportStatus
import me.kavishdevar.librepods.services.HeartRateMonitoringStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoStateTest {
    @Test
    fun fixtureExposesEveryCapabilityAndPopulatedHealthData() {
        val state = createDemoState(nowMillis = 1_800_000L)

        assertTrue(state.isLocallyConnected)
        assertEquals(Capability.entries.toSet(), state.capabilities)
        assertEquals(3, state.battery.size)
        assertEquals(HeartRateMonitoringStatus.LIVE, state.heartRate.status)
        assertTrue(state.heartRate.samples.isNotEmpty())
        assertNotNull(state.heartRateSessions.current)
        assertTrue(state.heartRateSessions.history.isNotEmpty())
        assertEquals(HealthConnectExportStatus.READY, state.healthConnect.status)
        assertNotNull(state.nearbyAirPods)
        assertNotNull(state.instance)
    }
}
