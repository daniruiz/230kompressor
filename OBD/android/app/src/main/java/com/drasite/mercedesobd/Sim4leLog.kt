package com.drasite.mercedesobd

/** Append-only CSV columns, in the same order as the PC SIM4LE exporter. */
internal object Sim4leLog {
    val fields = listOf(
        "ecu_family", "load_unit", "map_kpa", "ecu_voltage_V", "injection_basis",
        "maf_source", "cycle_ms", "acquisition_ms",
        "load_raw16", "map_model_kpa", "injection_base_raw", "scaled_load_raw",
        "pressure_reference_raw", "air_model_correction_raw", "bypass_position_raw",
        "advance_base_deg", "advance_idle_deg", "ignition_temperature_correction_deg",
        "ignition_other_correction_deg", "additive_raw",
        "lambda_trend_raw", "lambda_correction_raw",
        "injection_tick_us_nominal", "ltft_low_pct", "ltft_high_pct", "speed_kph",
        "model_ratio_raw", "coolant_retained_raw", "atmospheric_pressure_raw",
        "oxygen2_voltage_V", "idle_target_rpm", "bypass_reference_raw",
        "bypass_pot1_raw", "bypass_pot2_raw", "ecu_voltage_raw", "voltage_conversion_status",
    ) + (1..4).map { "knock_level_${it}_raw" } + listOf("sim4le_2101_raw") +
        (0 until Sim4leDecoder.DATA_SIZE).map { "sim4le_2101_byte_${it.toString().padStart(3, '0')}" }

    fun snapshotFields(sample: Sample): Map<String, Any?> {
        val v = sample.values
        // Preserve historical log meanings; the dashboard now displays scaled_load_raw.
        val load16 = sample.ecuFields["load_raw16"]
        val fields = linkedMapOf<String, Any?>(
            "ecu_family" to sample.ecuFamily, "load_raw" to load16, "load_value" to load16,
            "load_unit" to v.loadUnit,
            "map_kpa" to v.mapKpa, "ecu_voltage_V" to v.ecuVoltageV,
            "injection_basis" to v.injectionBasis, "injection_breakdown_status" to v.injectionBasis,
            "duty_over_100" to null, // Base occupancy does not prove electrical saturation.
            "adapter_voltage_source" to null,
            "maf_source" to "SIM4LE 2101 processed ECU airflow",
            "maf_ecu_source" to "SIM4LE 2101 offset 10",
            "sim4le_2101_raw" to sample.ecuRaw.hex(),
        )
        fields.putAll(sample.ecuFields)
        sample.ecuRaw.forEachIndexed { i, byte ->
            fields["sim4le_2101_byte_${i.toString().padStart(3, '0')}"] = byte.u()
        }
        return fields
    }
}
