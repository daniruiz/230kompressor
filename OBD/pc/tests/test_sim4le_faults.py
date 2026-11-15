"""Confirmed OBD faults only; synthetic KWP frames, no vehicle or adapter I/O."""
import io
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock, call, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ecu_reader import DiagnosticError, LinkTimeout
from sim4le_reader import Sim4leReader
from sim4le_kwp import positive_reply
from mercedes_dashboard import FaultMenu, parse_args
import dashboard_i18n
from rich.console import Console


def frame(payload, source=1):
    body = bytes((0x80 | len(payload), 0xF1, source)) + payload
    return (body + bytes((sum(body) & 255,))).hex().upper()


class Sim4leFaultTests(unittest.TestCase):
    def setUp(self):
        self.elm, self.journal = Mock(), Mock()
        self.reader = Sim4leReader(self.elm, parse_args([]), self.journal)

    def test_all_frames_and_code_families_are_read_without_response_count_suffix(self):
        first = frame(bytes.fromhex('43 0638 0106 0125'))
        second = frame(bytes.fromhex('43 4116 8128 C100'))
        last = frame(bytes.fromhex('43 0638 FFFF 0000'))
        self.elm.command.return_value = '03\r' + first + '\r' + second + '\r' + last + '\r>'
        faults = self.reader.read_faults()
        self.assertEqual([f['code'] for f in faults],
                         ['P0638', 'P0106', 'P0125', 'C0116', 'B0128', 'U0100'])
        self.assertTrue(all(f['status'] is None and f['scope'] == 'confirmed_obd' for f in faults))
        self.elm.command.assert_called_once_with('03')
        # The 2101/identity parser must still reject duplicate positive replies.
        with self.assertRaises(DiagnosticError):
            positive_reply(first + second, b'\x03', b'\x43', 7)

    def test_empty_list_requires_a_valid_positive_response(self):
        self.elm.command.return_value = frame(b'\x43' + bytes(6))
        self.assertEqual(self.reader.read_faults(), [])
        valid = frame(bytes.fromhex('43 0300 0000 0000'))
        damaged = valid[:-2] + f'{int(valid[-2:], 16) ^ 1:02X}'
        for bad in ('NO DATA', '', '?', valid[:-2], damaged, valid + damaged,
                    frame(bytes.fromhex('47 0300 0000 0000')),
                    frame(bytes.fromhex('43 0300')), frame(b'\x7f\x03\x11'),
                    frame(b'\x7f\x03\x78'), frame(b'\x43' + bytes(6), source=2)):
            with self.subTest(reply=bad), self.assertRaises(DiagnosticError):
                self.elm.command.return_value = bad
                self.reader.read_faults()

    def test_pending_followed_by_final_list_is_accepted(self):
        self.elm.command.return_value = frame(b'\x7f\x03\x78') + frame(bytes.fromhex('43 0300 0000 0000'))
        self.assertEqual(self.reader.read_faults()[0]['code'], 'P0300')

    def test_clear_sends_once_and_requires_exact_acknowledgement(self):
        for reply in ('?', 'NO DATA', frame(b'\x7f\x04\x22'), frame(b'\x44\x00'),
                      frame(b'\x44')[:-2], frame(b'\x44') * 2):
            with self.subTest(reply=reply):
                self.elm.command.reset_mock()
                self.elm.command.return_value = reply
                with self.assertRaises(DiagnosticError):
                    self.reader.clear_faults()
                self.elm.command.assert_called_once_with('04')
        self.journal.write.assert_not_called()
        self.elm.command.reset_mock()
        self.elm.command.side_effect = LinkTimeout('missing prompt')
        with self.assertRaises(LinkTimeout):
            self.reader.clear_faults()
        self.elm.command.assert_called_once_with('04')
        self.elm.command.side_effect = None
        self.elm.command.return_value = frame(b'\x44')
        self.reader.clear_faults()
        self.journal.write.assert_called_once_with('dtc_clear', ecu_family='SIM4LE', service='04', acknowledged=True)

    def test_polling_resumes_in_same_session_after_menu_requests(self):
        from test_sim4le_kwp import frame as long_frame
        self.elm.command.side_effect = [frame(b'\x43' + bytes(6)), frame(b'\x44'),
                                       long_frame(b'\x61\x01' + bytes(178))]
        self.reader.read_faults()
        self.reader.clear_faults()
        self.assertEqual(self.reader.read().ecu_family, 'SIM4LE')
        self.assertEqual(self.elm.command.call_args_list, [call('03'), call('04'), call('2101 1')])
        self.elm.at.assert_not_called()

    def test_menu_labels_confirmed_scope_even_when_empty_and_preserves_bosch_status(self):
        for language, confirmed in (('es', 'confirmados OBD'), ('en', 'confirmed OBD')):
            with patch.object(dashboard_i18n, 'LANGUAGE', language):
                menu = FaultMenu()
                menu.ready = True
                for payload in (bytes.fromhex('43 0300 0000 0000'), b'\x43' + bytes(6)):
                    self.elm.command.return_value = frame(payload)
                    menu.request(False)
                    menu.service(self.reader, self.journal)
                    self.assertEqual(menu.result_status, 'read')
                    out = io.StringIO()
                    console = Console(file=out, width=74, height=21, color_system=None)
                    console.print(menu.render(console))
                    self.assertIn(confirmed.lower(), out.getvalue().lower())
                    self.assertNotIn('0x00', out.getvalue())
        native = Mock(fault_scope=None)
        native.read_faults.return_value = [dict(code='P0300', status=0xA1, raw='native')]
        menu.request(False)
        menu.service(native, self.journal)
        self.assertIn('0xA1', menu.message)


if __name__ == '__main__':
    unittest.main()
