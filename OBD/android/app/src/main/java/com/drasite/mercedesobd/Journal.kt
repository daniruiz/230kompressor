package com.drasite.mercedesobd

import java.io.Closeable
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

class Journal(
    private val root: File,
    private val demo: Boolean,
    adapterTuning: Boolean = true,
    deferUntilConnected: Boolean = false,
) : Events, Closeable {
    private val stamp =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
    val jsonFile: File? = if (demo) null else File(root, "me21_${stamp}_${System.nanoTime()}.jsonl")
    val csvFile: File? = jsonFile?.let { File(root, it.nameWithoutExtension + ".csv") }
    private var json: java.io.BufferedWriter? = null
    private var csv: java.io.BufferedWriter? = null
    private val pending = ArrayList<String>()
    private var pendingSize = 0
    private var awaitingConnection = deferUntilConnected && !demo
    private var closed = false
    private var flushed = 0L
    private var csvStartedAt: Long? = null

    init {
        if (!awaitingConnection) beginLogging()
        event(
            "session",
            "demo" to demo,
            "platform" to "android_native",
            "adapter_tuning" to (adapterTuning && !demo),
        )
    }

    /** Auto-connect keeps the handshake in memory until the first valid sample.
     * A failed attempt never creates files or even the log directory.
     */
    @Synchronized
    fun beginLogging() {
        if (demo || closed || json != null) return
        root.mkdirs()
        val jsonWriter = requireNotNull(jsonFile).bufferedWriter(Charsets.UTF_8)
        val csvWriter = try {
            requireNotNull(csvFile).bufferedWriter(Charsets.UTF_8)
        } catch (e: Exception) {
            jsonWriter.close()
            throw e
        }
        json = jsonWriter
        csv = csvWriter
        csvWriter.write(CsvLog.row(CsvLog.fields))
        pending.forEach { jsonWriter.appendLine(it) }
        pending.clear()
        pendingSize = 0
        awaitingConnection = false
    }

    @Synchronized
    override fun event(type: String, vararg values: Pair<String, Any?>) {
        if (demo || closed) return
        val row = JSONObject().put("utc", CsvLog.utc()).put("type", type)
        for ((key, value) in values) row.put(
            key,
            when (value) {
                is Collection<*> -> JSONArray(value)
                is Map<*, *> -> JSONObject(value)
                else -> value ?: JSONObject.NULL
            },
        )
        val line = row.toString()
        val jsonWriter = json
        if (jsonWriter == null) {
            if (awaitingConnection) {
                pending.add(line)
                pendingSize += line.length
                // Preserve the session header while bounding failed handshakes.
                while (pending.size > 1 && (pending.size > 1024 || pendingSize > 1_048_576)) {
                    pendingSize -= pending.removeAt(1).length
                }
            }
            return
        }
        jsonWriter.appendLine(line)
        if (monoMs() - flushed > 1000) {
            jsonWriter.flush()
            csv?.flush()
            flushed = monoMs()
        }
    }

    @Synchronized
    fun sample(s: Sample, d: Display, raised: Boolean) {
        val csvWriter = csv ?: return
        val values = s.values
        val v =
            linkedMapOf<String, Any?>(
                "utc" to CsvLog.utc(),
                "demo" to demo,
                "rpm" to values.rpm,
                "load_raw" to s.load,
                "acquisition_ms" to s.acquisitionMs,
                "cycle_ms" to s.cycleMs,
                "observed_count" to d.count,
                "observed_event" to raised,
                "coolant_C" to values.coolantC,
                "iat_C" to values.iatC,
                "coolant_raw" to s.coolant,
                "iat_raw" to s.iat,
                "pairs_raw" to s.pairs,
                "diagnostic_ids" to s.diagnosticIds.map { "11%02X".format(it) },
                "throttle_raw" to s.throttle,
                "throttle_pct" to values.throttlePercent,
                "oxygen_raw" to s.oxygen,
                "oxygen_voltage_V" to values.oxygenVolts,
                "oxygen_state" to values.oxygenRich?.let { if (it) "rico" else "pobre" },
                "maf_raw" to s.maf,
                "maf_adc_word" to s.mafAdcWord,
                "maf_g_s_est" to values.mafGramsPerSecond,
                "maf_ecu_raw" to s.mafEcuRaw,
                "maf_ecu_g_s_est" to values.ecuMafGramsPerSecond,
                "maf_ecu_source" to "grouped_1107_processed",
                "maf_ecu_at_limit" to s.mafEcuRaw?.let { it == 255 },
                "maf_saturated" to values.mafAtLimit,
                "maf_source" to "grouped_1143_adc",
                "maf_calibration_status" to s.mafCalibrationStatus,
                "maf_calibration_source" to if (s.mafGramsPerSecond != null) MafCalibration.SOURCE else null,
                "maf_curve_sha256" to if (s.mafGramsPerSecond != null) MafCalibration.SHA256 else null,
                "advance_raw" to s.advance,
                "advance_deg" to values.advanceDegrees,
                "ecu_voltage_raw" to null,
                "ecu_voltage_V_est" to null,
                "ecu_voltage_scale_V_per_raw" to null,
                "adapter_voltage_V" to s.adapterVoltage,
                "adapter_voltage_age_ms" to s.adapterVoltageAgeMs,
                "adapter_voltage_status" to s.adapterVoltageStatus,
                "adapter_voltage_source" to "ATRV",
                "ecu_software" to d.ecuSoftware,
                "ecu_identification_status" to d.ecuIdentificationStatus,
                "ltft_low_address" to d.ecuProfile?.let { addressHex(it.ltftLowAddress) },
                "ltft_high_address" to d.ecuProfile?.let { addressHex(it.ltftHighAddress) },
                "stft_a_raw" to s.stft,
                "stft_a_pct" to values.stftPercent,
                "ltft_a_raw" to s.ltft,
                "ltft_a_pct" to values.ltftPercent,
            )
        s.injectionMetrics?.let { v.putAll(it.logValues()) }
        v["injection_ms_est"] = values.injectionMs
        v["injector_duty_est_pct"] = values.dutyPercent
        v["duty_over_100_est"] = values.dutyPercent?.let { it > 100 }
        s.corrections.forEachIndexed { i, n ->
            v["knock_channel_${i+1}_raw"] = n
        }
        values.retardDegrees.forEachIndexed { i, n ->
            v["knock_channel_${i+1}_deg_est"] = n
        }
        if (s.measurements != null) {
            // A different reader must not inherit ME2.1 addresses or sensor-curve claims.
            val legacyMetadata = setOf("diagnostic_ids", "maf_ecu_source", "maf_ecu_at_limit",
                "maf_source", "maf_calibration_status", "maf_calibration_source", "maf_curve_sha256",
                "ltft_low_address", "ltft_high_address")
            v.keys.removeAll { it.endsWith("_raw") || it in legacyMetadata }
            v["load_value"] = values.load
            v["load_unit"] = values.loadUnit
        }
        // Sample.at uses the monotonic acquisition clock; each log starts at zero.
        val start = csvStartedAt ?: s.at.also { csvStartedAt = it }
        csvWriter.write(CsvLog.sampleRow(v, s.at - start))
        event("csv_sample", *v.map { it.key to it.value }.toTypedArray())
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        pending.clear()
        pendingSize = 0
        try {
            csv?.close()
        } finally {
            json?.close()
            csv = null
            json = null
        }
    }
}
