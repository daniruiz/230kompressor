package com.drasite.mercedesobd

import java.io.Closeable
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

class Journal(root: File, private val demo: Boolean) : Events, Closeable {
    private val stamp =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
    val jsonFile: File
    val csvFile: File
    private val json: java.io.BufferedWriter
    private val csv: java.io.BufferedWriter
    private var flushed = 0L
    private val fields =
        listOf(
            "utc",
            "demo",
            "rpm",
            "load_raw",
            "F746_raw",
            "injection_ms_est",
            "injector_duty_est_pct",
            "duty_over_100_est",
            "acquisition_ms",
            "cycle_ms",
            "observed_count",
            "observed_event",
            "coolant_C",
            "iat_C",
            "coolant_raw",
            "iat_raw",
            "pairs_raw",
            "throttle_raw",
            "throttle_pct",
            "oxygen_raw",
            "oxygen_voltage_V",
            "oxygen_state",
            "maf_raw",
            "maf_g_s_est",
            "maf_saturated",
            "advance_raw",
            "advance_deg",
            "ecu_voltage_raw",
            "ecu_voltage_V_est",
            "ecu_voltage_scale_V_per_raw",
            "ltft_low_raw",
            "ltft_low_pct",
            "ltft_high_raw",
            "ltft_high_pct",
            "ltft_stored_age_s",
            "ltft_stored_status",
        ) +
            (1..4).flatMap { listOf("knock_channel_${it}_raw", "knock_channel_${it}_deg_est") } +
            listOf("stft_a_raw", "stft_a_pct", "ltft_a_raw", "ltft_a_pct") +
            listOf(
                "injection_cycle_720_ms",
                "injection_cycle_margin_ms_est",
                "F8C6_calc_raw",
                "injector_deadtime_ms_est",
                "injection_before_deadtime_raw",
                "injection_before_deadtime_ms_est",
                "injector_deadtime_fraction_pct",
                "injection_breakdown_status",
                "injection_F746_saturated",
                "ecu_software",
                "ecu_identification_status",
                "ltft_low_address",
                "ltft_high_address",
            )

    init {
        root.mkdirs()
        jsonFile = File(root, "me21_${stamp}_${System.nanoTime()}.jsonl")
        csvFile = File(root, jsonFile.nameWithoutExtension + ".csv")
        json = jsonFile.bufferedWriter()
        csv = csvFile.bufferedWriter()
        csv.appendLine(fields.joinToString(","))
        event(
            "session",
            "demo" to demo,
            "platform" to "android_native",
            "tick_us_assumed" to Decoder.TICK_US,
            "injection_tick_conversion_verified" to false,
            "injection_duty_basis" to "F746 common request; one pulse per 720 degrees assumed; not measured output duty",
            "injector_deadtime_source" to Injection.calibration.source,
            "injector_calibration_sha256" to Injection.calibration.sha256,
            "injector_deadtime_curve" to Injection.calibration.nodes,
            "injector_deadtime_is_direct_ram_read" to false,
            "injector_minimum_ticks" to Injection.calibration.minimumTicks,
            "voltage_scale_V_per_raw_assumed" to Decoder.VOLT_SCALE,
            "voltage_conversion_verified" to false,
            "correction_deg_per_raw_nominal" to 0.75,
            "detector" to "observed_correction_increase_NOT_ECU_knock_counter",
            "transport_revision" to "native_spp_v1",
            "diagnostic_ids" to Decoder.ids.map { "11%02X".format(it) },
            "ecu_identification_policy" to "ROM software reference; once at connection",
            "supported_ecu_software" to EcuProfiles.all.map { it.software },
        )
    }

    @Synchronized
    override fun event(type: String, vararg values: Pair<String, Any?>) {
        val row = JSONObject().put("utc", Instant.now().toString()).put("type", type)
        for ((key, value) in values) row.put(
            key,
            if (value is Collection<*>) JSONArray(value) else value ?: JSONObject.NULL,
        )
        json.appendLine(row.toString())
        if (monoMs() - flushed > 1000) {
            json.flush()
            csv.flush()
            flushed = monoMs()
        }
    }

    @Synchronized
    fun sample(s: Sample, d: Display, raised: Boolean) {
        val v =
            linkedMapOf<String, Any?>(
                "utc" to Instant.now().toString(),
                "demo" to demo,
                "rpm" to s.rpm,
                "load_raw" to s.load,
                "acquisition_ms" to s.acquisitionMs,
                "cycle_ms" to s.cycleMs,
                "observed_count" to d.count,
                "observed_event" to raised,
                "coolant_C" to s.coolant - 40,
                "iat_C" to s.iat - 40,
                "coolant_raw" to s.coolant,
                "iat_raw" to s.iat,
                "pairs_raw" to s.pairs,
                "throttle_raw" to s.throttle,
                "throttle_pct" to s.throttle * 100.0 / 255,
                "oxygen_raw" to s.oxygen,
                "oxygen_voltage_V" to Decoder.oxygen(s.oxygen),
                "oxygen_state" to if (s.oxygen > 0x2456) "rico" else "pobre",
                "maf_raw" to s.maf,
                "maf_g_s_est" to s.maf * Decoder.MAF_SCALE,
                "maf_saturated" to (s.maf == 255),
                "advance_raw" to s.advance,
                "advance_deg" to Decoder.advance(s.advance),
                "ecu_voltage_raw" to s.voltage,
                "ecu_voltage_V_est" to s.voltage * Decoder.VOLT_SCALE,
                "ecu_voltage_scale_V_per_raw" to Decoder.VOLT_SCALE,
                "ltft_low_raw" to d.low,
                "ltft_low_pct" to d.low?.let { Decoder.trim(it) },
                "ltft_high_raw" to d.high,
                "ltft_high_pct" to d.high?.let { Decoder.trim(it) },
                "ltft_stored_age_s" to d.trimsAt?.let { maxOf(0, s.at - it) / 1000.0 },
                "ltft_stored_status" to d.trimsStatus,
                "ecu_software" to d.ecuSoftware,
                "ecu_identification_status" to d.ecuIdentificationStatus,
                "ltft_low_address" to d.ecuProfile?.let { addressHex(it.ltftLowAddress) },
                "ltft_high_address" to d.ecuProfile?.let { addressHex(it.ltftHighAddress) },
                "stft_a_raw" to s.stft,
                "stft_a_pct" to Decoder.trim(s.stft),
                "ltft_a_raw" to s.ltft,
                "ltft_a_pct" to Decoder.trim(s.ltft),
            )
        v.putAll(s.injectionMetrics.logValues())
        s.corrections.forEachIndexed { i, n ->
            v["knock_channel_${i+1}_raw"] = n
            v["knock_channel_${i+1}_deg_est"] = n * .75
        }
        csv.appendLine(
            fields.joinToString(",") { key ->
                val value = v[key]?.toString() ?: ""
                "\"${value.replace("\"","\"\"")}\""
            }
        )
        event("csv_sample", *v.map { it.key to it.value }.toTypedArray())
    }

    @Synchronized
    override fun close() {
        try {
            csv.close()
        } finally {
            json.close()
        }
    }
}
