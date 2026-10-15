#!/usr/bin/env python3
"""Bosch ME2.1 SW404 dashboard. Python 3.10+, rich; pyserial for a real ECU.
Demo: python3 mercedes_dashboard.py --demo
Car:  python3 mercedes_dashboard.py /dev/ttyUSB0

The red alert counts observed rises in candidate knock corrections, NOT a
validated ECU knock counter. Injection milliseconds use a provisional scale.
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
import re
import sys
import threading
import time

try:
    from rich import box
    from rich.align import Align
    from rich.console import Console, Group
    from rich.layout import Layout
    from rich.live import Live
    from rich.panel import Panel
    from rich.table import Table
    from rich.text import Text
except ImportError:
    raise SystemExit('Instala la interfaz: python3 -m pip install rich')

# Each diagnostic identifier returns TWO consecutive bytes. Four requests cover
# all eight internal correction slots, without claiming eight physical cylinders.
IDS = (0x04, 0x0D, 0x06, 0x2B, 0x2D, 0x2F, 0x31, 0x1B, 0x1C, 0x23, 0x24, 0x01)
BG = '#0b1424'
MUTED = '#9aaec8'
CYAN = '#57d8ed'
GREEN = '#65e2ab'
AMBER = '#ffd27a'
MAGENTA = '#cfafff'


class DiagnosticError(RuntimeError):
    pass


class LinkTimeout(DiagnosticError):
    pass


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
        raise DiagnosticError('ECU: ' + ' / '.join(f.hex(' ').upper() for f in negative))
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
    trims: tuple[int, ...] = (32768,)*4
    temperatures: tuple[int, int] = (40,40)
    pairs_raw: str = ""


def from_pairs(pairs: list[bytes]) -> Sample:
    if len(pairs) != len(IDS) or any(len(p) != 2 for p in pairs):
        raise DiagnosticError('Número de señales incorrecto')
    return Sample(int.from_bytes(pairs[0], 'little'),
                  int.from_bytes(pairs[1], 'little'), pairs[2][0],
                  tuple(b for pair in pairs[3:7] for b in pair),
                  trims=tuple(int.from_bytes(p, "little") for p in pairs[7:11]),
                  temperatures=tuple(pairs[11]), pairs_raw=b"".join(pairs).hex())


def grouped(reply: str) -> Sample:
    frame = positive(reply, 0x2A, b'\x6a\x01', 2 + 2 * len(IDS))
    return from_pairs([frame[i:i+2] for i in range(2, len(frame), 2)])


def duty_percent(injection_raw: int, rpm: int, tick_us: float = 8/3) -> float:
    """Estimate duty assuming F746 is one pulse per 720 crank degrees."""
    if injection_raw < 0 or rpm < 0 or tick_us <= 0:
        raise ValueError('Valores inválidos para estimar el duty')
    pulse_ms = injection_raw * tick_us / 1000
    return pulse_ms * rpm / 1200


def trim_percent(raw):
    # Isolated multiplicative contribution in 15222–15276, neutral=0x8000.
    return ((16384 + (raw >> 1))/32768 - 1)*100


class Journal:
    def __init__(self, root: Path, enabled: bool):
        self.csv_stream = None
        self.stream = None
        self.path = None
        self.last_flush = 0.0
        if enabled:
            root.mkdir(parents=True, exist_ok=True)
            self.path = root / dt.datetime.now().strftime('me21_%Y%m%d_%H%M%S_%f.jsonl')
            self.stream = self.path.open('x', encoding='utf-8')
            self.csv_path = self.path.with_suffix('.csv')
            self.csv_stream = self.csv_path.open('x', encoding='utf-8', newline='')
            self.csv_fields = ['utc','demo','rpm','load_raw','F746_raw','injection_ms_est',
                'injector_duty_est_pct','cycle_ms','observed_count','observed_event',
                'coolant_C','iat_C','coolant_raw','iat_raw','pairs_raw']
            self.csv_fields += [f'knock_{i}_{unit}' for i in range(1,9) for unit in ('raw','deg')]
            self.csv_fields += [f'{name}_{unit}' for name in ('stft_a','stft_b','ltft_a','ltft_b') for unit in ('raw','pct')]
            self.csv_writer = csv.DictWriter(self.csv_stream, fieldnames=self.csv_fields)
            self.csv_writer.writeheader()

    def sample(self, sample, args, model, event):
        values = dict(demo=args.demo, rpm=sample.rpm, load_raw=sample.load,
            F746_raw=sample.injection, injection_ms_est=sample.injection*args.tick_us/1000,
            injector_duty_est_pct=duty_percent(sample.injection,sample.rpm,args.tick_us),
            cycle_ms=sample.duration_ms, observed_count=model.count, observed_event=event,
            coolant_C=sample.temperatures[0]-40, iat_C=sample.temperatures[1]-40,
            coolant_raw=sample.temperatures[0], iat_raw=sample.temperatures[1], pairs_raw=sample.pairs_raw)
        for i,n in enumerate(sample.corrections,1):
            values[f'knock_{i}_raw']=n
            values[f'knock_{i}_deg']=n*0.75
        for name,n in zip(('stft_a','stft_b','ltft_a','ltft_b'),sample.trims):
            values[name+'_raw']=n
            values[name+'_pct']=trim_percent(n)
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
    def __init__(self, port, baud, journal, stop, fast=True, repeat=True):
        try:
            import serial
        except ImportError as exc:
            raise DiagnosticError('Instala pyserial: python3 -m pip install pyserial') from exc
        self.serial = serial.serial_for_url(port, baudrate=baud, timeout=0.02, write_timeout=2)
        self.journal, self.stop = journal, stop
        self.fast, self.repeat = fast, repeat
        self.last_command = None
        self.serial.reset_input_buffer()

    def command(self, command: str, timeout=6, allow_repeat=False):
        if self.stop.is_set():
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
                               action='disable shortcuts and adaptive timing; retry once')
            self.fast = self.repeat = False
            self.last_command = None
            self.at('ATAT0')
            self.at('ATSTFF')
            if self.stop.wait(0.06):
                raise InterruptedError
            reply = self.command(payload, allow_repeat=False)
        return reply

    def setup(self, notice, adaptive):
        self.at('ATE0')
        notice('USB listo · ' + self.command('ATI').strip())
        self.at('ATPC')
        notice('Cerrando sesión anterior · 6 s')
        if self.stop.wait(6):
            raise InterruptedError
        for cmd in ('ATD', 'ATE0', 'ATL0', 'ATS0', 'ATH0', 'ATM0', 'ATSP4',
                    'ATIIA01', 'ATSH8101F1', 'ATKW0', 'ATSW00',
                    'ATAT'+('0' if not self.fast else adaptive), 'ATSTFF'):
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

    def close(self):
        self.serial.close()

def connect_adapter(args, journal, stop, notice):
    """Probe USB serial adapters with local AT commands before accessing the ECU."""
    if args.port:
        return ELM(args.port, args.baud, journal, stop,
                   fast=not args.conservative, repeat=not args.conservative)
    from serial.tools import list_ports
    ports = [p for p in list_ports.comports() if p.vid is not None]
    ports.sort(key=lambda p: (not any(s in (p.description or '').upper()
                                     for s in ('OBDLINK', 'ELM', 'OBD')), p.device))
    errors = []
    for port in ports:
        for baud in dict.fromkeys((args.baud, 38400, 9600, 57600, 19200)):
            elm = None
            try:
                notice(f'Buscando adaptador · {port.device} · {baud} baud')
                elm = ELM(port.device, baud, journal, stop,
                          fast=not args.conservative, repeat=not args.conservative)
                reply = elm.command('ATE0', timeout=1.2)
                if 'OK' not in reply.upper().split():
                    raise DiagnosticError('ATE0 sin OK')
                identity = elm.command('ATI', timeout=1.2).strip()
                if not re.search(r'ELM|OBDLINK|STN|OBDII|OBD-II', identity, re.I):
                    raise DiagnosticError('Identidad no reconocida: ' + identity)
                args.port, args.baud = port.device, baud
                journal.write('adapter', port=args.port, baud=baud, identity=identity)
                return elm
            except InterruptedError:
                if elm:
                    elm.close()
                raise
            except Exception as exc:
                errors.append(f'{port.device}@{baud}: {exc}')
                if elm:
                    elm.close()
    journal.write('discovery_failed', attempts=errors)
    raise DiagnosticError('No se encontró un adaptador USB ELM/OBDLink. '
                          'Comprueba alimentación y permisos, cierra otros programas '
                          'o indica el puerto manualmente (COM3 / /dev/ttyUSB0).')


class Model:
    def __init__(self):
        self.lock = threading.RLock()
        self.sample = None
        self.previous = None
        self.count = 0
        self.alert_until = 0.0
        self.mode = 'iniciando'
        self.status = 'Preparando conexión'
        self.fatal = False
        self.errors = 0
        self.failed = False
        self.samples = 0
        self.times = deque(maxlen=40)
        self.history = {k: deque(maxlen=90) for k in ('rpm', 'load', 'inj', 'duty', 'retard')}
        self.history['retard'] = []  # Entire session, never evict old peaks.
        self.events = deque(maxlen=5)

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
            old = self.previous
            # Initial/gapped samples establish a baseline; they are not events.
            continuous = old is not None and 0 < sample.at-old.at <= 2.0
            raised = []
            if continuous and sample.rpm >= 400 and old.rpm >= 400:
                raised = [i for i,(a,b) in enumerate(zip(old.corrections, sample.corrections)) if b > a]
            if raised:
                self.count += 1  # one observed update, NOT number of combustion knocks
                self.alert_until = sample.at + 3.0
                slots = ','.join(str(i+1) for i in raised)
                self.events.appendleft((sample.at, f'Aumento en índice(s) {slots}'))
            self.previous = self.sample = sample
            self.failed = False
            self.samples += 1
            self.times.append(sample.at)
            self.history['rpm'].append(sample.rpm)
            self.history['load'].append(sample.load)
            self.history['inj'].append(sample.injection)
            self.history['duty'].append(duty_percent(sample.injection, sample.rpm, tick_us))
            self.history['retard'].append(max(sample.corrections))
            self.status = 'Recibiendo datos' if not raised else 'Aumento de corrección observado'
            return bool(raised)


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
                corrections = (correction, max(0, correction-2), 0, 0, 0, 0, 0, 0)
                sample = Sample(rpm, int(1100 + load*18), load, corrections,
                                (now-prev_at)*1000, now)
                sample.temperatures = (130, 78)
                sample.trims = (int(32768+2500*math.sin(t)),32768,34000,33000)
                prev_at = now
                event = model.accept(sample, args.tick_us)
                journal.sample(sample, args, model, event)
                journal.write('sample', demo=True, rpm=rpm, F746_raw=sample.injection,
                              load_raw=load, correction_raw=corrections, observed_event=event,
                              observed_count=model.count, cycle_ms=sample.duration_ms)
                if stop.wait(0.125):
                    break
            return
        elm = connect_adapter(args, journal, stop, model.notice)
        elm.setup(model.notice, args.adaptive)
        mode = 'individual' if args.individual else 'grouped'
        if mode == 'grouped':
            model.notice('Configurando 12 entradas agrupadas · RAM de diagnóstico')
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
        model.mode = mode
        consecutive = 0
        while not stop.is_set():
            try:
                sample = elm.acquire(mode)
                event = model.accept(sample, args.tick_us)
                journal.sample(sample, args, model, event)
                consecutive = 0
                journal.write('sample', demo=False, mode=mode, rpm=sample.rpm,
                              F746_raw=sample.injection, load_raw=sample.load,
                              correction_raw=sample.corrections, cycle_ms=sample.duration_ms,
                              observed_event=event, observed_count=model.count,
                              injection_ms_est=sample.injection*args.tick_us/1000,
                              injector_duty_est_pct=duty_percent(sample.injection, sample.rpm, args.tick_us),
                              correction_deg_nominal=[n*0.75 for n in sample.corrections])
            except LinkTimeout:
                raise
            except DiagnosticError as exc:
                consecutive += 1
                model.reject(str(exc))
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


def bar(value, ceiling, width=24):
    fill = round(width*max(0, min(1, value/ceiling)))
    return '━'*fill + '─'*(width-fill)


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
                styled.append(char, style=f'bold {color}')
        lines.append(''.join(line))
        if color and cell_y < height-1:
            styled.append('\n')
    return styled if color else '\n'.join(lines)


def metric(title, value, unit, detail, color, fraction, trace, bg):
    text = Text(justify='center')
    text.append(value, style=f'bold {color}')
    text.append(' '+unit, style=MUTED)
    content = Group('', text, '', Align.center(Text(bar(fraction,1), style=color)),
                    Align.center(Text(detail, style=MUTED)),
                    Align.center(trace if isinstance(trace, Text) else Text(trace, style=color)))
    return Panel(content, title=Text(title, style=f'bold {color}'),
                 border_style=color, box=box.ROUNDED, style=f'on {bg}', padding=(0,1))


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
        layout.split_column(Layout(name='metrics', size=16),
                            Layout(name='lower', ratio=1, minimum_size=18),
                            Layout(name='foot', size=4))
        layout['lower'].split_row(Layout(name='knock', ratio=2), Layout(name='extras', ratio=1))
        if width < 100 or height < 42:
            return Panel(Text('Amplía la terminal a 100 columnas × 42 filas como mínimo.\n'+model.status, style=CYAN), style=f'on {bg}')
        shown = s is not None and not stale
        rpm, load, inj = (s.rpm,s.load,s.injection) if s else (0,0,0)
        trace_width = max(10, width//3-6)
        layout['metrics'].split_row(Layout(name='rpm'),Layout(name='load'),Layout(name='inj'))
        layout['rpm'].update(metric('RÉGIMEN', f'{rpm:,}'.replace(',',' ') if shown else '—', 'rpm',
            'Escala visual 0–6.500 rpm', CYAN, rpm/6500,
            spark(model.history['rpm'],6500,trace_width,color=CYAN),bg))
        layout['load'].update(metric('CARGA INTERNA', str(load) if shown else '—', 'raw',
            '0–255, no porcentaje', AMBER, load/255,
            spark(model.history['load'],255,trace_width,color=AMBER),bg))
        ms = inj*args.tick_us/1000
        duty = duty_percent(inj, rpm, args.tick_us) if s else 0
        layout['inj'].update(metric('INYECCIÓN / DUTY', f'~{duty:.1f}' if shown else '—', '%',
            (f'~{ms:.2f} ms · {inj} raw\nEstimación · 1 pulso/720°' if shown else 'Sin lectura actual de F746'), GREEN, duty/100,
            spark(model.history['duty'],100,trace_width,color=GREEN),bg))
        notice = 'AUMENTO OBSERVADO · AVISO 3 s' if flashing else 'SIN NUEVOS AUMENTOS OBSERVADOS'
        if not shown:
            notice = 'SIN DATOS ACTUALES · NO SE PUEDE EVALUAR'
        table = Table.grid(expand=True)
        table.add_column(ratio=1)
        table.add_column(width=max(12,len(str(model.count))+4), justify='right')
        counter = Text(f'{model.count:04d}', style='bold bright_red', justify='center')
        correction = max(s.corrections) if s else 0
        right = Text(notice, style=f'bold {"#ffffff" if flashing else AMBER}')
        right.append('\nRetardo solicitado máx.: ', style=MUTED)
        right.append(f'~{correction*0.75:.2f}°  ({correction} raw)' if shown else '—', style=f'bold {MAGENTA}')
        right.append('\nEscala nominal; antes de límites de encendido', style=f'italic {MUTED}')
        counter_box = Panel(counter,
                            border_style='bold bright_red', box=box.HEAVY, padding=(0,1))
        table.add_row(right, counter_box)
        indices = Table.grid(expand=True, padding=(0,1))
        for _ in range(4):
            indices.add_column(ratio=1)
        for start in (0,4):
            row=[]
            for i in range(start,start+4):
                n=s.corrections[i] if s else 0
                row.append(Text(f'I{i+1}  {n:3d} raw' if shown else f'I{i+1}   —',
                                style=MAGENTA if n and shown else MUTED))
            indices.add_row(*row)
        body = [table, indices,
                Text('Histórico completo · picos conservados · índices internos.', style=f'italic {MUTED}')]
        if height >= 34:
            body += [spark(model.history['retard'],max(20,max(model.history['retard'],default=0)),max(20,width*2//3-6),color=MAGENTA,full=True)]
            if model.events:
                at,message=model.events[0]
                body.append(Text(f'Último aviso hace {max(0,now-at):.1f} s · {message}',style=MUTED))
        layout['knock'].update(Panel(Group(*body), title=Text(' CONTROL DE PICADO · INDICADOR PROVISIONAL ', style=f'bold {MAGENTA}'),
            box=box.HEAVY if flashing else box.ROUNDED, border_style='#ff7b90' if flashing else MAGENTA,
            style=f'on {bg}', padding=(0,2)))
        extra = Text()
        for label,raw in zip(('STFT A','STFT B','LTFT A','LTFT B'),s.trims if s else (32768,)*4):
            extra.append(label+'\n',style=MUTED)
            extra.append((f'{trim_percent(raw):+.2f} %' if shown else '—')+'\n',style=f'bold {GREEN}')
        for label,raw in zip(('ADMISIÓN','REFRIGERANTE'),reversed(s.temperatures if s else (40,40))):
            extra.append(label+'\n',style=MUTED)
            extra.append((f'{raw-40} °C' if shown else '—')+'\n',style=f'bold {AMBER}')
        extra.append('\nLTFT: componente multiplicativo.\nA/B: canales internos.',style=f'italic {MUTED}')
        layout['extras'].update(Panel(extra,title='MEZCLA / TEMPERATURAS',border_style=GREEN,style=f'on {bg}'))
        hz = ((len(model.times)-1)/(model.times[-1]-model.times[0])) if len(model.times)>1 and model.times[-1]>model.times[0] else 0
        perf = f'{hz:.1f} Hz  ·  {s.duration_ms:.0f} ms/ciclo' if s else 'Esperando primera muestra'
        if s:
            perf += f'  ·  edad {age:.1f} s'
        footer = Text(perf + f'  ·  errores {model.errors}  ·  Ctrl+C salir', style=MUTED)
        mode_label = 'DEMO · SIN COCHE' if args.demo else model.mode.upper()
        footer.append('\n'+mode_label+' · '+model.status, style=AMBER if stale or args.demo else GREEN)
        layout['foot'].update(Panel(footer, border_style='#334d6d', box=box.ROUNDED, style=f'on {bg}'))
        return layout


def parse_args(argv=None):
    p=argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('port', nargs='?', help='Opcional: autodetección USB; o /dev/ttyUSB0, COM5, socket://IP:PUERTO')
    p.add_argument('--demo', action='store_true', help='Datos simulados, sin abrir puerto ni importar pyserial')
    p.add_argument('--baud', type=int, default=115200)
    p.add_argument('--seconds', type=float, default=0, help='Duración; 0 hasta Ctrl+C')
    p.add_argument('--adaptive', choices=['0','1','2'], default='2')
    p.add_argument('--individual', action='store_true', help='Usar 22 en vez de intentar la agrupación')
    p.add_argument('--conservative', action='store_true', help='Sin sufijo de una respuesta ni repetición por CR')
    p.add_argument('--tick-us', type=float, default=8/3, help='Escala provisional de F746; defecto 2.6666667 us/raw')
    p.add_argument('--no-flash', action='store_true', help='Mantener aviso sin parpadeo de fondo')
    p.add_argument('--log-dir', type=Path, default=Path('logs'))
    p.add_argument('--no-log', action='store_true')
    a=p.parse_args(argv)
    if a.baud <= 0 or not math.isfinite(a.seconds) or a.seconds < 0 or not math.isfinite(a.tick_us) or a.tick_us <= 0:
        p.error('baud y tick-us positivos; seconds >= 0')
    return a


def main(argv=None):
    args=parse_args(argv)
    console=Console()
    stop=threading.Event()
    model=Model()
    try:
        journal=Journal(args.log_dir, not args.no_log)
    except OSError as exc:
        console.print(Text('No se puede crear el registro: '+str(exc),style='red'))
        return 1
    journal.write('session', demo=args.demo, port=args.port, baud=args.baud,
                  tick_us_assumed=args.tick_us, correction_deg_per_raw_nominal=0.75,
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
        thread.join(timeout=3)
    console.print(Text('Sesión finalizada · '+model.status,style='red' if model.fatal else 'cyan'))
    if journal.path:
        console.print(Text('Registro: '+str(journal.path.resolve())))
        console.print(Text('CSV: '+str(journal.csv_path.resolve())))
    return 1 if model.fatal else 0


if __name__=='__main__':
    raise SystemExit(main())
