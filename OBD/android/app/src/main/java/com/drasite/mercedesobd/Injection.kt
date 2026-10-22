package com.drasite.mercedesobd

import kotlin.math.abs

/** SW404 bank 1, shared by stock and the analysed 230k_ME21 calibration. */
data class InjectorCalibration(
    // 47CA: F734, offset 0, shift 4, 17 nodes at 47D0..47E0.
    val nodes: List<Int> = listOf(112, 112, 112, 112, 112, 63, 43, 33, 26, 21, 21, 21, 21, 21, 21, 21, 21),
    val minimumTicks: Int = 187,
    val source: String = "404 banco 1, constantes fijas",
    val sha256: String? = null,
)

data class InjectionMetrics(
    val raw: Int,
    val totalMs: Double,
    val duty: Double,
    val cycleMs: Double?,
    val deadtimeRaw: Int?,
    val deadtimeMs: Double?,
    val beforeDeadtimeRaw: Int?,
    val beforeDeadtimeMs: Double?,
    val deadtimeFractionPct: Double?,
    val status: String,
) {
    /** Keep the CSV/JSONL names identical to mercedes_dashboard.injection_metrics. */
    fun logValues(): Map<String, Any?> = linkedMapOf(
        "F746_raw" to raw,
        "injection_ms_est" to totalMs,
        "injector_duty_est_pct" to duty,
        "duty_over_100_est" to (duty > 100),
        "injection_cycle_720_ms" to cycleMs,
        "injection_cycle_margin_ms_est" to cycleMs?.let { it - totalMs },
        "F8C6_calc_raw" to deadtimeRaw,
        "injector_deadtime_ms_est" to deadtimeMs,
        "injection_before_deadtime_raw" to beforeDeadtimeRaw,
        "injection_before_deadtime_ms_est" to beforeDeadtimeMs,
        "injector_deadtime_fraction_pct" to deadtimeFractionPct,
        "injection_breakdown_status" to status,
        "injection_F746_saturated" to (raw == 65535),
    )
}

object Injection {
    val calibration = InjectorCalibration()

    /** Common requested duration, including dead time, not a measured output pulse.
     * The timer scale and one pulse per 720 crank degrees remain assumptions.
     */
    fun dutyPercent(raw: Int, rpm: Int, tickUs: Double = Decoder.TICK_US): Double {
        require(raw >= 0 && rpm >= 0 && tickUs.isFinite() && tickUs > 0) {
            "Valores inválidos para estimar el duty"
        }
        return (raw * tickUs / 1000) * rpm / 1200
    }

    /** Reproduce 3C504 + 3C4DA/3C4EE, then multiply by 15.
     * Round the magnitude of the delta with ties upward before adding/subtracting;
     * rounding the interpolated value would disagree on descending half steps.
     * This uses the already-read F734 byte, not physical volts or a RAM read of F8C6.
     */
    fun deadtimeTicks(voltageRaw: Int?, cal: InjectorCalibration = calibration): Int? {
        if (voltageRaw == null) return null
        require(voltageRaw in 0..255) { "F734 debe ser un byte" }
        require(cal.nodes.size == 17 && cal.nodes.all { it in 0..255 }) {
            "Se requieren 17 nodos de un byte"
        }
        val index = voltageRaw shr 4
        val fraction = (voltageRaw and 15) shl 4
        val a = cal.nodes[index]
        val b = cal.nodes[index + 1]
        val delta = (abs(b - a) * fraction + 128) shr 8
        return 15 * (if (b >= a) a + delta else a - delta)
    }

    /** Shared by display, history and logs; no additional diagnostic requests. */
    fun metrics(
        raw: Int,
        rpm: Int,
        voltageRaw: Int?,
        tickUs: Double = Decoder.TICK_US,
        cal: InjectorCalibration = calibration,
    ): InjectionMetrics {
        val totalMs = raw * tickUs / 1000
        val duty = dutyPercent(raw, rpm, tickUs)
        val dead = deadtimeTicks(voltageRaw, cal)
        var net: Int? = null
        val status = when {
            rpm == 0 -> "engine_stopped_request_may_be_retained"
            raw == 65535 -> "F746_saturated_cannot_reverse"
            dead == null -> "missing_F734"
            rpm < 600 -> "low_rpm_start_path_not_excluded"
            raw - dead < cal.minimumTicks -> "below_running_minimum_path_not_applicable"
            else -> {
                // Mathematical decomposition assuming the running path, not proof of fuel flow.
                net = raw - dead
                "running_path_assumed"
            }
        }
        return InjectionMetrics(
            raw = raw,
            totalMs = totalMs,
            duty = duty,
            cycleMs = if (rpm != 0) 120000.0 / rpm else null,
            deadtimeRaw = dead,
            deadtimeMs = dead?.let { it * tickUs / 1000 },
            beforeDeadtimeRaw = net,
            beforeDeadtimeMs = net?.let { it * tickUs / 1000 },
            deadtimeFractionPct = if (dead != null && raw != 0 && net != null) 100.0 * dead / raw else null,
            status = status,
        )
    }
}
