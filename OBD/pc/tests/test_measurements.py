"""Engineering values must preserve ME2.1 and avoid rescaling another ECU."""
import io
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import mercedes_dashboard as dashboard
from dashboard_sample import Sample
from measurements import Measurements
from rich.console import Console


class MeasurementTests(unittest.TestCase):
    def test_me21_reference_values_and_mutable_demo_sample(self):
        sample = Sample(3000, 3000, 180, (0, 4, 8, 2),
                        trims=(32768, 49152), temperatures=(130, 78),
                        throttle_raw=255, advance_raw=100, oxygen_raw=9912,
                        maf_raw=1023, maf_g_s_est=100.0, maf_ecu_raw=100)
        values = sample.values()
        self.assertEqual((values.rpm, values.load), (3000, 180))
        self.assertEqual((values.injection_ms, values.duty_percent), (8.0, 20.0))
        self.assertEqual(values.retard_degrees, (0.0, 3.0, 6.0, 1.5))
        self.assertEqual(values.temperatures_c, (90, 38))
        self.assertEqual(values.trims_percent, (0.0, 25.0))
        self.assertEqual((values.throttle_percent, values.advance_degrees), (100.0, 9.0))
        self.assertEqual((values.oxygen_v, values.oxygen_rich), (0.5, True))
        self.assertEqual((values.maf_g_s, values.ecu_maf_g_s), (100.0, 111.08))
        self.assertTrue(values.maf_at_limit)
        sample.injection = 6000
        self.assertEqual(sample.values().injection_ms, 16.0)
        self.assertEqual(sample.values(tick_us=1).injection_ms, 6.0)

    def test_external_values_are_not_reinterpreted_as_me21_bytes(self):
        values = Measurements(rpm=4500, load=80, load_maximum=100, load_unit='%',
                              advance_degrees=12.3, retard_degrees=(0.0, 3.75),
                              temperatures_c=(None, 31), trims_percent=(None, 2.5))
        sample = Sample.from_measurements(values, at=10, duration_ms=55)
        self.assertIs(sample.values(tick_us=100), values)
        self.assertIsNone(sample.values().injection_ms)
        self.assertIsNone(sample.values().duty_percent)
        self.assertTrue(sample.high_retard)
        self.assertEqual(sample.at, 10)
        self.assertEqual(sample.duration_ms, 55)

    def test_alerts_compare_engineering_degrees_and_leave_missing_duty_empty(self):
        sound = Mock()
        model = dashboard.Model(sound=sound)
        def accept(at, retard):
            return model.accept(Sample.from_measurements(
                Measurements(rpm=1000, retard_degrees=(retard,)), at=at))
        self.assertFalse(accept(10, 0))
        self.assertTrue(accept(10.1, 3.0))
        sound.notify.assert_not_called()
        self.assertTrue(accept(10.2, 3.75))
        sound.notify.assert_called_once()
        self.assertEqual(model.count, 2)
        self.assertEqual(model.history['retard'], [3.0, 3.75])
        self.assertEqual(list(model.history['duty']), [])
        self.assertFalse(accept(20, 6.0))  # A gap establishes a fresh baseline.

    def test_render_accepts_partial_and_empty_reader_values(self):
        args = dashboard.parse_args(['--demo', '--no-log', '--no-flash'])
        for values in (Measurements(), Measurements(
                rpm=4500, load=80, load_maximum=100, load_unit='%',
                advance_degrees=12.3, temperatures_c=(None, 31),
                trims_percent=(None, 2.5), oxygen_v=None)):
            with self.subTest(values=values):
                model = dashboard.Model()
                model.accept(Sample.from_measurements(values, at=10))
                output = io.StringIO()
                console = Console(file=output, width=110, height=44, color_system=None)
                console.print(dashboard.render(model, args, 110, 44, now=10.1))
                text = output.getvalue()
                self.assertIn('—', text)
                self.assertNotIn('None', text)
                self.assertNotIn('nan', text)
                if values.advance_degrees is not None:
                    self.assertIn('+12.3', text)


if __name__ == '__main__':
    unittest.main()
