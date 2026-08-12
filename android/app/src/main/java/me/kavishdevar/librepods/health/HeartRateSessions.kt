/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.health

import android.content.SharedPreferences
import androidx.core.content.edit
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.bluetooth.HeartRateSample

data class RecordedHeartRateSample(val bpm: Int, val recordedAtMillis: Long)

data class HeartRateZoneConfig(val upperBounds: List<Int>) {
    init {
        require(upperBounds.size == ZONE_BOUNDARY_COUNT)
        require(upperBounds.zipWithNext().all { (left, right) -> left < right })
        require(upperBounds.all { it in MIN_ZONE_BOUNDARY..MAX_ZONE_BOUNDARY })
    }

    fun zoneIndex(bpm: Int): Int = upperBounds.indexOfFirst { bpm <= it }
        .takeIf { it >= 0 } ?: upperBounds.size

    companion object {
        const val ZONE_BOUNDARY_COUNT = 4
        const val MIN_ZONE_BOUNDARY = 40
        const val MAX_ZONE_BOUNDARY = 240
    }
}

data class HeartRateSession(
    val id: Long,
    val startedAtMillis: Long,
    val workoutEndedAtMillis: Long? = null,
    val endedAtMillis: Long? = null,
    val samples: List<RecordedHeartRateSample> = emptyList(),
) {
    val isRecovering: Boolean
        get() = workoutEndedAtMillis != null && endedAtMillis == null

    val averageBpm: Int?
        get() = workoutSamples.takeIf { it.isNotEmpty() }?.map { it.bpm }?.average()?.toInt()

    val workoutSamples: List<RecordedHeartRateSample>
        get() = workoutEndedAtMillis?.let { end -> samples.filter { it.recordedAtMillis <= end } }
            ?: samples

    val recoverySamples: List<RecordedHeartRateSample>
        get() = workoutEndedAtMillis?.let { end -> samples.filter { it.recordedAtMillis > end } }
            ?: emptyList()
}

data class HeartRateSessionState(
    val current: HeartRateSession? = null,
    val history: List<HeartRateSession> = emptyList(),
    val zoneConfig: HeartRateZoneConfig? = null,
    val recordingPaused: Boolean = false,
)

data class HeartRateZoneDuration(val zoneIndex: Int, val durationMillis: Long)

fun calculateZoneDurations(
    session: HeartRateSession,
    config: HeartRateZoneConfig,
): List<HeartRateZoneDuration> {
    val durations = LongArray(5)
    session.workoutSamples.sortedBy { it.recordedAtMillis }.zipWithNext().forEach { (sample, next) ->
        val interval = (next.recordedAtMillis - sample.recordedAtMillis)
            .coerceIn(0L, MAX_COUNTED_SAMPLE_INTERVAL_MILLIS)
        durations[config.zoneIndex(sample.bpm)] += interval
    }
    return durations.mapIndexed { index, duration -> HeartRateZoneDuration(index, duration) }
}

/**
 * Records validated heart-rate samples separately from transport state.
 * The current session and ten recent sessions survive process recreation. Explicit Finish records
 * two minutes of recovery while monitoring continues; Start begins the next workout session.
 */
class HeartRateSessionRepository(
    private val preferences: SharedPreferences,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var recoveryJob: Job? = null
    private var samplesSincePersist = 0
    private val _state = MutableStateFlow(load())
    val state: StateFlow<HeartRateSessionState> = _state

    init {
        scheduleRecoveryIfNeeded()
    }

    fun onSample(sample: HeartRateSample) {
        synchronized(lock) {
            val previous = _state.value
            if (previous.recordingPaused && previous.current == null) return
            val recorded = RecordedHeartRateSample(sample.bpm, sample.receivedAtMillis)
            val current = previous.current ?: HeartRateSession(
                id = sample.receivedAtMillis,
                startedAtMillis = sample.receivedAtMillis,
            )
            if (current.samples.size >= MAX_SAMPLES_PER_SESSION) return
            _state.value = previous.copy(current = current.copy(samples = current.samples + recorded))
            samplesSincePersist++
            if (samplesSincePersist >= PERSIST_EVERY_SAMPLES) persistLocked()
        }
    }

    fun startNewSession() {
        synchronized(lock) {
            recoveryJob?.cancel()
            recoveryJob = null
            closeCurrentLocked(nowMillis())
            _state.value = _state.value.copy(recordingPaused = false)
            persistLocked()
        }
    }

    fun finishWorkout() {
        val recoveryEndsAt = synchronized(lock) {
            val current = _state.value.current ?: return
            if (current.workoutEndedAtMillis != null) return
            val workoutEndedAt = nowMillis()
            _state.value = _state.value.copy(
                current = current.copy(workoutEndedAtMillis = workoutEndedAt),
            )
            persistLocked()
            workoutEndedAt + RECOVERY_DURATION_MILLIS
        }
        scheduleRecovery(recoveryEndsAt)
    }

    fun stopNow(pauseRecording: Boolean) {
        synchronized(lock) {
            recoveryJob?.cancel()
            recoveryJob = null
            closeCurrentLocked(nowMillis())
            _state.value = _state.value.copy(recordingPaused = pauseRecording)
            persistLocked()
        }
    }

    fun setZoneConfig(config: HeartRateZoneConfig?) {
        synchronized(lock) {
            _state.value = _state.value.copy(zoneConfig = config)
            persistLocked()
        }
    }

    fun flush() = synchronized(lock) { persistLocked() }

    private fun scheduleRecoveryIfNeeded() {
        val current = state.value.current ?: return
        val workoutEndedAt = current.workoutEndedAtMillis ?: return
        scheduleRecovery(workoutEndedAt + RECOVERY_DURATION_MILLIS)
    }

    private fun scheduleRecovery(recoveryEndsAt: Long) {
        recoveryJob?.cancel()
        recoveryJob = scope.launch {
            delay((recoveryEndsAt - nowMillis()).coerceAtLeast(0L))
            synchronized(lock) {
                closeCurrentLocked(recoveryEndsAt)
                _state.value = _state.value.copy(recordingPaused = true)
                persistLocked()
                recoveryJob = null
            }
        }
    }

    private fun closeCurrentLocked(endedAtMillis: Long) {
        val current = _state.value.current ?: return
        val completed = current.copy(endedAtMillis = endedAtMillis)
        val history = (listOf(completed) + _state.value.history).take(MAX_HISTORY_SESSIONS)
        _state.value = _state.value.copy(current = null, history = history)
    }

    private fun persistLocked() {
        samplesSincePersist = 0
        preferences.edit { putString(PREFERENCE_KEY, HeartRateSessionCodec.encode(_state.value)) }
    }

    private fun load(): HeartRateSessionState = preferences.getString(PREFERENCE_KEY, null)
        ?.let(HeartRateSessionCodec::decode)
        ?: HeartRateSessionState()

    companion object {
        const val RECOVERY_DURATION_MILLIS = 120_000L
        internal const val MAX_HISTORY_SESSIONS = 10
        internal const val MAX_SAMPLES_PER_SESSION = 14_400
        private const val PERSIST_EVERY_SAMPLES = 5
        private const val PREFERENCE_KEY = "heart_rate_sessions_v1"
    }
}

internal object HeartRateSessionCodec {
    private const val MAGIC = 0x4C504852
    private const val VERSION = 1

    fun encode(state: HeartRateSessionState): String {
        val bytes = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeInt(VERSION)
                data.writeBoolean(state.recordingPaused)
                val bounds = state.zoneConfig?.upperBounds.orEmpty()
                data.writeInt(bounds.size)
                bounds.forEach(data::writeInt)
                data.writeBoolean(state.current != null)
                state.current?.let { writeSession(data, it) }
                data.writeInt(state.history.size.coerceAtMost(HeartRateSessionRepository.MAX_HISTORY_SESSIONS))
                state.history.take(HeartRateSessionRepository.MAX_HISTORY_SESSIONS)
                    .forEach { writeSession(data, it) }
            }
            output.toByteArray()
        }
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun decode(encoded: String): HeartRateSessionState? = runCatching {
        DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded))).use { data ->
            require(data.readInt() == MAGIC)
            require(data.readInt() == VERSION)
            val paused = data.readBoolean()
            val boundaryCount = data.readInt()
            require(boundaryCount in 0..HeartRateZoneConfig.ZONE_BOUNDARY_COUNT)
            val bounds = List(boundaryCount) { data.readInt() }
            val config = bounds.takeIf { it.isNotEmpty() }?.let(::HeartRateZoneConfig)
            val current = if (data.readBoolean()) readSession(data) else null
            val historyCount = data.readInt()
            require(historyCount in 0..HeartRateSessionRepository.MAX_HISTORY_SESSIONS)
            HeartRateSessionState(
                current = current,
                history = List(historyCount) { readSession(data) },
                zoneConfig = config,
                recordingPaused = paused,
            )
        }
    }.getOrNull()

    private fun writeSession(data: DataOutputStream, session: HeartRateSession) {
        data.writeLong(session.id)
        data.writeLong(session.startedAtMillis)
        data.writeLong(session.workoutEndedAtMillis ?: -1L)
        data.writeLong(session.endedAtMillis ?: -1L)
        val samples = session.samples.take(HeartRateSessionRepository.MAX_SAMPLES_PER_SESSION)
        data.writeInt(samples.size)
        samples.forEach {
            data.writeInt(it.bpm)
            data.writeLong(it.recordedAtMillis)
        }
    }

    private fun readSession(data: DataInputStream): HeartRateSession {
        val id = data.readLong()
        val startedAt = data.readLong()
        val workoutEndedAt = data.readLong().takeIf { it >= 0L }
        val endedAt = data.readLong().takeIf { it >= 0L }
        val sampleCount = data.readInt()
        require(sampleCount in 0..HeartRateSessionRepository.MAX_SAMPLES_PER_SESSION)
        val samples = List(sampleCount) {
            RecordedHeartRateSample(data.readInt(), data.readLong())
        }
        return HeartRateSession(id, startedAt, workoutEndedAt, endedAt, samples)
    }
}

private const val MAX_COUNTED_SAMPLE_INTERVAL_MILLIS = 10_000L
