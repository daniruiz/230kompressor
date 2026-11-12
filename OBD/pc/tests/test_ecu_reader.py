"""ME2.1 acquisition policy tests; no adapter, UI or executable build required."""
import sys
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, call

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ecu_reader import DiagnosticError, LinkTimeout
from me21_reader import Me21Reader


class Me21ReaderTests(unittest.TestCase):
    def setUp(self):
        self.args = SimpleNamespace(individual=False, adaptive=True,
                                    no_adapter_tuning=False, elm_timing_probe=True)
        self.elm = Mock(is_stn=True, ecu_profile=None, ecu_software='404',
                        ecu_identification_status='verified', adapter_profile='STN')
        self.elm.restore_transport.return_value = False
        self.journal = Mock()
        self.reader = Me21Reader(self.elm, self.args, self.journal)
        self.notice, self.reject, self.publish = Mock(), Mock(), Mock()

    def test_start_publishes_first_group_before_tuning_and_voltage_polling(self):
        sequence = Mock()
        sequence.attach_mock(self.elm, 'elm')
        sequence.attach_mock(self.publish, 'publish')
        first = self.elm.define_group.return_value
        self.reader.identify(self.notice)
        self.reader.start(self.notice, self.reject, self.publish)
        self.assertEqual(sequence.mock_calls, [
            call.elm.setup(self.notice, True), call.elm.identify_ecu(self.notice),
            call.elm.define_group(), call.publish(first),
            call.elm.optimize_transport('grouped', self.notice, self.publish),
            call.elm.enable_adapter_voltage_polling(),
        ])
        self.reject.assert_not_called()
        self.assertEqual(self.reader.ecu_software, '404')

    def test_group_rejection_keeps_individual_reading_available(self):
        self.elm.define_group.side_effect = DiagnosticError('unsupported group')
        self.elm.is_stn = False
        self.reader.start(self.notice, self.reject, self.publish)
        self.assertEqual(self.reader.mode, 'individual')
        self.publish.assert_not_called()
        self.reject.assert_called_once()
        self.journal.write.assert_called_once_with('fallback', reason='unsupported group')
        self.elm.optimize_elm_transport.assert_called_once_with(
            'individual', self.notice, self.publish)
        self.assertIs(self.reader.read(), self.elm.acquire.return_value)
        self.elm.acquire.assert_called_once_with('individual')

    def test_timeout_does_not_try_more_commands_on_an_unsynchronized_link(self):
        self.elm.define_group.side_effect = LinkTimeout('missing prompt')
        with self.assertRaises(LinkTimeout):
            self.reader.start(self.notice, self.reject, self.publish)
        self.elm.optimize_transport.assert_not_called()
        self.elm.enable_adapter_voltage_polling.assert_not_called()
        self.assertEqual(self.reader.mode, 'grouped')

    def test_recovery_preserves_rollback_and_failure_thresholds(self):
        error = DiagnosticError('bad frame')
        self.elm.restore_transport.return_value = True
        self.assertEqual(self.reader.recover(error, 3, self.notice), 3)
        self.assertEqual(self.reader.mode, 'grouped')
        self.elm.restore_transport.return_value = False
        self.assertEqual(self.reader.recover(error, 2, self.notice), 2)
        self.assertEqual(self.reader.mode, 'grouped')
        self.assertEqual(self.reader.recover(error, 3, self.notice), 0)
        self.assertEqual(self.reader.mode, 'individual')
        self.assertEqual(self.reader.recover(error, 4, self.notice), 4)
        with self.assertRaises(DiagnosticError):
            self.reader.recover(error, 5, self.notice)

    def test_manual_individual_mode_and_disabled_tuning(self):
        self.args.individual = self.args.no_adapter_tuning = True
        reader = Me21Reader(self.elm, self.args, self.journal)
        reader.start(self.notice, self.reject, self.publish)
        self.elm.define_group.assert_not_called()
        self.elm.optimize_transport.assert_not_called()
        self.elm.enable_adapter_voltage_polling.assert_called_once()
        self.assertEqual(reader.mode, 'individual')

    def test_diagnostics_and_close_delegate_without_extra_reads(self):
        self.assertIs(self.reader.read_faults(), self.elm.read_faults.return_value)
        self.reader.clear_faults()
        self.assertIs(self.reader.read_fuel_corrections(),
                      self.elm.read_fuel_corrections.return_value)
        self.reader.close()
        self.assertEqual([c[0] for c in self.elm.mock_calls],
                         ['read_faults', 'clear_faults', 'read_fuel_corrections', 'close'])


if __name__ == '__main__':
    unittest.main()
