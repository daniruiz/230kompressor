"""Persistent temperature warnings, demo motion and playback without real audio."""
from pathlib import Path
import sys
import threading
import unittest
from unittest.mock import Mock, call, patch

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
        self.assertTrue(accept(115, 70))
        self.assertTrue(accept(115, 71))  # IAT enters while coolant stays hot.
        self.assertTrue(accept(116, 75))
        self.assertTrue(accept(110, 75))
        self.assertTrue(accept(111, 75))
        self.assertTrue(accept(None, None))
        self.assertTrue(accept(110, None))  # Missing IAT is not evidence of cooling.
        self.assertFalse(accept(None, 70))
        self.assertFalse(accept(110, 70))

    def test_first_hot_sample_warns_without_creating_a_knock_event(self):
        sound = Mock()
        model = dashboard.Model(sound=sound)
        for at in (10, 10.1, 10.2):
            self.assertFalse(model.accept(Sample.from_measurements(
                Measurements(rpm=1000, temperatures_c=(111, 71)), at=at)))
        self.assertEqual(sound.update.call_args_list,
                         [call(retard=False, temperature=True)] * 3)
        self.assertEqual(model.count, 0)
        self.assertEqual(model.alert_until, 0)

    def test_simultaneous_knock_and_temperature_share_the_playback_update(self):
        sound = Mock()
        model = dashboard.Model(sound=sound)
        def sample(at, temperature, retard):
            return Sample.from_measurements(Measurements(rpm=1000,
                temperatures_c=(temperature, 38), retard_degrees=(retard,)), at=at)
        model.accept(sample(10, 110, 0))
        self.assertTrue(model.accept(sample(10.1, 111, 3.75)))
        self.assertFalse(model.accept(sample(10.2, 111, 3.75)))
        self.assertEqual(sound.update.call_args_list, [
            call(retard=False, temperature=False),
            call(retard=True, temperature=True),
            call(retard=False, temperature=True),
        ])
        self.assertEqual(model.count, 1)

    def test_temperature_beeps_continue_past_one_burst_without_new_samples(self):
        recovered, extra = threading.Event(), threading.Event()
        tones = []
        def tone():
            tones.append(True)
            if len(tones) == 3:
                sound.update(retard=True, temperature=True)
            if len(tones) == 8:
                sound.update(temperature=False)
                recovered.set()
            elif len(tones) > 8:
                extra.set()
        with patch.object(dashboard.KnockSound, '_tone', side_effect=tone):
            sound = dashboard.KnockSound()
            try:
                sound.update(retard=True, temperature=True)
                self.assertTrue(recovered.wait(2))
                self.assertFalse(extra.wait(.15))
            finally:
                sound.close()
            self.assertFalse(sound.thread.is_alive())

    def test_knock_beeps_remain_a_finite_six_tone_burst(self):
        finished, extra = threading.Event(), threading.Event()
        tones = []
        def tone():
            tones.append(True)
            if len(tones) == 6:
                finished.set()
            elif len(tones) > 6:
                extra.set()
        with patch.object(dashboard.KnockSound, '_tone', side_effect=tone):
            sound = dashboard.KnockSound()
            try:
                sound.update(retard=True)
                self.assertTrue(finished.wait(2))
                self.assertFalse(extra.wait(.15))
            finally:
                sound.close()

    def test_session_failure_cancels_pending_sound(self):
        sound = Mock()
        model = dashboard.Model(sound=sound)
        factory = Mock(side_effect=RuntimeError('connection lost'))
        dashboard.worker(dashboard.parse_args(['--no-log']), model,
                         threading.Event(), Mock(), reader_factory=factory)
        self.assertTrue(model.fatal)
        sound.cancel.assert_called_once()

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
