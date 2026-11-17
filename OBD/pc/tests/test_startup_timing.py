"""Startup waits and retries without serial hardware or real sleeps."""
import sys
from pathlib import Path
import unittest
from unittest.mock import ANY, Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ecu_reader import DiagnosticError, LinkTimeout
from mercedes_dashboard import ELM


class StartupTimingTests(unittest.TestCase):
    def setUp(self):
        self.clock = 0.0
        self.elm = ELM.__new__(ELM)
        self.elm.fast = True
        self.elm.adapter_identity = 'ELM327'
        self.elm.port_metadata = {}
        self.elm.port, self.elm.baud = 'COM3', 38400
        self.elm.response_timeout = '80'
        self.elm.journal = Mock()
        self.elm.stop = Mock()
        self.elm.stop.wait.side_effect = self.wait
        self.elm.at = Mock()
        self.elm.command = Mock(return_value='OK')
        self.elm._read_single_frame = Mock()
        self.elm._read_adapter_voltage = Mock()
        self.notice = Mock()

    def wait(self, seconds):
        self.clock += seconds
        return False

    def setup(self, **kwargs):
        with patch('mercedes_dashboard.time.monotonic', side_effect=lambda: self.clock):
            self.elm.setup(self.notice, '0', **kwargs)

    def test_direct_attempt_has_no_sleep_and_verifies_link(self):
        self.setup(initial_wait=0, retry=False)
        self.elm.stop.wait.assert_not_called()
        self.elm.command.assert_any_call('ATSI', timeout=20)
        self.elm._read_single_frame.assert_called_once()
        self.elm._read_adapter_voltage.assert_called_once()

    def test_retry_wait_is_three_seconds_after_closing_protocol(self):
        events = []
        self.elm.at.side_effect = lambda command, **kw: events.append(command)
        def wait(seconds):
            events.append(('wait', seconds))
            return self.wait(seconds)
        self.elm.stop.wait.side_effect = wait
        self.setup(initial_wait=3, retry=False)
        self.assertEqual(self.clock, 3)
        self.assertEqual(events[0], 'ATPC')
        self.assertEqual(events[1], ('wait', 2))

    def test_fast_failure_does_not_run_internal_six_second_retry(self):
        self.elm.command.side_effect = lambda cmd, **kw: 'BUS INIT ERROR' if cmd == 'ATSI' else 'OK'
        with self.assertRaises(DiagnosticError):
            self.setup(initial_wait=0, retry=False)
        self.elm.stop.wait.assert_not_called()
        self.assertEqual(sum(c.args[0] == 'ATSI' for c in self.elm.command.call_args_list), 1)

    def test_conservative_defaults_keep_six_second_wait_and_retry(self):
        replies = iter(('BUS INIT ERROR', 'OK'))
        self.elm.command.side_effect = lambda cmd, **kw: next(replies) if cmd == 'ATSI' else 'OK'
        self.setup()
        self.assertEqual(self.clock, 12)
        self.elm.journal.write.assert_any_call('connection_retry', reason=ANY, wait_ms=6000)

    def test_lost_prompt_and_cancellation_stop_initialization(self):
        def command(cmd, **kwargs):
            if cmd == 'ATSI':
                raise LinkTimeout('lost prompt')
            return 'OK'
        self.elm.command.side_effect = command
        with self.assertRaises(LinkTimeout):
            self.setup(initial_wait=0, retry=False)
        self.elm._read_single_frame.assert_not_called()
        self.elm.command.reset_mock()
        self.elm.stop.wait.side_effect = None
        self.elm.stop.wait.return_value = True
        with self.assertRaises(InterruptedError):
            self.setup(initial_wait=3, retry=False)
        self.assertFalse(any(c.args[0] == 'ATSI' for c in self.elm.command.call_args_list))


if __name__ == '__main__':
    unittest.main()
