package com.drasite.mercedesobd

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import java.text.DateFormat
import java.util.Date

internal class FaultsDialog(
    private val activity: Activity,
    private val session: Session,
    demo: Boolean,
) {
    private val description = if (demo) tr("DEMO · datos simulados", "DEMO · simulated data") else tr("Memoria de averías del motor", "Engine fault memory")
    private val panel = DialogPanel(activity, tr("Errores de la ECU", "ECU fault codes"), description)
    private val handler = Handler(Looper.getMainLooper())
    private var busy = false
    private var confirmedClear = false
    private var confirmation: AlertDialog? = null
    private val read = panel.button(R.drawable.ic_refresh, tr("Actualizar", "Refresh"), DialogPanel.CYAN) { request(false) }
    private val clear = panel.button(R.drawable.ic_delete, tr("Borrar errores", "Clear faults"), DialogPanel.RED) { confirmClear() }

    init {
        panel.actions.addView(read, LinearLayout.LayoutParams(0, panel.dp(48), 1f).apply { marginEnd = panel.dp(8) })
        panel.actions.addView(clear, LinearLayout.LayoutParams(0, panel.dp(48), 1f))
        clear.available(false)
        panel.dialog.setOnShowListener { request(false) }
        panel.dialog.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            confirmation?.dismiss()
        }
    }

    fun show(): Dialog = panel.show()

    private fun live() = panel.dialog.isShowing && !activity.isFinishing && !activity.isDestroyed

    private fun status(title: String, detail: String, color: Int, loading: Boolean = false) {
        panel.content.removeAllViews()
        val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        if (loading) row.addView(ProgressBar(activity).apply {
            indeterminateTintList = ColorStateList.valueOf(DialogPanel.CYAN)
        }, LinearLayout.LayoutParams(panel.dp(24), panel.dp(24)).apply { marginEnd = panel.dp(12) })
        row.addView(panel.text(title, 18f, color, true), LinearLayout.LayoutParams(0, -2, 1f))
        panel.content.addView(row)
        val scroll = ScrollView(activity).apply {
            addView(panel.text(detail, 15f, DialogPanel.MUTED).apply { setPadding(0, panel.dp(12), 0, 0) })
        }
        panel.content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun showCodes(codes: List<FaultCode>) {
        panel.subtitle.text = description + " · " + DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date())
        clear.available(codes.isNotEmpty())
        if (codes.isEmpty()) {
            status(tr("Sin errores almacenados", "No stored faults"),
                if (confirmedClear) tr("El borrado se ha completado y la ECU ya no devuelve errores.", "Clearing is complete and the ECU reports no faults.")
                else tr("La ECU no ha devuelto códigos de error.", "The ECU returned no fault codes."), DialogPanel.GREEN)
            return
        }
        panel.content.removeAllViews()
        val count = if (codes.size == 1) tr("1 error almacenado", "1 stored fault") else tr("${codes.size} errores almacenados", "${codes.size} stored faults")
        panel.content.addView(panel.text(count, 17f, DialogPanel.AMBER, true))
        if (confirmedClear) panel.content.addView(panel.text(tr("La ECU sigue indicando estos errores tras el borrado.", "The ECU still reports these faults after clearing."), 13f, DialogPanel.MUTED))
        val rows = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, panel.dp(10), 0, 0)
        }
        codes.forEach { code ->
            val row = LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(panel.dp(14), panel.dp(10), panel.dp(14), panel.dp(10))
                background = panel.background(Color.rgb(21, 38, 58))
            }
            row.addView(panel.text(code.code, 23f, DialogPanel.AMBER, true), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(panel.text(tr("Estado ECU", "ECU status") + " 0x%02X".format(code.status), 14f, DialogPanel.MUTED))
            rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = panel.dp(8) })
        }
        panel.content.addView(ScrollView(activity).apply { addView(rows) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun request(clearRequest: Boolean, checkingClear: Boolean = false) {
        if (busy || !live()) return
        busy = true
        if (!checkingClear) confirmedClear = false
        read.available(false)
        clear.available(false)
        status(if (clearRequest) tr("Borrando errores…", "Clearing faults…") else tr("Leyendo errores…", "Reading faults…"),
            if (checkingClear) tr("Borrado aceptado. Comprobando la memoria de averías.", "Clear request accepted. Checking fault memory.")
            else tr("Esperando respuesta de la ECU", "Waiting for the ECU response"), DialogPanel.CYAN, true)
        val accepted = session.faults(clearRequest) { result ->
            handler.post {
                if (!live()) return@post
                busy = false
                read.available(true)
                result.fold(
                    onSuccess = { codes ->
                        if (clearRequest) {
                            confirmedClear = true
                            busy = true
                            read.available(false)
                            status(tr("Borrado aceptado", "Clear request accepted"),
                                tr("Comprobando si quedan errores…", "Checking for remaining faults…"), DialogPanel.GREEN, true)
                            // The ECU applies its erase flag on the next processing cycle.
                            handler.postDelayed({ busy = false; request(false, checkingClear = true) }, 250)
                        } else showCodes(codes)
                    },
                    onFailure = { error ->
                        status(if (clearRequest) tr("No se pudo confirmar el borrado", "Could not confirm clearing")
                            else tr("No se pudieron leer los errores", "Could not read faults"),
                            (if (confirmedClear) tr("El borrado fue aceptado, pero la comprobación ha fallado.\n\n", "Clearing was accepted, but verification failed.\n\n") else "") +
                                (error.message ?: error.toString()), DialogPanel.RED)
                    },
                )
            }
        }
        if (!accepted) {
            busy = false
            read.available(true)
            status(tr("ECU no disponible", "ECU unavailable"),
                tr("Conecta la ECU o inicia Demo. Si la conexión está arrancando, espera a recibir datos y pulsa Actualizar.",
                    "Connect the ECU or start Demo. If the connection is starting, wait for live data and tap Refresh."), DialogPanel.AMBER)
        }
    }

    private fun confirmClear() {
        if (busy || !live()) return
        confirmation = AlertDialog.Builder(activity)
            .setTitle(tr("¿Borrar los errores de la ECU?", "Clear ECU faults?"))
            .setMessage(tr("Se borrarán los errores y sus datos de diagnóstico. Motor parado y contacto puesto.",
                "Faults and their diagnostic data will be erased. Engine stopped, ignition on."))
            .setPositiveButton(tr("Borrar", "Clear")) { _, _ -> request(true) }
            .setNegativeButton(tr("Cancelar", "Cancel"), null).show()
    }
}
