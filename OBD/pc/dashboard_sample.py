"""Shared sample envelope; raw fields retain the ME2.1 log schema."""
from dataclasses import dataclass, field
import time

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

    @property
    def high_retard(self):
        return max(self.corrections, default=0) * 0.75 > KNOCK_ALERT_THRESHOLD_DEG
