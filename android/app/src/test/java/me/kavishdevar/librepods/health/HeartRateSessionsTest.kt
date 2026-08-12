package me.kavishdevar.librepods.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateSessionsTest {
    @Test
    fun codecRoundTripsCurrentHistoryAndZones() {
        val session = HeartRateSession(
            id = 10L,
            startedAtMillis = 1_000L,
            workoutEndedAtMillis = 3_000L,
            samples = listOf(
                RecordedHeartRateSample(120, 1_000L),
                RecordedHeartRateSample(145, 2_000L),
                RecordedHeartRateSample(130, 4_000L),
            ),
        )
        val state = HeartRateSessionState(
            current = session,
            history = listOf(session.copy(id = 9L, endedAtMillis = 5_000L)),
            zoneConfig = HeartRateZoneConfig(listOf(110, 130, 150, 170)),
            recordingPaused = true,
        )

        assertEquals(state, HeartRateSessionCodec.decode(HeartRateSessionCodec.encode(state)))
    }

    @Test
    fun codecRejectsInvalidData() {
        assertNull(HeartRateSessionCodec.decode("not-base64"))
    }

    @Test
    fun zonesUseOnlyConfiguredBoundariesAndMeasuredIntervals() {
        val session = HeartRateSession(
            id = 1L,
            startedAtMillis = 0L,
            samples = listOf(
                RecordedHeartRateSample(100, 0L),
                RecordedHeartRateSample(120, 1_000L),
                RecordedHeartRateSample(140, 3_000L),
                RecordedHeartRateSample(180, 6_000L),
            ),
        )

        assertEquals(
            listOf(1_000L, 2_000L, 3_000L, 0L, 0L),
            calculateZoneDurations(session, HeartRateZoneConfig(listOf(105, 125, 145, 165)))
                .map { it.durationMillis },
        )
    }

    @Test
    fun averageExcludesPostWorkoutRecoverySamples() {
        val session = HeartRateSession(
            id = 1L,
            startedAtMillis = 0L,
            workoutEndedAtMillis = 2_000L,
            samples = listOf(
                RecordedHeartRateSample(120, 1_000L),
                RecordedHeartRateSample(140, 2_000L),
                RecordedHeartRateSample(80, 3_000L),
            ),
        )

        assertEquals(130, session.averageBpm)
    }
}
