"""Values in display units, independent of ECU bytes and diagnostic services."""
from dataclasses import dataclass


@dataclass(frozen=True)
class Measurements:
    # None means unavailable, including an injection timer whose scale is unknown.
    rpm: int | None = None
    load: int | None = None
    load_maximum: float = 255.0
    load_unit: str = 'raw'
    injection_ms: float | None = None
    injection_raw: int | None = None
    duty_percent: float | None = None
    retard_degrees: tuple[float, ...] = ()
    temperatures_c: tuple[float | None, float | None] = (None, None)
    trims_percent: tuple[float | None, float | None] = (None, None)
    throttle_percent: float | None = None
    maf_g_s: float | None = None
    maf_at_limit: bool | None = None
    ecu_maf_g_s: float | None = None
    advance_degrees: float | None = None
    oxygen_v: float | None = None
    oxygen_rich: bool | None = None

    @property
    def retard(self):
        return max(self.retard_degrees, default=None)

    @property
    def high_retard(self):
        return (self.retard or 0) > 3.0
