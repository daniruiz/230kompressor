"""Exercise both offline ECU demos through the real measurement and worker paths."""
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import mercedes_dashboard as dashboard
from demo_samples import demo_sample
from sim4le_measurements import decode_snapshot


class DemoTests(unittest.TestCase):
    def test_cli_preserves_default_demo_and_selects_sim4le_without_changing_live_mode(self):
        for options, enabled, family in (([], False, 'ME2.1'), (['--demo'], True, 'ME2.1'),
                (['--demo', 'me21'], True, 'ME2.1'), (['--demo', 'sim4le'], True, 'SIM4LE')):
            args = dashboard.parse_args(options)
            self.assertIs(args.demo, enabled)
            self.assertEqual(args.demo_ecu, family)

    def test_both_demos_vary_all_visible_signals_and_cross_alert_thresholds(self):
        for family in ('ME2.1', 'SIM4LE'):
            samples = [demo_sample(t / 2, family, at=100 + t / 2, duration_ms=125)
                       for t in range(64)]
            values = [s.values() for s in samples]
            for name in ('rpm', 'load', 'injection_ms', 'duty_percent', 'throttle_percent',
                         'trims_percent', 'maf_g_s', 'advance_degrees', 'oxygen_v'):
                self.assertGreater(len({getattr(v, name) for v in values}), 1, (family, name))
            self.assertEqual({v.oxygen_rich for v in values}, {True, False, None})
            self.assertEqual({v.high_retard for v in values}, {True, False})
            for channel, limit in ((0, 110), (1, 70)):
                temperatures = [v.temperatures_c[channel] for v in values]
                self.assertLess(min(temperatures), limit)
                self.assertGreater(max(temperatures), limit)
            if family == 'SIM4LE':
                self.assertTrue(all(s.adapter_voltage_v is None for s in samples))
                self.assertGreater(len({v.map_kpa for v in values}), 1)
                self.assertGreater(len({v.ecu_voltage_v for v in values}), 1)
                for sample in samples:
                    self.assertEqual(len(sample.ecu_raw), 178)
                    self.assertEqual(sample.values(), decode_snapshot(b'\x61\x01' + sample.ecu_raw).values())
                    self.assertEqual(sample.values(tick_us=100), sample.values())
            else:
                self.assertTrue(all(s.adapter_voltage_status == 'ok' for s in samples))
                self.assertTrue(all(v.ecu_voltage_v is None and v.map_kpa is None for v in values))

    def test_worker_runs_each_demo_without_creating_a_reader(self):
        for family in ('me21', 'sim4le'):
            args = dashboard.parse_args(['--demo', family, '--no-log'])
            model, stop, journal, factory = dashboard.Model(), Mock(), Mock(), Mock()
            samples = []
            journal.sample.side_effect = lambda sample, *unused: samples.append(sample)
            stop.is_set.side_effect = lambda: len(samples) == 3
            stop.wait.return_value = False
            with patch.object(dashboard.time, 'monotonic', side_effect=[100, 100, 100.125, 100.25]):
                dashboard.worker(args, model, stop, journal, reader_factory=factory)
            self.assertFalse(model.fatal, model.status)
            factory.assert_not_called()
            self.assertEqual(len(samples), 3)
            self.assertEqual({s.ecu_family for s in samples}, {args.demo_ecu})
            self.assertAlmostEqual(samples[-1].interval_ms, 125)
            journal.close.assert_called_once()

    def test_sim4le_demo_does_not_supply_bosch_diagnostic_results(self):
        journal = Mock()
        faults = dashboard.FaultMenu(demo=True)
        faults.ready = True
        for clear in (False, True, False):
            faults.request(clear)
            faults.service(None, journal, 'SIM4LE')
            self.assertEqual(faults.result_status, 'cleared' if clear else 'read')
            self.assertEqual(faults.fault_scope, 'confirmed_obd')
            for fault in faults.faults or []:
                self.assertIsNone(fault['status'])
                self.assertEqual(fault['scope'], 'confirmed_obd')
        self.assertEqual(faults.faults, [])
        corrections = dashboard.CorrectionsMenu(demo=True)
        corrections.ready = True
        corrections.show()
        corrections.service(None, journal, 'SIM4LE')
        self.assertTrue(corrections.error)
        self.assertIsNone(corrections.result)
        self.assertIn('SIM4LE', corrections.message)


if __name__ == '__main__':
    unittest.main()
