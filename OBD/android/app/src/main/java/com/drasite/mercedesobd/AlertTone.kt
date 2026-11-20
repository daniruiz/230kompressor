package com.drasite.mercedesobd

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/** Buffered beeps; temperature warnings loop the cadence without restarting Bluetooth audio. */
internal class AlertTone {
    companion object {
        const val DURATION_MS = 1400L
        private const val SAMPLE_RATE = 48000
        private const val LEAD_IN_FRAMES = SAMPLE_RATE * 300 / 1000
        private const val PERIOD_FRAMES = SAMPLE_RATE * 130 / 1000
        private const val TONE_FRAMES = SAMPLE_RATE * 65 / 1000
        private const val LOOP_END_FRAMES = LEAD_IN_FRAMES + PERIOD_FRAMES * 6
    }
    private val samples = ShortArray(SAMPLE_RATE * DURATION_MS.toInt() / 1000) { i ->
        val frame = i - LEAD_IN_FRAMES
        val position = if (frame >= 0) frame % PERIOD_FRAMES else -1
        if (frame < 0 || i >= LOOP_END_FRAMES || position >= TONE_FRAMES) 0.toShort()
        else {
            val fade = minOf(1.0, position / 240.0, (TONE_FRAMES - 1 - position) / 240.0)
            (sin(2 * PI * 1800 * position / SAMPLE_RATE) * 24575 * fade).toInt().toShort()
        }
    }
    private val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size * 2).build()

    init {
        try {
            check(track.write(samples, 0, samples.size) == samples.size)
        } catch (e: Exception) {
            track.release()
            throw e
        }
    }

    fun play(continuous: Boolean) {
        track.stop()
        check(track.reloadStaticData() == AudioTrack.SUCCESS)
        // Repeat only the beeps, excluding the Bluetooth lead-in and trailing silence.
        check((if (continuous) track.setLoopPoints(LEAD_IN_FRAMES, LOOP_END_FRAMES, -1)
            else track.setLoopPoints(0, 0, 0)) == AudioTrack.SUCCESS)
        track.play()
    }

    fun stop() = track.stop()
    fun release() = track.release()
}
