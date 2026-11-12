"""Correction snapshots preserve units and remain strictly on demand."""
import io
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fuel_corrections import CorrectionMeasurements, FuelCorrections
from mercedes_dashboard import CorrectionsMenu
from rich.console import Console


class FuelCorrectionTests(unittest.TestCase):
    def test_me21_signed_additive_and_known_trim_values(self):
        result = FuelCorrections(32768, 49152, 0xFFFF)
        values = result.values()
        self.assertEqual((values.low_percent, values.high_percent), (0, 25))
        self.assertEqual((result.additive_signed, values.additive_raw), (-1, -1))
        self.assertAlmostEqual(values.additive_ms, -8 / 3000)
        logged = result.log_values(2)
        self.assertEqual(logged['additive_raw'], 65535)
        self.assertEqual(logged['additive_ms_est'], -.002)
        self.assertEqual(logged['additive_tick_us_assumed'], 2)

    def test_external_corrections_do_not_assume_a_timer_scale(self):
        values = CorrectionMeasurements(low_percent=-16.88, additive_raw=167)
        result = FuelCorrections.from_measurements(values)
        self.assertIs(result.values(100), values)
        logged = result.log_values(100)
        self.assertEqual(logged['ltft_low_pct'], -16.88)
        self.assertEqual(logged['additive_signed_raw'], 167)
        for key in ('ltft_low_raw', 'ltft_high_pct', 'additive_ms_est', 'additive_tick_us_assumed'):
            self.assertIsNone(logged[key], key)

    def test_menu_reads_once_per_request_and_renders_unknown_values(self):
        menu = CorrectionsMenu()
        reader, journal = Mock(ecu_profile=None), Mock()
        result = FuelCorrections.from_measurements(CorrectionMeasurements(low_percent=-16.88))
        reader.read_fuel_corrections.return_value = result
        menu.service(reader, journal)
        reader.read_fuel_corrections.assert_not_called()
        menu.ready = True
        menu.show()
        menu.service(reader, journal)
        menu.service(reader, journal)
        reader.read_fuel_corrections.assert_called_once()
        self.assertIs(menu.result, result)
        output = io.StringIO()
        console = Console(file=output, width=80, height=24, color_system=None)
        console.print(menu.render(console))
        text = output.getvalue()
        self.assertIn('-16.88', text)
        self.assertIn('—', text)
        self.assertNotIn('None', text)
        menu.request()
        menu.close()
        menu.service(reader, journal)
        reader.read_fuel_corrections.assert_called_once()
