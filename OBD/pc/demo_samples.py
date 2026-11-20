"""Deterministic simulated samples; no adapter access or calibration claims."""
import math

if __package__:
    from .dashboard_sample import Sample
    from .sim4le_measurements import decode_snapshot
    from .fuel_corrections import CorrectionMeasurements, FuelCorrections
else:
    from dashboard_sample import Sample
    from sim4le_measurements import decode_snapshot
    from fuel_corrections import CorrectionMeasurements, FuelCorrections


def demo_corrections(family='ME2.1'):
    if family == 'SIM4LE':
        return FuelCorrections.from_measurements(CorrectionMeasurements(
            low_percent=-1966 * 100 / 65536, high_percent=1311 * 100 / 65536,
            additive_raw=-35))
    if family != 'ME2.1':
        raise ValueError(f'Unknown demo ECU: {family}')
    return FuelCorrections(33768, 35168, 45)


def demo_sample(t, family='ME2.1', *, at, duration_ms):
    phase = t % 8
    load = int(60 + 175 * (.5 + .5 * math.sin(t / 2.2)))
    rpm = int(900 + 5200 * (.5 + .5 * math.sin(t / 3 - 1.5)))
    correction = max(0, 8 - int((phase - 2) * 2)) if phase >= 2 else 0
    corrections = (correction, max(0, correction - 2), 0, 0)
    coolant = int(100 + 18 * math.sin(t / 4 - 1.5))
    iat = int(58 + 20 * math.sin(t / 4 - 2.5))
    # Hold each O2 state long enough to see rich, lean and the unlabelled band.
    oxygen = (36, 85, 86, 87, 88, 89, 164)[int(t / 2) % 7]
    effort = .5 + .5 * math.sin(t / 3 - 1)
    if family == 'SIM4LE':
        data = bytearray(178)

        def word(offset, value):
            data[offset:offset + 2] = (round(value) & 0xFFFF).to_bytes(2, 'little')

        data[0] = round((coolant + 48) * 128 / 96)
        data[1] = round((iat + 48) * 128 / 96)
        word(2, 0xC30 * (.5 + .5 * math.sin(t / 3)))
        word(4, (40 + 130 * effort) * (1 << 19) / 0x10FA)
        word(6, int.from_bytes(data[4:6], 'little'))
        word(8, rpm)
        word(10, (20 + 180 * effort * rpm / 6500) * 100 * 65536 / 0xDE39)
        # Nominal M10062 demo relationship only; live readings use byte 12 as sent.
        word(13, load * 254 + 127)
        data[12] = load
        word(17, (3 + 15 * effort) / .004)
        word(19, 100 * (1 << 19) / 0x10FA)
        word(25, 50000 * (1 - effort))
        data[30] = round((10 + 20 * math.sin(t / 4) + 23.625) / .375)
        for offset, correction in zip((37, 39, 40, 38), corrections):
            data[offset] = 128 - correction * 2
        word(45, 2 * 65536 / 100)
        word(51, 4 * math.sin(t) * 65536 / 100)
        data[73] = round((13.8 + .3 * math.sin(t / 5)) * 10)
        word(74, oxygen)
        word(90, int.from_bytes(data[19:21], 'little'))
        word(92, 120)
        word(102, 2 * 65536 / 100)
        word(104, -3 * 65536 / 100)
        word(147, 850)
        return decode_snapshot(b'\x61\x01' + data, at=at, duration_ms=duration_ms)
    if family != 'ME2.1':
        raise ValueError(f'Unknown demo ECU: {family}')
    return Sample(
        rpm, 600 + round(7500 * effort), load, corrections,
        duration_ms=duration_ms, at=at,
        throttle_raw=round(255 * (.5 + .5 * math.sin(t / 3))),
        oxygen_raw=3512 + oxygen * 64,
        maf_ecu_raw=min(255, round((20 + 180 * effort * rpm / 6500) / 1.1108)),
        bypass_raw=round(255 * effort), bypass_limit_raw=255,
        advance_raw=round((-15 + 20 * math.sin(t / 4) + 66) / .75),
        temperatures=(coolant + 40, iat + 40),
        trims=(int(32768 + 2500 * math.sin(t)), 34000),
        adapter_voltage_v=13.8 + .3 * math.sin(t / 5),
        adapter_voltage_status='ok', adapter_voltage_age_ms=0,
    )
