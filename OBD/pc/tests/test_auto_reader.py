"""Connection ordering, recovery and transport safety, without an adapter."""
import sys
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from auto_reader import AutoReader
from ecu_reader import DiagnosticError, LinkTimeout


class AutoReaderTests(unittest.TestCase):
    def setUp(self):
        self.transport, self.journal, self.notice = Mock(), Mock(), Mock()
        self.attempts = []
        self.readers = []
        self.failures = []

    def factory(self, family):
        def create(*args):
            reader = Mock()
            def identify(notice, *, initial_wait, retry):
                self.attempts.append((family, initial_wait, retry))
                if self.failures:
                    error = self.failures.pop(0)
                    if error:
                        raise error
            reader.identify.side_effect = identify
            self.readers.append(reader)
            return reader
        return create

    def connect(self, preferred=None):
        reader = AutoReader(self.transport, Mock(), self.journal, preferred_family=preferred)
        with patch('auto_reader.Me21Reader', side_effect=self.factory('ME2.1')), \
             patch('auto_reader.Sim4leReader', side_effect=self.factory('SIM4LE')):
            reader.identify(self.notice)
        return reader

    def test_direct_connection_and_unknown_preference_default_to_bosch(self):
        for preference, expected in [(None, 'ME2.1'), ('invalid', 'ME2.1'), ('SIM4LE', 'SIM4LE')]:
            self.attempts.clear()
            reader = self.connect(preference)
            self.assertEqual(self.attempts, [(expected, 0, False)])
            reader.read()
            self.readers[-1].read.assert_called_once()

    def test_first_rejection_is_logged_then_same_ecu_retries_after_three_seconds(self):
        self.failures = [DiagnosticError('BUS INIT ERROR')]
        self.connect('SIM4LE')
        self.assertEqual(self.attempts, [('SIM4LE', 0, False), ('SIM4LE', 3, False)])
        self.journal.write.assert_any_call('ecu_family_probe', family='SIM4LE', error='BUS INIT ERROR')
        self.notice.assert_not_called()  # Errors are logged, not passed to the UI.

    def test_changed_car_tries_other_ecu_conservatively(self):
        self.failures = [DiagnosticError('wrong family')] * 2
        self.connect('SIM4LE')
        self.assertEqual(self.attempts, [('SIM4LE', 0, False), ('SIM4LE', 3, False), ('ME2.1', 6, True)])

    def test_final_recovery_preserves_original_wait_and_reports_only_final_failure(self):
        error = DiagnosticError('unavailable')
        self.failures = [error] * 4
        with self.assertRaises(DiagnosticError) as result:
            self.connect()
        self.assertIs(result.exception, error)
        self.assertEqual(self.attempts, [('ME2.1', 0, False), ('ME2.1', 3, False),
                                        ('SIM4LE', 6, True), ('ME2.1', 6, True)])

    def test_timeout_and_cancellation_never_try_another_reader(self):
        for error in (LinkTimeout('lost prompt'), InterruptedError()):
            self.attempts.clear()
            self.failures = [error]
            with self.assertRaises(type(error)):
                self.connect('SIM4LE')
            self.assertEqual(self.attempts, [('SIM4LE', 0, False)])

    def test_close_works_even_before_identification(self):
        AutoReader(self.transport, Mock(), self.journal).close()
        self.transport.close.assert_called_once()


if __name__ == '__main__':
    unittest.main()
