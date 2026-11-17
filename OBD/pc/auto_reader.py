"""Try a direct connection, a short retry, then conservative ECU detection."""
if __package__:
    from .ecu_reader import DiagnosticError, LinkTimeout
    from .me21_reader import Me21Reader
    from .sim4le_reader import Sim4leReader
else:
    from ecu_reader import DiagnosticError, LinkTimeout
    from me21_reader import Me21Reader
    from sim4le_reader import Sim4leReader


class AutoReader:
    def __init__(self, transport, args, journal, *, preferred_family=None):
        self.transport, self.args, self.journal = transport, args, journal
        self.preferred_family = preferred_family if preferred_family in ('ME2.1', 'SIM4LE') else 'ME2.1'
        self.reader = None

    def identify(self, notice):
        factories = {'ME2.1': Me21Reader, 'SIM4LE': Sim4leReader}
        preferred = self.preferred_family
        other = 'ME2.1' if preferred == 'SIM4LE' else 'SIM4LE'
        attempts = ((preferred, 0, False), (preferred, 3, False),
                    (other, 6, True), (preferred, 6, True))
        for index, (family, wait, retry) in enumerate(attempts):
            self.reader = factories[family](self.transport, self.args, self.journal)
            self.journal.write('ecu_connection_attempt', family=family,
                               attempt=index+1, wait_ms=wait*1000, conservative=retry)
            try:
                self.reader.identify(notice, initial_wait=wait, retry=retry)
                return
            except LinkTimeout:
                # The caller must close a transport whose prompt was lost.
                raise
            except DiagnosticError as exc:
                self.journal.write('ecu_family_probe', family=family, error=str(exc))
                if index == len(attempts)-1:
                    raise

    def close(self):
        self.transport.close()

    def __getattr__(self, name):
        return getattr(self.reader, name)
