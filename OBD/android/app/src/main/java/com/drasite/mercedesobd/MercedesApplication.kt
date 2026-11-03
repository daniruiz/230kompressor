package com.drasite.mercedesobd

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.io.File

class MercedesApplication : Application() {
    val model by lazy { Model() }
    internal val alerts by lazy { AlertPlayer(this, model) }
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    var demoMode = false
        internal set
    @Volatile internal var startGeneration = 0
    internal var startRequested = false
    internal var serviceStopping = false
    private var stopping = false
    val session: Session by lazy {
        Session(File(filesDir, "logs"), model) {
            val generation = startGeneration
            handler.post {
                if (generation == startGeneration && !stopping && session.running && prefs.getBoolean("sound", true)) alerts.play(demoMode)
            }
        }
    }
    val active: Boolean get() = startRequested || session.running || serviceStopping

    override fun onCreate() {
        super.onCreate()
        I18n.localeProvider = { resources.configuration.locales[0] }
    }

    fun start(demo: Boolean, address: String?): Boolean {
        if (active) return false
        startGeneration++
        startRequested = true
        stopping = false
        try {
            startForegroundService(Intent(this, DashboardService::class.java)
                .putExtra("demo", demo).putExtra("address", address).putExtra("generation", startGeneration))
        } catch (e: RuntimeException) {
            startRequested = false
            model.notice(tr("No se pudo iniciar la sesión: ${e.message}", "Could not start session: ${e.message}"))
            return false
        }
        return true
    }

    fun stop() {
        startGeneration++
        startRequested = false
        stopping = true
        session.stop()
        alerts.cancel()
    }
}
