"""Synchronize this repository's WinOLS 0x255 KP files with CSV definitions.

Uses the existing KP files as native metadata templates and refreshes reference
values from stock binaries. Python standard library only. The supported subset
is limited to the zero-stride definitions in this repository.
"""
from __future__ import annotations

import argparse
import copy
import csv
import io
import math
import os
from pathlib import Path
import re
import struct
import sys
import tempfile
import zipfile
import zlib


def require(condition, message):
    if not condition:
        raise ValueError(message)


class MetadataCipher:
    """WinOLS 0x255 metadata stream; the key includes its absolute file offset.

    Derived from the native archive reader. This only relocates existing
    metadata: its decoded contents, including project properties, stay intact.
    """
    MASK = 0xffffffff

    def __init__(self, seed, version, start):
        self.keys = [0xbb726b99 ^ seed, 0xa41571d8 ^ version,
                     0xd18c67ff ^ start, 0x7533a19e]
        self.state = [(self.keys[0] + self.keys[1] - 0x202a) & self.MASK,
                      (self.keys[0] + self.keys[2] - 0x7d2444) & self.MASK]
        self.index = 8

    def run(self, data, decode=True):
        output = bytearray()
        for value in data:
            if self.index >= 8:
                last, total = self.state[1], 0
                for _ in range(32):
                    total = (total + 0x9e3779b9) & self.MASK
                    selector = (total >> 2) & 3
                    for index in range(2):
                        mix = ((((last << 4) & self.MASK) ^ (last >> 5)) + last) & self.MASK
                        mix ^= (total + self.keys[(index & 3) ^ selector]) & self.MASK
                        last = self.state[index] = (self.state[index] + mix) & self.MASK
                self.cache = struct.pack('<II', *self.state)
                self.index = 0
            low, high = self.cache[self.index:self.index+2]
            plain = ((value ^ high) - low) & 255 if decode else value
            output.append(plain if decode else ((plain + low) & 255) ^ high)
            index = self.index // 2
            key = self.keys[index]
            self.keys[index] = (((key << 2) & self.MASK) ^ (key >> 30) ^ plain) & self.MASK
            self.index += 2
        return bytes(output)


NATIVE_METADATA_SUFFIX = bytes.fromhex(
    '00000000ffffffffffffffffffffffff0144bc3a120101'
    '0000000000000000000000000000000000000000'
    '02000000020000000000000000000000'
)


def read_metadata(pack):
    """Validate/decode the metadata layout observed in the native KP files.

    The two random byte arrays surround a 3264-byte serialized body. Restrict
    this reader to that known layout rather than guessing unsupported formats.
    """
    data = pack['original']
    body = 100 + u32(data, 96)
    payload_marker = u32(data, body + 664)
    try:
        marker6 = data.index(bytes.fromhex('83122788'), pack['zip_end'], payload_marker)
        marker4 = data.index(bytes.fromhex('8a0123cd'), pack['zip_end'], marker6)
        marker7 = data.index(bytes.fromhex('36adc084'), marker6+4, payload_marker)
    except ValueError as error:
        raise ValueError('Unsupported KP metadata markers') from error
    start, end = marker6+20, marker7-len(NATIVE_METADATA_SUFFIX)
    require(marker4+56 <= marker6 and start < end, 'Unsupported KP metadata boundaries')
    require(data[end:marker7] == NATIVE_METADATA_SUFFIX, 'Unsupported KP metadata suffix')
    seed, version = u32(data, marker4+52), u32(data, 16)
    plain = MetadataCipher(seed, version, start).run(data[start:end])
    require(len(plain) >= 4, 'Truncated encrypted KP metadata')
    first_padding = u32(plain, 0)
    require(first_padding <= 309,
            'KP metadata does not decode at its file position; use a valid native KP with --template')
    final_padding_at = 4 + first_padding + 3264
    require(final_padding_at + 4 <= len(plain), 'Unsupported KP metadata body length')
    final_padding = u32(plain, final_padding_at)
    require(final_padding <= 309 and final_padding_at + 4 + final_padding == len(plain),
            'KP metadata has an invalid final padding array')
    return dict(start=start, end=end, seed=seed, version=version, plain=plain)


class Reader:
    def __init__(self, data):
        self.data, self.pos = data, 0

    def take(self, n):
        require(n >= 0 and self.pos + n <= len(self.data), 'Truncated KP record')
        result = self.data[self.pos:self.pos+n]
        self.pos += n
        return result

    def u32(self):
        return struct.unpack('<I', self.take(4))[0]

    def i32(self):
        return struct.unpack('<i', self.take(4))[0]

    def f64(self):
        return struct.unpack('<d', self.take(8))[0]

    def string(self):
        n = self.i32()
        require(-3 <= n < 100000 and n != -2, 'Unsupported KP string encoding')
        return {'token': n, 'raw': self.take(n) if n > 0 else b''}


def string_bytes(s):
    return struct.pack('<i', s['token']) + s['raw']


def decode_string(s):
    if s['token'] >= 0:
        return s['raw'].decode('cp1252')
    return {-1: '-', -3: '%'}[s['token']]


def encode_string(s):
    # Native WinOLS reserves these common strings as signed tokens.
    if s in ('-', '%'):
        return {'token': {'-': -1, '%': -3}[s], 'raw': b''}
    raw = s.encode('cp1252')
    return {'token': len(raw), 'raw': raw}


def read_axis(r):
    a = dict(name=r.string(), name_lang4=r.take(4), opaque8=r.take(8),
             unit=r.string(), factor=r.f64(), offset=r.f64())
    leading = r.take(51)
    require(struct.unpack_from('<I', leading, 39)[0] == 0, 'Editable axes not supported')
    a['idname'] = r.string()
    trailing = r.take(8)
    require(trailing[:4] == bytes(4), 'Axis alternate descriptions not supported')
    require(a['name_lang4'] == bytes(4), 'Multilingual axis names not supported')
    a['tail63'] = leading + bytes(4) + trailing
    return a


def write_axis(a):
    return (string_bytes(a['name']) + a['name_lang4'] + a['opaque8']
            + string_bytes(a['unit']) + struct.pack('<dd', a['factor'], a['offset'])
            + a['tail63'][:51] + string_bytes(a['idname']) + a['tail63'][55:])


def read_record(r):
    start = r.pos
    result = dict(flag=r.take(1), identifier=r.i32(), comment=r.string(),
                  opaque13=r.take(13), name=r.string(), name_lang4=r.take(4))
    require(result['name_lang4'] == bytes(4), 'Multilingual map names not supported')
    leading = r.take(32)
    result['idname'] = r.string()
    result['header153'] = leading + bytes(4) + r.take(117)
    result.update(columns=r.u32(), rows=r.u32(),
                  cursor_x=r.u32(), cursor_y=r.u32(), precision=r.i32(),
                  field_name=r.string(), field_unit=r.string(), factor=r.f64(),
                  offset=r.f64(), address=r.u32(), end_address=r.u32(),
                  postfield32=r.take(32), x=read_axis(r), y=read_axis(r),
                  tail148=r.take(148))
    result['_start'] = start
    result['_end'] = r.pos
    return result


def write_record(x):
    return (x['flag'] + struct.pack('<i', x['identifier']) + string_bytes(x['comment'])
            + x['opaque13'] + string_bytes(x['name']) + x['name_lang4']
            + x['header153'][:32] + string_bytes(x['idname']) + x['header153'][36:]
            + struct.pack('<IIIIi', x['columns'], x['rows'],
                                         x['cursor_x'], x['cursor_y'], x['precision'])
            + string_bytes(x['field_name']) + string_bytes(x['field_unit'])
            + struct.pack('<ddII', x['factor'], x['offset'], x['address'], x['end_address'])
            + x['postfield32'] + write_axis(x['x']) + write_axis(x['y']) + x['tail148'])


def read_intern(data):
    r = Reader(data)
    result = dict(flag=r.take(1), count=r.u32())
    result['records'] = [read_record(r) for _ in range(result['count'])]
    require(r.pos == len(data), 'Unparsed bytes in KP map records')
    return result


def write_intern(x):
    return x['flag'] + struct.pack('<I', len(x['records'])) + b''.join(map(write_record, x['records']))


def read_kp(path):
    return read_kp_bytes(Path(path).read_bytes())


def read_kp_bytes(data):
    require(len(data) >= 1029, 'Truncated KP file')
    require(data[:16] == b'\x0b\x00\x00\x00WinOLS File\x00', 'Not a supported WinOLS file')
    require(struct.unpack_from('<I', data, 16)[0] == 0x255, 'Only KP format 0x255 is supported')
    require(struct.unpack_from('<I', data, 20)[0] == len(data), 'KP file size mismatch')
    # In this format the fixed project header follows its variable filename.
    zstart = 100 + struct.unpack_from('<I', data, 96)[0] + 902
    require(zstart < len(data) and data[zstart:zstart+4] == b'PK\x03\x04', 'Unsupported KP project header')
    zlength = struct.unpack_from('<I', data, zstart-4)[0]
    zend = zstart + zlength
    require(zend <= len(data), 'KP ZIP length exceeds file')
    archive = data[zstart:zend]
    with zipfile.ZipFile(io.BytesIO(archive)) as z:
        require(z.namelist() == ['intern'], 'Unsupported KP archive entries')
        intern_data = z.read('intern')
    intern = read_intern(intern_data)
    require(write_intern(intern) == intern_data, 'KP record round-trip mismatch')
    pack = dict(original=data, prefix=data[:zstart], zip=archive, suffix=data[zend:],
                zip_start=zstart, zip_end=zend, intern_bytes=intern_data, intern=intern)
    payload_and_middle(pack)
    pack['metadata'] = read_metadata(pack)
    return pack


def read_csv(path):
    reader = csv.DictReader(io.StringIO(Path(path).read_bytes().decode('cp1252')), delimiter=';')
    require(reader.fieldnames is not None and len(reader.fieldnames) == 57
            and len(set(reader.fieldnames)) == 57, f'{path}: expected 57 distinct CSV columns')
    rows = list(reader)
    require(bool(rows), f'{path}: no map definitions')
    for index, row in enumerate(rows, 2):
        require(None not in row and None not in row.values(), f'{path}, line {index}: invalid column count')
    return rows


TYPE = {'eEinzel': 2, 'eEindim': 3, 'eZweidim': 4}
RWIN = {'eRightWinNone': 0, 'eHex': 1, 'eBars': 2}
VIEW = {'eViewText': 1}
ORG = {'eByte': (1, 1), 'eLoHi': (3, 2)}
SOURCE = {'eDataSrcNone': 0, 'eRom': 1}


def u32(data, offset):
    return struct.unpack_from('<I', data, offset)[0]


def i32(data, offset):
    return struct.unpack_from('<i', data, offset)[0]


def reverse_enum(mapping, value):
    for key, candidate in mapping.items():
        if candidate == value:
            return key
    raise ValueError(f'Unsupported KP enumeration value: {value}')


def address(text):
    if text.startswith('$'):
        return int(text[1:], 16)
    if text.startswith('0x'):
        return int(text[2:], 16)
    return int(text)


def as_bool(text):
    require(text in ('0', '1'), f'Unsupported boolean: {text}')
    return int(text)


def record_to_csv(m, folder_name='My maps'):
    """Decode all current CSV properties. Two original-view flags are inferred.

    tail148[0:2] are independently known serialized boolean fields. Their
    association with bOriginal/bOriginalValues follows property order; none of
    the supplied KP files has a positive example. This display-state mapping
    has not been confirmed through a running WinOLS instance.
    """
    h, t = m['header153'], m['tail148']
    require(u32(h, 28) == 0, 'Only the current My maps folder is supported')
    r = dict(Name=decode_string(m['name']), IdName=decode_string(m['idname']),
             FolderName=folder_name, Type=reverse_enum(TYPE, u32(h, 8)),
             ViewMode=reverse_enum(VIEW, u32(t, 14)), RWin=reverse_enum(RWIN, u32(h, 12)),
             DataOrg=reverse_enum(ORG, (u32(h, 16), u32(h, 20))),
             bReciprocal=str(h[149]), bSigned=str(h[150]), bDelta=str(h[151]),
             bPercent=str(h[152]), bOriginal=str(t[0]), bOriginalValues=str(t[1]),
             Columns=str(m['columns']), Rows=str(m['rows']), Radix=str(u32(h, 24)),
             Comment=decode_string(m['comment']), Precision=str(m['precision']),
             SkipBytes='0', LineSkipBytes='0')
    r.update({'Fieldvalues.Name': decode_string(m['field_name']),
              'Fieldvalues.Unit': decode_string(m['field_unit']),
              'Fieldvalues.Factor': repr(m['factor']), 'Fieldvalues.Offset': repr(m['offset']),
              'Fieldvalues.StartAddr': f'${m["address"]:X}'})
    for key, label in [('x', 'AxisX'), ('y', 'AxisY')]:
        a = m[key]
        data = a['tail63']
        decoded = dict(Name=decode_string(a['name']), IdName=decode_string(a['idname']),
                       Unit=decode_string(a['unit']), Factor=repr(a['factor']), Offset=repr(a['offset']),
                       Radix=str(u32(data, 16)), bBackwards=str(u32(data, 20)),
                       bReciprocal=str(data[33]), bSigned=str(data[38]), Precision=str(i32(data, 34)),
                       DataSrc=reverse_enum(SOURCE, u32(data, 0)), DataHeader=str(i32(data, 43)),
                       DataAddr=f'${u32(data, 4):X}',
                       DataOrg=reverse_enum(ORG, (u32(data, 8), u32(data, 12))),
                       SignatureByte=f'0x{i32(data, 47):x}', SkipBytes=str(i32(data, 59)))
        r.update({label + '.' + k: v for k, v in decoded.items()})
    require(len(r) == 57, 'CSV property schema mismatch')
    return r


def record_from_csv(row, template_record, rom_size=262144):
    """Build one record while retaining uninterpreted template display state.

    Restricted to observed byte/word tables, text view, ordinary ROM/index
    axes, and zero skips. It deliberately rejects unsupported storage modes.
    """
    require(row['FolderName'] == 'My maps', 'Only the My maps folder is supported')
    require(row['SkipBytes'] == row['LineSkipBytes'] == '0', 'Strided maps not supported')
    require(int(row['Columns']) > 0 and int(row['Rows']) > 0, 'Map dimensions must be positive')
    m = copy.deepcopy(template_record)
    for key, field in [('name', 'Name'), ('idname', 'IdName'), ('comment', 'Comment'),
                       ('field_name', 'Fieldvalues.Name'), ('field_unit', 'Fieldvalues.Unit')]:
        m[key] = encode_string(row[field])
    # The two words after dimensions are editor cursor coordinates: a native
    # export can store 0xffffffff here while CSV LineSkipBytes remains zero.
    m.update(columns=int(row['Columns']), rows=int(row['Rows']),
             precision=int(row['Precision']), factor=float(row['Fieldvalues.Factor']),
             offset=float(row['Fieldvalues.Offset']), address=address(row['Fieldvalues.StartAddr']))
    kind, width = ORG[row['DataOrg']]
    span = m['columns'] * m['rows'] * width
    m['end_address'] = m['address'] + span
    require(0 <= m['address'] < m['end_address'] <= rom_size, 'Map extends outside the ROM')
    h = bytearray(m['header153'])
    for pos, value in [(8, TYPE[row['Type']]), (12, RWIN[row['RWin']]), (16, kind),
                       (20, width), (24, int(row['Radix'])), (28, 0)]:
        struct.pack_into('<I', h, pos, value)
    for pos, field in enumerate(['bReciprocal', 'bSigned', 'bDelta', 'bPercent'], 149):
        h[pos] = as_bool(row[field])
    m['header153'] = bytes(h)
    p = bytearray(m['postfield32'])
    struct.pack_into('<I', p, 0, rom_size)
    struct.pack_into('<I', p, 24, m['columns'] * span)
    m['postfield32'] = bytes(p)
    t = bytearray(m['tail148'])
    t[0], t[1] = as_bool(row['bOriginal']), as_bool(row['bOriginalValues'])
    struct.pack_into('<III', t, 6, m['columns'], m['rows'], VIEW[row['ViewMode']])
    m['tail148'] = bytes(t)
    for key, label in [('x', 'AxisX'), ('y', 'AxisY')]:
        a = m[key]
        require(row[label + '.SkipBytes'] == '0', 'Strided axes not supported')
        for prop, field in [('name', 'Name'), ('idname', 'IdName'), ('unit', 'Unit')]:
            a[prop] = encode_string(row[label + '.' + field])
        a['factor'], a['offset'] = float(row[label + '.Factor']), float(row[label + '.Offset'])
        data = bytearray(a['tail63'])
        axis_kind, axis_width = ORG[row[label + '.DataOrg']]
        source, addr = SOURCE[row[label + '.DataSrc']], address(row[label + '.DataAddr'])
        for pos, value in [(0, source), (4, addr), (8, axis_kind), (12, axis_width),
                           (16, int(row[label + '.Radix'])), (20, as_bool(row[label + '.bBackwards']))]:
            struct.pack_into('<I', data, pos, value)
        data[33], data[38] = as_bool(row[label + '.bReciprocal']), as_bool(row[label + '.bSigned'])
        for pos, value in [(34, int(row[label + '.Precision'])), (43, int(row[label + '.DataHeader'])),
                           (47, address(row[label + '.SignatureByte'])), (59, 0)]:
            struct.pack_into('<i', data, pos, value)
        if source:
            count = m['columns'] if key == 'x' else m['rows']
            require(0 <= addr < addr + count * axis_width <= rom_size, 'Axis extends outside the ROM')
        a['tail63'] = bytes(data)
    return m


def reference_payload(rows, rom):
    """KP stores original map bytes, then its ROM axes, in map-list order."""
    blocks = []
    for row in rows:
        cols, count_rows = int(row['Columns']), int(row['Rows'])
        if cols <= 0 or count_rows <= 0:
            raise ValueError(f"{row['Name']}: dimensions must be positive")
        if row['DataOrg'] not in ('eByte', 'eLoHi'):
            raise ValueError(f"{row['Name']}: unsupported DataOrg {row['DataOrg']}")
        if int(row['SkipBytes']) or int(row['LineSkipBytes']):
            raise ValueError(f"{row['Name']}: nonzero map strides are not supported")
        spans = [(address(row['Fieldvalues.StartAddr']),
                  cols * count_rows * (1 if row['DataOrg'] == 'eByte' else 2))]
        for axis, count in [('AxisX', cols), ('AxisY', count_rows)]:
            if row[axis + '.DataSrc'] not in ('eRom', 'eDataSrcNone'):
                raise ValueError(f"{row['Name']}: unsupported {axis} source")
            if int(row[axis + '.SkipBytes']) or int(row[axis + '.DataHeader']):
                raise ValueError(f"{row['Name']}: nonzero axis strides/headers are not supported")
            if row[axis + '.DataOrg'] != 'eByte':
                raise ValueError(f"{row['Name']}: only byte axes are supported")
            if row[axis + '.DataSrc'] == 'eRom':
                spans.append((address(row[axis + '.DataAddr']), count))
        for start, length in spans:
            if start < 0 or start + length > len(rom):
                raise ValueError(f"{row['Name']}: range {start:#x}+{length:#x} exceeds ROM")
            blocks.append(rom[start:start + length])
    return b''.join(blocks)


def payload_and_middle(pack):
    data = pack['original']
    body = 100 + struct.unpack_from('<I', data, 96)[0]
    marker, length = struct.unpack_from('<II', data, body + 664)
    if marker < pack['zip_end'] or data[marker:marker+4] != bytes.fromhex('33887298'):
        raise ValueError('Invalid KP reference-data marker')
    payload = data[marker+4:]
    if len(payload) != length:
        raise ValueError('KP reference-data size mismatch')
    if struct.unpack_from('<I', data, body + 50)[0] != (~zlib.crc32(payload) & 0xffffffff):
        raise ValueError('KP reference-data CRC mismatch')
    if struct.unpack_from('<I', data, body + 58)[0] != sum(payload) & 0xffff:
        raise ValueError('KP reference-data sum mismatch')
    if struct.unpack_from('<I', data, body + 214)[0] != pack['intern']['count']:
        raise ValueError('KP map counts disagree')
    middle = data[pack['zip_end']:marker+4]
    if middle[:16] != bytes.fromhex('11886398000000001188639803000000'):
        raise ValueError('Unsupported KP folder block')
    # Folder state is retained. Its encrypted section must be re-encoded if moved.
    return payload, middle


def build_kp(template, rows, rom, filename):
    previous_payload, middle = payload_and_middle(template)
    payload = reference_payload(rows, rom)
    existing = template['intern']['records']
    if not existing:
        raise ValueError('The KP template contains no maps')
    by_address = {m['address']: m for m in existing}
    candidates = [(m, record_to_csv(m)) for m in existing]
    records = []
    for row in rows:
        map_address = address(row['Fieldvalues.StartAddr'])
        base = by_address.get(map_address)
        if base is None:
            # Retain native display state from the closest existing map layout.
            base = min(candidates, key=lambda pair: sum(
                pair[1][key] != row[key] for key in
                ('Type', 'DataOrg', 'Columns', 'Rows', 'AxisX.DataSrc', 'AxisY.DataSrc')
            ))[0]
        records.append(record_from_csv(row, base, len(rom)))
    internal = write_intern({'flag': template['intern']['flag'], 'records': records})
    with zipfile.ZipFile(io.BytesIO(template['zip'])) as old:
        info = copy.copy(old.getinfo('intern'))
    archive_buffer = io.BytesIO()
    with zipfile.ZipFile(archive_buffer, 'w') as archive:
        archive.writestr(info, internal, compress_type=zipfile.ZIP_DEFLATED, compresslevel=6)
    archive_data = archive_buffer.getvalue()
    # A native export may contain a project title instead of its disk filename.
    # Leave a current native file completely intact, including that title.
    if internal == template['intern_bytes'] and payload == previous_payload and archive_data == template['zip']:
        return template['original']
    encoded_name = filename.encode('cp1252')
    old_body = 100 + struct.unpack_from('<I', template['prefix'], 96)[0]
    prefix = bytearray(template['prefix'][:96] + struct.pack('<I', len(encoded_name))
                       + encoded_name + template['prefix'][old_body:])
    body = 100 + len(encoded_name)
    metadata = template['metadata']
    relative_start = metadata['start'] - template['zip_end']
    relative_end = metadata['end'] - template['zip_end']
    new_start = len(prefix) + len(archive_data) + relative_start
    relocated = MetadataCipher(metadata['seed'], metadata['version'], new_start).run(
        metadata['plain'], decode=False)
    middle = middle[:relative_start] + relocated + middle[relative_end:]
    marker = len(prefix) + len(archive_data) + len(middle) - 4
    struct.pack_into('<I', prefix, body + 50, ~zlib.crc32(payload) & 0xffffffff)
    struct.pack_into('<I', prefix, body + 58, sum(payload) & 0xffff)
    struct.pack_into('<I', prefix, body + 214, len(records))
    struct.pack_into('<II', prefix, body + 664, marker, len(payload))
    struct.pack_into('<I', prefix, len(prefix)-4, len(archive_data))
    struct.pack_into('<I', prefix, 20, marker + 4 + len(payload))
    return bytes(prefix) + archive_data + middle + payload


def comparable(key, value):
    if key.endswith(('.Factor', '.Offset')):
        number = float(value)
        if not math.isfinite(number):
            raise ValueError(f'{key}: non-finite number')
        return number
    if key.endswith(('.StartAddr', '.DataAddr')):
        return address(value)
    if key.endswith('.SignatureByte'):
        return int(value[2:], 16) if value.startswith('0x') else int(value)
    if key in ('Columns', 'Rows', 'Radix', 'Precision', 'SkipBytes', 'LineSkipBytes') or key.startswith('b') or key.split('.')[-1] in ('Radix', 'Precision', 'SkipBytes', 'DataHeader', 'bBackwards', 'bReciprocal', 'bSigned'):
        return int(value)
    return value


def validate_kp(pack, rows, rom, filename=None):
    payload, _ = payload_and_middle(pack)
    if filename is not None:
        n = struct.unpack_from('<I', pack['original'], 96)[0]
        if pack['original'][100:100+n].decode('cp1252') != filename:
            raise ValueError('KP embedded filename does not match its destination')
    if payload != reference_payload(rows, rom):
        raise ValueError('KP reference values do not match the original binary')
    records = pack['intern']['records']
    if len(records) != len(rows):
        raise ValueError(f'KP has {len(records)} maps; CSV has {len(rows)}')
    for index, (record, row) in enumerate(zip(records, rows), 1):
        actual = record_to_csv(record)
        if set(actual) != set(row):
            raise ValueError(f'CSV columns differ from the supported schema: {set(actual) ^ set(row)}')
        for key in row:
            if comparable(key, row[key]) != comparable(key, actual[key]):
                raise ValueError(f"Map {index} {row['Name']}, {key}: CSV={row[key]!r}, KP={actual[key]!r}")
        width = 1 if row['DataOrg'] == 'eByte' else 2
        size = int(row['Columns']) * int(row['Rows']) * width
        if record['end_address'] != record['address'] + size:
            raise ValueError(f"{row['Name']}: inconsistent end address")
        if struct.unpack_from('<I', record['postfield32'], 0)[0] != len(rom):
            raise ValueError(f"{row['Name']}: inconsistent image size")
        if struct.unpack_from('<I', record['postfield32'], 24)[0] != size * int(row['Columns']):
            raise ValueError(f"{row['Name']}: inconsistent stored row size")
        if struct.unpack_from('<II', record['tail148'], 6) != (int(row['Columns']), int(row['Rows'])):
            raise ValueError(f"{row['Name']}: inconsistent stored dimensions")


def find_binary(csv_path, binary_dir):
    versions = re.findall(r'\d{3}\.\d{6}', csv_path.stem)
    if not versions:
        raise ValueError(f'{csv_path.name}: no software version in filename')
    # Combined 338/438 definitions use the first (338) as their stock reference.
    matches = [p for p in binary_dir.iterdir()
               if p.is_file() and p.suffix.lower() == '.bin' and versions[0] + '.' in p.name]
    if len(matches) != 1:
        raise ValueError(f'{csv_path.name}: expected one original binary for {versions[0]}, found {len(matches)}')
    return matches[0]


def atomic_write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix='.' + path.name + '.', suffix='.tmp', delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(data)
        os.replace(temporary, path)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


def main(argv=None):
    root = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description='Update the repository\'s WinOLS KP map packs from CSV and stock binaries.')
    parser.add_argument('csv', nargs='*', type=Path, help='CSV files; default: all files in --definitions')
    parser.add_argument('--definitions', type=Path, default=root / 'Map Definitions')
    parser.add_argument('--binaries', type=Path, default=root / 'Original Binaries')
    parser.add_argument('--output-dir', type=Path, help='Write KP files here instead of beside the CSV files')
    parser.add_argument('--template', type=Path,
                        help='Use this valid KP as the template for every selected CSV (also repairs old generated packs)')
    parser.add_argument('--check', action='store_true', help='Check existing KP files without modifying them')
    args = parser.parse_args(argv)
    try:
        sources = args.csv or sorted(args.definitions.glob('*.csv'))
        if not sources:
            raise ValueError('No CSV files found')
        pending = []
        for source in sources:
            rows = read_csv(source)
            rom_path = find_binary(source, args.binaries)
            rom = rom_path.read_bytes()
            target = (args.output_dir / source.with_suffix('.kp').name
                      if args.output_dir else source.with_suffix('.kp'))
            if args.check:
                validate_kp(read_kp(target), rows, rom)
                print(f'OK {target.name}: {len(rows)} maps')
                continue
            template_path = args.template or source.with_suffix('.kp')
            if not template_path.exists():
                require(args.template is None, f'Template not found: {template_path}')
                template_path = args.definitions / '230Kompressor_114.235404.kp'
            template = read_kp(template_path)
            result = build_kp(template, rows, rom, target.name)
            validate_kp(read_kp_bytes(result), rows, rom)
            pending.append((target, result, len(rows)))
        # Prepare and validate every selected pack before replacing any file.
        for target, result, count in pending:
            unchanged = target.exists() and target.read_bytes() == result
            if not unchanged:
                atomic_write(target, result)
            print(f'{"Unchanged" if unchanged else "Updated"} {target.name}: {count} maps')
        return 0
    except (OSError, ValueError, AssertionError, KeyError, struct.error, zipfile.BadZipFile) as error:
        print(f'Error: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
