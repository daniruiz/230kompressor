"""Strict SIM4LE KWP framing with ELM headers enabled (including long replies)."""
import re

if __package__:
    from .ecu_reader import DiagnosticError, NegativeResponse
else:
    from ecu_reader import DiagnosticError, NegativeResponse


def positive_reply(raw, request, prefix, length):
    lines = []
    echo = request.hex().upper()
    for line in raw.upper().replace('>', '\n').replace('\r', '\n').splitlines():
        compact = re.sub(r'\s+', '', line)
        if not compact or (not lines and compact in (echo, echo + '1')):
            continue
        line = re.sub(r'(?:SEARCHING|BUS INIT|BUSINIT)[ .:]*(?:OK)?', '', line).strip()
        if not line or line == 'OK':
            continue
        compact = re.sub(r'\s+', '', line)
        if not re.fullmatch(r'[0-9A-F]+', compact) or len(compact) % 2:
            raise DiagnosticError('SIM4LE: ' + line[:100])
        lines.append(bytes.fromhex(compact))
    stream, pos, positives = b''.join(lines), 0, []
    while pos < len(stream):
        if len(stream) - pos < 4:
            raise DiagnosticError('SIM4LE: incomplete KWP header')
        fmt = stream[pos]
        if fmt & 0xC0 not in (0x80, 0xC0):
            raise DiagnosticError('SIM4LE: invalid KWP addressing')
        size, head = fmt & 0x3F, 3
        if not size:
            size, head = stream[pos + 3], 4
        end = pos + head + size + 1
        if not size or end > len(stream):
            raise DiagnosticError('SIM4LE: truncated KWP frame')
        frame = stream[pos:end]
        if sum(frame[:-1]) & 255 != frame[-1]:
            raise DiagnosticError('SIM4LE: invalid KWP checksum')
        if frame[1:3] != b'\xF1\x01':
            raise DiagnosticError('SIM4LE: unexpected KWP source or destination')
        payload = frame[head:-1]
        if payload[:2] == bytes((0x7F, request[0])) and len(payload) == 3:
            if payload[2] != 0x78:
                raise NegativeResponse(request[0], payload[2])
        elif payload.startswith(prefix) and (length is None or len(payload) == length):
            positives.append(payload)
        else:
            raise DiagnosticError('SIM4LE: unexpected payload or length')
        pos = end
    if len(positives) != 1:
        raise DiagnosticError('SIM4LE: expected one complete positive response')
    return positives[0]
