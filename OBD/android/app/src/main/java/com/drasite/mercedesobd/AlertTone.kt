package com.drasite.mercedesobd

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/** A short 1800 Hz media tone, independent of device telephony/CDMA tone support. */
internal class AlertTone {
    private val samples = ShortArray(24000 * 65 / 1000) { i ->
        val fade = minOf(1.0, i / 120.0, (24000 * 65 / 1000 - 1 - i) / 120.0)
        (sin(2 * PI * 1800 * i / 24000) * 24575 * fade).toInt().toShort()
    }
    private val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(24000)
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
