"""Append-only CSV columns shared by PC and Android, including unknown bytes."""

EXTRA_FIELDS = (
    'ecu_family', 'load_unit', 'map_kpa', 'ecu_voltage_V', 'injection_basis',
    'maf_source', 'cycle_ms', 'acquisition_ms',
    'load_raw16', 'map_model_kpa', 'injection_base_raw', 'scaled_load_raw',
    'pressure_reference_raw', 'air_model_correction_raw', 'bypass_position_raw',
    'advance_base_deg', 'advance_idle_deg', 'ignition_temperature_correction_deg',
    'ignition_other_correction_deg', 'ltft_applied_pct', 'additive_raw',
    'lambda_trend_raw', 'lambda_correction_raw', 'lambda_correction_pct',
    'injection_tick_us_nominal', 'ltft_low_pct', 'ltft_high_pct', 'speed_kph',
    'model_ratio_raw', 'coolant_retained_raw', 'atmospheric_pressure_raw',
    'oxygen2_voltage_V', 'idle_target_rpm', 'bypass_reference_raw',
    'bypass_pot1_raw', 'bypass_pot2_raw', 'ecu_voltage_raw', 'voltage_conversion_status',
) + tuple(f'knock_level_{i}_raw' for i in range(1, 5)) + (
    'sim4le_2101_raw',
) + tuple(f'sim4le_2101_byte_{i:03d}' for i in range(178))


def snapshot_fields(sample):
    """Keep standard STFT/LTFT columns empty: these are different internal terms."""
    v = sample.values()
    return dict(
        # Preserve the original 16-bit log columns when the display uses byte 12.
        ecu_family=sample.ecu_family, load_raw=sample.ecu_fields['load_raw16'],
        load_value=sample.ecu_fields['load_raw16'], load_unit=v.load_unit,
        map_kpa=v.map_kpa, ecu_voltage_V=v.ecu_voltage_v,
        injection_basis=v.injection_basis, injection_breakdown_status=v.injection_basis,
        duty_over_100_est=None,  # A base occupancy is not evidence of electrical saturation.
        stft_a_pct=None, ltft_a_pct=None, adapter_voltage_source=None,
        maf_source='SIM4LE 2101 processed ECU airflow',
        maf_ecu_source='SIM4LE 2101 offset 10',
        sim4le_2101_raw=sample.ecu_raw.hex().upper(),
        **sample.ecu_fields,
        **{f'sim4le_2101_byte_{i:03d}': value for i, value in enumerate(sample.ecu_raw)},
    )
