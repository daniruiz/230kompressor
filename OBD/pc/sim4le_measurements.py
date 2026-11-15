"""SIM4LE 21 01: 178 scattered bytes, offsets relative to data after 61 01.

Layout checked against 5122/5126 ROM pointer lists and the 5116 live trace.
Never interpret these bytes using ME2.1 diagnostic identifiers or scales.
"""
if __package__:
    from .dashboard_sample import Sample
    from .measurements import Measurements
    from .ecu_reader import DiagnosticError
else:
    from dashboard_sample import Sample
    from measurements import Measurements
    from ecu_reader import DiagnosticError


def signed(value, bits=16):
    return value - (1 << bits) if value & (1 << (bits - 1)) else value


def pressure(raw):
    """Stock PID 0B conversion, kPa absolute, including its quantization."""
    return min(255, raw * 0x10FA >> 19)


def temperature(raw):
    return max(0, raw * 96 // 128 - 8) - 40


def decode_snapshot(payload, **timing):
    if len(payload) != 180 or payload[:2] != b'\x61\x01':
        raise DiagnosticError('SIM4LE: expected 61 01 followed by 178 bytes')
    data = bytes(payload[2:])
    word = lambda i: int.from_bytes(data[i:i + 2], 'little')
    percent = lambda i: signed(word(i)) * 100 / 65536
    # Fixed byte 30 (E00C) is the mean channel angle, positive before TDC.
    # The PID 0E path instead uses a complemented per-channel value (F58C in 5126).
    angle = lambda i: data[i] * .375 - 23.625
    maf = (word(10) * 0xDE39 >> 16) / 100
    throttle = 255 if word(2) > 0xC30 else word(2) * 0xA7FF >> 19
    fields = dict(
        load_raw16=word(13), map_model_kpa=pressure(word(6)),
        injection_base_raw=word(17), scaled_load_raw=data[12],
        pressure_reference_raw=word(19), air_model_correction_raw=word(21),
        bypass_position_raw=word(25), advance_base_deg=angle(31),
        advance_idle_deg=angle(33),
        ignition_temperature_correction_deg=signed(data[35], 8) * .375,
        ignition_other_correction_deg=signed(data[36], 8) * .375,
        ltft_applied_pct=percent(45), additive_raw=signed(word(47)),
        lambda_trend_raw=signed(word(49)), lambda_correction_raw=signed(word(51)),
        lambda_correction_pct=percent(51), injection_tick_us_nominal=4.0,
        ltft_low_pct=percent(104), ltft_high_pct=percent(102),
        speed_kph=data[53], model_ratio_raw=word(54),
        coolant_retained_raw=data[89], atmospheric_pressure_raw=word(90),
        oxygen2_voltage_V=min(word(92), 200) / 200,
        idle_target_rpm=word(147), bypass_reference_raw=word(149),
        bypass_pot1_raw=word(171), bypass_pot2_raw=word(173),
        ecu_voltage_raw=data[73], voltage_conversion_status='nominal_0.1V_per_count',
    )
    fields.update({f'knock_level_{i + 1}_raw': data[95 + i] for i in range(4)})
    values = Measurements(
        rpm=word(8), load=word(13), load_maximum=65535.0,
        injection_raw=word(17), injection_ms=word(17) * .004,
        duty_percent=word(17) * .004 * word(8) / 1200,
        injection_basis='base_4us_nominal_720deg',
        retard_degrees=tuple(max(0, 128 - data[i]) * .375 for i in (37, 39, 40, 38)),
        temperatures_c=(temperature(data[0]), temperature(data[1])),
        trims_percent=(percent(51), percent(45)), trim_labels=('Lambda corr.', 'LTFT applied'),
        throttle_percent=throttle * 100 / 255, maf_g_s=maf, ecu_maf_g_s=maf,
        map_kpa=pressure(word(4)), ecu_voltage_v=data[73] / 10,
        advance_degrees=angle(30), oxygen_v=min(word(74), 200) / 200,
    )
    return Sample.from_measurements(values, ecu_family='SIM4LE', ecu_raw=data,
                                    ecu_fields=fields, **timing)
