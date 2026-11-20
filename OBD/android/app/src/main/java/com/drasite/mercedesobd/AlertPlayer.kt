package com.drasite.mercedesobd

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** Owns focus and volume through bursts, repeating while temperatures remain high. */
internal class AlertPlayer(context: Context, private val model: Model) {
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private var tone: AlertTone? = null
    private var playing = false
    private var temperatureWarning = false
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

    /** Initialize playback when connecting, before an alert is needed. */
    fun prepare(): Boolean {
        return try {
            if (tone == null) tone = AlertTone()
            true
        } catch (e: RuntimeException) {
            model.notice(tr("No se pudo preparar el audio: ${e.message}", "Could not prepare audio: ${e.message}"))
            false
        }
    }

    fun update(demo: Boolean, retard: Boolean, temperature: Boolean) {
        if (temperatureWarning && !temperature) cancel()
        temperatureWarning = temperature
        if (temperature || retard) play(demo)
    }

    private fun play(demo: Boolean) {
        // Coalesce overlapping alerts instead of accumulating delayed warnings.
        if (playing || !prepare()) return
        try {
            if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
            playing = true
            volume.begin(demo)
            // Audio focus is transient system coordination, not a user permission dialog.
            playBurst()
        } catch (e: RuntimeException) {
            playbackFailed(e)
        }
    }

    private fun playBurst() {
        try {
            tone!!.play()
            handler.postDelayed({
                if (temperatureWarning) playBurst() else cancel()
            }, AlertTone.DURATION_MS + 250)
        } catch (e: RuntimeException) {
            playbackFailed(e)
        }
    }

    private fun playbackFailed(e: RuntimeException) {
        release()
        model.notice(tr("No se pudo reproducir el aviso: ${e.message}", "Could not play alert: ${e.message}"))
    }

    fun cancelRetard() {
        if (!temperatureWarning) cancel()
    }

    fun cancel() {
        handler.removeCallbacksAndMessages(null)
        playing = false
        temperatureWarning = false
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
