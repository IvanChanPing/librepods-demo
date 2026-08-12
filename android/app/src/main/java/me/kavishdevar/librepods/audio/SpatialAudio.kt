/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin
import me.kavishdevar.librepods.utils.HeadTracking

enum class SpatialAudioMode(val label: String) {
    OFF("Off"),
    FIXED("Fixed"),
    HEAD_TRACKED("Head Tracked"),
}

/**
 * Plays LibrePods' stereo direction preview. This does not intercept or spatialize other apps.
 * Fixed keeps the source in front of the listener; Head Tracked offsets it using AirPods yaw.
 */
class SpatialAudioDemoEngine : AutoCloseable {
    @Volatile
    var mode: SpatialAudioMode = SpatialAudioMode.OFF

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var track: AudioTrack? = null

    fun play(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        return runCatching {
            val minBuffer = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            require(minBuffer > 0)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
                )
                .setBufferSizeInBytes(minBuffer.coerceAtLeast(BLOCK_FRAMES * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also(AudioTrack::play)
            worker = Thread(::renderLoop, "librepods-spatial-preview").also(Thread::start)
            true
        }.getOrElse {
            running.set(false)
            releaseTrack()
            false
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        worker?.interrupt()
        worker = null
        releaseTrack()
    }

    private fun renderLoop() {
        val output = ShortArray(BLOCK_FRAMES * 2)
        var frame = 0L
        try {
            while (running.get()) {
                val currentMode = mode
                val relativeYaw = when (currentMode) {
                    SpatialAudioMode.OFF, SpatialAudioMode.FIXED -> 0f
                    SpatialAudioMode.HEAD_TRACKED -> -HeadTracking.orientation.value.yaw
                }.coerceIn(-90f, 90f)
                val pan = if (currentMode == SpatialAudioMode.OFF) 0f
                else sin(relativeYaw / 180f * PI).toFloat().coerceIn(-0.85f, 0.85f)
                val leftGain = if (currentMode == SpatialAudioMode.OFF) 0.72f else 0.74f * (1f - pan)
                val rightGain = if (currentMode == SpatialAudioMode.OFF) 0.72f else 0.74f * (1f + pan)
                repeat(BLOCK_FRAMES) { index ->
                    val time = (frame + index).toDouble() / SAMPLE_RATE
                    val sample = (
                        sin(2.0 * PI * 220.0 * time) * 0.32 +
                            sin(2.0 * PI * 330.0 * time) * 0.18
                        ).toFloat()
                    output[index * 2] = (sample * leftGain * Short.MAX_VALUE).toInt().toShort()
                    output[index * 2 + 1] = (sample * rightGain * Short.MAX_VALUE).toInt().toShort()
                }
                track?.write(output, 0, output.size, AudioTrack.WRITE_BLOCKING)
                frame += BLOCK_FRAMES
            }
        } finally {
            releaseTrack()
        }
    }

    @Synchronized
    private fun releaseTrack() {
        val audioTrack = track ?: return
        track = null
        runCatching { audioTrack.stop() }
        audioTrack.release()
    }

    override fun close() = stop()

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val BLOCK_FRAMES = 960
    }
}
