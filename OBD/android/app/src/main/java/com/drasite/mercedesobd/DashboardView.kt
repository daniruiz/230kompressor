package com.drasite.mercedesobd

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import java.util.Locale
import kotlin.math.abs

/** Native Canvas dashboard: vector graphics, no terminal or web/runtime layer. */
class DashboardView(context: Context, private val model: Model) : View(context) {
    var flashEnabled = true
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cyan = Color.rgb(87, 216, 237)
    private val amber = Color.rgb(255, 210, 122)
    private val green = Color.rgb(69, 237, 203)
    private val purple = Color.rgb(135, 56, 252)
    private val red = Color.rgb(255, 34, 34)
    private val muted = Color.rgb(154, 174, 200)
    private val path = Path()
    private val regularFont = resources.getFont(R.font.cascadia_regular)
    private val boldFont = resources.getFont(R.font.cascadia_bold)
    private var canvasScaleX = 1f
    private var canvasScaleY = 1f
    private var textScale = 1f
    private var textTransform = CanvasTextTransform(1f, 1f, 1f)
    private val menuSizePx = 48 * resources.displayMetrics.density

    init {
        contentDescription =
            "MercedesOBD: RPM, carga, inyección, correcciones y mezcla"
        isFocusable = true
    }

    private fun formatValue(pattern: String, vararg values: Any) =
        String.format(Locale.ROOT, pattern, *values)

    private fun drawText(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int = muted,
        bold: Boolean = false,
        width: Float = Float.MAX_VALUE,
        align: Paint.Align = Paint.Align.LEFT,
    ) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = size
        paint.typeface = if (bold) boldFont else regularFont
        paint.textAlign = align
        // Anchor positions follow the responsive grid; glyphs keep equal X/Y scales.
        val availableWidth = textTransform.availableWidth(width)
        val measuredWidth = paint.measureText(value)
        if (measuredWidth > availableWidth) paint.textSize = size * availableWidth / measuredWidth
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(textTransform.correctionX, textTransform.correctionY)
        canvas.drawText(value, 0f, 0f, paint)
        canvas.restore()
    }

    private fun drawCard(
        canvas: Canvas,
        bounds: RectF,
        title: String,
        color: Int,
        flash: Boolean = false,
    ) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = if (flash) Color.rgb(105, 8, 25) else Color.rgb(7, 14, 23)
        canvas.drawRoundRect(bounds, 12f, 12f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f
        paint.color = if (flash) red else color
        canvas.drawRoundRect(bounds, 12f, 12f, paint)
        drawText(
            canvas,
            title,
            bounds.centerX(),
            bounds.top + 24,
            12f,
            color,
            true,
            bounds.width() - 24,
            Paint.Align.CENTER,
        )
    }

    private fun gradientPeak(color: Int) =
        when (color) {
            cyan -> Color.rgb(52, 135, 255)
            amber -> Color.rgb(255, 123, 36)
            green -> Color.rgb(2, 215, 250)
            purple -> Color.rgb(66, 232, 255)
            else -> Color.rgb(255, 117, 64)
        }

    private fun drawMeter(canvas: Canvas, bounds: RectF, fraction: Double, color: Int) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(26, 37, 51)
        canvas.drawRoundRect(bounds, 2f, 2f, paint)
        paint.shader =
            LinearGradient(
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.top,
                color,
                gradientPeak(color),
                Shader.TileMode.CLAMP,
            )
        canvas.drawRect(
            bounds.left,
            bounds.top,
            bounds.left + bounds.width() * fraction.coerceIn(0.0, 1.0).toFloat(),
            bounds.bottom,
            paint,
        )
        paint.shader = null
    }

    private fun drawChart(
        canvas: Canvas,
        bounds: RectF,
        values: DoubleArray,
        ceiling: Double,
        color: Int,
    ) {
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = .6f
        paint.color = Color.rgb(36, 49, 65)
        for (i in 1..3) canvas.drawLine(
            bounds.left,
            bounds.top + bounds.height() * i / 4,
            bounds.right,
            bounds.top + bounds.height() * i / 4,
            paint,
        )
        if (values.isEmpty()) return
        fun y(v: Double) =
            bounds.bottom -
                bounds.height() * (v / ceiling.coerceAtLeast(1.0)).coerceIn(0.0, 1.0).toFloat()
        path.reset()
        path.moveTo(bounds.left, bounds.bottom)
        path.lineTo(bounds.left, y(values.first()))
        if (values.size == 1) path.lineTo(bounds.right, y(values.first()))
        else
            for (i in 1 until values.size) path.lineTo(
                bounds.left + bounds.width() * i / (values.size - 1),
                y(values[i]),
            )
        path.lineTo(bounds.right, bounds.bottom)
        path.close()
        paint.style = Paint.Style.FILL
        paint.shader =
            LinearGradient(
                0f,
                bounds.bottom,
                0f,
                bounds.top,
                color,
                gradientPeak(color),
                Shader.TileMode.CLAMP,
            )
        paint.alpha = 130
        canvas.drawPath(path, paint)
        paint.alpha = 255
        path.reset()
        path.moveTo(bounds.left, y(values.first()))
        if (values.size == 1) path.lineTo(bounds.right, y(values.first()))
        else
            for (i in 1 until values.size) path.lineTo(
                bounds.left + bounds.width() * i / (values.size - 1),
                y(values[i]),
            )
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawPath(path, paint)
        paint.shader = null
    }

    private fun drawMetric(
        canvas: Canvas,
        bounds: RectF,
        title: String,
        value: String,
        detail: String,
        trace: DoubleArray,
        ceiling: Double,
        fraction: Double,
        color: Int,
    ) {
        drawCard(canvas, bounds, title, color)
        drawText(
            canvas,
            value,
            bounds.centerX(),
            bounds.top + 72,
            34f,
            color,
            true,
            bounds.width() - 24,
            Paint.Align.CENTER,
        )
        drawText(
            canvas,
            detail,
            bounds.centerX(),
            bounds.top + 100,
            13f,
            muted,
            false,
            bounds.width() - 20,
            Paint.Align.CENTER,
        )
        drawMeter(
            canvas,
            RectF(bounds.left + 12, bounds.top + 113, bounds.right - 12, bounds.top + 119),
            fraction,
            color,
        )
        drawChart(
            canvas,
            RectF(bounds.left + 12, bounds.top + 132, bounds.right - 12, bounds.bottom - 12),
            trace,
            ceiling,
            color,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val layout = DashboardLayout(width.toFloat(), height.toFloat(), menuSizePx)
        canvasScaleX = layout.scaleX
        canvasScaleY = layout.contentScaleY
        textScale = minOf(canvasScaleX, canvasScaleY)
        textTransform = CanvasTextTransform(canvasScaleX, canvasScaleY, textScale)
        canvas.drawColor(Color.rgb(3, 6, 12))
        val display = model.snapshot(760)
        val now = monoMs()
        val sample = display.sample
        val valid = sample != null && !display.failed && now - sample.at <= SAMPLE_MAX_AGE_MS

        canvas.save()
        canvas.scale(canvasScaleX, canvasScaleY)
        drawMetrics(canvas, display, sample, valid)
        drawKnock(canvas, display, sample, valid, now)
        drawMixture(canvas, display, sample, valid, now)
        drawTemperaturesAndAdvance(canvas, sample, valid)
        canvas.restore()

        canvas.save()
        canvas.translate(0f, layout.contentHeight)
        canvasScaleY = layout.footerHeight / DashboardLayout.FOOTER_HEIGHT
        textTransform = CanvasTextTransform(canvasScaleX, canvasScaleY, textScale)
        canvas.scale(canvasScaleX, canvasScaleY)
        drawFooter(canvas, display, sample, valid, layout)
        canvas.restore()
    }

    private fun drawMetrics(canvas: Canvas, display: Display, sample: Sample?, valid: Boolean) {
        val rpm = if (valid) sample!!.rpm else 0
        val load = if (valid) sample!!.load else 0
        val duty = if (valid) sample!!.duty else 0.0
        drawMetric(
            canvas,
            RectF(8f, 8f, 397f, 264f),
            "RÉGIMEN",
            if (valid) "%,d rpm".format(Locale.GERMANY, rpm) else "—",
            "0–6.500 rpm",
            display.rpm,
            6500.0,
            rpm / 6500.0,
            cyan,
        )
        drawMetric(
            canvas,
            RectF(405f, 8f, 794f, 264f),
            "CARGA INTERNA",
            if (valid) "$load" else "—",
            if (valid) formatValue("Mariposa %.1f %%", sample!!.throttle * 100.0 / 255)
            else "Mariposa —",
            display.load,
            255.0,
            load / 255.0,
            amber,
        )
        val injColor = if (duty > 100) red else if (duty >= 90) amber else green
        drawMetric(
            canvas,
            RectF(802f, 8f, 1192f, 264f),
            "INYECCIÓN / DUTY EST.",
            if (valid) formatValue("~%.1f %%", duty) else "—",
            if (valid) formatValue("~%.2f ms · %d raw", sample!!.injectionMs, sample.injection)
            else "F746 sin lectura actual",
            display.duty,
            maxOf(100.0, display.duty.maxOrNull() ?: 100.0),
            duty / 100,
            injColor,
        )
    }

    private fun drawKnock(
        canvas: Canvas,
        display: Display,
        sample: Sample?,
        valid: Boolean,
        now: Long,
    ) {
        val flash = flashEnabled && now < display.alertUntil && (now / 250) % 2 == 0L
        val kr = RectF(8f, 272f, 795f, 621f)
        drawCard(canvas, kr, "CONTROL DE PICADO", purple, flash)
        drawText(
            canvas,
            if (valid) formatValue("Retardo %.2f°", sample!!.retard) else "Retardo —",
            340f,
            339f,
            27f,
            purple,
            true,
            560f,
            Paint.Align.CENTER,
        )
        if (display.count > 0) {
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = red
            canvas.drawRoundRect(RectF(673f, 308f, 782f, 353f), 4f, 4f, paint)
            drawText(
                canvas,
                "%04d".format(display.count),
                728f,
                340f,
                29f,
                red,
                true,
                95f,
                Paint.Align.CENTER,
            )
        }
        val channels =
            if (valid)
                sample!!
                    .corrections
                    .mapIndexed { i, v -> formatValue("C%d  %.2f°", i + 1, v * .75) }
                    .joinToString("       ")
            else "C1 —       C2 —       C3 —       C4 —"
        drawText(canvas, channels, 401f, 377f, 15f, muted, false, 740f, Paint.Align.CENTER)
        drawChart(
            canvas,
            RectF(20f, 392f, 783f, 586f),
            display.knock,
            maxOf(3.0, display.knock.maxOrNull() ?: 3.0),
            purple,
        )
        drawText(
            canvas,
            if (display.lastAlertAt != null)
                formatValue(
                    "Último aviso hace %.1f s · Aumento en canal(es): %s",
                    maxOf(0L, now - display.lastAlertAt) / 1000.0,
                    display.lastAlertChannels.joinToString(","),
                )
            else ""
            401f,
            607f,
            12f,
            muted,
            false,
            740f,
            Paint.Align.CENTER,
        )
    }

    private fun drawMixture(
        canvas: Canvas,
        display: Display,
        sample: Sample?,
        valid: Boolean,
        now: Long,
    ) {
        drawCard(canvas, RectF(803f, 272f, 1192f, 435f), "MEZCLA", green)
        fun row(label: String, value: String, y: Float, color: Int = green) {
            drawText(canvas, label, 816f, y, 14f, muted, false, 145f)
            drawText(canvas, value, 1179f, y, 15f, color, true, 215f, Paint.Align.RIGHT)
        }
        row(
            "STFT / LTFT",
            if (valid)
                formatValue(
                    "%+.2f / %+.2f %%",
                    Decoder.trim(sample!!.stft),
                    Decoder.trim(sample.ltft),
                )
            else "—",
            320f,
        )
        val trimFresh =
            display.trimsAt != null &&
                now - display.trimsAt <= STORED_TRIM_MAX_AGE_MS &&
                display.trimsStatus == "ok"
        row(
            "LTFT low / high",
            if (trimFresh && display.low != null && display.high != null)
                formatValue(
                    "%+.2f / %+.2f %%",
                    Decoder.trim(display.low),
                    Decoder.trim(display.high),
                )
            else "—",
            350f,
        )
        row(
            "Caudalímetro",
            if (valid)
                formatValue(
                    "%s%.1f g/s",
                    if (sample!!.maf == 255) "≥" else "~",
                    sample.maf * Decoder.MAF_SCALE,
                )
            else "—",
            380f,
            cyan,
        )
        row(
            "Sonda λ",
            if (valid)
                formatValue(
                    "%.3f V · %s",
                    Decoder.oxygen(sample!!.oxygen),
                    if (sample.oxygen > 0x2456) "rico" else "pobre",
                )
            else "—",
            410f,
            amber,
        )
    }

    private fun drawTemperaturesAndAdvance(canvas: Canvas, sample: Sample?, valid: Boolean) {
        drawCard(canvas, RectF(803f, 443f, 1192f, 514f), "TEMPERATURAS", amber)
        drawText(
            canvas,
            if (valid) "Admisión ${sample!!.iat-40} °C     Refrigerante ${sample.coolant-40} °C"
            else "Admisión —     Refrigerante —",
            997f,
            496f,
            16f,
            amber,
            true,
            365f,
            Paint.Align.CENTER,
        )
        drawCard(canvas, RectF(803f, 522f, 1192f, 621f), "AVANCE DE ENCENDIDO", cyan)
        val adv = if (valid) Decoder.advance(sample!!.advance) else null
        drawText(
            canvas,
            adv?.let { formatValue("%+.1f°", it) } ?: "—",
            997f,
            576f,
            29f,
            cyan,
            true,
            360f,
            Paint.Align.CENTER,
        )
        drawMeter(canvas, RectF(815f, 584f, 1180f, 590f), (adv?.let { abs(it) } ?: 0.0) / 40, cyan)
        drawText(
            canvas,
            if (adv == null) "Sin lectura"
            else if (adv < 0) "Antes del PMS" else if (adv > 0) "Después del PMS" else "En el PMS",
            997f,
            610f,
            12f,
            muted,
            false,
            360f,
            Paint.Align.CENTER,
        )
    }

    private fun drawFooter(
        canvas: Canvas,
        display: Display,
        sample: Sample?,
        valid: Boolean,
        layout: DashboardLayout,
    ) {
        val footerWidth = layout.footerTextWidth
        val footerCenter = 8f + footerWidth / 2
        val volts =
            if (valid) formatValue("~%.2f V", sample!!.voltage * Decoder.VOLT_SCALE) else "— V"
        val perf =
            formatValue("%.1f Hz", display.hz) +
                " · ${sample?.cycleMs?:"—"} ms/ciclo · ECU $volts · errores ${display.errors} · ${display.mode} ${display.profile}"
        drawText(
            canvas,
            perf,
            footerCenter,
            26f,
            13f,
            muted,
            false,
            footerWidth,
            Paint.Align.CENTER,
        )
        drawText(
            canvas,
            display.status.replace('\r', ' ').replace('\n', ' ').take(180),
            footerCenter,
            49f,
            13f,
            if (valid) green else amber,
            false,
            footerWidth,
            Paint.Align.CENTER,
        )
    }

    private companion object {
        const val SAMPLE_MAX_AGE_MS = 2_000L
        const val STORED_TRIM_MAX_AGE_MS = 35_000L
    }
}
