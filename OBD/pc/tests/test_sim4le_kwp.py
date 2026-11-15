"""Transport framing using both malformed messages and recorded Veepeak replies."""
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ecu_reader import DiagnosticError, NegativeResponse
from sim4le_kwp import positive_reply, positive_replies
from sim4le_measurements import decode_snapshot


def frame(payload, source=1):
    head = bytes((0x80, 0xF1, source, len(payload)))
    raw = head + payload
    return (raw + bytes((sum(raw) & 255,))).hex().upper()


class KwpTests(unittest.TestCase):
    def parse(self, text):
        return positive_reply(text, b'\x21\x01', b'\x61\x01', 180)

    def test_multiple_responses_are_explicit_and_every_frame_is_validated(self):
        first, last = bytes.fromhex('43 0638 0106 0125'), bytes.fromhex('43 0116 0128 0000')
        self.assertEqual(positive_replies(frame(first) + frame(last), b'\x03', b'\x43', 7), [first, last])
        with self.assertRaises(DiagnosticError):
            positive_replies(frame(first) + frame(last)[:-2], b'\x03', b'\x43', 7)
        with self.assertRaises(DiagnosticError):
            positive_reply(frame(first) + frame(last), b'\x03', b'\x43', 7)

    def test_live_engine_off_and_running_replies(self):
        path = Path(__file__).resolve().parents[2] / 'fixtures/sim4le_5116_fixed.json'
        for row in json.loads(path.read_text()):
            with self.subTest(case=row['case']):
                # Long ELM replies may wrap in the middle of an arbitrary byte pair.
                wire = row['reply']
                wrapped = '\r'.join(wire[i:i + 32] for i in range(0, len(wire), 32))
                sample = decode_snapshot(self.parse('2101 1\r' + wrapped))
                self.assertEqual(sample.values().rpm, row['rpm'])
                self.assertEqual(sample.values().load, row['load'])
                self.assertEqual(sample.values().advance_degrees, row['advance'])

    def test_truncated_corrupt_duplicate_and_foreign_frames(self):
        valid = frame(b'\x61\x01' + bytes(178))
        for bad in (valid[:-2], valid[:-2] + 'FF', valid + valid,
                    frame(b'\x61\x01' + bytes(178), source=2),
                    frame(b'\x61\x01' + bytes(177)), 'BUFFER FULL', 'NO DATA', '?'):
            with self.subTest(text=bad[:20]), self.assertRaises(DiagnosticError):
                self.parse(bad)

    def test_pending_requires_a_complete_final_response(self):
        pending = frame(b'\x7f\x21\x78')
        valid = frame(b'\x61\x01' + bytes(178))
        self.assertEqual(len(self.parse(pending + '\r' + valid)), 180)
        with self.assertRaises(DiagnosticError):
            self.parse(pending)
        with self.assertRaises(NegativeResponse) as caught:
            self.parse(frame(b'\x7f\x21\x11'))
        self.assertEqual(caught.exception.code, 0x11)

    def test_a_wrapped_data_fragment_matching_request_is_not_an_echo(self):
        payload = b'\x61\x01\x21\x01' + bytes(176)
        wire = frame(payload)
        self.assertEqual(self.parse(wire[:12] + '\r2101\r' + wire[16:]), payload)


if __name__ == '__main__':
    unittest.main()
