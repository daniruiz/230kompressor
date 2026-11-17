"""Fixed-block conversions, independent of serial transport or dashboard UI."""
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from sim4le_measurements import decode_snapshot
from ecu_reader import DiagnosticError
from dashboard_sample import Sample


class Sim4leMeasurementsTests(unittest.TestCase):
    def test_ecu_knock_retard_reduces_positive_before_tdc_advance(self):
        # 404 adds eight 0.75-degree counts; SIM4LE subtracts sixteen 0.375-degree
        # counts per channel. These opposite native directions both retard by 6°.
        for me21_raw, sim4le_raw, me21_retard, sim4le_retard, expected in (
                (68, 103, 0, 128, 15), (76, 87, 8, 112, 9)):
            data = bytearray(178)
            data[30] = sim4le_raw
            data[37:41] = bytes([sim4le_retard] * 4)
            samples = (Sample(3000, 3000, 180, (me21_retard,) * 4, advance_raw=me21_raw),
                       decode_snapshot(b'\x61\x01' + data))
            for sample in samples:
                self.assertEqual(sample.values().advance_degrees, expected)
                self.assertEqual(sample.values().retard_degrees, (15 - expected,) * 4)

    def test_both_ecus_use_voltage_thresholds_with_an_unlabelled_middle_band(self):
        for raw, expected in ((85, False), (86, None), (87, None), (88, None), (89, True)):
            with self.subTest(volts=raw / 200):
                data = bytearray(178)
                data[74:76] = raw.to_bytes(2, 'little')
                sim4le = decode_snapshot(b'\x61\x01' + data).values()
                me21 = Sample(3000, 3000, 180, (0, 0, 0, 0),
                              oxygen_raw=3512 + raw * 64).values()
                for values in (me21, sim4le):
                    self.assertEqual(values.oxygen_v, raw / 200)
                    self.assertIs(values.oxygen_rich, expected)

    def test_snapshot_uses_own_scales_and_preserves_all_bytes(self):
        data = bytearray(178)
        for offset, value in {0: 160, 1: 100, 12: 100, 30: 100, 37: 120, 38: 125,
                              39: 124, 40: 255, 73: 137}.items():
            data[offset] = value
        for offset, value in {2: 0xC31, 4: 12000, 8: 3000, 10: 65535,
                              13: 4000, 17: 1000, 45: 0xF000, 51: 0x1000,
                              74: 100}.items():
            data[offset:offset + 2] = value.to_bytes(2, 'little')
        sample = decode_snapshot(b'\x61\x01' + data, at=100, duration_ms=222)
        v = sample.values(tick_us=12345)  # A ME2.1 CLI override cannot change SIM4LE.
        self.assertEqual(sample.ecu_raw, data)
        self.assertEqual(sample.ecu_family, 'SIM4LE')
        # The display must use the received byte, not word(13)/254 or /256.
        self.assertEqual((v.rpm, v.load, v.load_maximum, v.load_unit), (3000, 100, 255, 'raw'))
        self.assertEqual(sample.ecu_fields['load_raw16'], 4000)
        self.assertEqual(sample.ecu_fields['scaled_load_raw'], 100)
        self.assertEqual(v.temperatures_c, (72, 27))
        self.assertEqual(v.throttle_percent, 100)
        self.assertEqual(v.maf_g_s, 568.88)
        self.assertEqual((v.map_kpa, v.ecu_voltage_v), (99, 13.7))
        self.assertEqual(v.advance_degrees, 13.875)
        self.assertEqual(v.retard_degrees, (3, 1.5, 0, 1.125))
        self.assertEqual(v.trims_percent, (6.25, -6.25))
        self.assertEqual((v.injection_ms, v.duty_percent), (4, 10))
        self.assertEqual(v.injection_basis, 'base_4us_nominal_720deg')
        self.assertIsNone(sample.adapter_voltage_v)

    def test_wrong_prefix_and_length_are_rejected(self):
        valid = b'\x61\x01' + bytes(178)
        for payload in (b'', valid[:-1], valid + b'\0', b'\x61\xf0' + bytes(178)):
            with self.subTest(payload=payload[:2]), self.assertRaises(DiagnosticError):
                decode_snapshot(payload)

    def test_signed_corrections_and_distinct_learned_endpoints(self):
        data = bytearray(178)
        for offset, value in {45: 0x8000, 51: 0x7FFF, 47: 0xFFFF,
                              102: 0x1000, 104: 0xF000, 147: 850}.items():
            data[offset:offset + 2] = value.to_bytes(2, 'little')
        data[35:37] = b'\xff\x80'
        sample = decode_snapshot(b'\x61\x01' + data)
        self.assertEqual(sample.values().trims_percent, (49.99847412109375, -50))
        self.assertEqual(sample.ecu_fields['additive_raw'], -1)
        self.assertEqual(sample.ecu_fields['ignition_temperature_correction_deg'], -.375)
        self.assertEqual(sample.ecu_fields['ignition_other_correction_deg'], -48)
        self.assertEqual(sample.ecu_fields['ltft_low_pct'], -6.25)
        self.assertEqual(sample.ecu_fields['ltft_high_pct'], 6.25)
        self.assertEqual(sample.ecu_fields['idle_target_rpm'], 850)


if __name__ == '__main__':
    unittest.main()
