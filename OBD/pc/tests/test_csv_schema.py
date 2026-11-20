"""CSV and JSONL share metric names, values and ECU-specific provenance."""
import csv
import json
from pathlib import Path
import tempfile
import unittest
import test_measurement_logs as legacy
from dashboard_sample import Sample
from mercedes_dashboard import Journal
from sim4le_kwp import positive_reply
from sim4le_measurements import decode_snapshot


class CsvSchemaTests(unittest.TestCase):
    capture = legacy.MeasurementLogTests.capture

    def test_headers_are_unique_and_never_contain_est(self):
        with tempfile.TemporaryDirectory() as directory:
            journal = Journal(Path(directory), True)
            try:
                self.assertEqual(len(journal.csv_fields), len(set(journal.csv_fields)))
                self.assertEqual(len(journal.csv_fields), 239)
                self.assertNotIn('maf_ecu_g_s', journal.csv_fields)
                self.assertFalse(any('_est' in name for name in journal.csv_fields))
                self.assertTrue({'stft_pct', 'ltft_pct'}.issubset(journal.csv_fields))
                self.assertFalse({'stft_a_pct', 'ltft_a_pct', 'lambda_correction_pct',
                                  'ltft_applied_pct'}.intersection(journal.csv_fields))
            finally:
                journal.close()

    def test_both_ecus_use_common_metric_names_in_csv_and_jsonl(self):
        me21 = Sample(3000, 3000, 180, (0, 4, 8, 2), at=5,
                      trims=(32768, 49152), temperatures=(130, 78),
                      throttle_raw=255, advance_raw=100, oxygen_raw=9912,
                      bypass_raw=128, bypass_limit_raw=255, maf_ecu_raw=100,
                      adapter_voltage_v=13.8)
        path = Path(__file__).resolve().parents[2] / 'fixtures/sim4le_5116_fixed.json'
        reply = json.loads(path.read_text())[1]['reply']
        sim4le = decode_snapshot(positive_reply(reply, b'\x21\x01', b'\x61\x01', 180), at=6)
        records, rows = self.capture(me21, sim4le)
        self.assertEqual(list(rows[0]), list(rows[1]))
        for sample, record, row in zip((me21, sim4le), records, rows):
            self.assertFalse(any('_est' in name for name in record))
            self.assertFalse({'stft_a_pct', 'ltft_a_pct', 'lambda_correction_pct',
                              'ltft_applied_pct'}.intersection(record))
            self.assertFalse({'stft_a_raw', 'ltft_a_raw'}.intersection(record))
            values = sample.values()
            expected = dict(rpm=values.rpm, throttle_pct=values.throttle_percent,
                            maf_g_s=values.maf_g_s, injection_ms=values.injection_ms,
                            injector_duty_pct=values.duty_percent,
                            advance_deg=values.advance_degrees,
                            coolant_C=values.temperatures_c[0], iat_C=values.temperatures_c[1],
                            oxygen_voltage_V=values.oxygen_v,
                            stft_pct=values.trims_percent[0], ltft_pct=values.trims_percent[1])
            expected.update({f'knock_channel_{i}_deg': value
                             for i, value in enumerate(values.retard_degrees, 1)})
            for name, value in expected.items():
                self.assertEqual(record[name], value, name)
                self.assertEqual(float(row[name]), value, name)
        # Similar gauges do not imply interchangeable source signals or raw scales.
        for record, row in zip(records, rows):
            self.assertNotIn('maf_source', record)
            self.assertNotIn('maf_source', row)
            self.assertNotIn('maf_ecu_g_s', record)
            self.assertNotIn('maf_ecu_g_s', row)
        self.assertAlmostEqual(float(rows[0]['bypass_pct']), me21.values().bypass_percent)
        self.assertEqual(rows[1]['bypass_pct'], '')
        self.assertEqual(rows[0]['adapter_voltage_V'], '13.8')
        self.assertEqual(rows[0]['ecu_voltage_V'], '')
        self.assertEqual(rows[1]['adapter_voltage_V'], '')
        self.assertEqual(float(rows[1]['ecu_voltage_V']), sim4le.values().ecu_voltage_v)
        self.assertEqual(rows[0]['stft_pct'], '0.0')
        self.assertEqual(rows[0]['ltft_pct'], '25.0')
        self.assertEqual(float(rows[1]['stft_pct']), sim4le.ecu_fields['stft_pct'])
        self.assertEqual(float(rows[1]['ltft_pct']), sim4le.ecu_fields['ltft_pct'])
        self.assertEqual(rows[1]['load_raw16'], '5310')
        self.assertEqual(rows[1]['scaled_load_raw'], '21')

    def test_common_trims_keep_zero_negative_and_missing_values_for_both_ecus(self):
        sources = [
            dict(ecu_family='ME2.1', stft_pct=-12.5, ltft_pct=0),
            dict(ecu_family='SIM4LE', stft_pct=0, ltft_pct=-2.5),
            dict(ecu_family='SIM4LE', stft_pct=None),
            dict(ecu_family='ME2.1'),
        ]
        with tempfile.TemporaryDirectory() as directory:
            journal = Journal(Path(directory), True)
            try:
                for index, source in enumerate(sources):
                    journal.write('csv_sample', csv_sample_at=index, **source)
            finally:
                journal.close()
            with journal.csv_path.open(encoding='utf-8', newline='') as stream:
                rows = list(csv.DictReader(stream))
            self.assertEqual([(r['stft_pct'], r['ltft_pct']) for r in rows],
                             [('-12.5', '0'), ('0', '-2.5'), ('', ''), ('', '')])


if __name__ == '__main__':
    unittest.main()
