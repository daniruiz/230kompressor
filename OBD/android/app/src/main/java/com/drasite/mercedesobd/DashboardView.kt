package com.drasite.mercedesobd

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs

/** Native Canvas dashboard: vector graphics, no terminal or web/runtime layer. */
class DashboardView(context: Context, private val model: Model) : View(context) {
    var flashEnabled = true
    private var temperaturesPage = false
    private var pagePosition = 0f
    private var pageAnimator: ValueAnimator? = null
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchStartPosition = 0f
    private var draggingPage = false
    private val swipeDistance = maxOf(
        48 * resources.displayMetrics.density,
        ViewConfiguration.get(context).scaledTouchSlop * 3f,
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pageAnimator?.cancel()
                touchStartX = event.x
                touchStartY = event.y
                touchStartPosition = pagePosition
                draggingPage = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - touchStartX
                val dy = event.y - touchStartY
                if (!draggingPage && abs(dx) >= swipeDistance && abs(dx) > abs(dy) * 1.5f) {
                    draggingPage = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (draggingPage && width > 0) {
                    // Positive finger movement brings temperatures in from the left.
                    pagePosition = (touchStartPosition + dx / width).coerceIn(0f, 1f)
                    postInvalidateOnAnimation()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - touchStartX
                val dy = event.y - touchStartY
                if (abs(dx) >= swipeDistance && abs(dx) > abs(dy) * 1.5f) {
                    // Follow the finger: swipe right for temperatures, left for the main panel.
                    showPage(dx > 0)
                } else {
                    showPage(temperaturesPage)
                    performClick()
                }
                draggingPage = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                draggingPage = false
                parent?.requestDisallowInterceptTouchEvent(false)
                showPage(temperaturesPage)
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun showPage(showTemperatures: Boolean) {
        val targetPosition = if (showTemperatures) 1f else 0f
        if (temperaturesPage == showTemperatures && pagePosition == targetPosition) return
        temperaturesPage = showTemperatures
        contentDescription = if (temperaturesPage)
            tr("MercedesOBD: temperaturas de motor y admisión, control de picado", "MercedesOBD: engine and intake temperatures, knock control")
        else tr("MercedesOBD: RPM, carga, inyección, correcciones y mezcla", "MercedesOBD: RPM, load, injection, corrections and mixture")
        pageAnimator?.cancel()
        pageAnimator = ValueAnimator.ofFloat(pagePosition, targetPosition).apply {
            duration = 280L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                pagePosition = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        pageAnimator?.cancel()
        pageAnimator = null
        pagePosition = if (temperaturesPage) 1f else 0f
        super.onDetachedFromWindow()
    }

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
            tr("MercedesOBD: RPM, carga, inyección, correcciones y mezcla", "MercedesOBD: RPM, load, injection, corrections and mixture")
        isFocusable = true
    }

    private fun formatValue(pattern: String, vararg values: Any) =
        String.format(I18n.locale, pattern, *values)

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
        cornerRadius: Float = 12f,
    ) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = if (flash) Color.rgb(105, 8, 25) else Color.rgb(7, 14, 23)
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f
        paint.color = if (flash) red else color
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, paint)
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
        canvas.clipRect(0f, 0f, width.toFloat(), layout.contentHeight)
        canvas.scale(canvasScaleX, canvasScaleY)
        if (pagePosition < 1f) {
            drawPage(canvas, false, pagePosition * DashboardLayout.WIDTH, display, sample, valid, now)
        }
        if (pagePosition > 0f) {
            drawPage(canvas, true, (pagePosition - 1f) * DashboardLayout.WIDTH, display, sample, valid, now)
        }
        canvas.restore()

        canvas.save()
        canvas.translate(0f, layout.contentHeight)
        canvasScaleY = layout.footerHeight / DashboardLayout.FOOTER_HEIGHT
        textTransform = CanvasTextTransform(canvasScaleX, canvasScaleY, textScale)
        canvas.scale(canvasScaleX, canvasScaleY)
        drawFooter(canvas, display, sample, valid, layout)
        canvas.restore()
        drawPageIndicator(canvas)
    }

    private fun drawPage(
        canvas: Canvas,
        showTemperatures: Boolean,
        offset: Float,
        display: Display,
        sample: Sample?,
        valid: Boolean,
        now: Long,
    ) {
        canvas.save()
        canvas.translate(offset, 0f)
        canvas.clipRect(0f, 0f, DashboardLayout.WIDTH, DashboardLayout.CONTENT_HEIGHT)
        if (showTemperatures) {
            drawTemperaturePage(canvas, display, sample, valid, now)
        } else {
            drawMetrics(canvas, display, sample, valid)
            drawKnock(canvas, display, sample, valid, now)
            drawMixture(canvas, display, sample, valid, now)
            drawTemperaturesAndAdvance(canvas, sample, valid)
        }
        canvas.restore()
    }

    private fun drawPageIndicator(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val radius = 3f * density
        val spacing = 14f * density
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = muted
        // Temperatures sit to the left of the main panel and enter with a right swipe.
        paint.alpha = (70 + 110 * pagePosition).toInt()
        canvas.drawCircle(width / 2f - spacing / 2f, 7f * density, radius, paint)
        paint.alpha = (70 + 110 * (1f - pagePosition)).toInt()
        canvas.drawCircle(width / 2f + spacing / 2f, 7f * density, radius, paint)
        paint.alpha = 255
    }

    private fun drawMetrics(canvas: Canvas, display: Display, sample: Sample?, valid: Boolean) {
        val rpm = if (valid) sample!!.rpm else 0
        val load = if (valid) sample!!.load else 0
        val duty = if (valid) sample!!.duty else 0.0
        drawMetric(
            canvas,
            RectF(8f, 8f, 397f, 264f),
            tr("RÉGIMEN", "ENGINE SPEED"),
            if (valid) formatValue("%,d rpm", rpm) else "—",
            tr("0–6.500 rpm", "0–6,500 rpm"),
            display.rpm,
            6500.0,
            rpm / 6500.0,
            cyan,
        )
        drawMetric(
            canvas,
            RectF(405f, 8f, 794f, 264f),
            tr("CARGA INTERNA", "INTERNAL LOAD"),
            if (valid) "$load" else "—",
            if (valid) formatValue(tr("Mariposa %.1f %%", "Throttle %.1f %%"), sample!!.throttle * 100.0 / 255)
            else tr("Mariposa —", "Throttle —"),
            display.load,
            255.0,
            load / 255.0,
            amber,
        )
        val injColor = if (duty > 100) red else if (duty >= 90) amber else green
        drawMetric(
            canvas,
            RectF(802f, 8f, 1192f, 264f),
            tr("INYECCIÓN / DUTY EST.", "INJECTION / EST. DUTY"),
            if (valid) formatValue("~%.1f %%", duty) else "—",
            if (valid) formatValue("~%.2f ms · %d raw", sample!!.injectionMs, sample.injection)
            else tr("F746 sin lectura actual", "No current F746 reading"),
            display.duty,
            maxOf(100.0, display.duty.maxOrNull() ?: 100.0),
            duty / 100,
            injColor,
        )
    }

    private fun drawTemperaturePage(
        canvas: Canvas,
        display: Display,
        sample: Sample?,
        valid: Boolean,
        now: Long,
    ) {
        drawTemperatureGauge(canvas, RectF(8f, 8f, 596f, 405f), tr("TEMPERATURA MOTOR", "ENGINE TEMPERATURE"),
            if (valid) sample!!.coolant - 40 else null, TemperatureAlerts.MOTOR_LIMIT, 140, green, now)
        drawTemperatureGauge(canvas, RectF(604f, 8f, 1192f, 405f), tr("TEMPERATURA ADMISIÓN", "INTAKE TEMPERATURE"),
            if (valid) sample!!.iat - 40 else null, TemperatureAlerts.INTAKE_LIMIT, 100, cyan, now)
        drawKnock(canvas, display, sample, valid, now, compact = true)
    }

    private fun drawTemperatureGauge(
        canvas: Canvas,
        bounds: RectF,
        title: String,
        temperature: Int?,
        limit: Int,
        maximum: Int,
        color: Int,
        now: Long,
    ) {
        val hot = temperature != null && temperature > limit
        val flash = hot && flashEnabled && (now / 250) % 2 == 0L
        val gaugeColor = if (hot) red else color
        drawCard(canvas, bounds, title, gaugeColor, flash, cornerRadius = 24f)
        val cx = bounds.centerX()
        val cy = bounds.top + 208f
        // Keep the dial and its labels proportional inside the responsive card.
        canvas.save()
        canvas.scale(textScale / canvasScaleX, textScale / canvasScaleY, cx, cy)
        val cardTextTransform = textTransform
        textTransform = CanvasTextTransform(textScale, textScale, textScale)
        val dial = RectF(cx - 148f, bounds.top + 60f, cx + 148f, bounds.top + 356f)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 16f
        val previousCap = paint.strokeCap
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.rgb(36, 49, 65)
        canvas.drawArc(dial, 135f, 270f, false, paint)
        paint.color = red
        paint.strokeWidth = 5f
        val limitAngle = 270f * limit / maximum
        canvas.drawArc(dial, 135f + limitAngle, 270f - limitAngle, false, paint)
        if (temperature != null) {
            paint.strokeWidth = 16f
            paint.color = gaugeColor
            canvas.drawArc(dial, 135f, 270f * (temperature.toFloat() / maximum).coerceIn(0f, 1f), false, paint)
        }
        paint.strokeCap = previousCap
        drawText(canvas, temperature?.let { "$it" } ?: "—", cx, bounds.top + 230f,
            76f, gaugeColor, true, bounds.width() - 40f, Paint.Align.CENTER)
        drawText(canvas, "0", cx - 116f, bounds.top + 342f, 16f, muted, width = 65f, align = Paint.Align.CENTER)
        drawText(canvas, "$maximum °C", cx + 116f, bounds.top + 342f, 16f, muted, width = 90f, align = Paint.Align.CENTER)
        textTransform = cardTextTransform
        canvas.restore()
        drawText(canvas, if (temperature == null) tr("Sin datos actuales", "No current data") else if (hot) tr("TEMPERATURA ALTA · > $limit °C", "HIGH TEMPERATURE · > $limit °C") else tr("Aviso por encima de $limit °C", "Alert above $limit °C"),
            cx, bounds.bottom - 16f, 16f, gaugeColor, hot, bounds.width() - 32f, Paint.Align.CENTER)
    }

    private fun drawKnock(
        canvas: Canvas,
        display: Display,
        sample: Sample?,
        valid: Boolean,
        now: Long,
        compact: Boolean = false,
    ) {
        val flash = flashEnabled && now < display.alertUntil && (now / 250) % 2 == 0L
        val kr = if (compact) RectF(8f, 417f, 1192f, 621f) else RectF(8f, 272f, 795f, 621f)
        val center = kr.centerX()
        val valueY = if (compact) 470f else 339f
        val channelY = if (compact) 497f else 377f
        drawCard(canvas, kr, tr("CONTROL DE PICADO", "KNOCK CONTROL"), purple, flash)
        drawText(
            canvas,
            if (valid) formatValue(tr("Retardo %.2f°", "Retard %.2f°"), sample!!.retard) else tr("Retardo —", "Retard —"),
            center,
            valueY,
            if (compact) 23f else 27f,
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
            canvas.drawRoundRect(RectF(kr.right - 122f, valueY - 32f, kr.right - 13f, valueY + 13f), 4f, 4f, paint)
            drawText(
                canvas,
                "%04d".format(display.count),
                kr.right - 67f,
                valueY,
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
        drawText(canvas, channels, center, channelY, 15f, muted, false, kr.width() - 40f, Paint.Align.CENTER)
        drawChart(
            canvas,
            RectF(20f, if (compact) 510f else 392f, kr.right - 12f, 586f),
            display.knock,
            maxOf(3.0, display.knock.maxOrNull() ?: 3.0),
            purple,
        )
        drawText(
            canvas,
            if (display.lastAlertAt != null)
                formatValue(
                    tr("Último aviso hace %.1f s · Aumento en canal(es): %s", "Last alert %.1f s ago · Increase in channel(s): %s"),
                    maxOf(0L, now - display.lastAlertAt) / 1000.0,
                    display.lastAlertChannels.joinToString(","),
                )
            else "",
            center,
            607f,
            12f,
            muted,
            false,
            kr.width() - 40f,
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
        drawCard(canvas, RectF(803f, 272f, 1192f, 435f), tr("MEZCLA", "MIXTURE"), green)
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
            tr("LTFT baja / alta", "LTFT low / high"),
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
            tr("Caudalímetro", "Airflow"),
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
            tr("Sonda λ", "O₂ sensor"),
            if (valid)
                formatValue(
                    "%.3f V · %s",
                    Decoder.oxygen(sample!!.oxygen),
                    if (sample.oxygen > 0x2456) tr("rico", "rich") else tr("pobre", "lean"),
                )
            else "—",
            410f,
            amber,
        )
    }

    private fun drawTemperaturesAndAdvance(canvas: Canvas, sample: Sample?, valid: Boolean) {
        drawCard(canvas, RectF(803f, 443f, 1192f, 514f), tr("TEMPERATURAS", "TEMPERATURES"), amber)
        drawText(
            canvas,
            if (valid) tr("Admisión ${sample!!.iat-40} °C     Refrigerante ${sample.coolant-40} °C", "Intake ${sample!!.iat-40} °C     Coolant ${sample.coolant-40} °C")
            else tr("Admisión —     Refrigerante —", "Intake —     Coolant —"),
            997f,
            496f,
            16f,
            amber,
            true,
            365f,
            Paint.Align.CENTER,
        )
        drawCard(canvas, RectF(803f, 522f, 1192f, 621f), tr("AVANCE DE ENCENDIDO", "IGNITION ADVANCE"), cyan)
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
            if (adv == null) tr("Sin lectura", "No reading")
            else if (adv < 0) tr("Antes del PMS", "Before TDC") else if (adv > 0) tr("Después del PMS", "After TDC") else tr("En el PMS", "At TDC"),
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
        val software = if (display.ecuIdentificationStatus == "demo") "" else
            tr(" · SW ${display.ecuSoftware ?: "no identificada"}", " · SW ${display.ecuSoftware ?: "unidentified"}") +
                if (display.ecuProfile == null) tr(" · LTFT RAM desactivado", " · stored LTFT disabled") else ""
        val perf =
            tr("${sample?.cycleMs?:"—"} ms/ciclo · ECU $volts$software", "${sample?.cycleMs?:"—"} ms/cycle · ECU $volts$software") +
                " · ${I18n.mode(display.mode)} ${I18n.profile(display.profile)}"
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
            (if (temperaturesPage) tr("Desliza →: panel principal · ", "Swipe →: main dashboard · ") else tr("Desliza ←: temperaturas · ", "Swipe ←: temperatures · ")) +
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
