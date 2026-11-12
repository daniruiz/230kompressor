"""CSV/JSONL retain legacy data without inventing values for a different reader."""
import csv
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import mercedes_dashboard as dashboard
from dashboard_sample import Sample
from measurements import Measurements


class MeasurementLogTests(unittest.TestCase):
    def capture(self, *samples):
        args = dashboard.parse_args([])
        model = dashboard.Model()
        with tempfile.TemporaryDirectory(prefix='me21_reader_test_') as directory:
            journal = dashboard.Journal(Path(directory), True)
            try:
                for sample in samples:
                    event = model.accept(sample, args.tick_us)
                    journal.sample(sample, args, model, event)
            finally:
                journal.close()
            records = [json.loads(line) for line in journal.path.read_text(encoding='utf-8').splitlines()]
            with journal.csv_path.open(encoding='utf-8', newline='') as stream:
                rows = list(csv.DictReader(stream))
        return records, rows

    def test_me21_log_retains_raw_fields_and_expected_engineering_values(self):
        sample = Sample(3000, 3000, 180, (0, 4, 8, 2), at=5,
                        trims=(32768, 49152), temperatures=(130, 78),
                        throttle_raw=255, advance_raw=100, oxygen_raw=9912,
                        maf_raw=1023, maf_g_s_est=100.0, maf_ecu_raw=100,
                        diagnostic_ids=dashboard.IDS)
        records, rows = self.capture(sample)
        expected = dict(rpm=3000, load_raw=180, F746_raw=3000,
                        injection_ms_est=8.0, injector_duty_est_pct=20.0,
                        coolant_C=90, iat_C=38, stft_a_pct=0.0, ltft_a_pct=25.0,
                        advance_deg=9.0, oxygen_voltage_V=0.5, maf_ecu_g_s_est=111.08,
                        knock_channel_3_raw=8, knock_channel_3_deg_est=6.0)
        for key, value in expected.items():
            self.assertEqual(records[0][key], value, key)
        self.assertEqual(records[0]['maf_source'], 'grouped_1143_adc')
        self.assertEqual(records[0]['diagnostic_ids'], [f'11{i:02X}' for i in dashboard.IDS])
        self.assertEqual(rows[0]['Time'], '0.000')
        self.assertEqual(rows[0]['injection_ms_est'], '8.0')

    def test_external_reader_keeps_unknowns_blank_and_excludes_me21_claims(self):
        values = Measurements(rpm=4500, load=80, load_unit='%',
                              advance_degrees=14.25, retard_degrees=(0, 3.375),
                              trims_percent=(-1.25, None), temperatures_c=(None, 33),
                              maf_g_s=37.5, ecu_maf_g_s=38.0)
        records, rows = self.capture(Sample.from_measurements(values, at=5))
        row = records[0]
        self.assertEqual(row['knock_channel_2_deg_est'], 3.375)
        self.assertEqual(row['advance_deg'], 14.25)
        self.assertEqual(row['stft_a_pct'], -1.25)
        self.assertEqual((row['load_value'], row['load_unit']), (80, '%'))
        for key in ('injection_ms_est', 'injector_duty_est_pct', 'oxygen_voltage_V',
                    'coolant_C', 'ltft_a_pct', 'maf_saturated'):
            self.assertIsNone(row[key], key)
        for key in ('F746_raw', 'load_raw', 'maf_source', 'maf_curve_sha256',
                    'maf_adc_word', 'diagnostic_ids', 'ltft_low_address'):
            self.assertNotIn(key, row)
        for key in ('injection_ms_est', 'injector_duty_est_pct', 'coolant_C', 'load_raw'):
            self.assertEqual(rows[0][key], '', key)

    def test_supplied_injection_and_sample_clock_are_preserved(self):
        values = Measurements(rpm=2000, injection_ms=9.25, duty_percent=17.125)
        samples = [Sample.from_measurements(values, at=at) for at in (5, 6.25)]
        records, rows = self.capture(*samples)
        self.assertEqual(records[1]['injection_ms_est'], 9.25)
        self.assertEqual(records[1]['injector_duty_est_pct'], 17.125)
        self.assertEqual(records[1]['cycle_ms'], 1250.0)
        self.assertEqual([row['Time'] for row in rows], ['0.000', '1.250'])


if __name__ == '__main__':
    unittest.main()
