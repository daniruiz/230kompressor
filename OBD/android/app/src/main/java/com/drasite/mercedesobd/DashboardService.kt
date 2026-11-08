package com.drasite.mercedesobd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/** Owns acquisition lifetime independently of the dashboard Activity. */
class DashboardService : Service() {
    private val runtime get() = application as MercedesApplication
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private val monitor = object : Runnable {
        override fun run() {
            if (!runtime.session.running && !runtime.startRequested) finishService()
            else handler.postDelayed(this, 250)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            runtime.stop()
            if (!runtime.session.running) finishService()
            return START_NOT_STICKY
        }
        if (intent == null || !runtime.startRequested || intent.getIntExtra("generation", -1) != runtime.startGeneration) {
            if (!runtime.active) finishService()
            return START_NOT_STICKY
        }
        val demo = intent.getBooleanExtra("demo", false)
        try {
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(
                CHANNEL, tr("Sesión OBD", "OBD session"), NotificationManager.IMPORTANCE_LOW,
            ))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(this, 1, Intent(this, DashboardService::class.java)
                .setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(if (demo) tr("MercedesOBD · Demo", "MercedesOBD · Demo") else "MercedesOBD")
                .setContentText(tr("Lecturas y avisos activos en segundo plano", "Readings and alerts active in the background"))
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(Notification.Action.Builder(null, tr("Parar", "Stop"), stop).build())
                .build()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, notification, if (demo) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    else ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else startForeground(1, notification)
            if (wakeLock?.isHeld != true) {
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MercedesOBD:acquisition").apply { acquire() }
            }
            runtime.demoMode = demo
            val tuning = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("adapter_tuning", true)
            check(runtime.session.start(demo, intent.getStringExtra("address"), tuning,
                automatic = intent.getBooleanExtra("automatic", false) && !demo))
            runtime.startRequested = false
            if (getSharedPreferences("settings", MODE_PRIVATE).getBoolean("sound", true)) runtime.alerts.prepare()
            handler.removeCallbacks(monitor)
            handler.post(monitor)
        } catch (e: RuntimeException) {
            runtime.stop()
            runtime.model.notice(tr("No se pudo mantener la sesión: ${e.message}", "Could not keep session running: ${e.message}"))
            finishService()
        }
        // A killed service never restarts itself; optional connection happens on app launch.
        return START_NOT_STICKY
    }

    private fun finishService() {
        runtime.serviceStopping = true
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runtime.stop()
        runtime.alerts.release()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        runtime.serviceStopping = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "obd_session"
        const val ACTION_STOP = "com.drasite.mercedesobd.STOP_SESSION"
    }
}
