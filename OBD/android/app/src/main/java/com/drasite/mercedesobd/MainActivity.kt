package com.drasite.mercedesobd

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : Activity() {
    private val model = Model()
    private lateinit var session: Session
    private lateinit var dashboard: DashboardView
    private val handler = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    private var soundGeneration = 0
    private var nextBurst = 0L
    private var resumed = false
    private var wasRunning = false
    private var exporting = false
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
                if (resumed) handler.postDelayed(this, 100)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = Session(File(filesDir, "logs"), model) { handler.post { beepBurst() } }
        setContentView(createContentView())
        if (intent.getBooleanExtra("demo", false)) start(true, null)
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
                contentDescription = "Menú de acciones"
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
        val deviceName = prefs.getString("name", "Adaptador") ?: "Adaptador"
        val actions =
            listOf<Pair<String, () -> Unit>>(
                "Adaptador: $deviceName" to { selectDevice() },
                "Conectar" to { connect() },
                "Demo" to { start(true, null) },
                "Parar" to { stop() },
                "Exportar" to { export() },
                "Ajustes" to { settings() },
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
            } else model.notice("Permiso Dispositivos cercanos denegado")
        }
    }

    @SuppressLint("MissingPermission")
    private fun selectDevice() {
        if (!permission(false)) return
        try {
            val adapter =
                BluetoothAdapter.getDefaultAdapter()
                    ?: throw IllegalStateException("Este dispositivo no tiene Bluetooth")
            if (!adapter.isEnabled) {
                model.notice("Activa Bluetooth y empareja el adaptador en Ajustes")
                return
            }
            val devices = adapter.bondedDevices.sortedBy { it.name ?: it.address }
            if (devices.isEmpty()) {
                model.notice("Empareja el OBDLink/ELM en los ajustes Bluetooth de Android")
                return
            }
            AlertDialog.Builder(this)
                .setTitle("Adaptadores ya emparejados")
                .setItems(
                    devices.map { "${it.name?:"Bluetooth"}\n${it.address}" }.toTypedArray()
                ) { _, i ->
                    val d = devices[i]
                    prefs
                        .edit()
                        .putString("address", d.address)
                        .putString("name", d.name ?: d.address)
                        .apply()
                    model.notice("Seleccionado ${d.name?:d.address}")
                }
                .setNegativeButton("Cerrar", null)
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
        if (exporting) {
            model.notice("Espera a que termine la exportación")
            return
        }
        if (
            session.start(
                demo,
                address,
                prefs.getBoolean("tuning", true),
                prefs.getBoolean("conservative", false),
            )
        ) {
            cancelSound()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            wasRunning = true
        } else model.notice("Detén la sesión actual antes de iniciar otra")
    }

    private fun stop() {
        val active = session.running
        session.stop()
        cancelSound()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (active) model.notice("Deteniendo sesión…") else model.stop()
    }

    private fun beepBurst() {
        if (!prefs.getBoolean("sound", true) || !session.running || !resumed) return
        if (tone == null)
            try {
                tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
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
                        gen == soundGeneration && session.running && prefs.getBoolean("sound", true)
                    )
                        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 65)
                },
                start + i * 130,
            )
        }
    }

    private fun cancelSound() {
        soundGeneration++
        nextBurst = 0
        tone?.stopTone()
    }

    private fun settings() {
        val names =
            arrayOf(
                "Seis pitidos por aumento observado",
                "Parpadeo rojo durante 3 segundos",
                "Optimización medida OBDLink/STN",
                "Modo conservador ELM",
            )
        val keys = arrayOf("sound", "flash", "tuning", "conservative")
        val values = BooleanArray(4) { prefs.getBoolean(keys[it], it != 3) }
        AlertDialog.Builder(this)
            .setTitle("Ajustes · ME2.1 SW404")
            .setMultiChoiceItems(names, values) { _, i, value -> values[i] = value }
            .setPositiveButton("Guardar") { _, _ ->
                val edit = prefs.edit()
                keys.forEachIndexed { i, k -> edit.putBoolean(k, values[i]) }
                edit.apply()
                dashboard.flashEnabled = values[1]
                if (!values[0]) cancelSound()
                model.notice("Guardado · ajustes de conexión en la próxima sesión")
            }
            .setNeutralButton("Información") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Interpretación de lecturas")
                    .setMessage(
                        "Duty y tiempo F746 usan 2,6667 µs/unidad y una inyección por 720°: estimados. Voltaje ECU usa 0,09375 V/unidad: provisional. La sonda estrecha indica voltaje, no AFR. El contador registra aumentos observados, no un contador de detonaciones validado.\n\nLa sesión se detiene al salir de primer plano."
                    )
                    .setPositiveButton("Cerrar", null)
                    .show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun export() {
        if (session.running) {
            model.notice("Pulsa Parar y espera al cierre antes de exportar")
            return
        }
        if (exporting) return
        exporting = true
        model.notice("Preparando ZIP de registros…")
        Thread {
                try {
                    val files =
                        File(filesDir, "logs").listFiles()?.filter {
                            it.extension in listOf("csv", "jsonl")
                        } ?: emptyList()
                    require(files.isNotEmpty()) { "Todavía no hay registros" }
                    ZipOutputStream(File(cacheDir, "me21_logs.zip").outputStream()).use { z ->
                        files.forEach { f ->
                            z.putNextEntry(ZipEntry(f.name))
                            f.inputStream().use { it.copyTo(z) }
                            z.closeEntry()
                        }
                    }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) {
                            exporting = false
                            return@runOnUiThread
                        }
                        val intent =
                            Intent(Intent.ACTION_CREATE_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE)
                                .setType("application/zip")
                                .putExtra(Intent.EXTRA_TITLE, "me21_logs.zip")
                        try {
                            startActivityForResult(intent, 404)
                        } catch (e: Exception) {
                            exporting = false
                            model.notice("No se pudo abrir el selector: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        exporting = false
                        model.notice("Exportación: ${e.message}")
                    }
                }
            }
            .start()
    }

    @Deprecated("Platform activity result used to avoid extra dependencies")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 404) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            exporting = false
            model.notice("Exportación cancelada")
            return
        }
        Thread {
                try {
                    contentResolver.openOutputStream(uri, "wt").use { out ->
                        requireNotNull(out)
                        File(cacheDir, "me21_logs.zip").inputStream().use { it.copyTo(out) }
                    }
                    model.notice("Registros exportados")
                } catch (e: Exception) {
                    model.notice("No se pudo exportar: ${e.message}")
                } finally {
                    runOnUiThread { exporting = false }
                }
            }
            .start()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        handler.post(ticker)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onStop() {
        if (session.running) stop()
        super.onStop()
    }

    override fun onDestroy() {
        session.stop()
        handler.removeCallbacksAndMessages(null)
        tone?.release()
        tone = null
        super.onDestroy()
    }
}
