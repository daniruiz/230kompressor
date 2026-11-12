"""Shared sample envelope; raw fields retain the ME2.1 log schema."""
from dataclasses import dataclass, field
import time

if __package__:
    from .measurements import Measurements
    from .me21_measurements import measurements as me21_measurements
else:
    from measurements import Measurements
    from me21_measurements import measurements as me21_measurements

KNOCK_ALERT_THRESHOLD_DEG = 3.0


@dataclass
class Sample:
    rpm: int
    injection: int
    load: int
    corrections: tuple[int, ...]
    duration_ms: float = 0.0
    at: float = field(default_factory=time.monotonic)
    trims: tuple[int, ...] = (32768,)*2
    temperatures: tuple[int, int] = (40,40)
    pairs_raw: str = ""
    maf_raw: int | None = None
    maf_adc_word: int | None = None
    maf_g_s_est: float | None = None
    maf_calibration_status: str = 'unverified_software'
    interval_ms: float | None = None
    advance_raw: int | None = None
    throttle_raw: int | None = None
    oxygen_raw: int | None = None
    # Legacy metric input: the fixed acquisition group leaves voltage unavailable.
    voltage_raw: int | None = None
    diagnostic_ids: tuple[int, ...] = ()
    maf_ecu_raw: int | None = None
    adapter_voltage_v: float | None = None
    adapter_voltage_age_ms: float | None = None
    adapter_voltage_status: str = 'pending'
    measurements: Measurements | None = None

    @classmethod
    def from_measurements(cls, values: Measurements, **timing):
        """Other readers need not manufacture ME2.1 raw bytes."""
        return cls(values.rpm or 0, 0, values.load or 0, (), measurements=values, **timing)

    def values(self, tick_us=8/3) -> Measurements:
        # Samples are mutable during acquisition/demo; do not cache conversions.
        return self.measurements if self.measurements is not None else me21_measurements(self, tick_us)

    @property
    def high_retard(self):
        return self.values().high_retard
