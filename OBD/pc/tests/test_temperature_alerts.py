"""Temperature entry warnings, demo motion and presentation without real audio."""
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import mercedes_dashboard as dashboard
from dashboard_sample import Sample
from measurements import Measurements
from temperature_alerts import TemperatureAlerts


class TemperatureTests(unittest.TestCase):
    def test_thresholds_recovery_and_independent_channels(self):
        alerts = TemperatureAlerts()
        def accept(coolant, iat):
            return alerts.accept(Measurements(temperatures_c=(coolant, iat)))
        self.assertFalse(accept(110, 70))
        self.assertTrue(accept(111, 70))
        self.assertFalse(accept(115, 70))
        self.assertTrue(accept(115, 71))  # IAT enters while coolant stays hot.
        self.assertFalse(accept(116, 75))
        self.assertFalse(accept(110, 75))
        self.assertTrue(accept(111, 75))
        self.assertFalse(accept(None, None))
        self.assertTrue(accept(111, None))  # Matches Android rearming after missing data.

    def test_first_hot_sample_warns_without_creating_a_knock_event(self):
        sound = Mock()
        model = dashboard.Model(sound=sound)
        for at in (10, 10.1, 10.2):
            self.assertFalse(model.accept(Sample.from_measurements(
                Measurements(rpm=1000, temperatures_c=(111, 71)), at=at)))
        sound.notify.assert_called_once()
        self.assertEqual(model.count, 0)
        self.assertEqual(model.alert_until, 0)

    def test_simultaneous_knock_and_temperature_produce_one_burst(self):
        sound = Mock()
        model = dashboard.Model(sound=sound)
        def sample(at, temperature, retard):
            return Sample.from_measurements(Measurements(rpm=1000,
                temperatures_c=(temperature, 38), retard_degrees=(retard,)), at=at)
        model.accept(sample(10, 110, 0))
        self.assertTrue(model.accept(sample(10.1, 111, 3.75)))
        self.assertFalse(model.accept(sample(10.2, 111, 3.75)))
        sound.notify.assert_called_once()
        self.assertEqual(model.count, 1)

    def test_demo_temperatures_move_and_cross_both_warning_thresholds(self):
        args = dashboard.parse_args(['--demo', '--no-log'])
        model, stop, journal, factory = dashboard.Model(), Mock(), Mock(), Mock()
        samples = []
        times = list(range(0, 32, 4))
        journal.sample.side_effect = lambda sample, *unused: samples.append(sample.values())
        stop.is_set.side_effect = lambda: len(samples) == len(times)
        stop.wait.return_value = False
        with patch.object(dashboard.time, 'monotonic', side_effect=[0, *times]):
            dashboard.worker(args, model, stop, journal, reader_factory=factory)
        self.assertFalse(model.fatal)
        factory.assert_not_called()
        coolant, iat = zip(*(sample.temperatures_c for sample in samples))
        for values, limit in ((coolant, 110), (iat, 70)):
            self.assertGreater(len(set(values)), 1)
            self.assertLess(min(values), limit)
            self.assertGreater(max(values), limit)

    def test_hot_panel_respects_no_flash_and_stale_samples(self):
        args = dashboard.parse_args(['--demo', '--no-log'])
        model = dashboard.Model()
        model.accept(Sample.from_measurements(
            Measurements(temperatures_c=(111, 71)), at=10))
        def panel(now):
            return dashboard.render(model, args, now=now)['temps'].renderable
        self.assertEqual(panel(10.1).border_style, dashboard.RED)
        self.assertEqual(panel(10.1).style, 'on #811c31')
        args.no_flash = True
        self.assertEqual(panel(10.1).style, f'on {dashboard.BG}')
        self.assertEqual(panel(13).border_style, dashboard.AMBER)


if __name__ == '__main__':
    unittest.main()
