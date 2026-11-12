"""On-demand correction results, with optional reader-defined physical values."""
from dataclasses import dataclass

if __package__:
    from .me21_measurements import trim_percent
else:
    from me21_measurements import trim_percent


@dataclass(frozen=True)
class CorrectionMeasurements:
    low_percent: float | None = None
    high_percent: float | None = None
    additive_ms: float | None = None
    additive_raw: int | None = None


@dataclass(frozen=True)
class FuelCorrections:
    low: int
    high: int
    additive: int
    measurements: CorrectionMeasurements | None = None

    @classmethod
    def from_measurements(cls, values):
        return cls(0, 0, 0, values)

    @property
    def additive_signed(self):
        return self.additive - 65536 if self.additive & 0x8000 else self.additive

    def values(self, tick_us=8/3):
        return self.measurements if self.measurements is not None else CorrectionMeasurements(
            trim_percent(self.low), trim_percent(self.high),
            self.additive_signed * tick_us / 1000, self.additive_signed)

    def log_values(self, tick_us):
        values = self.values(tick_us)
        legacy = self.measurements is None
        return dict(ltft_low_raw=self.low if legacy else None, ltft_high_raw=self.high if legacy else None,
                    ltft_low_pct=values.low_percent, ltft_high_pct=values.high_percent,
                    additive_raw=self.additive if legacy else None, additive_signed_raw=values.additive_raw,
                    additive_ms_est=values.additive_ms,
                    additive_tick_us_assumed=tick_us if legacy else None)
