"""Keep both ECU layouts readable, and never mix their voltage/MAF sources."""
import io
import json
from pathlib import Path
import unittest
from unittest.mock import patch
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import mercedes_dashboard as dashboard
import dashboard_i18n
from dashboard_sample import Sample
from sim4le_kwp import positive_reply
from sim4le_measurements import decode_snapshot
from demo_samples import demo_sample
from rich.console import Console


class DisplayTests(unittest.TestCase):
    def draw(self, sample, width=110, height=44, language='en', now=10.1, demo=None):
        args = dashboard.parse_args(['--no-log', '--no-flash'] + (['--demo', demo] if demo else []))
        model = dashboard.Model()
        model.accept(sample)
        stream = io.StringIO()
        console = Console(file=stream, width=width, height=height, color_system=None)
        with patch.object(dashboard_i18n, 'LANGUAGE', language):
            console.print(dashboard.render(model, args, width, height, now=now))
        return stream.getvalue()

    def sim_sample(self):
        path = Path(__file__).resolve().parents[2] / 'fixtures/sim4le_5116_fixed.json'
        reply = json.loads(path.read_text())[1]['reply']
        return decode_snapshot(positive_reply(reply, b'\x21\x01', b'\x61\x01', 180), at=10)

    def test_map_and_maf_share_a_line_at_both_supported_sizes_and_languages(self):
        sample = self.sim_sample()
        sample.adapter_voltage_v, sample.adapter_voltage_status = 99.9, 'ok'
        for language in ('en', 'es'):
            for width, height in ((78, 30), (110, 44)):
                with self.subTest(language=language, width=width):
                    text = self.draw(sample, width, height, language)
                    line = next(line for line in text.splitlines() if 'MAP/MAF' in line)
                    self.assertIn('kPa', line)
                    self.assertIn('g/s', line)
                    self.assertIn('ECU 13.3 V', text)
                    self.assertNotIn('99.9', text)
                    self.assertIn('INYECCIÓN' if language == 'es' else 'INJECTION', text)
                    self.assertIn('~1.46 ms', text)
                    self.assertNotIn('366 raw', text)  # SIM4LE hides the raw injection timer.
                    self.assertIn('LTFT', text)
                    for i in range(1, 5):
                        self.assertIn(f'C{i}', text)

    def test_me21_keeps_adapter_voltage_and_only_airflow(self):
        sample = Sample(3000, 3000, 180, (0, 4, 8, 2), at=10,
                        maf_g_s_est=100, adapter_voltage_v=12.8, adapter_voltage_status='ok')
        text = self.draw(sample)
        self.assertIn('Airflow', text)
        self.assertNotIn('MAP/MAF', text)
        self.assertIn('OBD 12.8 V', text)
        self.assertNotIn('BASE DUTY', text)

    def test_stale_sample_hides_ecu_voltage(self):
        text = self.draw(self.sim_sample(), now=14)
        self.assertNotIn('ECU 13.3 V', text)
        line = next(line for line in text.splitlines() if 'MAP/MAF' in line)
        self.assertIn('—', line)

    def test_demo_layouts_and_oxygen_labels_in_both_languages(self):
        for family in ('ME2.1', 'SIM4LE'):
            for language, lean, rich in (('en', 'lean', 'rich'), ('es', 'pobre', 'rico')):
                for t, label in ((0, lean), (2, lean), (4, None), (6, None), (8, None), (10, rich)):
                    with self.subTest(family=family, language=language, t=t):
                        sample = demo_sample(t, family, at=10, duration_ms=125)
                        text = self.draw(sample, language=language,
                                         demo='sim4le' if family == 'SIM4LE' else 'me21')
                        line = next(line for line in text.splitlines()
                                    if ('O₂ sensor' if language == 'en' else 'Sonda λ') in line)
                        self.assertIn(f'{sample.values().oxygen_v:.3f} V', line)
                        self.assertEqual(lean in line, label == lean)
                        self.assertEqual(rich in line, label == rich)
                        self.assertEqual('MAP/MAF' in text, family == 'SIM4LE')
                        voltage = sample.values().ecu_voltage_v if family == 'SIM4LE' else sample.adapter_voltage_v
                        self.assertIn(('ECU ' if family == 'SIM4LE' else 'OBD ') + f'{voltage:.1f} V', text)
                        self.assertIn('DEMO', text)
                        self.assertIn(family, text)

    def test_me21_keeps_validated_advance_direction(self):
        sample = Sample(3000, 3000, 180, (0, 0, 0, 0), at=10)
        for raw, label in ((68, 'before TDC'), (88, 'at TDC'), (108, 'after TDC')):
            sample.advance_raw = raw
            self.assertIn(label, self.draw(sample))


if __name__ == '__main__':
    unittest.main()
