"""The full snapshot must survive CSV export, including unassigned bytes."""
import json
from pathlib import Path
import unittest
import test_measurement_logs as legacy
from sim4le_kwp import positive_reply
from sim4le_measurements import decode_snapshot


class Sim4leLogTests(unittest.TestCase):
    capture = legacy.MeasurementLogTests.capture
    def test_recorded_snapshot_round_trips_all_178_bytes_through_csv(self):
        path = Path(__file__).resolve().parents[2] / 'fixtures/sim4le_5116_fixed.json'
        reply = json.loads(path.read_text())[1]['reply']
        sample = decode_snapshot(positive_reply(reply, b'\x21\x01', b'\x61\x01', 180),
                                 at=5, duration_ms=315)
        records, rows = self.capture(sample)
        row, record = rows[0], records[0]
        restored = bytes(int(row[f'sim4le_2101_byte_{i:03d}']) for i in range(178))
        self.assertEqual(restored, sample.ecu_raw)
        self.assertEqual(bytes.fromhex(row['sim4le_2101_raw']), sample.ecu_raw)
        for key, value in sample.ecu_fields.items():
            self.assertEqual(record[key], value, key)
            self.assertEqual(row[key], str(value), key)
        self.assertEqual(row['ecu_family'], 'SIM4LE')
        self.assertEqual(float(row['map_kpa']), sample.values().map_kpa)
        self.assertEqual(float(row['ecu_voltage_V']), sample.values().ecu_voltage_v)
        self.assertEqual(row['acquisition_ms'], '315')
        for key in ('adapter_voltage_V', 'stft_a_pct', 'ltft_a_pct'):
            self.assertEqual(row[key], '')
        self.assertNotIn('F746_raw', record)
        self.assertNotIn('maf_curve_sha256', record)
        self.assertIn('base', row['injection_basis'])
