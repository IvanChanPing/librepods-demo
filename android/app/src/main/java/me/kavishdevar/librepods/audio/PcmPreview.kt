package me.kavishdevar.librepods.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

data class PcmClip(val data: ByteArray, val sampleRate: Int, val channelCount: Int)

/** Owns playback of one bounded decoded-microphone preview clip. */
class PcmPreviewPlayer : AutoCloseable {
    private var track: AudioTrack? = null

    @Synchronized
    fun play(clip: PcmClip): Boolean {
        close()
        if (clip.data.isEmpty() || clip.sampleRate <= 0 || clip.channelCount !in 1..2) return false
        return runCatching {
            val channelMask = if (clip.channelCount == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(clip.sampleRate)
                        .setChannelMask(channelMask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                )
                .setBufferSizeInBytes(clip.data.size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
                .also {
                    it.write(clip.data, 0, clip.data.size)
                    it.play()
                }
            true
        }.getOrElse {
            close()
            false
        }
    }

    @Synchronized
    override fun close() {
        val current = track ?: return
        track = null
        runCatching { current.stop() }
        current.release()
    }
}
