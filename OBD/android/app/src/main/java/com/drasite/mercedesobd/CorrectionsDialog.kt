package com.drasite.mercedesobd

import android.app.Activity
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

internal class CorrectionsDialog(
    private val activity: Activity,
    private val session: Session,
    demo: Boolean,
) {
    private val description = if (demo) tr("DEMO · datos simulados", "DEMO · simulated data")
        else tr("Adaptaciones aprendidas · lectura puntual", "Learned adaptations · snapshot")
    private val panel = DialogPanel(activity, tr("Lectura de correcciones", "Correction readings"), description)
    private val handler = Handler(Looper.getMainLooper())
    private var busy = false
    @Volatile private var active = false
    private val refresh = panel.button(R.drawable.ic_refresh, tr("Actualizar", "Refresh"), DialogPanel.CYAN) { request() }

    init {
        panel.actions.addView(panel.text(tr("Sin lecturas periódicas", "No periodic reads"), 13f, DialogPanel.MUTED),
            LinearLayout.LayoutParams(0, -2, 1f))
        panel.actions.addView(refresh, LinearLayout.LayoutParams(-2, panel.dp(48)))
        panel.dialog.setOnShowListener { active = true; request() }
        panel.dialog.setOnDismissListener {
            active = false
            handler.removeCallbacksAndMessages(null)
        }
    }

    fun show(): Dialog = panel.show()

    private fun live() = active && panel.dialog.isShowing && !activity.isFinishing && !activity.isDestroyed

    private fun status(message: String, color: Int, loading: Boolean = false) {
        panel.content.removeAllViews()
        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        if (loading) rows.addView(ProgressBar(activity).apply {
            indeterminateTintList = ColorStateList.valueOf(DialogPanel.CYAN)
        }, LinearLayout.LayoutParams(panel.dp(28), panel.dp(28)).apply { bottomMargin = panel.dp(12) })
        rows.addView(panel.text(message, 16f, color))
        panel.content.addView(ScrollView(activity).apply { addView(rows) }, LinearLayout.LayoutParams(-1, -1))
    }

    private fun showResult(value: FuelCorrections) {
        panel.subtitle.text = description + " · " + DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date())
        panel.content.removeAllViews()
        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun row(title: String, reading: String, detail: String? = null) {
            val card = LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(panel.dp(14), panel.dp(10), panel.dp(14), panel.dp(10))
                background = panel.background(Color.rgb(21, 38, 58))
            }
            card.addView(panel.text(title, 15f, DialogPanel.MUTED), LinearLayout.LayoutParams(0, -2, 1f))
            val values = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                addView(panel.text(reading, 21f, DialogPanel.GREEN, true))
                if (detail != null) addView(panel.text(detail, 12f, DialogPanel.MUTED))
            }
            card.addView(values)
            rows.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = panel.dp(8) })
        }
        row("LTFT low", String.format(I18n.locale, "%+.2f %%", Decoder.trim(value.low)))
        row("LTFT high", String.format(I18n.locale, "%+.2f %%", Decoder.trim(value.high)))
        row(tr("Corrección aditiva", "Additive correction"), String.format(I18n.locale, "~%+.3f ms", value.additiveMs),
            String.format(I18n.locale, "%+d", value.additiveSigned) + tr(" unidades ECU", " ECU units"))
        rows.addView(panel.text(tr("Tiempo de la aditiva estimado. Actualiza para volver a consultar.",
            "Additive duration is estimated. Refresh to read again."), 13f, DialogPanel.MUTED))
        panel.content.addView(ScrollView(activity).apply { addView(rows) }, LinearLayout.LayoutParams(-1, -1))
    }

    private fun request() {
        if (busy || !live()) return
        busy = true
        refresh.available(false)
        panel.subtitle.text = description
        status(tr("Leyendo LTFT low, high y corrección aditiva…", "Reading LTFT low, high and additive correction…"), DialogPanel.CYAN, true)
        val accepted = session.corrections({ active }) { result ->
            handler.post {
                if (!live()) return@post
                busy = false
                refresh.available(true)
                result.fold(onSuccess = ::showResult, onFailure = {
                    status(tr("No se pudieron leer las correcciones.\n\n", "Could not read corrections.\n\n") +
                        (it.message ?: it.toString()), DialogPanel.RED)
                })
            }
        }
        if (!accepted) {
            busy = false
            refresh.available(true)
            status(tr("Conecta la ECU o inicia Demo. Espera a recibir datos y pulsa Actualizar. Si hay otra consulta en curso, espera a que termine.",
                "Connect the ECU or start Demo. Wait for live data and tap Refresh. If another request is running, wait for it to finish."), DialogPanel.AMBER)
        }
    }
}
