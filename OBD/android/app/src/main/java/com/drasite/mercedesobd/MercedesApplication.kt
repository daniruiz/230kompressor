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
        // Old per-adapter entries do not identify which ECU was connected last.
        // Remove them without guessing a preference from their iteration order.
        try {
            val obsolete = prefs.all.keys.filter { it.startsWith("last_ecu_family_") }
            if (obsolete.isNotEmpty()) prefs.edit().also { editor ->
                obsolete.forEach { editor.remove(it) }
            }.apply()
        } catch (_: RuntimeException) {
            // Optional preferences must never prevent a connection.
        }
        Session(File(filesDir, "logs"), model, connected = { address ->
            prefs.edit().putString("last_connected_address", address).apply()
        }, ecuMemory = EcuMemory(
            { key -> prefs.getString(key, null) },
            { key, family -> prefs.edit().putString(key, family).apply() },
        )) { temperature ->
            val generation = startGeneration
            handler.post {
                if (generation == startGeneration && !stopping && session.running &&
                    (temperature || prefs.getBoolean("sound", true))) alerts.play(demoMode, temperature)
            }
        }
    }
    val active: Boolean get() = startRequested || session.running || serviceStopping

    override fun onCreate() {
        super.onCreate()
        I18n.localeProvider = { resources.configuration.locales[0] }
    }

    fun start(demo: Boolean, address: String?, automatic: Boolean = false): Boolean {
        if (active) return false
        startGeneration++
        startRequested = true
        stopping = false
        try {
            startForegroundService(Intent(this, DashboardService::class.java)
                .putExtra("demo", demo).putExtra("address", address).putExtra("automatic", automatic && !demo)
                .putExtra("generation", startGeneration))
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
