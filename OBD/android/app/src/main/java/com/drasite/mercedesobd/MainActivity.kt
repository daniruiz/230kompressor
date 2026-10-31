package com.drasite.mercedesobd

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.ClipData
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.PopupMenu
import java.io.File

class MainActivity : Activity() {
    private companion object {
        const val EXPORT_LOGS_REQUEST = 1001
    }

    private val model = Model()
    private lateinit var session: Session
    private lateinit var dashboard: DashboardView
    private val handler = Handler(Looper.getMainLooper())
    private var tone: AlertTone? = null
    private var soundGeneration = 0
    private var nextBurst = 0L
    private var demoMode = false
    private val alertVolume by lazy {
        val audio = getSystemService(AudioManager::class.java)
        AlertVolume(
            { audio.getStreamVolume(AudioManager.STREAM_MUSIC) },
            { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) },
            { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) },
        )
    }
    private var resumed = false
    private var wasRunning = false
    private var exporting = false
    private var pendingExport: File? = null
    private val logFiles by lazy { LogFiles(File(filesDir, "logs"), File(cacheDir, "log_exports")) }
    private var pendingAdapterAddress: String? = null
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val ticker =
        object : Runnable {
            override fun run() {
                dashboard.invalidate()
                val running = session.running
                if (wasRunning && !running) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    cancelSound()
                }
                wasRunning = running
                if (!running && resumed) {
                    pendingAdapterAddress?.let { address ->
                        pendingAdapterAddress = null
                        start(false, address)
                    }
                }
                if (resumed) handler.postDelayed(this, 100)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Hardware volume keys adjust the same stream used by demo alerts.
        volumeControlStream = AudioManager.STREAM_MUSIC
        session = Session(File(filesDir, "logs"), model) { handler.post { beepBurst() } }
        setContentView(createContentView())
        savedInstanceState?.getStringArrayList("pending_export")?.let {
            pendingExport = runCatching { LogFiles.exportedFile(File(cacheDir, "log_exports"), it) }.getOrNull()
            exporting = pendingExport != null
        }
        if (savedInstanceState == null && intent.getBooleanExtra("demo", false)) start(true, null)
    }

    private fun createContentView(): View {
        val root =
            FrameLayout(this).apply {
                setBackgroundColor(Color.rgb(3, 6, 12))
                setOnApplyWindowInsetsListener { view, insets ->
                    view.setPadding(
                        insets.systemWindowInsetLeft,
                        insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight,
                        insets.systemWindowInsetBottom,
                    )
                    insets
                }
            }
        dashboard =
            DashboardView(this, model).apply { flashEnabled = prefs.getBoolean("flash", true) }
        root.addView(dashboard, FrameLayout.LayoutParams(-1, -1))
        val menuButton =
            Button(this).apply {
                text = "⋮"
                textSize = 24f
                contentDescription = tr("Menú de acciones", "Actions menu")
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, 0)
                minWidth = 0
                minimumWidth = 0
                setOnClickListener { showActions(this) }
            }
        root.addView(
            menuButton,
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.RIGHT),
        )
        return root
    }

    private fun showActions(anchor: View) {
        val popup = PopupMenu(this, anchor, Gravity.RIGHT)
        val deviceName = prefs.getString("name", tr("Adaptador", "Adapter")) ?: tr("Adaptador", "Adapter")
        val actions =
            listOf<Pair<String, () -> Unit>>(
                tr("Adaptador: $deviceName", "Adapter: $deviceName") to { selectDevice() },
                tr("Conectar", "Connect") to { connect() },
                "Demo" to { start(true, null) },
                tr("Parar", "Stop") to { stop() },
                tr("Errores de la ECU", "ECU fault codes") to { faultsMenu() },
                tr("Lectura de correcciones", "Correction readings") to { correctionsMenu() },
                tr("Registros", "Logs") to { logsMenu() },
                tr("Ajustes", "Settings") to { settings() },
            )
        actions.forEach { (label, action) ->
            popup.menu.add(label).setOnMenuItemClickListener {
                action()
                true
            }
        }
        popup.show()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private var faultDialog: Dialog? = null
    private var correctionsDialog: Dialog? = null
    private var logsDialog: Dialog? = null

    private fun faultsMenu() {
        if (faultDialog?.isShowing == true) return
        faultDialog = FaultsDialog(this, session, demoMode).show()
    }

    private fun correctionsMenu() {
        if (correctionsDialog?.isShowing == true) return
        correctionsDialog = CorrectionsDialog(this, session, demoMode).show()
    }

    private fun permitted() =
        Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

    private var pendingConnect = false

    private fun permission(connect: Boolean): Boolean {
        if (permitted()) return true
        pendingConnect = connect
        requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                if (pendingConnect) connect() else selectDevice()
            } else model.notice(tr("Permiso Dispositivos cercanos denegado", "Nearby devices permission denied"))
        }
    }

    @SuppressLint("MissingPermission")
    private fun selectDevice() {
        if (!permission(false)) return
        try {
            val adapter =
                BluetoothAdapter.getDefaultAdapter()
                    ?: throw IllegalStateException(tr("Este dispositivo no tiene Bluetooth", "This device has no Bluetooth"))
            if (!adapter.isEnabled) {
                model.notice(tr("Activa Bluetooth y empareja el adaptador en Ajustes", "Enable Bluetooth and pair the adapter in Settings"))
                return
            }
            val devices = adapter.bondedDevices.sortedBy { it.name ?: it.address }
            if (devices.isEmpty()) {
                model.notice(tr("Empareja el OBDLink/ELM en los ajustes Bluetooth de Android", "Pair the OBDLink/ELM in Android Bluetooth settings"))
                return
            }
            AlertDialog.Builder(this)
                .setTitle(tr("Adaptadores ya emparejados", "Paired adapters"))
                .setItems(
                    devices.map { "${it.name?:"Bluetooth"}\n${it.address}" }.toTypedArray()
                ) { _, i ->
                    val d = devices[i]
                    prefs
                        .edit()
                        .putString("address", d.address)
                        .putString("name", d.name ?: d.address)
                        .apply()
                    stop()
                    pendingAdapterAddress = d.address
                    model.notice(tr("Conectando a ${d.name?:d.address}…", "Connecting to ${d.name?:d.address}…"))
                }
                .setNegativeButton(tr("Cerrar", "Close"), null)
                .show()
        } catch (e: Exception) {
            model.notice(e.message ?: e.toString())
        }
    }

    private fun connect() {
        if (!permission(true)) return
        val address = prefs.getString("address", null)
        if (address == null) {
            selectDevice()
            return
        }
        start(false, address)
    }

    private fun start(demo: Boolean, address: String?) {
        pendingAdapterAddress = null
        if (exporting) {
            model.notice(tr("Espera a que termine la exportación", "Wait for the export to finish"))
            return
        }
        if (session.start(demo, address, prefs.getBoolean("adapter_tuning", true))) {
            cancelSound()
            demoMode = demo
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            wasRunning = true
        } else model.notice(tr("Detén la sesión actual antes de iniciar otra", "Stop the current session before starting another"))
    }

    private fun stop() {
        pendingAdapterAddress = null
        val active = session.running
        session.stop()
        cancelSound()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (active) model.notice(tr("Deteniendo sesión…", "Stopping session…")) else model.stop()
    }

    private fun beepBurst() {
        if (!prefs.getBoolean("sound", true) || !session.running || !resumed) return
        if (tone == null)
            try {
                tone = AlertTone()
            } catch (e: Exception) {
                model.notice(tr("No se pudo iniciar el sonido: ${e.message}", "Could not start audio: ${e.message}"))
                return
            }
        val gen = soundGeneration
        val start = maxOf(SystemClock.uptimeMillis(), nextBurst)
        nextBurst = start + 6 * 130
        repeat(6) { i ->
            handler.postAtTime(
                {
                    if (
                        gen == soundGeneration && resumed && session.running && prefs.getBoolean("sound", true)
                    ) {
                        prepareAlertVolume()
                        try {
                            tone?.play()
                        } catch (e: RuntimeException) {
                            cancelSound()
                            model.notice(tr("No se pudo reproducir el aviso: ${e.message}", "Could not play alert: ${e.message}"))
                        }
                    }
                },
                start + i * 130,
            )
        }
        val end = nextBurst
        handler.postAtTime(
            {
                // A later burst may still be using the saved volume.
                if (gen == soundGeneration && end == nextBurst) restoreAlertVolume()
            },
            end,
        )
    }

    private fun prepareAlertVolume() {
        try {
            alertVolume.begin(demoMode)
        } catch (_: SecurityException) {
            model.notice(tr("Android no permite ajustar el volumen de los avisos", "Android does not allow changing alert volume"))
        }
    }

    private fun restoreAlertVolume() {
        try {
            alertVolume.restore()
        } catch (_: SecurityException) {
            model.notice(tr("Android no permite restaurar el volumen anterior", "Android does not allow restoring the previous volume"))
        }
    }

    private fun cancelSound() {
        soundGeneration++
        nextBurst = 0
        try {
            tone?.stop()
        } catch (_: IllegalStateException) {
            // A lost audio track must not prevent restoring the media volume.
        } finally {
            restoreAlertVolume()
        }
    }

    private fun settings() {
        val names =
            arrayOf(
                tr("Sonido: retardo > 3° o temperatura alta", "Sound: retard > 3° or high temperature"),
                tr("Parpadeo de avisos durante 3 segundos", "Alert flash for 3 seconds"),
                tr("Optimizar tiempos del adaptador (próxima conexión)", "Optimize adapter timings (next connection)"),
            )
        val keys = arrayOf("sound", "flash", "adapter_tuning")
        val values = BooleanArray(keys.size) { prefs.getBoolean(keys[it], true) }
        AlertDialog.Builder(this)
            .setTitle(tr("Ajustes · MercedesOBD", "Settings · MercedesOBD"))
            .setMultiChoiceItems(names, values) { _, i, value -> values[i] = value }
            .setPositiveButton(tr("Guardar", "Save")) { _, _ ->
                val edit = prefs.edit()
                keys.forEachIndexed { i, k -> edit.putBoolean(k, values[i]) }
                edit.apply()
                dashboard.flashEnabled = values[1]
                if (!values[0]) cancelSound()
                model.notice(tr("Ajustes guardados", "Settings saved"))
            }
            .setNegativeButton(tr("Cancelar", "Cancel"), null)
            .show()
    }

    private fun logsAvailable(): Boolean {
        if (session.running) {
            model.notice(tr("Pulsa Parar y espera al cierre para gestionar los registros", "Tap Stop and wait for the session to end to manage logs"))
            return false
        }
        return !exporting
    }

    private fun logsMenu() {
        if (!logsAvailable() || logsDialog?.isShowing == true) return
        logsDialog = LogsDialog(this, logFiles.list(),
            export = { prepareLogs(it, false) },
            share = { prepareLogs(it, true) },
            delete = ::deleteLogs,
        ).show()
    }

    private fun deleteLogs(names: List<String>) {
        if (!logsAvailable()) return
        exporting = true
        Thread {
            val result = runCatching { logFiles.delete(names) }
            runOnUiThread {
                exporting = false
                model.notice(result.fold(
                    onSuccess = { failed -> if (failed.isEmpty()) tr("Registros borrados", "Logs deleted")
                        else tr("No se pudieron borrar: ", "Could not delete: ") + failed.joinToString(", ") },
                    onFailure = { it.message ?: it.toString() },
                ))
                if (!isFinishing && !isDestroyed) logsMenu()
            }
        }.start()
    }

    private fun prepareLogs(names: List<String>, share: Boolean) {
        if (!logsAvailable()) return
        exporting = true
        model.notice(tr("Preparando registros…", "Preparing logs…"))
        Thread {
                try {
                    val file = logFiles.prepare(names)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) {
                            exporting = false
                            return@runOnUiThread
                        }
                        try {
                            if (share) {
                                val uri = Uri.Builder().scheme("content").authority("$packageName.logs")
                                    .appendPath(file.parentFile!!.name).appendPath(file.name).build()
                                val intent = Intent(Intent.ACTION_SEND).setType(LogFiles.mime(file))
                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                intent.clipData = ClipData.newRawUri(file.name, uri)
                                startActivity(Intent.createChooser(intent, tr("Compartir registros", "Share logs")))
                                exporting = false
                            } else {
                                pendingExport = file
                                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                                    .addCategory(Intent.CATEGORY_OPENABLE)
                                    .setType(LogFiles.mime(file)).putExtra(Intent.EXTRA_TITLE, file.name)
                                startActivityForResult(intent, EXPORT_LOGS_REQUEST)
                            }
                        } catch (e: Exception) {
                            pendingExport = null
                            exporting = false
                            model.notice(tr("No se pudo abrir el selector: ${e.message}", "Could not open file picker: ${e.message}"))
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        exporting = false
                        model.notice(tr("Exportación: ${e.message}", "Export: ${e.message}"))
                    }
                }
            }
            .start()
    }

    @Deprecated("Platform activity result used to avoid extra dependencies")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != EXPORT_LOGS_REQUEST) return
        val uri = data?.data
        val file = pendingExport
        pendingExport = null
        if (resultCode != RESULT_OK || uri == null) {
            exporting = false
            model.notice(tr("Exportación cancelada", "Export cancelled"))
            return
        }
        if (file == null) {
            exporting = false
            model.notice(tr("Exportación no disponible; selecciona de nuevo los registros", "Export unavailable; select the logs again"))
            return
        }
        Thread {
                try {
                    contentResolver.openOutputStream(uri, "wt").use { out ->
                        requireNotNull(out)
                        file.inputStream().use { it.copyTo(out) }
                    }
                    model.notice(tr("Registros exportados", "Logs exported"))
                } catch (e: Exception) {
                    model.notice(tr("No se pudo exportar: ${e.message}", "Could not export: ${e.message}"))
                } finally {
                    runOnUiThread { exporting = false }
                }
            }
            .start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingExport?.let { outState.putStringArrayList("pending_export", arrayListOf(it.parentFile!!.name, it.name)) }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        handler.post(ticker)
    }

    override fun onPause() {
        resumed = false
        cancelSound()
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onStop() {
        stop()
        super.onStop()
    }

    override fun onDestroy() {
        faultDialog?.dismiss()
        correctionsDialog?.dismiss()
        logsDialog?.dismiss()
        session.stop()
        cancelSound()
        handler.removeCallbacksAndMessages(null)
        tone?.release()
        tone = null
        super.onDestroy()
    }
}
