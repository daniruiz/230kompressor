"""Persistence must never turn a successful connection into a failure."""
import sys
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from auto_reader import AutoReader
from dashboard_sample import Sample
from ecu_preferences import EcuPreferences
from ecu_reader import DiagnosticError
from mercedes_dashboard import create_reader


class PreferenceTests(unittest.TestCase):
    def test_round_trip_keeps_only_last_working_family(self):
        with TemporaryDirectory() as root:
            path = Path(root) / 'settings' / 'ecu-families.json'
            prefs = EcuPreferences(path)
            self.assertIsNone(prefs.get())
            self.assertFalse(path.exists())
            prefs.remember('SIM4LE')
            self.assertEqual(EcuPreferences(path).get(), 'SIM4LE')
            prefs.remember('ME2.1')
            self.assertEqual(prefs.get(), 'ME2.1')
            self.assertEqual(json.loads(path.read_text()), {'last_ecu_family': 'ME2.1'})
            self.assertEqual(list(path.parent.iterdir()), [path])

    def test_invalid_cache_is_ignored_and_failed_replace_preserves_previous_file(self):
        with TemporaryDirectory() as root:
            path = Path(root) / 'settings.json'
            prefs = EcuPreferences(path)
            for content in ('not json', '[]', '{"last_ecu_family":"unknown"}'):
                path.write_text(content, encoding='utf-8')
                self.assertIsNone(prefs.get())
            prefs.remember('ME2.1')
            with patch('ecu_preferences.os.replace', side_effect=PermissionError('read only')):
                with self.assertRaises(OSError):
                    prefs.remember('SIM4LE')
            self.assertEqual(prefs.get(), 'ME2.1')
            self.assertEqual(list(path.parent.iterdir()), [path])

    def test_old_adapter_map_is_replaced_after_valid_connection(self):
        with TemporaryDirectory() as root:
            path = Path(root) / 'settings.json'
            path.write_text('{"serial:COM3|USB VID:PID=1234:5678":"SIM4LE"}', encoding='utf-8')
            prefs = EcuPreferences(path)
            self.assertIsNone(prefs.get())
            prefs.remember('ME2.1')
            self.assertEqual(json.loads(path.read_text()), {'last_ecu_family': 'ME2.1'})

    def test_launch_uses_global_family_and_saves_only_after_valid_sample(self):
        transport = Mock(port='COM3', port_metadata={})
        prefs, journal = Mock(), Mock()
        prefs.get.return_value = 'SIM4LE'
        with patch('mercedes_dashboard.connect_adapter', return_value=transport), \
             patch('mercedes_dashboard.EcuPreferences', return_value=prefs):
            reader = create_reader(Mock(), journal, Mock(), Mock())
        prefs.get.assert_called_once_with()
        self.assertEqual(reader.preferred_family, 'SIM4LE')
        prefs.remember.assert_not_called()
        reader.reader = Mock()
        sample = Sample(800, 0, 0, (), ecu_family='SIM4LE')
        reader.reader.read.side_effect = [DiagnosticError('bad frame'), sample, sample]
        with self.assertRaises(DiagnosticError):
            reader.read()
        prefs.remember.assert_not_called()
        self.assertIs(reader.read(), sample)
        self.assertIs(reader.read(), sample)
        prefs.remember.assert_called_once_with('SIM4LE')

    def test_first_start_sample_updates_family_and_unwritable_cache_does_not_interrupt(self):
        record = Mock(side_effect=PermissionError('read only'))
        journal = Mock()
        reader = AutoReader(Mock(), Mock(), journal, preferred_family='SIM4LE', record_family=record)
        reader.reader = Mock()
        sample = Sample(800, 0, 0, (), ecu_family='ME2.1')
        reader.reader.start.side_effect = lambda notice, reject, publish: publish(sample)
        publish = Mock()
        reader.start(Mock(), Mock(), publish)
        record.assert_called_once_with('ME2.1')
        publish.assert_called_once_with(sample)
        journal.write.assert_called_once_with('ecu_preference_error', error='read only')


if __name__ == '__main__':
    unittest.main()
