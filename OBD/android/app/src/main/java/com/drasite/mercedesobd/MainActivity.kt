package com.drasite.mercedesobd

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.ClipData
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
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
import android.text.format.Formatter
import java.io.File
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private companion object {
        const val EXPORT_LOGS_REQUEST = 1001
    }

    private val model = Model()
    private lateinit var session: Session
    private lateinit var dashboard: DashboardView
    private val handler = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    private var soundGeneration = 0
    private var nextBurst = 0L
    private var demoMode = false
    private var volumeBeforeAlert: Int? = null
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

    private var faultBusy = false

    private fun faultsMenu() {
        AlertDialog.Builder(this)
            .setTitle(tr("Errores de la ECU", "ECU fault codes") + if (demoMode) " · DEMO" else "")
            .setItems(arrayOf(tr("Leer errores", "Read faults"), tr("Borrar errores…", "Clear faults…"))) { _, item ->
                if (item == 0) requestFaults(false)
                else AlertDialog.Builder(this)
                    .setTitle(tr("¿Borrar errores de la ECU?", "Clear ECU faults?"))
                    .setMessage(tr("Se borrarán los errores y sus datos de diagnóstico. Motor parado y contacto puesto.", "Faults and their diagnostic data will be erased. Engine stopped, ignition on."))
                    .setPositiveButton(tr("Borrar", "Clear")) { _, _ -> requestFaults(true) }
                    .setNegativeButton(tr("Cancelar", "Cancel"), null).show()
            }
            .setNegativeButton(tr("Cerrar", "Close"), null).show()
    }

    private fun requestFaults(clear: Boolean) {
        if (faultBusy) return
        faultBusy = true
        val accepted = session.faults(clear) { result ->
            handler.post {
                faultBusy = false
                if (isFinishing || isDestroyed) return@post
                val message = result.fold(
                    onSuccess = { codes ->
                        when {
                            clear -> tr("La ECU ha aceptado el borrado. Vuelve a leer para comprobar si quedan errores.", "The ECU accepted the clear request. Read again to check for remaining faults.")
                            codes.isEmpty() -> tr("No hay errores almacenados", "No stored faults")
                            else -> codes.joinToString("\n") { it.label() }
                        }
                    },
                    onFailure = { it.message ?: it.toString() },
                )
                AlertDialog.Builder(this)
                    .setTitle(tr("Errores de la ECU", "ECU fault codes") + if (demoMode) " · DEMO" else "")
                    .setMessage(message).setPositiveButton(tr("Cerrar", "Close"), null).show()
            }
        }
        if (!accepted) {
            faultBusy = false
            model.notice(tr("Conecta la ECU o inicia Demo y espera a recibir datos", "Connect the ECU or start Demo and wait for live data"))
        } else model.notice(tr("Consultando averías…", "Processing fault request…"))
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
                tone = ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME)
            } catch (_: Exception) {
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
                        if (!demoMode) maximizeAlertVolume()
                        try {
                            tone?.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 65)
                        } catch (_: RuntimeException) {
                            cancelSound()
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

    private fun maximizeAlertVolume() {
        if (volumeBeforeAlert != null) return
        val audio = getSystemService(AudioManager::class.java)
        try {
            volumeBeforeAlert = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                0,
            )
        } catch (_: SecurityException) {
            model.notice(tr("Android no permite ajustar el volumen de los avisos", "Android does not allow changing alert volume"))
        }
    }

    private fun restoreAlertVolume() {
        val volume = volumeBeforeAlert ?: return
        try {
            getSystemService(AudioManager::class.java)
                .setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
            volumeBeforeAlert = null
        } catch (_: SecurityException) {
            model.notice(tr("Android no permite restaurar el volumen anterior", "Android does not allow restoring the previous volume"))
        }
    }

    private fun cancelSound() {
        soundGeneration++
        nextBurst = 0
        try {
            tone?.stopTone()
        } finally {
            restoreAlertVolume()
        }
    }

    private fun settings() {
        val names =
            arrayOf(
                tr("Seis pitidos por aumento observado", "Six beeps per observed increase"),
                tr("Parpadeo rojo durante 3 segundos", "Red flash for 3 seconds"),
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
        if (!logsAvailable()) return
        val files = logFiles.list()
        if (files.isEmpty()) {
            AlertDialog.Builder(this).setTitle(tr("Registros", "Logs"))
                .setMessage(tr("Todavía no hay registros", "No logs yet"))
                .setPositiveButton(tr("Cerrar", "Close"), null).show()
            return
        }
        val selected = BooleanArray(files.size)
        val dates = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val labels = files.map { "${it.name}\n${dates.format(Date(it.lastModified()))} · ${Formatter.formatFileSize(this, it.length())}" }.toTypedArray()
        val dialog = AlertDialog.Builder(this)
            .setTitle(tr("Registros · selecciona archivos", "Logs · select files"))
            .setMultiChoiceItems(labels, selected) { _, i, checked -> selected[i] = checked }
            .setPositiveButton(tr("Acciones", "Actions"), null)
            .setNeutralButton(tr("Todos", "All"), null)
            .setNegativeButton(tr("Cerrar", "Close"), null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val check = !selected.all { it }
                selected.indices.forEach { selected[it] = check; dialog.listView.setItemChecked(it, check) }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val names = files.filterIndexed { i, _ -> selected[i] }.map { it.name }
                if (names.isNotEmpty()) {
                    dialog.dismiss()
                    logActions(names)
                }
            }
        }
        dialog.show()
    }

    private fun logActions(names: List<String>) {
        AlertDialog.Builder(this)
            .setTitle(tr("${names.size} archivo(s)", "${names.size} file(s)"))
            .setItems(arrayOf(tr("Exportar", "Export"), tr("Compartir", "Share"), tr("Borrar…", "Delete…"))) { _, action ->
                when (action) {
                    0 -> prepareLogs(names, false)
                    1 -> prepareLogs(names, true)
                    else -> AlertDialog.Builder(this)
                        .setTitle(tr("¿Borrar ${names.size} archivo(s)?", "Delete ${names.size} file(s)?"))
                        .setMessage(names.joinToString("\n"))
                        .setPositiveButton(tr("Borrar", "Delete")) { _, _ -> deleteLogs(names) }
                        .setNegativeButton(tr("Cancelar", "Cancel"), null).show()
                }
            }
            .setNegativeButton(tr("Volver", "Back")) { _, _ -> logsMenu() }.show()
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
        session.stop()
        cancelSound()
        handler.removeCallbacksAndMessages(null)
        tone?.release()
        tone = null
        super.onDestroy()
    }
}
