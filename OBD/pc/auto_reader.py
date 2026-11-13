"""Select a reader once; keep successful ME2.1 startup and polling unchanged."""
if __package__:
    from .ecu_reader import DiagnosticError, LinkTimeout
    from .me21_reader import Me21Reader
    from .sim4le_reader import Sim4leReader
else:
    from ecu_reader import DiagnosticError, LinkTimeout
    from me21_reader import Me21Reader
    from sim4le_reader import Sim4leReader


class AutoReader:
    def __init__(self, transport, args, journal):
        self.transport, self.args, self.journal = transport, args, journal
        self.reader = Me21Reader(transport, args, journal)

    def identify(self, notice):
        try:
            self.reader.identify(notice)
        except LinkTimeout:
            # Missing '>' loses serial framing; do not issue another ECU command.
            raise
        except DiagnosticError as exc:
            self.journal.write('ecu_family_probe', family='ME2.1', error=str(exc))
            self.reader = Sim4leReader(self.transport, self.args, self.journal)
            self.reader.identify(notice)

    def __getattr__(self, name):
        return getattr(self.reader, name)
