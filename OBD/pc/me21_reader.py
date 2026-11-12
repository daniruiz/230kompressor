"""ME2.1 acquisition policy; the serial transport is supplied by the launcher."""
if __package__:
    from .ecu_reader import DiagnosticError, LinkTimeout
    from .dashboard_i18n import tr
else:
    from ecu_reader import DiagnosticError, LinkTimeout
    from dashboard_i18n import tr


class Me21Reader:
    def __init__(self, transport, args, journal):
        self.elm, self.args, self.journal = transport, args, journal
        self.mode = 'individual' if args.individual else 'grouped'

    @property
    def ecu_profile(self):
        return self.elm.ecu_profile

    @property
    def ecu_software(self):
        return self.elm.ecu_software

    @property
    def ecu_identification_status(self):
        return self.elm.ecu_identification_status

    @property
    def adapter_profile(self):
        return self.elm.adapter_profile

    def identify(self, notice):
        self.elm.setup(notice, self.args.adaptive)
        self.elm.identify_ecu(notice)

    def start(self, notice, reject, publish):
        if self.mode == 'grouped':
            notice(tr('Configurando 12 entradas agrupadas · RAM de diagnóstico',
                      'Configuring 12 grouped entries · diagnostic RAM'))
            try:
                publish(self.elm.define_group())
            except LinkTimeout:
                raise
            except DiagnosticError as exc:
                self.journal.write('fallback', reason=str(exc))
                reject(tr('Grupo rechazado; paso a lecturas individuales',
                          'Group rejected; switching to individual reads'))
                self.mode = 'individual'
        if not self.args.no_adapter_tuning:
            if self.elm.is_stn:
                self.elm.optimize_transport(self.mode, notice, publish)
            elif self.args.elm_timing_probe:
                self.elm.optimize_elm_transport(self.mode, notice, publish)
        self.elm.enable_adapter_voltage_polling()

    def read(self):
        return self.elm.acquire(self.mode)

    def recover(self, error, consecutive, notice):
        if self.elm.restore_transport(str(error)):
            notice(tr('Tiempos compatibles restaurados · reintentando lectura',
                      'Compatible timings restored · retrying read'))
            return consecutive
        if self.mode == 'grouped' and consecutive >= 3:
            self.mode = 'individual'
            self.journal.write('fallback', reason='3 respuestas de grupo inválidas')
            return 0
        if consecutive >= 5:
            raise DiagnosticError(tr('Cinco ciclos sin datos válidos. ',
                                     'Five cycles without valid data. ') + str(error))
        return consecutive

    def read_faults(self):
        return self.elm.read_faults()

    def clear_faults(self):
        return self.elm.clear_faults()

    def read_fuel_corrections(self):
        return self.elm.read_fuel_corrections()

    def close(self):
        self.elm.close()
