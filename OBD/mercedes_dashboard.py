#!/usr/bin/env python3
"""MercedesOBD dashboard. Python 3.10+, rich; pyserial for a real ECU.
Demo: python3 mercedes_dashboard.py --demo
Car:  python3 mercedes_dashboard.py /dev/ttyUSB0

The red alert counts observed rises in candidate knock corrections, NOT a
validated ECU knock counter. F746 is the common requested injection duration,
not a measured electrical pulse. Milliseconds use a provisional timer scale.
SW404 bank 1: reconstruct dead-time compensation from the already-read F734
and the 47CA curve (239F4..23A06), without additional diagnostic requests.
SW404 bank 1 calibration constants are fixed in this script.
No binary file is required. The curve result is NOT a direct RAM read of F8C6.
The before-compensation value assumes the running path, without saturation.
The ECU knock correction loop rotates four internal channels (8398..839B).
The fast group reads four correction bytes, live STFT/LTFT, temperatures,
RPM, injection, load, coarse calculated MAF, ignition advance, throttle, narrowband oxygen sensor and supply voltage.
STN/OBDLink: adapter-managed P3 and measured P4 trial with automatic rollback.
Use --no-adapter-tuning to keep previous timings. Supply voltage scale remains provisional.
Stored LTFT endpoints are read every 30 s via service 23, one per fast cycle.
This is a polling policy, not a verified ECU adaptation time.
Oxygen sensor voltage follows the stock PID 14 conversion, NOT wideband lambda/AFR.
MAF is the ECU calculated airflow (also available in substitute operation),
quantized to approximately 1.1108 g/s per F74C count; 255 is saturated.
Ignition conversion matches the OBD PID 0E path, positive = BTDC.
Memory service framing is derived from ECU code, pending on-car validation.
"""
from __future__ import annotations
import argparse
import csv
from collections import deque
from dataclasses import dataclass, field
import datetime as dt
import json
import math
import os
from pathlib import Path
import queue
import re
import statistics
import sys
import threading
import time

try:
    from rich import box
    from rich.align import Align
    from rich.console import Console, Group
    from rich.color import Color
    from rich.layout import Layout
    from rich.live import Live
    from rich.panel import Panel
    from rich.table import Table
    from rich.text import Text
except ImportError:
    raise SystemExit('Instala la interfaz: python3 -m pip install rich')

# ROM pointer table 0xDE48, service 22 at 0x3ABC0 and group at 0x3AF14.
# 1107 -> F74C/F74D: MAF and load; replaces redundant 1106.
# 1110 -> F734/F735: voltage is ONLY the first byte; F735 is unrelated.
IDS = (0x04, 0x0D, 0x2B, 0x2D, 0x1B, 0x23, 0x01, 0x07, 0x0B, 0x0A, 0x1F, 0x10)
# Eleven existing IDs plus voltage, still one group of twelve.
# Provisional physical scale, NOT established by the disassembly.
# F734 is an ADC-derived byte; ROM 42A2 substitutes 149 below threshold 47.
# 149 * 3/32 = 13.97 V is plausible, but is not proof of the divider ratio.
VOLTAGE_V_PER_RAW_EST = 3 / 32
LTFT_INTERVAL = 30.0
# SW404 bank 1. Both stock and the analysed 230k_ME21 tune have this curve.
# 47CA: pointer F734, offset 0, shift 4, count 17; data at 47D0..47E0.
DEADTIME_NODES_404 = (112,112,112,112,112,63,43,33,26,21,21,21,21,21,21,21,21)
# 111F -> 81C0; comparator at 159F0 uses ROM 41A8 = 0x2456.
O2_SWITCH_RAW = 0x2456
# 110A -> F74F: byte used directly for OBD throttle position.
# A narrowband signal cannot be converted to measured lambda or AFR.
# 0x1108C: F74C=floor(F8C0/40), clamped 255.
# 0x3A3CE: OBD MAF raw=floor(F8C0*2777/1000), OBD units 0.01 g/s.
MAF_GS_PER_RAW = 40 * 2777 / 100000
BG = '#000000'
MUTED = '#9aaec8'
CYAN = '#57d8ed'
RED = '#ff2222'
GREEN = '#45edcb'
AMBER = '#ffd27a'
MAGENTA = '#8738fc'

PEAK_COLORS = {
    CYAN: '#3487ff',
    AMBER: '#ff7b24',
    MAGENTA: RED,
    GREEN: '#02d7fa',
    RED: '#ff7540',
}


class DiagnosticError(RuntimeError):
    pass


class NegativeResponse(DiagnosticError):
    def __init__(self, service, code):
        self.service, self.code = service, code
        super().__init__(f'ECU: 7F {service:02X} {code:02X}')


class LinkTimeout(DiagnosticError):
    pass


class KnockSound:
    """Play six quick tones away from the acquisition and rendering threads."""
    def __init__(self):
        self.pending = queue.Queue()
        self.stopped = threading.Event()
        self.thread = threading.Thread(target=self._run, name='knock-sound', daemon=True)
        self.thread.start()

    def notify(self):
        self.pending.put_nowait(True)

    def _tone(self):
        if sys.platform == 'win32':
            import winsound
            winsound.Beep(1800, 70)
        else:
            # Audible if the user's terminal has its bell enabled.
            sys.stdout.write('\a')
            sys.stdout.flush()
            self.stopped.wait(0.07)

    def _burst(self):
        for i in range(6):
            if self.stopped.is_set():
                return
            try:
                self._tone()
            except (OSError, RuntimeError):
                pass  # Audio support must never interrupt ECU acquisition.
            if i < 5 and self.stopped.wait(0.04):
                return

    def _run(self):
        while not self.stopped.is_set():
            item = self.pending.get()
            if item is None:
                return
            self._burst()

    def close(self):
        self.stopped.set()
        self.pending.put_nowait(None)
        self.thread.join(timeout=0.5)


def hex_frames(reply: str) -> list[bytes]:
    frames = []
    for line in reply.replace('>', '').replace('\r', '\n').splitlines():
        compact = re.sub(r'\s+', '', line).upper()
        if compact and len(compact) % 2 == 0 and re.fullmatch('[0-9A-F]+', compact):
            frames.append(bytes.fromhex(compact))
    return frames


def positive(reply: str, service: int, prefix: bytes, length: int) -> bytes:
    frames = hex_frames(reply)
    found = [f for f in frames if f.startswith(prefix)]
    if len(found) == 1 and len(found[0]) == length:
        return found[0]
    if len(found) > 1:
        raise DiagnosticError('Respuestas múltiples; no se elige una arbitrariamente')
    negative = [f for f in frames if len(f) == 3 and f[:2] == bytes([0x7f, service])]
    if negative:
        raise NegativeResponse(service, negative[0][2])
    raise DiagnosticError('Respuesta inválida o incompleta: ' + repr(reply[:160]))


def definition(slot: int, ident: int, clear: bool = False) -> str:
    if not 1 <= slot <= 12 or not 1 <= ident <= 255:
        raise ValueError('Slot o identificador inválido')
    # 809E=01, 809F=D2 (define) / D0 (clear), 80A0=slot,
    # 80A1=11, 80A2=nonzero id (also required on the clear path).
    return f'2C01{0xD0 if clear else 0xD2:02X}{slot:02X}11{ident:02X}'


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
    interval_ms: float | None = None
    advance_raw: int | None = None
    throttle_raw: int | None = None
    oxygen_raw: int | None = None
    voltage_raw: int | None = None


def from_pairs(pairs: list[bytes]) -> Sample:
    if len(pairs) != len(IDS) or any(len(p) != 2 for p in pairs):
        raise DiagnosticError('Número de señales incorrecto')
    signals = dict(zip(IDS, pairs))
    return Sample(int.from_bytes(signals[0x04], 'little'),
                  int.from_bytes(signals[0x0D], 'little'), signals[0x07][1],
                  tuple(signals[0x2B] + signals[0x2D]),
                  trims=tuple(int.from_bytes(signals[i], 'little') for i in (0x1B, 0x23)),
                  temperatures=tuple(signals[0x01]), pairs_raw=b''.join(pairs).hex(),
                  maf_raw=signals[0x07][0], advance_raw=signals[0x0B][0],
                  throttle_raw=signals[0x0A][0], oxygen_raw=int.from_bytes(signals[0x1F], 'little'),
                  voltage_raw=signals[0x10][0])


def ecu_voltage_est(raw: int | None, scale: float = VOLTAGE_V_PER_RAW_EST) -> float | None:
    """Processed ECU supply estimate, possibly substituted; not adapter ATRV."""
    return None if raw is None else raw * scale



def grouped(reply: str) -> Sample:
    frame = positive(reply, 0x2A, b'\x6a\x01', 2 + 2 * len(IDS))
    return from_pairs([frame[i:i+2] for i in range(2, len(frame), 2)])


def duty_percent(injection_raw: int, rpm: int, tick_us: float = 8/3) -> float:
    """Estimate duty of the common request; include dead-time compensation.

    tick_us and one pulse per 720 crank degrees remain assumptions to validate.
    F746 is upstream of per-output corrections, scheduling and cuts. It does
    not establish the actual electrical pulse or injector effective flow time.
    """
    if injection_raw < 0 or rpm < 0 or not math.isfinite(tick_us) or tick_us <= 0:
        raise ValueError('Valores inválidos para estimar el duty')
    pulse_ms = injection_raw * tick_us / 1000
    return pulse_ms * rpm / 1200


@dataclass(frozen=True)
class InjectorCalibration:
    nodes: tuple[int, ...] = DEADTIME_NODES_404
    minimum_ticks: int = 187
    source: str = '404 banco 1, constantes fijas'
    sha256: str | None = None


def deadtime_ticks(voltage_raw: int | None, calibration: InjectorCalibration) -> int | None:
    """Reproduce 3C504 + 3C4DA/3C4EE, followed by multiplication by 15.

    ROR by 4 yields the index in RL5 and fraction in RH5. SHR #8 feeds
    carry into ADDCB/SUBCB: round the magnitude of the delta, ties upward,
    then add/subtract it. Python round() would disagree at half steps.
    C166 ISA: https://www.keil.com/dd/docs/datashts/infineon/c166ism.pdf
    No physical battery-voltage or injector-clock assumption enters this.
    """
    if voltage_raw is None:
        return None
    if not isinstance(voltage_raw, int) or not 0 <= voltage_raw <= 255:
        raise ValueError('F734 debe ser un byte')
    if len(calibration.nodes) != 17 or any(not 0 <= x <= 255 for x in calibration.nodes):
        raise ValueError('Se requieren 17 nodos de un byte')
    index, fraction = voltage_raw >> 4, (voltage_raw & 15) << 4
    a, b = calibration.nodes[index:index+2]
    delta = (abs(b-a)*fraction+128) >> 8
    return 15*(a+delta if b >= a else a-delta)


def injection_metrics(sample: Sample, args) -> dict:
    """Same calculation for UI, CSV and JSONL; no additional ECU requests."""
    cal = args.injector_calibration
    raw = sample.injection
    total_ms = raw*args.tick_us/1000
    duty = duty_percent(raw, sample.rpm, args.tick_us)
    dead = deadtime_ticks(sample.voltage_raw, cal)
    net = None
    if sample.rpm == 0:
        status = 'engine_stopped_request_may_be_retained'
    elif raw == 65535:
        status = 'F746_saturated_cannot_reverse'
    elif dead is None:
        status = 'missing_F734'
    elif sample.rpm < 600:
        status = 'low_rpm_start_path_not_excluded'
    elif raw-dead < cal.minimum_ticks:
        status = 'below_running_minimum_path_not_applicable'
    else:
        # A mathematical decomposition of the normal running path, not a
        # measurement of effective injector flow or proof that fuel is on.
        net = raw-dead
        status = 'running_path_assumed'
    return dict(F746_raw=raw, injection_ms_est=total_ms,
                injector_duty_est_pct=duty, duty_over_100_est=duty > 100,
                injection_cycle_720_ms=120000/sample.rpm if sample.rpm else None,
                injection_cycle_margin_ms_est=(120000/sample.rpm-total_ms) if sample.rpm else None,
                F8C6_calc_raw=dead,
                injector_deadtime_ms_est=dead*args.tick_us/1000 if dead is not None else None,
                injection_before_deadtime_raw=net,
                injection_before_deadtime_ms_est=net*args.tick_us/1000 if net is not None else None,
                injector_deadtime_fraction_pct=100*dead/raw if dead is not None and raw and net is not None else None,
                injection_breakdown_status=status,
                injection_F746_saturated=raw == 65535)


def advance_degrees(raw):
    # 0x3A378..0x3A3A0 converts F759 to PID 0E as floor(3*N/2)-4,
    # clamped 0..255, then OBD decodes A/2-64. Keep native 0.75° precision.
    return raw * 0.75 - 66


def oxygen_voltage(raw):
    """81C0 -> 3B7E4..3B7FA -> 8CFE -> PID 14 at 3A474.

    DPP3=3; ROM E104 contains 0x0DB8 (3512). Subtract with a
    zero floor, clamp to 0x3FBE, shift left 2 and take high byte.
    PID 14 voltage is that byte / 200. No additional diagnostic request.
    """
    if raw is None:
        return None
    return (min(0x3FBE, max(0, raw - 3512)) >> 6) / 200


def oxygen_state(raw):
    if raw is None:
        return "sin datos"
    return "rico" if raw > O2_SWITCH_RAW else "pobre"


def trim_percent(raw):
    # Isolated multiplicative contribution in 15222–15276, neutral=0x8000.
    return ((16384 + (raw >> 1))/32768 - 1)*100


class Journal:
    def __init__(self, root: Path, enabled: bool):
        self.csv_stream = None
        self.stream = None
        self.path = None
        self.csv_path = None
        self.last_flush = 0.0
        if enabled:
            root.mkdir(parents=True, exist_ok=True)
            self.path = root / dt.datetime.now().strftime('me21_%Y%m%d_%H%M%S_%f.jsonl')
            self.stream = self.path.open('x', encoding='utf-8')
            self.csv_path = self.path.with_suffix('.csv')
            try:
                self.csv_stream = self.csv_path.open('x', encoding='utf-8', newline='')
            except OSError:
                self.stream.close()
                raise
            self.csv_fields = ['utc','demo','rpm','load_raw','F746_raw','injection_ms_est',
                'injector_duty_est_pct','duty_over_100_est','acquisition_ms','cycle_ms','observed_count','observed_event',
                'coolant_C','iat_C','coolant_raw','iat_raw','pairs_raw',
                'throttle_raw','throttle_pct','oxygen_raw','oxygen_voltage_V','oxygen_state',
                'maf_raw','maf_g_s_est','maf_saturated','advance_raw','advance_deg',
                'ecu_voltage_raw','ecu_voltage_V_est','ecu_voltage_scale_V_per_raw',
                'ltft_low_raw','ltft_low_pct','ltft_high_raw','ltft_high_pct',
                'ltft_stored_age_s','ltft_stored_status']
            self.csv_fields += [f'knock_channel_{i}_{unit}' for i in range(1,5) for unit in ('raw','deg_est')]
            self.csv_fields += [f'{name}_{unit}' for name in ('stft_a','ltft_a') for unit in ('raw','pct')]
            self.csv_fields += ['injection_cycle_720_ms','injection_cycle_margin_ms_est',
                'F8C6_calc_raw','injector_deadtime_ms_est','injection_before_deadtime_raw',
                'injection_before_deadtime_ms_est','injector_deadtime_fraction_pct',
                'injection_breakdown_status','injection_F746_saturated']
            self.csv_writer = csv.DictWriter(self.csv_stream, fieldnames=self.csv_fields)
            self.csv_writer.writeheader()

    def sample(self, sample, args, model, event):
        values = dict(demo=args.demo, rpm=sample.rpm, load_raw=sample.load,
            **injection_metrics(sample, args),
            cycle_ms=sample.interval_ms, acquisition_ms=sample.duration_ms, observed_count=model.count, observed_event=event,
            coolant_C=sample.temperatures[0]-40, iat_C=sample.temperatures[1]-40,
            coolant_raw=sample.temperatures[0], iat_raw=sample.temperatures[1], pairs_raw=sample.pairs_raw)
        for i,n in enumerate(sample.corrections,1):
            values[f'knock_channel_{i}_raw']=n
            values[f'knock_channel_{i}_deg_est']=n*0.75
        for name,n in zip(('stft_a','ltft_a'),sample.trims):
            values[name+'_raw']=n
            values[name+'_pct']=trim_percent(n)
        values.update(ecu_voltage_raw=sample.voltage_raw,
                      ecu_voltage_V_est=ecu_voltage_est(sample.voltage_raw, args.voltage_scale),
                      ecu_voltage_scale_V_per_raw=args.voltage_scale,
                      throttle_raw=sample.throttle_raw,
                      throttle_pct=sample.throttle_raw*100/255 if sample.throttle_raw is not None else None,
                      oxygen_raw=sample.oxygen_raw,
                      oxygen_voltage_V=oxygen_voltage(sample.oxygen_raw),
                      oxygen_state=oxygen_state(sample.oxygen_raw),
                      maf_raw=sample.maf_raw,
                      maf_g_s_est=sample.maf_raw*MAF_GS_PER_RAW if sample.maf_raw is not None else None,
                      maf_saturated=sample.maf_raw == 255,
                      advance_raw=sample.advance_raw,
                      advance_deg=advance_degrees(sample.advance_raw) if sample.advance_raw is not None else None)
        with model.lock:
            for label, raw in (('low', model.ltft_low), ('high', model.ltft_high)):
                values[f'ltft_{label}_raw'] = raw
                values[f'ltft_{label}_pct'] = trim_percent(raw) if raw is not None else None
            values['ltft_stored_age_s'] = max(0, sample.at-model.ltft_at) if model.ltft_at is not None else None
            values['ltft_stored_status'] = model.ltft_status
        self.write('csv_sample', **values)

    def write(self, kind, **values):
        if self.stream:
            row = {'utc': dt.datetime.now(dt.timezone.utc).isoformat(), 'type': kind, **values}
            self.stream.write(json.dumps(row, ensure_ascii=False) + '\n')
            if kind == 'csv_sample':
                self.csv_writer.writerow({k:row.get(k,'') for k in self.csv_fields})
            now = time.monotonic()
            if now - self.last_flush >= 1:
                self.stream.flush()
                self.csv_stream.flush()
                self.last_flush = now

    def close(self):
        if self.csv_stream:
            self.csv_stream.close()
        if self.stream:
            self.stream.close()


class ELM:
    def __init__(self, port, baud, journal, stop, fast=True, repeat=False):
        try:
            import serial
        except ImportError as exc:
            raise DiagnosticError('Instala pyserial: python3 -m pip install pyserial') from exc
        self.serial = serial.serial_for_url(port, baudrate=baud, timeout=0.02, write_timeout=2)
        self.journal, self.stop = journal, stop
        self.fast, self.repeat = fast, repeat
        self.adaptive = '0'
        self.is_stn = False
        self.adapter_profile = 'ELM compatible'
        self.adapter_p3 = False
        self.p4_fast = False
        self.timing_fallbacks = 0
        self.response_timeout = '80'  # 128 * 4 ms = 512 ms; suffix 1 returns early.
        self.request_gap = 0.060
        self.last_bus_reply = None
        self.last_command = None
        self.serial.reset_input_buffer()

    def command(self, command: str, timeout=6, allow_repeat=False):
        if self.stop.is_set():
            raise InterruptedError
        is_bus_request = not command.upper().startswith(('AT', 'ST'))
        if is_bus_request and self.last_bus_reply is not None:
            remaining = self.request_gap - (time.monotonic() - self.last_bus_reply)
            if remaining > 0 and self.stop.wait(remaining):
                raise InterruptedError
        repeated = allow_repeat and self.repeat and command == self.last_command
        wire = '' if repeated else command
        start = time.monotonic()
        self.serial.write((wire + '\r').encode('ascii'))
        data = bytearray()
        while not self.stop.is_set() and time.monotonic() - start < timeout:
            chunk = self.serial.read(max(1, min(self.serial.in_waiting, 4096)))
            data.extend(chunk)
            if b'>' in data:
                raw, trailing = bytes(data).split(b'>', 1)
                reply = raw.decode('ascii', errors='replace')
                self.journal.write('exchange', command=command, wire=wire, repeat=repeated,
                                   reply=reply, raw_hex=raw.hex(), prompt=True,
                                   seconds=time.monotonic()-start)
                # Unexpected traffic after prompt must not silently become next response.
                if trailing.strip():
                    raise LinkTimeout('Datos inesperados después del prompt; se detiene la sesión')
                self.last_command = command
                if is_bus_request:
                    self.last_bus_reply = time.monotonic()
                return reply
            if len(data) > 65536:
                break
        self.journal.write('exchange', command=command, wire=wire,
                           raw_hex=bytes(data).hex(), prompt=False,
                           seconds=time.monotonic()-start)
        self.last_command = None
        if self.stop.is_set():
            raise InterruptedError
        raise LinkTimeout('ELM sin prompt >: ' + command)

    def at(self, command, optional=False):
        reply = self.command(command)
        okay = 'OK' in reply.upper().split()
        if not okay and not optional:
            raise DiagnosticError(command + ' rechazado: ' + repr(reply))
        return okay

    def query(self, payload, optimized=True):
        command = payload + ' 1' if self.fast and optimized else payload
        reply = self.command(command, allow_repeat=optimized)
        if reply.strip() == '?' and command != payload:
            # An older clone may not accept the response-count suffix.
            self.fast = False
            reply = self.command(payload)
        # Retry only read services, never replay group definitions or writes.
        if 'NO DATA' in reply.upper() and payload.startswith(('22', '2A')):
            self.journal.write('recovery', reason='NO DATA', payload=payload,
                               action='fixed timeout, explicit command, single-response retry',
                               fast=self.fast, adaptive=self.adaptive)
            self.restore_transport('NO DATA')
            self.repeat = False
            self.last_command = None
            # Do not return to a timing mode that just failed. Keep the suffix:
            # disabling it caused the 1.4 s successful retries seen in logs.
            if self.adaptive != '0':
                self.at('ATAT0')
                self.adaptive = '0'
            retry_command = payload + ' 1' if self.fast and optimized else payload
            reply = self.command(retry_command, allow_repeat=False)
            self.journal.write('recovery_complete', fast=self.fast,
                               adaptive=self.adaptive, repeat=self.repeat)
        return reply

    def setup(self, notice, adaptive):
        self.adaptive = adaptive if self.fast else '0'
        self.at('ATE0')
        identity = self.command('ATI').strip()
        notice('Adaptador listo · ' + identity)
        # Detect actual ST command support, not merely the serial port name.
        firmware = self.command('STI', timeout=2).strip()
        self.is_stn = bool(re.search(r'\bSTN\d+\b|OBDLINK', firmware, re.I))
        hardware = self.command('STDI', timeout=2).strip() if re.search(r'STN|OBDLINK', firmware, re.I) else None
        self.journal.write('adapter_details', identity=identity, firmware=firmware, hardware=hardware,
                           group_ids=[f'11{x:02X}' for x in IDS],
                           decoder_revision='12_ids_load_from_1107_voltage_1110_v2')
        self.at('ATPC')
        notice('Cerrando sesión anterior · 6 s')
        if self.stop.wait(6):
            raise InterruptedError
        for cmd in ('ATD', 'ATE0', 'ATL0', 'ATS0', 'ATH0', 'ATM0', 'ATSP4',
                    'ATIIA01', 'ATSH8101F1', 'ATKW0', 'ATSW00',
                    'ATAT'+self.adaptive, 'ATST'+self.response_timeout):
            self.at(cmd)
        # Group packets exceed the 7-byte limit of some ELM implementations.
        self.at('ATAL', optional=True)
        notice('Inicializando diagnosis Mercedes · dirección 01')
        init = self.command('ATSI', timeout=20)
        kw = self.command('ATKW').strip()
        self.command('ATDP')
        if any(s in init.upper() for s in ('ERROR', 'UNABLE', 'BUS BUSY')):
            raise DiagnosticError('Inicialización fallida: ' + init.strip())
        notice('Sesión lista · palabras clave ' + kw)
        # Read one known identifier with the conservative response wait first.
        positive(self.query('221104', optimized=False), 0x22, b'\x62\x11\x04', 5)

    def restore_transport(self, reason):
        """Rollback once; never repeatedly retest a timing that failed this session."""
        if not (self.adapter_p3 or self.p4_fast):
            return False
        # These are volatile adapter settings, not ECU calibration writes.
        if self.p4_fast:
            self.at('STIP4 5')
            self.p4_fast = False
        self.request_gap = 0.060
        self.adapter_p3 = False
        self.repeat = False
        self.timing_fallbacks += 1
        self.adapter_profile = 'STN compatible'
        self.journal.write('transport_fallback', reason=reason,
                           host_gap_ms=60, p4_ms=5)
        return True

    def optimize_transport(self, mode, notice, publish):
        """Use documented STN timing controls; trial P4=0 on validated reads only.

        Reference: https://www.obdlink.com/frpm, STPTRQ and STIP4.
        Keep the established 60 ms P3 floor. Benchmark P4=5 versus P4=0,
        retain zero only with six valid responses and >=3% measured improvement.
        This short startup test is not proof of long-term link reliability.
        """
        if not self.is_stn or not self.fast:
            return
        self.adapter_profile = 'STN compatible'
        # Configure after ATD/ATSP/ATSI, which can reset protocol settings.
        if self.at('STPTRQ 60', optional=True):
            self.adapter_p3 = True
            self.request_gap = 0.0
            self.adapter_profile = 'STN P3 adaptador'
        self.journal.write('transport_profile', profile=self.adapter_profile,
                           host_gap_ms=self.request_gap*1000, p3_ms=60,
                           response_timeout_hex=self.response_timeout)
        # Avoid dozens of individual requests just to benchmark startup.
        if mode != 'grouped':
            return
        notice('OBDLink/STN · comprobando tiempos de lectura')
        try:
            baseline = []
            for _ in range(4):
                sample = self.acquire(mode)
                publish(sample)
                baseline.append(sample.duration_ms)
                if self.timing_fallbacks or not self.fast:
                    return
            if not self.at('STIP4 0', optional=True):
                return
            self.p4_fast = True
            trial = []
            for _ in range(6):
                sample = self.acquire(mode)
                publish(sample)
                trial.append(sample.duration_ms)
                if self.timing_fallbacks or not self.fast:
                    self.restore_transport('Atajo de respuesta rechazado durante prueba')
                    return
            # Exclude first request after a local AT/ST command from each set.
            base_ms = statistics.median(baseline[1:])
            trial_ms = statistics.median(trial[1:])
            accepted = trial_ms < base_ms * 0.97
            if accepted:
                self.adapter_profile = 'STN rápido P4=0'
            else:
                self.at('STIP4 5')
                self.p4_fast = False
            self.journal.write('transport_benchmark', baseline_ms=baseline,
                               trial_ms=trial, baseline_median_ms=base_ms,
                               trial_median_ms=trial_ms, p4_zero_accepted=accepted,
                               profile=self.adapter_profile)
        except LinkTimeout:
            # No prompt: do not send more commands into an unsynchronised stream.
            raise
        except DiagnosticError as exc:
            self.restore_transport(str(exc))
            self.journal.write('transport_benchmark_error', error=str(exc))
        notice(self.adapter_profile + ' · lectura preparada')

    def define_group(self):
        # Define active slots. Clear remaining slots so frame layout is exact,
        # regardless of any diagnostic client previously connected.
        for slot in range(1, 13):
            clear = slot > len(IDS)
            ident = 0x04 if clear else IDS[slot-1]
            cmd = definition(slot, ident, clear)
            expected = bytes([0x6C, 0x01, 0xD0 if clear else 0xD2])
            positive(self.query(cmd, optimized=False), 0x2C, expected, 3)
        # Learn/verify one complete single response BEFORE the '1' shortcut.
        return grouped(self.query('2A0101', optimized=False))

    def acquire(self, mode):
        start = time.monotonic()
        if mode == 'grouped':
            sample = grouped(self.query('2A0101'))
        else:
            pairs = []
            for ident in IDS:
                f = positive(self.query(f'2211{ident:02X}'), 0x22,
                             bytes([0x62, 0x11, ident]), 5)
                pairs.append(f[3:])
            sample = from_pairs(pairs)
        sample.at = time.monotonic()
        sample.duration_ms = (sample.at-start)*1000
        return sample

    def read_stored_trim(self, address):
        # Custom ECU service 23 returns one word. No length byte.
        if address not in (0x900C, 0x9014):
            raise ValueError('Dirección LTFT inválida')
        suffix = bytes([0, address >> 8, address & 255])
        reply = self.query('23' + suffix.hex().upper())
        frame = positive(reply, 0x23, b'\x63' + suffix, 6)
        return int.from_bytes(frame[4:6], 'little')

    def close(self):
        self.serial.close()

def adapter_ports():
    """Enumerate serial USB and Bluetooth SPP ports, including Linux rfcomm."""
    from serial.tools import list_ports
    found = {}
    for p in list_ports.comports():
        # Bluetooth virtual COM ports normally have no USB VID/PID.
        found.setdefault(p.device, (p.description or '', p.hwid or ''))
    if os.name == 'posix':
        # pyserial's Linux enumeration may omit bound RFCOMM serial devices.
        for p in sorted(Path('/dev').glob('rfcomm*')):
            found.setdefault(str(p), ('Bluetooth RFCOMM', ''))
    def priority(item):
        device, metadata = item
        info = ' '.join((device, *metadata)).upper()
        known = any(tag in info for tag in ('OBDLINK', 'ELM', 'STN', 'OBD'))
        # Natural ordering: COM2 before COM10, deterministic within each group.
        natural = tuple((1, int(x)) if x.isdigit() else (0, x.lower())
                        for x in re.split(r'(\d+)', device))
        return (not known, natural)
    return [device for device, _ in sorted(found.items(), key=priority)]


def connect_adapter(args, journal, stop, notice):
    """Select the first responding ELM-compatible serial USB/Bluetooth adapter.

    Bluetooth must already be paired and expose a serial port (Windows COM or
    Linux /dev/rfcommN). BLE-only devices do not expose this serial interface.
    Discovery uses adapter AT commands only, without initializing the ECU.
    """
    if args.port:
        return ELM(args.port, args.baud, journal, stop,
                   fast=not args.conservative, repeat=False)
    try:
        ports = adapter_ports()
    except ImportError as exc:
        raise DiagnosticError('Instala pyserial: python3 -m pip install pyserial') from exc
    errors = []
    for port in ports:
        for baud in dict.fromkeys((args.baud, 115200, 38400, 9600, 57600, 19200)):
            if stop.is_set():
                raise InterruptedError
            elm = None
            try:
                notice(f'Buscando adaptador · {port} · {baud} baud')
                elm = ELM(port, baud, journal, stop,
                          fast=not args.conservative, repeat=False)
                reply = elm.command('ATE0', timeout=1.5)
                if 'OK' not in reply.upper().split():
                    raise DiagnosticError('ATE0 sin OK')
                identity = elm.command('ATI', timeout=1.5).strip()
                if not re.search(r'ELM|OBDLINK|STN|OBDII|OBD-II', identity, re.I):
                    raise DiagnosticError('Identidad no reconocida: ' + identity)
                args.port, args.baud = port, baud
                journal.write('adapter', port=port, baud=baud, identity=identity)
                return elm
            except InterruptedError:
                if elm:
                    elm.close()
                raise
            except Exception as exc:
                errors.append(f'{port}@{baud}: {exc}')
                if elm:
                    elm.close()
                else:
                    # An unavailable/busy port cannot be fixed by changing baud.
                    break
    journal.write('discovery_failed', attempts=errors)
    raise DiagnosticError('No se encontró un adaptador serie ELM/OBDLink USB o Bluetooth. '
                          'Comprueba alimentación y permisos, cierra otros programas; '
                          'para Bluetooth, empareja y habilita un puerto serie COM o rfcomm. '
                          'También puedes indicar COM3, /dev/ttyUSB0 o /dev/rfcomm0.')


class Model:
    def __init__(self, sound=None):
        self.sound = sound
        self.lock = threading.RLock()
        self.sample = None
        self.ltft_low = self.ltft_high = None
        self.ltft_at = None
        self.ltft_status = 'pendiente'
        self.ltft_disabled = False
        self.next_ltft = 0.0
        self.ltft_pending = None
        self.previous = None
        self.count = 0
        self.alert_until = 0.0
        self.mode = 'iniciando'
        self.adapter_profile = ''
        self.status = 'Preparando conexión'
        self.fatal = False
        self.errors = 0
        self.failed = False
        self.samples = 0
        self.times = deque(maxlen=40)
        self.history = {k: deque(maxlen=90) for k in ('rpm', 'load', 'inj', 'duty', 'retard')}
        self.history['retard'] = []  # Start at first positive correction, then retain every sample.
        self.events = deque(maxlen=5)

    def stored_trims(self, low, high, at=None):
        with self.lock:
            self.ltft_low, self.ltft_high = low, high
            self.ltft_at = time.monotonic() if at is None else at
            self.ltft_status = 'ok'

    def notice(self, message):
        with self.lock:
            self.status = message

    def reject(self, message):
        with self.lock:
            self.errors += 1
            self.failed = True
            self.previous = None
            self.status = message

    def accept(self, sample, tick_us=8/3):
        with self.lock:
            if self.sample is not None:
                sample.interval_ms = (sample.at-self.sample.at)*1000
            old = self.previous
            # Initial/gapped samples establish a baseline; they are not events.
            continuous = old is not None and 0 < sample.at-old.at <= 2.0
            raised = []
            if continuous and sample.rpm >= 400 and old.rpm >= 400:
                # The ECU ignition routine indexes four active channel bytes.
                raised = [i for i,(a,b) in enumerate(zip(old.corrections[:4], sample.corrections[:4])) if b > a]
            if raised:
                self.count += 1  # one observed update, NOT number of combustion knocks
                if self.sound is not None:
                    self.sound.notify()
                self.alert_until = sample.at + 3.0
                slots = ','.join(str(i+1) for i in raised)
                self.events.appendleft((sample.at, f'Aumento en canal(es): {slots}'))
            self.previous = self.sample = sample
            self.failed = False
            self.samples += 1
            self.times.append(sample.at)
            self.history['rpm'].append(sample.rpm)
            self.history['load'].append(sample.load)
            self.history['inj'].append(sample.injection)
            self.history['duty'].append(duty_percent(sample.injection, sample.rpm, tick_us))
            retard = max(sample.corrections, default=0)
            if self.history['retard'] or retard > 0:
                self.history['retard'].append(retard)
            self.status = 'Recibiendo datos' if not raised else 'Aumento de corrección observado'
            return bool(raised)


def poll_stored_trims(elm, model, journal, now=None):
    """One optional request per fast cycle; publish a complete cached pair."""
    now = time.monotonic() if now is None else now
    if model.ltft_disabled or now < model.next_ltft:
        return
    try:
        if model.ltft_pending is None:
            low = elm.read_stored_trim(0x900C)
            model.ltft_pending = (low, time.monotonic())
            return  # Next worker iteration reads the fast group before high.
        low, low_at = model.ltft_pending
        high = elm.read_stored_trim(0x9014)
        model.stored_trims(low, high, low_at)
        journal.write('ltft_stored', low_raw=low, high_raw=high,
                      low_pct=trim_percent(low), high_pct=trim_percent(high),
                      pair_span_s=time.monotonic()-low_at)
    except LinkTimeout:
        raise
    except DiagnosticError as exc:
        elm.restore_transport('Lectura LTFT: ' + str(exc))
        disabled = isinstance(exc, NegativeResponse) and exc.code in (0x11, 0x12, 0x31, 0x33)
        with model.lock:
            model.ltft_disabled = disabled
            model.ltft_status = 'no disponible' if disabled else 'error de lectura'
        journal.write('ltft_stored_error', error=str(exc), disabled=disabled)
    model.ltft_pending = None
    model.next_ltft = time.monotonic() + LTFT_INTERVAL


def worker(args, model, stop, journal):
    elm = None
    try:
        if args.demo:
            model.mode = 'DEMO'
            model.notice('Simulación · eventos programados cada 8 segundos')
            started = time.monotonic()
            prev_at = started
            while not stop.is_set():
                now = time.monotonic()
                t = now-started
                phase = t % 8
                load = int(60 + 175 * (0.5 + 0.5*math.sin(t/2.2)))
                rpm = int(900 + 5200*(0.5 + 0.5*math.sin(t/3-1.5)))
                correction = max(0, 8-int((phase-2)*2)) if phase >= 2 else 0
                corrections = (correction, max(0, correction-2), 0, 0)
                sample = Sample(rpm, int(1100 + load*18), load, corrections,
                                (now-prev_at)*1000, now)
                sample.throttle_raw = round(255*(0.5+0.5*math.sin(t/3)))
                sample.oxygen_raw = round(O2_SWITCH_RAW*(1+0.7*math.sin(t*4)))
                sample.voltage_raw = round((14.0 + 0.2*math.sin(t/5))/args.voltage_scale)
                sample.voltage_raw = min(255, max(0, sample.voltage_raw))
                sample.maf_raw = min(255, int(load*rpm/100000))
                sample.injection = (deadtime_ticks(sample.voltage_raw, args.injector_calibration)
                                    + args.injector_calibration.minimum_ticks
                                    + round(7500*(0.5+0.5*math.sin(t/3-1))))
                sample.advance_raw = round((-15 + 20*math.sin(t/4) + 66)/0.75)
                if now >= model.next_ltft:
                    model.stored_trims(int(32768+1000*math.sin(t/12)),
                                       int(32768+2400*math.cos(t/17)), now)
                    model.next_ltft = now + LTFT_INTERVAL
                sample.temperatures = (130, 78)
                sample.trims = (int(32768+2500*math.sin(t)),34000)
                prev_at = now
                event = model.accept(sample, args.tick_us)
                journal.sample(sample, args, model, event)
                journal.write('sample', demo=True, rpm=rpm, **injection_metrics(sample, args),
                              load_raw=load, correction_raw=corrections, observed_event=event,
                              observed_count=model.count, cycle_ms=sample.interval_ms, acquisition_ms=sample.duration_ms)
                if stop.wait(0.125):
                    break
            return
        elm = connect_adapter(args, journal, stop, model.notice)
        elm.setup(model.notice, args.adaptive)
        mode = 'individual' if args.individual else 'grouped'
        if mode == 'grouped':
            model.notice(f'Configurando {len(IDS)} entradas agrupadas · RAM de diagnóstico')
            try:
                first = elm.define_group()
                event = model.accept(first, args.tick_us)
                journal.sample(first, args, model, event)
            except LinkTimeout:
                raise
            except DiagnosticError as exc:
                journal.write('fallback', reason=str(exc))
                model.reject('Grupo rechazado; paso a lecturas individuales')
                mode = 'individual'
        def publish_probe(sample):
            event = model.accept(sample, args.tick_us)
            journal.sample(sample, args, model, event)
        if not args.no_adapter_tuning:
            elm.optimize_transport(mode, model.notice, publish_probe)
        model.mode = mode
        model.adapter_profile = elm.adapter_profile
        consecutive = 0
        while not stop.is_set():
            try:
                sample = elm.acquire(mode)
                model.adapter_profile = elm.adapter_profile
                event = model.accept(sample, args.tick_us)
                journal.sample(sample, args, model, event)
                consecutive = 0
                journal.write('sample', demo=False, mode=mode, rpm=sample.rpm,
                              **injection_metrics(sample, args), load_raw=sample.load,
                              correction_raw=sample.corrections, cycle_ms=sample.interval_ms, acquisition_ms=sample.duration_ms,
                              observed_event=event, observed_count=model.count,
                              correction_deg_nominal=[n*0.75 for n in sample.corrections])
                poll_stored_trims(elm, model, journal)
            except LinkTimeout:
                raise
            except DiagnosticError as exc:
                consecutive += 1
                model.reject(str(exc))
                if elm.restore_transport(str(exc)):
                    model.adapter_profile = elm.adapter_profile
                    model.notice('Tiempos compatibles restaurados · reintentando lectura')
                    continue
                if mode == 'grouped' and consecutive >= 3:
                    mode = model.mode = 'individual'
                    journal.write('fallback', reason='3 respuestas de grupo inválidas')
                    consecutive = 0
                elif consecutive >= 5:
                    raise DiagnosticError('Cinco ciclos sin datos válidos. ' + str(exc))
    except InterruptedError:
        pass
    except Exception as exc:
        with model.lock:
            model.fatal = True
            model.failed = True
            model.previous = None
            model.status = f'{type(exc).__name__}: {exc}'
        journal.write('fatal', error=str(exc))
    finally:
        if elm:
            elm.close()
        journal.close()


def gradient_color(color, level):
    """Exact original color at zero; accent hue at full scale."""
    start = Color.parse(color).get_truecolor()
    end = Color.parse(PEAK_COLORS.get(color, color)).get_truecolor()
    amount = max(0.0, min(1.0, level)) ** 1.5
    return '#'+''.join(f'{round(a+(b-a)*amount):02x}' for a,b in zip(start,end))


def bar(value, ceiling, width=24, color=None):
    width = max(1, width)
    fill = round(width*max(0, min(1, value/ceiling))) if ceiling > 0 else 0
    if color is None:
        return '━'*fill + '─'*(width-fill)
    result = Text()
    for x in range(width):
        # Color follows the full scale, not the current filled length:
        # low readings stay near the original color, high readings reach accent.
        result.append('━' if x < fill else '─',
                      style=f'bold {gradient_color(color, x/max(1,width-1))}'
                      if x < fill else MUTED)
    return result


def spark(values, ceiling, width=48, height=7, color=None, full=False):
    """Braille graph: two horizontal and four vertical pixels per terminal cell."""
    tail = list(values) if full else list(values)[-90:]
    width = max(1, width)
    pixels_w, pixels_h = width*2, max(1,height)*4
    if full and len(tail) > pixels_w:
        tail = [max(tail[i*len(tail)//pixels_w:(i+1)*len(tail)//pixels_w])
                for i in range(pixels_w)]
    fill = set()
    if tail and ceiling > 0:
        for x in range(pixels_w):
            position = x*(len(tail)-1)/(pixels_w-1) if pixels_w > 1 else len(tail)-1
            left = int(position)
            right = min(left+1,len(tail)-1)
            fraction = position-left
            value = tail[left]*(1-fraction)+tail[right]*fraction
            y_from_bottom = min(pixels_h-1,max(0,round(value/ceiling*(pixels_h-1))))
            curve_y = pixels_h-1-y_from_bottom
            fill.update((x,y) for y in range(curve_y,pixels_h))
    # Braille dot bit positions, indexed [row][column].
    bit = ((0,3),(1,4),(2,5),(6,7))
    lines=[]
    styled = Text()
    for cell_y in range(height):
        if color:
            # Outline and fill share the same row color and bold styling.
            level = 1-cell_y/(height-1) if height > 1 else 0
            row_color = gradient_color(color, level)
        line=[]
        for cell_x in range(width):
            mask=0
            for dy in range(4):
                for dx in range(2):
                    pixel = (cell_x*2+dx,cell_y*4+dy)
                    if pixel in fill:
                        mask |= 1 << bit[dy][dx]
            char = chr(0x2800+mask)
            line.append(char)
            if color:
                styled.append(char, style=f'bold {row_color}')
        lines.append(''.join(line))
        if color and cell_y < height-1:
            styled.append('\n')
    return styled if color else '\n'.join(lines)


def metric(title, value, unit, detail, color, fraction, trace, bg):
    text = Text(justify='center')
    text.append(value, style=f'bold {color}')
    text.append(' '+unit, style=MUTED)
    content = Group('', text, '', Align.center(bar(fraction,1,color=color)),
                    Align.center(Text(detail, style=MUTED)),
                    Align.center(trace if isinstance(trace, Text) else Text(trace, style=color)))
    return Panel(content, title=Text(title, style=f'bold {color}'),
                 border_style=color, box=box.ROUNDED, style=f'on {bg}', padding=(0,0))


def render(model, args, width=110, height=44, now=None):
    now = time.monotonic() if now is None else now
    with model.lock:
        s = model.sample
        age = now-s.at if s else math.inf
        stale = model.failed or age > 2
        flashing = now < model.alert_until
        red = flashing and not args.no_flash and int(now*2)%2 == 0
        bg = '#811c31' if red else BG
        layout = Layout()
        layout.split_column(Layout(name='metrics', size=14),
                            Layout(name='lower', ratio=1, minimum_size=20),
                            Layout(name='foot', size=4))
        layout['lower'].split_row(Layout(name='knock', ratio=2), Layout(name='extras', ratio=1))
        if width < 82 or height < 34:
            return Panel(Text('Amplía la terminal a 82 columnas × 34 filas como mínimo.\n'+model.status, style=CYAN), style=f'on {bg}')
        shown = s is not None and not stale
        rpm, load, inj = (s.rpm,s.load,s.injection) if s else (0,0,0)
        trace_width = width//3-2
        layout['metrics'].split_row(Layout(name='rpm'),Layout(name='load'),Layout(name='inj'))
        layout['rpm'].update(metric('RÉGIMEN', f'{rpm:,}'.replace(',','.') if shown else '—', 'rpm',
            '0–6.500 rpm', CYAN, rpm/6500,
            spark(model.history['rpm'],6500,trace_width,color=CYAN),bg))
        layout['load'].update(metric('CARGA INTERNA', str(load) if shown else '—', 'raw',
            (f'Mariposa: {s.throttle_raw*100/255:.1f} %' if shown and s.throttle_raw is not None else 'Mariposa: —'), AMBER, load/255,
            spark(model.history['load'],255,trace_width,color=AMBER),bg))
        injection = injection_metrics(s, args) if shown else None
        duty = injection['injector_duty_est_pct'] if injection else 0
        duty_color = RED if shown and duty > 100 else AMBER if shown and duty >= 90 else GREEN
        detail = (
            f"~{injection['injection_ms_est']:.2f} ms · {inj} raw"
            if injection else 'Sin lectura actual de F746'
        )
        layout['inj'].update(metric(
            'INYECCIÓN / DUTY',
            f'~{duty:.1f}' if shown else '—', '%',
            detail, duty_color, duty/100,
            spark(
                model.history['duty'],
                max(100, max(model.history['duty'], default=100)),
                trace_width, color=duty_color
            ), bg
        ))
        table = Table.grid(expand=True, padding=(0,2))
        table.add_column(ratio=1)
        correction = max(s.corrections) if s else 0
        indices = Table.grid(expand=True, padding=(0,2))
        for _ in range(4):
            indices.add_column(ratio=1)
        row=[]
        for i in range(4):
            n=s.corrections[i] if s and i < len(s.corrections) else 0
            row.append(Text(f'\nC{i+1}: {n*0.75:.2f}°' if shown else f'CANAL {i+1}   —',
                            style=f'italic bold {MAGENTA}' if n and shown else MUTED))
        indices.add_row(*row)
        if model.count > 0:
            table.add_column(width=max(12,len(str(model.count))+4), justify='right')
            counter = Text(f'{model.count:04d}', style='bold bright_red', justify='center')
            counter_box = Panel(counter, border_style='bold bright_red',
                                box=box.HEAVY, padding=(0,1))
            table.add_row(indices, counter_box)
        else:
            table.add_row(indices)
        retard_line = Text('\nRetardo de encendido: ', style=MUTED)
        retard_line.append(f'{correction*0.75:.2f}°' if shown else '—',
                           style=f'bold {MAGENTA}')
        body = [table,
                Align.center(retard_line),
                '',
                spark(model.history['retard'],max(4,max(model.history['retard'],default=0)),
                      max(20,width*2//3-6),height=max(7,height-33),color=MAGENTA,full=True)]
        if model.events:
            at,message=model.events[0]
            body.append(Text(f'\nÚltimo aviso hace {max(0,now-at):.1f} s · {message}',style=MUTED))
        layout['knock'].update(Panel(Group(*body), title=Text(' CONTROL DE PICADO ', style=f'bold {MAGENTA}'),
            box=box.HEAVY if flashing else box.ROUNDED, border_style=RED if flashing else MAGENTA,
            style=f'on {bg}', padding=(0,2)))
        mixture = Table.grid(expand=True, padding=(0,1))
        mixture.add_column(style=MUTED)
        mixture.add_column(justify='right')
        for label,raw in zip(('STFT','LTFT'),s.trims if s else (32768,)*2):
            mixture.add_row(label, Text(f'{trim_percent(raw):+.2f} %' if shown else '—',
                                        style=f'bold {GREEN}'))
        stored_age = now-model.ltft_at if model.ltft_at is not None else math.inf
        for label,raw in (('LTFT low',model.ltft_low), ('LTFT high',model.ltft_high)):
            valid = raw is not None and stored_age <= LTFT_INTERVAL + 5 and model.ltft_status == 'ok'
            value = f'{trim_percent(raw):+.2f} %' if valid else '—'
            mixture.add_row(label, Text(value, style=f'bold {GREEN if valid else MUTED}'))
        maf = f'{s.maf_raw*MAF_GS_PER_RAW:.1f} g/s' if shown and s.maf_raw is not None else '—'
        mixture.add_row('Caudalímetro', Text(maf, style=f'bold {CYAN}'))
        oxygen = f'{oxygen_voltage(s.oxygen_raw):.3f} V · {oxygen_state(s.oxygen_raw)}' if shown and s.oxygen_raw is not None else '—'
        mixture.add_row('Sonda λ', Text(oxygen, style=f'bold {AMBER}'))
        temperatures = Table.grid(expand=True, padding=(0,1))
        temperatures.add_column(style=MUTED)
        temperatures.add_column(justify='right')
        for label,raw in zip(('ADMISIÓN','REFRIGERANTE'),reversed(s.temperatures if s else (40,40))):
            temperatures.add_row(label, Text(f'{raw-40} °C' if shown else '—', style=f'bold {AMBER}'))
        layout['extras'].split_column(Layout(name='mixture',size=8), Layout(name='temps',size=4), Layout(name='advance',minimum_size=8))
        layout['mixture'].update(Panel(mixture, title='MEZCLA', border_style=GREEN,
                                       box=box.ROUNDED, style=f'on {bg}'))
        layout['temps'].update(Panel(temperatures, title='TEMPERATURAS', border_style=AMBER,
                                     box=box.ROUNDED, style=f'on {bg}'))
        adv = advance_degrees(s.advance_raw) if shown and s.advance_raw is not None else None
        advance_text = Text(f'{adv:+.1f}°' if adv is not None else '—',
                            style=f'bold {CYAN}', justify='center')
        advance_bar = bar(abs(adv), 40, color=CYAN) if adv is not None else Text('─'*24, style=MUTED)
        direction = ('antes del PMS' if adv < 0 else 'después del PMS' if adv > 0 else 'en el PMS') if adv is not None else 'Sin lectura actual'
        layout['advance'].update(Panel(Group('', Align.center(advance_text), '',
            Align.center(advance_bar), Align.center(Text(direction, style=MUTED))),
            title=Text('AVANCE DE ENCENDIDO', style=f'bold {CYAN}'), border_style=CYAN,
            box=box.ROUNDED, style=f'on {bg}', padding=(0,1)))
        hz = ((len(model.times)-1)/(model.times[-1]-model.times[0])) if len(model.times)>1 and model.times[-1]>model.times[0] else 0
        interval = f'{s.interval_ms:.0f} ms/ciclo' if s and s.interval_ms is not None else 'primer ciclo'
        perf = f'{hz:.1f} Hz · {interval} · adquisición {s.duration_ms:.0f} ms' if s else 'Esperando primera muestra'
        if s:
            perf += f'  ·  edad {age:.1f} s'
        footer = Text(perf + f'  ·  errores {model.errors}  ·  Ctrl+C salir', style=MUTED)
        mode_label = 'DEMO · SIN COCHE' if args.demo else model.mode.upper() + (' · '+model.adapter_profile if model.adapter_profile else '')
        voltage = ecu_voltage_est(s.voltage_raw, args.voltage_scale) if shown else None
        footer.append('\nECU ' + (f'~{voltage:.2f} V' if voltage is not None else '— V'), style=f'bold {CYAN}')
        footer.append(' · '+mode_label+' · '+model.status, style=AMBER if stale or args.demo else GREEN)
        layout['foot'].update(Panel(footer, border_style='#334d6d', box=box.ROUNDED, style=f'on {bg}'))
        return layout


def parse_args(argv=None):
    p=argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('port', nargs='?', help='Opcional: autodetección USB/Bluetooth; o /dev/ttyUSB0, /dev/rfcomm0, COM5, socket://IP:PUERTO')
    p.add_argument('--demo', action='store_true', help='Datos simulados, sin abrir puerto ni importar pyserial')
    p.add_argument('--baud', type=int, default=115200)
    p.add_argument('--seconds', type=float, default=0, help='Duración; 0 hasta Ctrl+C')
    p.add_argument('--adaptive', choices=['0','1','2'], default='0',
                   help='Ajuste ELM: 0 fijo (por defecto, más estable); 1/2 adaptativo')
    p.add_argument('--individual', action='store_true', help='Usar 22 en vez de intentar la agrupación')
    p.add_argument('--no-adapter-tuning', action='store_true',
                   help='Desactivar ajustes y prueba de tiempos específicos de OBDLink/STN')
    p.add_argument('--conservative', action='store_true', help='Sin sufijo de una respuesta ni repetición por CR')
    p.add_argument('--tick-us', type=float, default=8/3, help='Escala no verificada de F746; provisional 2.6666667 us/raw')
    p.add_argument('--voltage-scale', type=float, default=VOLTAGE_V_PER_RAW_EST,
                   help='Escala provisional F734 en V/raw, por defecto 0.09375; pendiente de validar')
    p.add_argument('--no-flash', action='store_true', help='Mantener aviso sin parpadeo de fondo')
    p.add_argument('--log-dir', type=Path, default=Path('logs'))
    p.add_argument('--no-log', action='store_true')
    a=p.parse_args(argv)
    if a.baud <= 0 or not math.isfinite(a.seconds) or a.seconds < 0 or not math.isfinite(a.tick_us) or a.tick_us <= 0:
        p.error('baud y tick-us positivos; seconds >= 0')
    if not math.isfinite(a.voltage_scale) or a.voltage_scale <= 0:
        p.error('voltage-scale debe ser finito y positivo')
    a.injector_calibration = InjectorCalibration()
    return a


def main(argv=None):
    args=parse_args(argv)
    console=Console()
    stop=threading.Event()
    sound = KnockSound()
    model=Model(sound=sound)
    try:
        journal=Journal(args.log_dir, not args.no_log)
    except OSError as exc:
        sound.close()
        console.print(Text('No se puede crear el registro: '+str(exc),style='red'))
        return 1
    journal.write('session', demo=args.demo, port=args.port, baud=args.baud,
                  diagnostic_ids=[f'11{i:02X}' for i in IDS],
                  voltage_scale_V_per_raw_assumed=args.voltage_scale, voltage_conversion_verified=False,
                  tick_us_assumed=args.tick_us, correction_deg_per_raw_nominal=0.75,
                  injection_tick_conversion_verified=False,
                  injection_duty_basis='F746 common request; one pulse per 720 degrees assumed; not measured output duty',
                  injector_deadtime_source=args.injector_calibration.source,
                  injector_calibration_sha256=args.injector_calibration.sha256,
                  injector_deadtime_curve=list(args.injector_calibration.nodes),
                  injector_deadtime_is_direct_ram_read=False,
                  injector_minimum_ticks=args.injector_calibration.minimum_ticks,
                  adaptive=args.adaptive, response_timeout_ms=512, request_gap_ms=60,
                  transport_revision='stn_measured_p4_with_rollback_v3',
                  adapter_tuning=not args.no_adapter_tuning and not args.conservative,
                  detector='observed_correction_increase_NOT_ECU_knock_counter')
    thread=threading.Thread(target=worker,args=(args,model,stop,journal),daemon=True)
    thread.start()
    started=time.monotonic()
    try:
        with Live(console=console,screen=True,auto_refresh=False,vertical_overflow='crop') as live:
            while not args.seconds or time.monotonic()-started < args.seconds:
                live.update(render(model,args,console.width,console.height),refresh=True)
                if model.fatal:
                    stop.wait(1)
                    break
                stop.wait(0.05)  # UI only; acquisition runs independently without sleeps
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        sound.close()
        thread.join(timeout=3)
    console.print(Text('Sesión finalizada · '+model.status,style='red' if model.fatal else 'cyan'))
    if journal.path:
        console.print(Text('Registro: '+str(journal.path.resolve())))
        console.print(Text('CSV: '+str(journal.csv_path.resolve())))
    return 1 if model.fatal else 0


if __name__=='__main__':
    raise SystemExit(main())
