package com.drasite.mercedesobd

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.ClipData
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.PopupMenu
import java.io.File

class MainActivity : Activity() {
    private companion object {
        const val EXPORT_LOGS_REQUEST = 1001
        const val ENABLE_BLUETOOTH_REQUEST = 1002
        const val ADAPTER_PERMISSIONS_REQUEST = 3
    }

    private enum class BluetoothAction { CONNECT, AUTO_CONNECT }
    private data class PendingStart(val demo: Boolean, val address: String?, val automatic: Boolean)

    private val runtime get() = application as MercedesApplication
    private val model get() = runtime.model
    private val session get() = runtime.session
    private lateinit var dashboard: DashboardView
    private val handler = Handler(Looper.getMainLooper())
    private val demoMode get() = runtime.demoMode
    private var resumed = false
    private var wasRunning = false
    private var exporting = false
    private var pendingExport: File? = null
    private val logFiles by lazy { LogFiles(File(filesDir, "logs"), File(cacheDir, "log_exports")) }
    private var pendingAdapterAddress: String? = null
    private var pendingNotificationStart: PendingStart? = null
    private var pendingBluetoothAction: BluetoothAction? = null
    private val pendingAdapterPermissions = ArrayList<() -> Unit>()
    private val pendingBluetoothEnable = ArrayList<() -> Unit>()
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val ticker =
        object : Runnable {
            override fun run() {
                dashboard.invalidate()
                val running = runtime.active
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
        setContentView(createContentView())
        applyFullscreen()
        if (savedInstanceState?.containsKey("pending_notification_demo") == true) {
            pendingNotificationStart = PendingStart(
                savedInstanceState.getBoolean("pending_notification_demo"),
                savedInstanceState.getString("pending_notification_address"),
                savedInstanceState.getBoolean("pending_notification_automatic"),
            )
        }
        pendingBluetoothAction = savedInstanceState?.getString("pending_bluetooth_action")?.let { name ->
            BluetoothAction.entries.firstOrNull { it.name == name }
        }
        savedInstanceState?.getStringArrayList("pending_export")?.let {
            pendingExport = runCatching { LogFiles.exportedFile(File(cacheDir, "log_exports"), it) }.getOrNull()
            exporting = pendingExport != null
        }
        if (savedInstanceState == null) {
            if (intent.getBooleanExtra("demo", false)) start(true, null)
            else autoConnect()
        }
    }

    @Suppress("DEPRECATION")
    private fun applyFullscreen() {
        val fullscreen = prefs.getBoolean("fullscreen", true)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (fullscreen) hide(WindowInsets.Type.systemBars()) else show(WindowInsets.Type.systemBars())
            }
        } else {
            val layoutFlags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            window.decorView.systemUiVisibility = if (fullscreen) layoutFlags or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY else layoutFlags
        }
        window.decorView.requestApplyInsets()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    private fun createContentView(): View {
        val root =
            FrameLayout(this).apply {
                setBackgroundColor(Color.rgb(3, 6, 12))
                setOnApplyWindowInsetsListener { view, insets ->
                    if (Build.VERSION.SDK_INT >= 30) {
                        val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                        view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                    } else {
                        val cutout = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout else null
                        view.setPadding(
                            maxOf(insets.systemWindowInsetLeft, cutout?.safeInsetLeft ?: 0),
                            maxOf(insets.systemWindowInsetTop, cutout?.safeInsetTop ?: 0),
                            maxOf(insets.systemWindowInsetRight, cutout?.safeInsetRight ?: 0),
                            maxOf(insets.systemWindowInsetBottom, cutout?.safeInsetBottom ?: 0),
                        )
                    }
                    insets
                }
            }
        dashboard = DashboardView(this, model)
        root.addView(dashboard, FrameLayout.LayoutParams(-1, -1))
        val authorLink =
            Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = "@dani_ruiz24"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                isAllCaps = false
                contentDescription = tr("@dani_ruiz24 en Instagram", "@dani_ruiz24 on Instagram")
                setTextColor(Color.rgb(76, 159, 255))
                setPadding(dp(8), 0, dp(8), 0)
                minWidth = 0
                minimumWidth = 0
                setOnClickListener { openLink("https://www.instagram.com/dani_ruiz24/") }
                addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                    dashboard.footerLinkWidthPx = view.width.toFloat()
                    dashboard.footerControlHeightPx = view.height.toFloat()
                }
            }
        val controlSize = dp(DashboardLayout.FOOTER_CONTROL_DP)
        val controlGap = dp(DashboardLayout.FOOTER_CONTROL_GAP_DP)
        val controlHeight = maxOf(controlSize, kotlin.math.ceil(authorLink.paint.fontSpacing + dp(8)).toInt())
        val indicatorHeight = dp(DashboardLayout.INDICATOR_HEIGHT_DP)
        root.addView(
            authorLink,
            FrameLayout.LayoutParams(-2, controlHeight, Gravity.BOTTOM or Gravity.RIGHT).apply {
                rightMargin = controlSize + controlGap
                bottomMargin = indicatorHeight
            },
        )
        val menuButton =
            ImageButton(this).apply {
                setImageResource(R.drawable.ic_settings)
                setBackgroundResource(R.drawable.dashboard_options_background)
                contentDescription = tr("Menú de acciones", "Actions menu")
                tooltipText = contentDescription
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setOnClickListener { showActions(this) }
            }
        root.addView(
            menuButton,
            FrameLayout.LayoutParams(controlSize, controlHeight, Gravity.BOTTOM or Gravity.RIGHT).apply {
                bottomMargin = indicatorHeight
            },
        )
        return root
    }

    private fun showActions(anchor: View) {
        val popup = PopupMenu(this, anchor, Gravity.RIGHT)
        val actions =
            listOf<Pair<String, () -> Unit>>(
                tr("Adaptadores OBD", "OBD adapters") to { selectDevice() },
                tr("Conectar", "Connect") to { connect() },
                "ECU tune" to { openLink("https://m111kompressor.drasite.com/") },
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

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            model.notice(tr("No hay una aplicación disponible para abrir el enlace", "No app is available to open this link"))
        }
    }

    private var faultDialog: Dialog? = null
    private var correctionsDialog: Dialog? = null
    private var logsDialog: Dialog? = null
    private var adaptersDialog: AdaptersDialog? = null
    private var adaptersWindow: Dialog? = null

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

    private fun permission(action: BluetoothAction): Boolean {
        if (permitted()) return true
        pendingBluetoothAction = action
        requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == ADAPTER_PERMISSIONS_REQUEST) {
            // The chooser checks the actual grants, including denial/cancellation.
            completeAdapterRequest(pendingAdapterPermissions)
        } else if (requestCode == 2) {
            val pending = pendingNotificationStart
            pendingNotificationStart = null
            // Declining notifications does not prevent an explicitly started OBD session.
            pending?.let { (demo, address, automatic) -> start(demo, address, automatic) }
        } else if (requestCode == 1) {
            val pending = pendingBluetoothAction
            pendingBluetoothAction = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                when (pending) {
                    BluetoothAction.CONNECT -> connect()
                    BluetoothAction.AUTO_CONNECT -> autoConnect()
                    null -> Unit
                }
            } else model.notice(tr("Permiso Dispositivos cercanos denegado", "Nearby devices permission denied"))
        }
    }

    @SuppressLint("MissingPermission")
    private fun selectDevice() {
        if (adaptersWindow?.isShowing == true) return
        lateinit var chooser: AdaptersDialog
        fun current() = adaptersDialog === chooser && adaptersWindow?.isShowing == true &&
            !isFinishing && !isDestroyed
        chooser = AdaptersDialog(
            activity = this,
            selectedAddress = prefs.getString("address", null),
            isSessionActive = { runtime.active },
            requestPermissions = { requested, done ->
                requestAdapterPermissions(requested) { if (current()) done() }
            },
            requestEnable = { done ->
                requestBluetoothEnable { if (current()) done() }
            },
            onSelected = { device ->
                try {
                    val address = device.address
                    val name = device.name ?: address
                    prefs
                        .edit()
                        .putString("address", address)
                        .putString("name", name)
                        .apply()
                    stop()
                    pendingAdapterAddress = address
                    model.notice(tr("Conectando a $name…", "Connecting to $name…"))
                } catch (_: SecurityException) {
                    model.notice(tr("Permiso Bluetooth no disponible", "Bluetooth permission unavailable"))
                }
            },
        )
        adaptersDialog = chooser
        adaptersWindow = chooser.show()
    }

    private fun requestAdapterPermissions(requested: Array<String>, done: () -> Unit) {
        val missing = requested.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            done()
            return
        }
        // A reopened chooser must also hear the result of an existing OS prompt.
        val alreadyPending = pendingAdapterPermissions.isNotEmpty()
        pendingAdapterPermissions.add(done)
        if (alreadyPending) return
        try {
            requestPermissions(missing.toTypedArray(), ADAPTER_PERMISSIONS_REQUEST)
        } catch (e: RuntimeException) {
            model.notice(tr("No se pudieron solicitar permisos Bluetooth: ${e.message}", "Could not request Bluetooth permissions: ${e.message}"))
            completeAdapterRequest(pendingAdapterPermissions)
        }
    }

    private fun completeAdapterRequest(callbacks: MutableList<() -> Unit>) {
        val waiting = callbacks.toList()
        callbacks.clear()
        waiting.forEach { it() }
    }

    @SuppressLint("MissingPermission")
    private fun requestBluetoothEnable(done: () -> Unit) {
        val alreadyPending = pendingBluetoothEnable.isNotEmpty()
        pendingBluetoothEnable.add(done)
        if (alreadyPending) return
        try {
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), ENABLE_BLUETOOTH_REQUEST)
        } catch (e: RuntimeException) {
            model.notice(tr("No se pudo solicitar activar Bluetooth: ${e.message}", "Could not request enabling Bluetooth: ${e.message}"))
            completeAdapterRequest(pendingBluetoothEnable)
        }
    }

    private fun connect() {
        if (!permission(BluetoothAction.CONNECT)) return
        val address = prefs.getString("address", null)
        if (address == null) {
            selectDevice()
            return
        }
        start(false, address)
    }

    private fun autoConnect() {
        if (!prefs.getBoolean("auto_connect", false) || runtime.active || exporting) return
        val address = prefs.getString("last_connected_address", null)
        if (address == null) {
            model.notice(tr("Conecta una vez al OBD para recordar la conexión automática", "Connect to an OBD once to remember it for automatic connection"))
            return
        }
        if (!permission(BluetoothAction.AUTO_CONNECT)) return
        start(false, address, automatic = true)
    }

    private fun start(demo: Boolean, address: String?, automatic: Boolean = false) {
        pendingAdapterAddress = null
        if (exporting) {
            model.notice(tr("Espera a que termine la exportación", "Wait for the export to finish"))
            return
        }
        if (runtime.active) {
            model.notice(tr("Detén la sesión actual antes de iniciar otra", "Stop the current session before starting another"))
            return
        }
        if (pendingNotificationStart != null) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("notification_permission_asked", false)) {
            pendingNotificationStart = PendingStart(demo, address, automatic)
            prefs.edit().putBoolean("notification_permission_asked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            return
        }
        if (runtime.start(demo, address, automatic)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            wasRunning = true
        }
    }

    private fun stop() {
        pendingNotificationStart = null
        pendingBluetoothAction = null
        pendingAdapterAddress = null
        val active = runtime.active
        runtime.stop()
        cancelSound()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (active) model.notice(tr("Deteniendo sesión…", "Stopping session…")) else model.stop()
    }

    private fun cancelSound() = runtime.alerts.cancel()

    private fun settings() {
        val names =
            arrayOf(
                tr("Sonido: retardo > 3° o temperatura alta", "Sound: retard > 3° or high temperature"),
                tr("Pantalla completa", "Fullscreen"),
                tr("Optimizar tiempos del adaptador (próxima conexión)", "Optimize adapter timings (next connection)"),
                tr("Conectar al último OBD al abrir (3 intentos)", "Connect to the last OBD on launch (3 attempts)"),
            )
        val keys = arrayOf("sound", "fullscreen", "adapter_tuning", "auto_connect")
        val values = BooleanArray(keys.size) { prefs.getBoolean(keys[it], keys[it] != "auto_connect") }
        AlertDialog.Builder(this)
            .setTitle(tr("Ajustes · MercedesOBD", "Settings · MercedesOBD"))
            .setMultiChoiceItems(names, values) { _, i, value -> values[i] = value }
            .setPositiveButton(tr("Guardar", "Save")) { _, _ ->
                val edit = prefs.edit().remove("flash")
                keys.forEachIndexed { i, k -> edit.putBoolean(k, values[i]) }
                edit.apply()
                applyFullscreen()
                if (values[0] && runtime.active) runtime.alerts.prepare()
                if (!values[0]) cancelSound()
                model.notice(tr("Ajustes guardados", "Settings saved"))
            }
            .setNegativeButton(tr("Cancelar", "Cancel"), null)
            .show()
    }

    private fun logsAvailable(): Boolean {
        if (runtime.active) {
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
        if (requestCode == ENABLE_BLUETOOTH_REQUEST) {
            completeAdapterRequest(pendingBluetoothEnable)
            return
        }
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
        pendingNotificationStart?.let { (demo, address, automatic) ->
            outState.putBoolean("pending_notification_demo", demo)
            outState.putString("pending_notification_address", address)
            outState.putBoolean("pending_notification_automatic", automatic)
        }
        pendingBluetoothAction?.let { outState.putString("pending_bluetooth_action", it.name) }
        pendingExport?.let { outState.putStringArrayList("pending_export", arrayListOf(it.parentFile!!.name, it.name)) }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        applyFullscreen()
        if (runtime.active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        adaptersDialog?.refresh()
        handler.post(ticker)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        pendingAdapterPermissions.clear()
        pendingBluetoothEnable.clear()
        adaptersDialog?.dismiss()
        adaptersDialog = null
        adaptersWindow = null
        faultDialog?.dismiss()
        correctionsDialog?.dismiss()
        logsDialog?.dismiss()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
