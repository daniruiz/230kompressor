package com.drasite.mercedesobd

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Wire format shared with the PC Journal's csv.DictWriter (excel dialect). */
internal object CsvLog {
    val fields = listOf(
        "utc", "demo", "rpm", "load_raw", "F746_raw", "injection_ms_est",
        "injector_duty_est_pct", "duty_over_100_est", "acquisition_ms", "cycle_ms",
        "observed_count", "observed_event", "coolant_C", "iat_C", "coolant_raw", "iat_raw",
        "pairs_raw", "throttle_raw", "throttle_pct", "oxygen_raw", "oxygen_voltage_V",
        "oxygen_state", "maf_raw", "maf_g_s_est", "maf_saturated", "advance_raw", "advance_deg",
        "ecu_voltage_raw", "ecu_voltage_V_est", "ecu_voltage_scale_V_per_raw",
        "ltft_low_raw", "ltft_low_pct", "ltft_high_raw", "ltft_high_pct",
        "ltft_stored_age_s", "ltft_stored_status",
    ) + (1..4).flatMap { listOf("knock_channel_${it}_raw", "knock_channel_${it}_deg_est") } +
        listOf(
            "stft_a_raw", "stft_a_pct", "ltft_a_raw", "ltft_a_pct",
            "injection_cycle_720_ms", "injection_cycle_margin_ms_est", "F8C6_calc_raw",
            "injector_deadtime_ms_est", "injection_before_deadtime_raw",
            "injection_before_deadtime_ms_est", "injector_deadtime_fraction_pct",
            "injection_breakdown_status", "injection_F746_saturated",
            "ecu_software", "ecu_identification_status", "ltft_low_address", "ltft_high_address",
        )

    private val utcSeconds = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss", Locale.ROOT)
        .withZone(ZoneOffset.UTC)

    /** Match datetime.now(timezone.utc).isoformat(): microseconds and +00:00. */
    fun utc(instant: Instant = Instant.now()): String {
        val micros = instant.nano / 1000
        val fraction = if (micros == 0) "" else "." + micros.toString().padStart(6, '0')
        return utcSeconds.format(instant) + fraction + "+00:00"
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
