"""Exercise the UI/acquisition boundary with a reader that has no ELM API."""
import sys
from pathlib import Path
import threading
import unittest
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import mercedes_dashboard as dashboard
from ecu_reader import DiagnosticError, LinkTimeout


class WorkerTests(unittest.TestCase):
    def setUp(self):
        self.args = dashboard.parse_args(['--no-log'])
        self.model = dashboard.Model()
        self.stop = threading.Event()
        self.journal, self.faults, self.corrections = Mock(), Mock(), Mock()
        self.reader = Mock(spec=[
            'ecu_profile', 'ecu_software', 'ecu_identification_status', 'mode',
            'adapter_profile', 'identify', 'start', 'read', 'recover', 'close',
        ], ecu_profile=None, ecu_software='test reader',
            ecu_identification_status='test', mode='test mode', adapter_profile='test link')
        self.first = dashboard.Sample(1000, 1200, 70, (0, 0, 0, 0), at=10)
        self.next = dashboard.Sample(1100, 1250, 75, (0, 0, 0, 0), at=10.1)
        self.reader.start.side_effect = lambda notice, reject, publish: publish(self.first)
        self.factory = Mock(return_value=self.reader)

    def run_worker(self):
        dashboard.worker(self.args, self.model, self.stop, self.journal,
                         self.faults, self.corrections, reader_factory=self.factory)

    def stop_with_sample(self):
        self.stop.set()
        return self.next

    def test_reader_injection_publishes_and_serializes_menu_requests(self):
        order = []
        self.reader.identify.side_effect = lambda notice: order.append('identify')
        self.faults.service.side_effect = lambda reader, log: order.append('faults')
        self.corrections.service.side_effect = lambda reader, log: order.append('corrections')
        def read():
            order.append('read')
            return self.stop_with_sample()
        self.reader.read.side_effect = read
        self.run_worker()
        self.assertEqual(order, ['identify', 'faults', 'corrections', 'read'])
        self.assertEqual(self.model.samples, 2)
        self.assertIs(self.model.sample, self.next)
        self.assertEqual(self.model.ecu_software, 'test reader')
        self.assertEqual(self.model.mode, 'test mode')
        self.assertFalse(self.model.fatal)
        self.assertEqual(self.journal.sample.call_count, 2)
        self.faults.service.assert_called_once_with(self.reader, self.journal)
        self.corrections.service.assert_called_once_with(self.reader, self.journal)
        self.assertFalse(self.faults.ready)
        self.corrections.finish.assert_called_once()
        self.reader.close.assert_called_once()
        self.journal.close.assert_called_once()

    def test_link_timeout_terminates_without_recovery_or_second_read(self):
        self.reader.read.side_effect = LinkTimeout('missing prompt')
        self.run_worker()
        self.assertTrue(self.model.fatal)
        self.reader.read.assert_called_once()
        self.reader.recover.assert_not_called()
        self.reader.close.assert_called_once()
        self.corrections.finish.assert_called_once()
        self.journal.close.assert_called_once()

    def test_recoverable_errors_are_delegated_and_valid_sample_resumes(self):
        errors = [DiagnosticError('first'), DiagnosticError('second')]
        remaining = iter(errors + [self.next])
        def read():
            item = next(remaining)
            if isinstance(item, Exception):
                raise item
            return self.stop_with_sample()
        self.reader.read.side_effect = read
        self.reader.recover.side_effect = lambda error, count, notice: count
        self.run_worker()
        self.assertEqual([c.args[:2] for c in self.reader.recover.call_args_list],
                         [(errors[0], 1), (errors[1], 2)])
        self.assertEqual(self.model.errors, 2)
        self.assertFalse(self.model.fatal)
        self.assertFalse(self.model.failed)
        self.assertIs(self.model.sample, self.next)

    def test_failed_reader_creation_still_closes_journal(self):
        self.factory.side_effect = DiagnosticError('adapter unavailable')
        self.run_worker()
        self.assertTrue(self.model.fatal)
        self.reader.close.assert_not_called()
        self.journal.close.assert_called_once()
