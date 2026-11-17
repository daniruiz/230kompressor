"""Fake adapter: startup selection, one request per cycle, and prompt loss."""
from pathlib import Path
import json
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from auto_reader import AutoReader
from ecu_reader import DiagnosticError, LinkTimeout
from sim4le_reader import Sim4leReader
from mercedes_dashboard import parse_args
from test_sim4le_kwp import frame


class ReaderTests(unittest.TestCase):
    def setUp(self):
        self.args = parse_args([])
        self.journal = Mock()
        self.elm = Mock(adapter_profile='ELM', adapter_voltage_polling=True)
        self.elm.stop.wait.return_value = False
        path = Path(__file__).resolve().parents[2] / 'fixtures/sim4le_5116_fixed.json'
        self.reply = json.loads(path.read_text())[1]['reply']
        self.elm.command.side_effect = lambda command, **kw: (
            frame(b'\x5a\x94' + b'6577815116--') if command.startswith('1A94') else
            self.reply if command.startswith('2101') else 'OK')

    def test_fixed_reader_delivers_every_value_in_one_transaction(self):
        reader = Sim4leReader(self.elm, self.args, self.journal)
        reader.identify(Mock())
        self.assertEqual(reader.ecu_software, '6577815116--')
        self.assertFalse(self.elm.adapter_voltage_polling)
        self.elm.command.reset_mock()
        publish = Mock()
        reader.start(Mock(), Mock(), publish)
        sample = publish.call_args.args[0]
        self.assertEqual(sample.ecu_family, 'SIM4LE')
        self.assertEqual(len(sample.ecu_raw), 178)
        self.assertIsNotNone(sample.values().advance_degrees)
        self.elm.command.assert_called_once_with('2101 1')
        self.assertEqual(self.elm.request_gap, 0)

    def test_corrections_use_fixed_block_and_no_bosch_memory_addresses(self):
        reader = Sim4leReader(self.elm, self.args, self.journal)
        result = reader.read_fuel_corrections().values()
        self.assertIsNotNone(result.low_percent)
        self.assertIsNone(result.additive_ms)
        self.elm.command.assert_called_once_with('2101 1')

    def test_startup_wait_can_be_skipped_or_set_to_three_seconds(self):
        reader = Sim4leReader(self.elm, self.args, self.journal)
        reader.identify(Mock(), initial_wait=0, retry=False)
        self.elm.stop.wait.assert_not_called()
        reader.identify(Mock(), initial_wait=3, retry=False)
        self.elm.stop.wait.assert_called_once_with(3)

    def test_cancel_during_startup_wait_sends_no_init(self):
        reader = Sim4leReader(self.elm, self.args, self.journal)
        self.elm.stop.wait.return_value = True
        with self.assertRaises(InterruptedError):
            reader.identify(Mock(), initial_wait=3, retry=False)
        self.elm.command.assert_not_called()

    def test_unknown_identity_and_corrupt_frames_are_rejected(self):
        reader = Sim4leReader(self.elm, self.args, self.journal)
        self.elm.command.side_effect = lambda c, **kw: frame(b'\x5a\x94' + b'OTHER_ECU---')
        with self.assertRaises(DiagnosticError):
            reader.identify(Mock())
        self.elm.command.side_effect = None
        self.elm.command.return_value = self.reply[:-2] + 'FF'
        with self.assertRaises(DiagnosticError):
            reader.read()

    def test_me21_success_does_not_probe_sim4le_and_timeout_never_retries(self):
        with patch('auto_reader.Me21Reader') as me21, patch('auto_reader.Sim4leReader') as sim:
            reader = AutoReader(self.elm, self.args, self.journal)
            reader.identify(Mock())
            sim.assert_not_called()
            me21.return_value.identify.side_effect = LinkTimeout('lost prompt')
            with self.assertRaises(LinkTimeout):
                reader.identify(Mock())
            sim.assert_not_called()
            me21.return_value.identify.side_effect = DiagnosticError('7F 22 11')
            reader.identify(Mock())
            sim.return_value.identify.assert_called_once()


if __name__ == '__main__':
    unittest.main()
