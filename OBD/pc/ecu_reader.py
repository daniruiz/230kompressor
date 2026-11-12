"""UI-independent acquisition boundary, used only on the acquisition thread.

The launcher injects worker(..., reader_factory=...), defaulting to Me21Reader.
Each factory receives args, journal, stop and notice, and owns its transport.
identify/start/read/recover and menu diagnostics run on that same thread;
close must release the transport even after a failed handshake.

A future SIM4LE reader should return Sample.from_measurements(Measurements(...))
and leave unverified units as None, especially injection duration and duty.
No ME2.1-shaped placeholder bytes are needed. Optional correction results use
FuelCorrections.from_measurements; unsupported diagnostics must raise an error.
The existing raw log schema is retained only for legacy ME2.1 samples; adding
family-specific raw fields or CSV columns is a separate exporter change.
This boundary does not enable SIM4LE or select an ECU automatically.
"""
from typing import Protocol, Callable, Any

if __package__:
    from .dashboard_sample import Sample
    from .fuel_corrections import FuelCorrections
else:
    from dashboard_sample import Sample
    from fuel_corrections import FuelCorrections

Notice = Callable[[str], None]


class DiagnosticError(RuntimeError):
    pass


class NegativeResponse(DiagnosticError):
    def __init__(self, service, code):
        self.service, self.code = service, code
        super().__init__(f'ECU: 7F {service:02X} {code:02X}')


class LinkTimeout(DiagnosticError):
    pass


class EcuReader(Protocol):
    """An implementation owns protocol selection, timing and fallback decisions.

    Samples carry engineering measurements independently of ECU-specific raw
    values. Diagnostic operations remain serialized between polling cycles.
    """
    @property
    def ecu_profile(self) -> Any: ...
    @property
    def ecu_software(self) -> str | None: ...
    @property
    def ecu_identification_status(self) -> str: ...
    @property
    def mode(self) -> str: ...
    @property
    def adapter_profile(self) -> str: ...

    def identify(self, notice: Notice) -> None: ...
    def start(self, notice: Notice, reject: Notice, publish: Callable[[Sample], None]) -> None: ...
    def read(self) -> Sample: ...
    def recover(self, error: DiagnosticError, consecutive: int, notice: Notice) -> int: ...
    def read_faults(self) -> list[dict]: ...
    def clear_faults(self) -> None: ...
    def read_fuel_corrections(self) -> FuelCorrections: ...
    def close(self) -> None: ...
