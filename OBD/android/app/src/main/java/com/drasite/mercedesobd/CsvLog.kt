package com.drasite.mercedesobd

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Wire format shared with the PC Journal's csv.DictWriter (excel dialect). */
internal object CsvLog {
    // Keep the original ME2.1 columns first; SIM4LE appends decoded and raw fields.
    // Load stays in ECU units because its physical scale is not verified.
    val fields = listOf(
        "Time", "utc", "rpm", "load_raw", "throttle_pct", "maf_g_s", "maf_ecu_g_s",
        "injection_ms", "injector_duty_pct", "advance_deg",
    ) + (1..4).map { "knock_channel_${it}_deg" } +
        listOf(
            "stft_pct", "ltft_pct",
            "coolant_C", "iat_C", "oxygen_voltage_V", "adapter_voltage_V",
        ) + Sim4leLog.fields + listOf("bypass_pct", "app_version")

    private val utcSeconds = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss", Locale.ROOT)
        .withZone(ZoneOffset.UTC)

    /** Match datetime.now(timezone.utc).isoformat(): microseconds and +00:00. */
    fun utc(instant: Instant = Instant.now()): String {
        val micros = instant.nano / 1000
        val fraction = if (micros == 0) "" else "." + micros.toString().padStart(6, '0')
        return utcSeconds.format(instant) + fraction + "+00:00"
    }

    fun sampleRow(values: Map<String, Any?>, elapsedMillis: Long): String {
        val seconds = String.format(Locale.ROOT, "%.3f", elapsedMillis / 1000.0)
        return row(fields.map { if (it == "Time") seconds else values[it] })
    }

    fun row(values: Iterable<Any?>): String {
        val cells = values.map { value ->
            when (value) {
                null -> ""
                true -> "True"
                false -> "False"
                else -> value.toString()
            }
        }
        return cells.joinToString(",") { cell ->
            if (cell.any { it == ',' || it == '"' || it == '\r' || it == '\n' } ||
                (cells.size == 1 && cell.isEmpty())) {
                "\"${cell.replace("\"", "\"\"")}\""
            } else cell
        } + "\r\n"
    }
}
