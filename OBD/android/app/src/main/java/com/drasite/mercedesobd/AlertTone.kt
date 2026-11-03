package com.drasite.mercedesobd

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/** One complete burst: Bluetooth can buffer it without per-beep stop/reload cycles. */
internal class AlertTone {
    companion object {
        const val DURATION_MS = 1400L
    }
    private val samples = ShortArray(48000 * DURATION_MS.toInt() / 1000) { i ->
        val frame = i - 48000 * 300 / 1000
        val period = 48000 * 130 / 1000
        val toneLength = 48000 * 65 / 1000
        val position = if (frame >= 0) frame % period else -1
        if (frame < 0 || frame >= period * 6 || position >= toneLength) 0.toShort()
        else {
            val fade = minOf(1.0, position / 240.0, (toneLength - 1 - position) / 240.0)
            (sin(2 * PI * 1800 * position / 48000) * 24575 * fade).toInt().toShort()
        }
    }
    private val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(48000)
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

    fun play() {
        track.stop()
        check(track.reloadStaticData() == AudioTrack.SUCCESS)
        track.play()
    }

    fun stop() = track.stop()
    fun release() = track.release()
}
