package com.drasite.mercedesobd

/** SIM4LE fixed 21 01 snapshot; offsets start after the 61 01 response prefix. */
internal object Sim4leDecoder {
    const val DATA_SIZE = 178
    fun pressure(raw: Int): Int = minOf(255, raw * 0x10FA shr 19)
    fun temperature(raw: Int): Int = maxOf(0, raw * 96 / 128 - 8) - 40

    fun decode(payload: ByteArray, at: Long = monoMs(), acquisitionMs: Double = 0.0): Sample {
        if (payload.size != DATA_SIZE + 2 || payload[0].u() != 0x61 || payload[1].u() != 0x01)
            throw DiagnosticException("SIM4LE: expected 61 01 followed by 178 bytes")
        val data = payload.copyOfRange(2, payload.size)
        fun raw(i: Int) = data[i].u()
        fun word(i: Int) = raw(i) or (raw(i + 1) shl 8)
        fun signedWord(i: Int) = word(i).toShort().toInt()
        fun percent(i: Int) = signedWord(i) * 100.0 / 65536
        // Fixed byte 30 (E00C) is the mean channel angle, positive before TDC.
        // PID 0E uses a complemented per-channel value instead (F58C in 5126).
        fun angle(i: Int) = raw(i) * .375 - 23.625
        val maf = (word(10).toLong() * 0xDE39 shr 16) / 100.0
        val throttle = if (word(2) > 0xC30) 255 else word(2) * 0xA7FF shr 19
        val fields = linkedMapOf<String, Any?>(
            "load_raw16" to word(13), "map_model_kpa" to pressure(word(6)),
            "injection_base_raw" to word(17), "scaled_load_raw" to raw(12),
            "pressure_reference_raw" to word(19), "air_model_correction_raw" to word(21),
            "bypass_position_raw" to word(25), "advance_base_deg" to angle(31),
            "advance_idle_deg" to angle(33),
            "ignition_temperature_correction_deg" to data[35].toInt() * .375,
            "ignition_other_correction_deg" to data[36].toInt() * .375,
            "ltft_pct" to percent(45), "additive_raw" to signedWord(47),
            "lambda_trend_raw" to signedWord(49), "lambda_correction_raw" to signedWord(51),
            "stft_pct" to percent(51), "injection_tick_us_nominal" to 4.0,
            "ltft_low_pct" to percent(104), "ltft_high_pct" to percent(102),
            "speed_kph" to raw(53), "model_ratio_raw" to word(54),
            "coolant_retained_raw" to raw(89), "atmospheric_pressure_raw" to word(90),
            "oxygen2_voltage_V" to minOf(word(92), 200) / 200.0,
            "idle_target_rpm" to word(147), "bypass_reference_raw" to word(149),
            "bypass_pot1_raw" to word(171), "bypass_pot2_raw" to word(173),
            "ecu_voltage_raw" to raw(73), "voltage_conversion_status" to "nominal_0.1V_per_count",
        )
        (0..3).forEach { fields["knock_level_${it + 1}_raw"] = raw(95 + it) }
        val values = Measurements(
            // Display the ECU's scaled byte directly, independently of its 16-bit load.
            rpm = word(8), load = raw(12), loadMaximum = 255.0, loadUnit = "raw",
            injectionRaw = word(17), injectionMs = word(17) * .004,
            dutyPercent = word(17) * .004 * word(8) / 1200,
            injectionBasis = "base_4us_nominal_720deg",
            retardDegrees = listOf(37, 39, 40, 38).map { maxOf(0, 128 - raw(it)) * .375 },
            coolantC = temperature(raw(0)), iatC = temperature(raw(1)),
            stftPercent = percent(51), ltftPercent = percent(45),
            trimLabels = listOf("Lambda corr.", "LTFT applied"),
            throttlePercent = throttle * 100.0 / 255,
            mafGramsPerSecond = maf, ecuMafGramsPerSecond = maf,
            mapKpa = pressure(word(4)).toDouble(), ecuVoltageV = raw(73) / 10.0,
            advanceDegrees = angle(30), oxygenVolts = minOf(word(74), 200) / 200.0,
        )
        return Sample.fromMeasurements(values, at, acquisitionMs, "SIM4LE", data, fields)
    }
}
