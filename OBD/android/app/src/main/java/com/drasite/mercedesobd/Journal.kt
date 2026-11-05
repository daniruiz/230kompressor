package com.drasite.mercedesobd

import java.io.Closeable
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

class Journal(root: File, private val demo: Boolean, adapterTuning: Boolean = true) : Events, Closeable {
    private val stamp =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
    val jsonFile: File? = if (demo) null else File(root, "me21_${stamp}_${System.nanoTime()}.jsonl")
    val csvFile: File? = jsonFile?.let { File(root, it.nameWithoutExtension + ".csv") }
    private val json: java.io.BufferedWriter?
    private val csv: java.io.BufferedWriter?
    private var flushed = 0L
    private var csvStartedAt: Long? = null

    init {
        if (!demo) root.mkdirs()
        json = jsonFile?.bufferedWriter()
        csv = csvFile?.bufferedWriter(Charsets.UTF_8)
        csv?.write(CsvLog.row(CsvLog.fields))
        event(
            "session",
            "demo" to demo,
            "platform" to "android_native",
            "measurement_conversions" to "family_specific; see sample ecu_family and sources",
            "me21_tick_us_assumed" to Decoder.TICK_US,
            "sim4le_injection_conversion_available" to false,
            "detector" to "observed_correction_increase_NOT_ECU_knock_counter",
            "transport_revision" to "native_spp_elm_auto_host_gap_and_chunk_timestamps_v2",
            "adapter_tuning" to (adapterTuning && !demo),
            "elm_timing_probe" to (adapterTuning && !demo),
            "adaptive" to "0",
            "response_timeout_ms" to 512,
            "request_gap_ms" to 60,
            "exchange_timing_basis" to "host_receive_batches_not_kline",
            "me21_diagnostic_ids" to Decoder.ids.map { "11%02X".format(it) },
            "sim4le_group_request" to "2101",
            "ecu_identification_policy" to "ME21 ROM software reference or SIM4LE exact 1A94 program identity; once at connection",
            "me21_supported_ecu_software" to EcuProfiles.all.map { it.software },
            "sim4le_supported_programs" to listOf("6577815126--"),
        )
    }

    @Synchronized
    override fun event(type: String, vararg values: Pair<String, Any?>) {
        val jsonWriter = json ?: return
        val row = JSONObject().put("utc", CsvLog.utc()).put("type", type)
        for ((key, value) in values) row.put(
            key,
            if (value is Collection<*>) JSONArray(value) else value ?: JSONObject.NULL,
        )
        jsonWriter.appendLine(row.toString())
        if (monoMs() - flushed > 1000) {
            jsonWriter.flush()
            csv?.flush()
            flushed = monoMs()
        }
    }

    @Synchronized
    fun sample(s: Sample, d: Display, raised: Boolean) {
        val csvWriter = csv ?: return
        val me21 = s.family == EcuFamily.ME21
        val v =
            linkedMapOf<String, Any?>(
                "utc" to CsvLog.utc(),
                "demo" to demo,
                "rpm" to s.rpm,
                "ecu_family" to if (me21) "ME2.1" else "SIM4LE",
                "sample_source" to if (me21) "ME21_11xx" else "SIM4LE_2101",
                "injection_raw" to s.injection,
                "injection_raw_source" to if (me21) "ME21_F746" else "SIM4LE_F7EE",
                "load_raw" to if (me21) s.load else null,
                "load_obd_pct" to if (!me21) s.loadValue else null,
                "map_kPa" to s.mapKpa,
                "speed_km_h" to s.speedKph,
                "acquisition_ms" to s.acquisitionMs,
                "cycle_ms" to s.cycleMs,
                "observed_count" to d.count,
                "observed_event" to raised,
                "coolant_C" to s.coolantC,
                "iat_C" to s.iatC,
                "coolant_raw" to s.coolant,
                "coolant_source" to if (me21) "ME21_1101" else "SIM4LE_2101_E06F_processed_filtered",
                "iat_raw" to s.iat,
                "pairs_raw" to s.pairs,
                "throttle_raw" to s.throttle,
                "throttle_pct" to s.throttlePct,
                "oxygen_raw" to s.oxygen,
                "oxygen_voltage_V" to s.oxygenV,
                "oxygen_state" to if (!me21) null else if (s.oxygenRich) "rico" else "pobre",
                "maf_raw" to s.maf,
                "maf_g_s_est" to s.mafGs,
                "maf_saturated" to s.mafSaturated,
                "advance_raw" to if (me21) s.advance else null,
                "advance_deg" to s.advanceDeg,
                "advance_positive_direction" to if (me21) "after_TDC" else "before_TDC",
                "ecu_voltage_raw" to if (me21) s.voltage else null,
                "ecu_voltage_V_est" to s.voltageV,
                "ecu_voltage_scale_V_per_raw" to if (me21) Decoder.VOLT_SCALE else null,
                "ecu_software" to d.ecuSoftware,
                "ecu_identification_status" to d.ecuIdentificationStatus,
                "ltft_low_address" to d.ecuProfile?.let { addressHex(it.ltftLowAddress) },
                "ltft_high_address" to d.ecuProfile?.let { addressHex(it.ltftHighAddress) },
                "stft_a_raw" to if (me21) s.stft else null,
                "stft_a_pct" to s.stftPct,
                "ltft_a_raw" to if (me21) s.ltft else null,
                "ltft_a_pct" to s.ltftPct,
            )
        if (me21) {
            v.putAll(requireNotNull(s.injectionMetrics).logValues())
            v["injection_tick_us_assumed"] = Decoder.TICK_US
            v["injector_deadtime_source"] = Injection.calibration.source
        } else {
            v["injection_raw"] = s.injection
            v["injection_raw_address"] = "F7EE"
            v["injection_ms_est"] = null
            v["injector_duty_est_pct"] = null
            v["injection_breakdown_status"] = "sim4le_timer_scale_unverified"
            v["auxiliary_source"] = "OBD_mode_01"
            v["auxiliary_ttl_ms"] = 3000
            for (pid in Sim4le.auxiliaryPids) {
                val reading = s.auxiliary[pid]
                val key = "obd_01%02X".format(java.util.Locale.ROOT, pid)
                v["${key}_raw"] = reading?.raw
                v["${key}_age_ms"] = reading?.let { s.at - it.at }
                v["${key}_valid"] = reading != null && s.at - reading.at in 0..3000
            }
        }
        s.corrections.forEachIndexed { i, n ->
            v["knock_channel_${i+1}_raw"] = n
            v["knock_channel_${i+1}_deg_est"] = s.correctionDegrees[i]
        }
        // Sample.at uses the monotonic acquisition clock; each log starts at zero.
        val start = csvStartedAt ?: s.at.also { csvStartedAt = it }
        csvWriter.write(CsvLog.sampleRow(v, s.at - start))
        event("csv_sample", *v.map { it.key to it.value }.toTypedArray())
    }

    @Synchronized
    override fun close() {
        try {
            csv?.close()
        } finally {
            json?.close()
        }
    }
}
