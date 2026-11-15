"""One fixed 21 01 request per dashboard cycle; no auxiliary polling."""
import re
import time

if __package__:
    from .ecu_reader import DiagnosticError, LinkTimeout
    from .dashboard_i18n import tr
    from .fuel_corrections import FuelCorrections, CorrectionMeasurements
    from .sim4le_kwp import positive_reply, positive_replies
    from .sim4le_measurements import decode_snapshot
else:
    from ecu_reader import DiagnosticError, LinkTimeout
    from dashboard_i18n import tr
    from fuel_corrections import FuelCorrections, CorrectionMeasurements
    from sim4le_kwp import positive_reply, positive_replies
    from sim4le_measurements import decode_snapshot


class Sim4leReader:
    ecu_profile = None  # Never expose ME2.1 RAM addresses to menu diagnostics.
    mode = 'SIM4LE · 2101'
    fault_scope = 'confirmed_obd'

    def __init__(self, transport, args, journal):
        self.elm, self.args, self.journal = transport, args, journal
        self.ecu_software = None
        self.ecu_identification_status = 'pending'
        self.latest = None
        self.suffix = not args.conservative

    @property
    def adapter_profile(self):
        return self.elm.adapter_profile

    def query(self, request, prefix, length):
        command = request.hex().upper()
        reply = self.elm.command(command + (' 1' if self.suffix else ''))
        if reply.strip() == '?' and self.suffix:
            self.suffix = False
            reply = self.elm.command(command)
        return positive_reply(reply, request, prefix, length)

    def identify(self, notice):
        e = self.elm
        e.adapter_voltage_polling = False
        # End any previous slow-init session before changing protocol.
        e.at('ATPC')
        notice(tr('Probando SIM4LE · cerrando sesión anterior · 6 s',
                  'Trying SIM4LE · closing previous session · 6 s'))
        if e.stop.wait(6):
            raise InterruptedError
        e.last_bus_reply = e.last_command = None
        e.request_gap = 0.060
        for command in ('ATD', 'ATE0', 'ATL0', 'ATS0', 'ATH1', 'ATM0', 'ATSP5',
                        'ATIIA01', 'ATSH8101F1', 'ATKW0', 'ATSW00', 'ATAT1', 'ATST80'):
            e.at(command, optional=command in ('ATM0', 'ATIIA01', 'ATKW0', 'ATSW00'))
        e.at('ATAL')  # The 185-byte response requires long-message reception.
        e.command('ATFI', timeout=20)
        identity = self.query(b'\x1A\x94', b'\x5A\x94', 14)[2:]
        if not re.fullmatch(rb'657781\d{4}[-\w]{2}', identity):
            raise DiagnosticError('SIM4LE: unrecognised software family ' + repr(identity))
        self.ecu_software = identity.decode('ascii')
        self.ecu_identification_status = 'fixed_layout_checked_5122_5126'
        # Log measured identity separately: 5116 has live layout evidence, not its ROM.
        self.journal.write('ecu_identification', ecu_family='SIM4LE',
            software=self.ecu_software, status=self.ecu_identification_status,
            group_read_policy='2101 fixed 178 bytes; no auxiliary reads',
            injection_basis='base_4us_nominal_720deg', voltage_source='ECU 2101 offset 73')
        notice('SIM4LE · ' + self.ecu_software)

    def start(self, notice, reject, publish):
        # Same adapter pacing as the successful Veepeak trace. No 83 session change.
        self.elm.request_gap = .060 if self.args.no_adapter_tuning or self.args.conservative else 0
        publish(self.read())

    def read(self):
        started = time.monotonic()
        payload = self.query(b'\x21\x01', b'\x61\x01', 180)
        ended = time.monotonic()
        self.latest = decode_snapshot(payload, at=ended, duration_ms=(ended - started) * 1000)
        return self.latest

    def recover(self, error, consecutive, notice):
        if consecutive >= 5:
            raise DiagnosticError(tr('Cinco ciclos SIM4LE sin datos válidos: ',
                                     'Five SIM4LE cycles without valid data: ') + str(error))
        return consecutive

    def read_fuel_corrections(self):
        fields = self.read().ecu_fields
        return FuelCorrections.from_measurements(CorrectionMeasurements(
            low_percent=fields['ltft_low_pct'], high_percent=fields['ltft_high_pct'],
            additive_raw=fields['additive_raw']))

    def read_faults(self):
        # 03 can return several frames: never use the one-response ELM suffix.
        frames = positive_replies(self.elm.command('03'), b'\x03', b'\x43', 7)
        faults, seen = [], set()
        for payload in frames:
            for offset in (1, 3, 5):
                first, second = payload[offset:offset + 2]
                raw = first << 8 | second
                if raw in (0, 0xFFFF) or raw in seen:
                    continue  # Padding or a native fault without an OBD mapping.
                seen.add(raw)
                code = f'{"PCBU"[first >> 6]}{(first >> 4) & 3:X}{first & 15:X}{second:02X}'
                faults.append(dict(code=code, status=None, scope=self.fault_scope,
                                   raw=payload.hex().upper()))
        self.journal.write('dtc_read', ecu_family='SIM4LE', scope=self.fault_scope,
                           codes=[f['code'] for f in faults],
                           records=[payload.hex().upper() for payload in frames])
        return faults

    def clear_faults(self):
        # Send once. Missing or negative acknowledgement must not trigger a retry.
        positive_reply(self.elm.command('04'), b'\x04', b'\x44', 1)
        self.journal.write('dtc_clear', ecu_family='SIM4LE', service='04', acknowledged=True)

    def close(self):
        self.elm.close()
