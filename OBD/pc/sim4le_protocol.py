"""Read-only SIM4LE M10062 decoder, verified against the complete firmware.

Offsets are zero-based after 61 01. Evidence: Analysis/sim4le_decompile,
snapshot pointer table 0x318E0, handler 0xF67A0 and Mode 01 producer paths.
This layout must only be selected after matching a verified ECU identity.
"""
from __future__ import annotations

FAMILY = 'SIM4LE'
SNAPSHOT_LENGTH = 180
AUXILIARY_PIDS = (0x04, 0x06, 0x07, 0x0E)
AUXILIARY_INTERVAL = 0.250
AUXILIARY_MAX_AGE = 3.0
AUXILIARY_KEYS = {0x04: 'load_pct', 0x05: 'coolant_C', 0x06: 'stft_a_pct',
                  0x07: 'ltft_a_pct', 0x0E: 'advance_deg'}


def decode_snapshot(frame: bytes) -> dict:
    if len(frame) != SNAPSHOT_LENGTH or frame[:2] != b'\x61\x01':
        raise ValueError('SIM4LE requires exactly 61 01 + 178 data bytes')
    data = frame[2:]
    def word(offset):
        return int.from_bytes(data[offset:offset + 2], 'little')
    tps_raw = word(2)
    tps_obd = 255 if tps_raw > 0x0C30 else (tps_raw * 0xA7FF) >> 19
    # These are internal channels D164..D167, not verified cylinder numbers.
    corrections = tuple(data[i] for i in (37, 39, 40, 38))
    values = dict(coolant_C=max((data[0] * 3 // 4) - 8, 0) - 40,
                  iat_C=max((data[1] * 3 // 4) - 8, 0) - 40,
                  throttle_pct=tps_obd * 100 / 255,
                  maf_g_s_est=((word(10) * 0xDE39) >> 16) / 100,
                  map_kPa=min(255, (word(6) * 0x10FA) >> 19),
                  speed_kmh=data[53],
                  oxygen_voltage_V=min(word(74), 200) / 200)
    return dict(rpm=word(8), injection=word(17), corrections=corrections,
                maf_raw=word(10), throttle_raw=tps_raw,
                oxygen_raw=word(74), coolant_raw=data[0], iat_raw=data[1], values=values,
                correction_degrees=tuple(max(128 - raw, 0) * 0.375 for raw in corrections))


def decode_auxiliary(pid: int, frame: bytes) -> tuple[str, float]:
    if pid not in AUXILIARY_KEYS or len(frame) != 3 or frame[:2] != bytes((0x41, pid)):
        raise ValueError('Unexpected SIM4LE Mode 01 auxiliary response')
    raw = frame[2]
    value = (raw * 100 / 255 if pid == 0x04 else raw - 40 if pid == 0x05
             else raw / 2 - 64 if pid == 0x0E else (raw - 128) * 100 / 128)
    return AUXILIARY_KEYS[pid], value
