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
    def __init__(self, transport, args, journal, *, preferred_family=None, record_family=None,
                 reconnect=None):
        self.transport, self.args, self.journal = transport, args, journal
        self.preferred_family = preferred_family if preferred_family in ('ME2.1', 'SIM4LE') else 'ME2.1'
        self.reader = None
        self.record_family = record_family
        self.remembered = False
        self.reconnect = reconnect

    def identify(self, notice):
        self.remembered = False
        factories = {'ME2.1': Me21Reader, 'SIM4LE': Sim4leReader}
        preferred = self.preferred_family
        other = 'ME2.1' if preferred == 'SIM4LE' else 'SIM4LE'
        attempts = ((preferred, 0, False), (preferred, 3, False),
                    (other, 6, True), (preferred, 6, True))
        for index, (family, wait, retry) in enumerate(attempts):
            # A rejected single-frame read can disable the response-count suffix.
            # Each fresh attempt gets the user's original transport policy again.
            self.transport.fast = not self.args.conservative
            self.reader = factories[family](self.transport, self.args, self.journal)
            self.journal.write('ecu_connection_attempt', family=family,
                               attempt=index+1, wait_ms=wait*1000, conservative=retry)
            try:
                self.reader.identify(notice, initial_wait=wait, retry=retry)
                return
            except LinkTimeout as exc:
                if index != 0 or self.reconnect is None:
                    raise
                self.journal.write('ecu_connection_reopen', family=family, error=str(exc))
                # Never issue another command on a stream whose prompt was lost.
                self.transport.close()
                self.transport = self.reconnect()
            except DiagnosticError as exc:
                self.journal.write('ecu_family_probe', family=family, error=str(exc))
                if index == len(attempts)-1:
                    raise

    def close(self):
        self.transport.close()

    def _remember(self, sample):
        if not self.remembered and self.record_family:
            self.remembered = True
            try:
                self.record_family(sample.ecu_family)
            except OSError as exc:
                # An unwritable preferences file must not stop acquisition.
                self.journal.write('ecu_preference_error', error=str(exc))
        return sample

    def start(self, notice, reject, publish):
        self.reader.start(notice, reject, lambda sample: publish(self._remember(sample)))

    def read(self):
        return self._remember(self.reader.read())

    def __getattr__(self, name):
        return getattr(self.reader, name)
