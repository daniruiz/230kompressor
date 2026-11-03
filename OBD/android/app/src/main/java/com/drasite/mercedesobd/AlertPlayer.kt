package com.drasite.mercedesobd

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** Owns focus and volume for a complete burst, including Bluetooth drain time. */
internal class AlertPlayer(context: Context, private val model: Model) {
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private var tone: AlertTone? = null
    private var playing = false
    private val volume = AlertVolume(
        { audio.getStreamVolume(AudioManager.STREAM_MUSIC) },
        { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) },
        { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) },
    )
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .setOnAudioFocusChangeListener({ change -> if (change < 0) cancel() }, handler)
        .build()

    fun play(demo: Boolean) {
        // Coalesce overlapping alerts instead of accumulating delayed warnings.
        if (playing) return
        try {
            if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
            playing = true
            volume.begin(demo)
            if (tone == null) tone = AlertTone()
            tone!!.play()
            handler.postDelayed({ cancel() }, AlertTone.DURATION_MS + 250)
        } catch (e: RuntimeException) {
            release()
            model.notice(tr("No se pudo reproducir el aviso: ${e.message}", "Could not play alert: ${e.message}"))
        }
    }

    fun cancel() {
        handler.removeCallbacksAndMessages(null)
        playing = false
        try { tone?.stop() } catch (_: IllegalStateException) { }
        try { volume.restore() } catch (_: SecurityException) {
            model.notice(tr("Android no permite restaurar el volumen anterior", "Android does not allow restoring the previous volume"))
        } finally { audio.abandonAudioFocusRequest(focus) }
    }

    fun release() {
        cancel()
        tone?.release()
        tone = null
    }
}
