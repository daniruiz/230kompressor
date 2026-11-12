"""UI-independent acquisition boundary, used only on the acquisition thread."""
from typing import Protocol, Callable, Any


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
    ecu_profile: Any
    ecu_software: str | None
    ecu_identification_status: str
    mode: str
    adapter_profile: str

    def identify(self, notice: Callable[[str], None]) -> None: ...
    def start(self, notice, reject, publish) -> None: ...
    def read(self): ...
    def recover(self, error: DiagnosticError, consecutive: int, notice) -> int: ...
    def read_faults(self): ...
    def clear_faults(self) -> None: ...
    def read_fuel_corrections(self): ...
    def close(self) -> None: ...
