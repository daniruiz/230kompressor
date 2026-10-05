#!/usr/bin/env python3
"""Export WinOLS CSV definitions as PNG tables, with ROM or index-based axes.

Axis units and conversions come from the CSV; raw axes are not converted to
physical units automatically. BIN files are never modified.
"""
import argparse
import csv
import io
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.colors import Normalize
import numpy as np


def integer(value):
    value = str(value).strip()
    if value.startswith('$'):
        return int(value[1:], 16)
    return int(value, 16 if value.lower().startswith('0x') else 10)


def number(value):
    return float(str(value).replace(',', '.'))


def safe_name(value):
    value = re.sub(r'[<>:"/\\|?*\x00-\x1f]', '_', value).strip().rstrip('.')
    if not value or value in ('.', '..'):
        raise ValueError('Empty or invalid file/folder name')
    return value


def read_definitions(path):
    data = path.read_bytes()
    try:
        text = data.decode('utf-8-sig')
    except UnicodeDecodeError:
        text = data.decode('cp1252')
    reader = csv.DictReader(io.StringIO(text), delimiter=';')
    required = {'Name', 'Columns', 'Rows', 'DataOrg', 'Fieldvalues.StartAddr',
                'Fieldvalues.Factor', 'Fieldvalues.Offset'}
    if not required.issubset(reader.fieldnames or []):
        raise ValueError(f'{path.name}: missing columns {required - set(reader.fieldnames or [])}')
    result = []
    for line, row in enumerate(reader, 2):
        if not any(row.values()):
            continue
        if None in row or any(v is None for v in row.values()):
            raise ValueError(f'{path.name}:{line}: incomplete CSV row or extra columns')
        row['Name'] = row['Name'].strip()
        row['_line'] = line
        result.append(row)
    if not result:
        raise ValueError(f'{path.name}: no definitions found')
    return result


def read_values(blob, address, count, organization, signed=False, skip_bytes=0):
    formats = {'eByte': ('i1' if signed else 'u1'),
               'eLoHi': ('<i2' if signed else '<u2'),
               'eHiLo': ('>i2' if signed else '>u2')}
    if organization not in formats:
        raise ValueError(f'Unsupported DataOrg: {organization}')
    dtype = np.dtype(formats[organization])
    if skip_bytes < 0:
        raise ValueError('SkipBytes must not be negative')
    stride = dtype.itemsize + skip_bytes
    end = address + (count - 1) * stride + dtype.itemsize
    if address < 0 or count <= 0 or end > len(blob):
        raise ValueError(f'Read outside binary bounds: 0x{address:X}..0x{end:X}, size {len(blob)}')
    return np.ndarray((count,), dtype=dtype, buffer=blob, offset=address,
                      strides=(stride,)).astype(float)


def extract(blob, row):
    # The supplied CSV files use contiguous data and linear conversion.
    for key in ('SkipBytes', 'LineSkipBytes', 'bReciprocal'):
        if integer(row.get(key) or '0'):
            raise ValueError(f'{key} is unsupported; refusing to interpret the map incorrectly')
    cols, rows = integer(row['Columns']), integer(row['Rows'])
    if cols <= 0 or rows <= 0 or cols * rows > 100000:
        raise ValueError('Invalid or excessive dimensions')
    raw = read_values(blob, integer(row['Fieldvalues.StartAddr']), rows * cols,
                      row['DataOrg'], row.get('bSigned') == '1')
    values = raw * number(row['Fieldvalues.Factor']) + number(row['Fieldvalues.Offset'])
    if not np.isfinite(values).all():
        raise ValueError('Non-finite values')
    return values.reshape(rows, cols)


def axis(blob, row, prefix, count):
    """Read exactly the axis points specified by the definition.

    DataAddr is the first value, not the ECU's descriptor/header. Do not add
    three bytes or infer a second-bank displacement: the CSV already does that.
    Unsupported WinOLS modes fail explicitly rather than producing wrong labels.
    """
    source = row.get(prefix + '.DataSrc') or 'eDataSrcNone'
    for suffix in ('.bBackwards', '.bReciprocal', '.DataHeader'):
        if integer(row.get(prefix + suffix) or '0'):
            raise ValueError(f'{prefix + suffix} is unsupported')
    signature = (row.get(prefix + '.SignatureByte') or '0x-1').strip().lower()
    if signature not in ('0x-1', '-1', '$-1'):
        raise ValueError(f'{prefix}.SignatureByte is unsupported')
    if source == 'eRom':
        address = row.get(prefix + '.DataAddr')
        if not address or not address.strip():
            raise ValueError(f'{prefix}: eRom axis requires DataAddr')
        try:
            raw = read_values(blob, integer(address), count,
                              row.get(prefix + '.DataOrg') or 'eByte',
                              integer(row.get(prefix + '.bSigned') or '0') != 0,
                              integer(row.get(prefix + '.SkipBytes') or '0'))
        except ValueError as exc:
            raise ValueError(f'{prefix}: {exc}') from exc
    elif source == 'eDataSrcNone':
        if integer(row.get(prefix + '.SkipBytes') or '0'):
            raise ValueError(f'{prefix}.SkipBytes is unsupported for index axes')
        raw = np.arange(count, dtype=float)
    else:
        raise ValueError(f'{prefix}: unsupported DataSrc: {source}')
    values = raw * number(row.get(prefix + '.Factor') or '1')
    values += number(row.get(prefix + '.Offset') or '0')
    if not np.isfinite(values).all():
        raise ValueError(f'{prefix}: non-finite axis values')
    name = (row.get(prefix + '.Name') or '-').strip()
    unit = (row.get(prefix + '.Unit') or '-').strip()
    if (row['Name'].strip().casefold() == 'temp ignition retard'):
        unit = 'ºC'
    label = name if name != '-' else 'Index'
    if unit and unit != '-':
        label += f' [{unit}]'
    return values, label


def axis_metadata(row, prefix, values, label):
    source = row.get(prefix + '.DataSrc') or 'eDataSrcNone'
    unit = (row.get(prefix + '.Unit') or '-').strip()
    if (row['Name'].strip().casefold() == 'temp ignition retard'
            and unit.casefold() == '(internal)[raw]'):
        unit = 'ºC'
    return dict(source=source,
                address=row.get(prefix + '.DataAddr') if source == 'eRom' else None,
                label=label, unit=unit,
                factor=number(row.get(prefix + '.Factor') or '1'),
                offset=number(row.get(prefix + '.Offset') or '0'),
                values=values.tolist())


def axis_origin(row, prefix):
    if row.get(prefix + '.DataSrc') == 'eRom':
        return f"ROM 0x{integer(row[prefix + '.DataAddr']):X}"
    return 'index 0'


def fmt(value, precision=-1):
    if precision >= 0:
        return f'{value:.{min(precision, 8)}f}'
    return f'{value:.4f}'.rstrip('0').rstrip('.') if value else '0'


def render(job, destination, limits, cmap_name, dpi):
    row, values = job['row'], job['values']
    rows, cols = values.shape
    x, xlabel = job['xaxis']
    y, ylabel = job['yaxis']
    fig, ax = plt.subplots(figsize=(max(8, cols * .66 + 2.4), max(3.4, rows * .39 + 2.5)))
    cmap = plt.get_cmap(cmap_name)
    lo, hi = limits
    if lo == hi:
        lo, hi = lo - .5, hi + .5
    norm = Normalize(lo, hi)
    im = ax.imshow(values, cmap=cmap, norm=norm, aspect='auto', interpolation='nearest')
    precision = integer(row.get('Precision') or '-1')
    for (r, c), value in np.ndenumerate(values):
        red, green, blue, _ = cmap(norm(value))
        color = 'black' if .2126 * red + .7152 * green + .0722 * blue > .53 else 'white'
        ax.text(c, r, fmt(value, precision), ha='center', va='center', fontsize=8, color=color)
    ax.set_xticks(range(cols), [fmt(v, integer(row.get('AxisX.Precision') or '-1')) for v in x])
    ax.set_yticks(range(rows), [fmt(v, integer(row.get('AxisY.Precision') or '-1')) for v in y])
    ax.tick_params(labelsize=8)
    ax.xaxis.tick_top()
    ax.xaxis.set_label_position('top')
    ax.set_xlabel(xlabel, labelpad=10)
    ax.set_ylabel(ylabel)
    ax.set_xticks(np.arange(-.5, cols, 1), minor=True)
    ax.set_yticks(np.arange(-.5, rows, 1), minor=True)
    ax.grid(which='minor', color='white', linewidth=.5, alpha=.6)
    ax.tick_params(which='minor', bottom=False, left=False)
    unit = row.get('Fieldvalues.Unit', '-')
    fig.colorbar(im, ax=ax, fraction=.035, pad=.03).set_label(unit if unit != '-' else 'Value')
    fig.suptitle(row['Name'] + '\n' + job['binary'].name, fontsize=11, y=.99)
    fig.text(.02, .015,
             f"Address: 0x{integer(row['Fieldvalues.StartAddr']):X} | {rows} × {cols} | "
             f"{row['DataOrg']} | CSV: {job['definition'].name}\n"
             f"Conversion: raw × {row['Fieldvalues.Factor']} + ({row['Fieldvalues.Offset']}) | "
             f"Axes: X = {axis_origin(row, 'AxisX')} | Y = {axis_origin(row, 'AxisY')}", fontsize=8)
    fig.tight_layout(rect=(0, .075, 1, .91))
    try:
        fig.savefig(destination, dpi=dpi)
    finally:
        plt.close(fig)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('binaries', type=Path)
    parser.add_argument('definitions', type=Path)
    parser.add_argument('-o', '--output', type=Path, default=Path('tables'))
    parser.add_argument('--assign', action='append', default=[], metavar='BINARY=CSV',
                        help='Explicit assignment using exact filenames; repeatable')
    parser.add_argument('--scale', choices=['name', 'image'], default='name',
                        help='Shared scale by name and unit (default), or per image')
    parser.add_argument('--cmap', default='RdYlGn_r', help='Low values in green, high values in red by default')
    parser.add_argument('--dpi', type=int, default=150)
    args = parser.parse_args()
    if args.dpi <= 0:
        parser.error('--dpi must be positive')
    plt.get_cmap(args.cmap)
    definitions = {p.name: (p, read_definitions(p)) for p in sorted(args.definitions.iterdir())
                   if p.is_file() and p.suffix.lower() == '.csv'}
    binaries = [p for p in sorted(args.binaries.iterdir()) if p.is_file() and p.suffix.lower() == '.bin']
    if not definitions or not binaries:
        parser.error('.bin and .csv files are required in the specified folders')
    assignments = {}
    for item in args.assign:
        if '=' not in item:
            parser.error('--assign must be BINARY=CSV')
        binary, definition = item.split('=', 1)
        if binary not in {p.name for p in binaries} or definition not in definitions:
            parser.error(f'Assignment references a missing file: {item}')
        assignments[binary] = definition
    jobs, errors = [], []
    for binary in binaries:
        if binary.name in assignments:
            matches = [definitions[assignments[binary.name]]]
        else:
            versions = set(re.findall(r'(?<!\d)\d{3}\.\d{6}(?!\d)', binary.name))
            matches = [value for name, value in definitions.items()
                       if versions & set(re.findall(r'(?<!\d)\d{3}\.\d{6}(?!\d)', name))]
        if len(matches) != 1:
            errors.append(f'{binary.name}: {len(matches)} matching CSV files; use --assign')
            continue
        definition, rows = matches[0]
        blob = binary.read_bytes()
        # Sort numerically by address; preserve CSV order for equal addresses.
        ordered_rows = sorted(rows, key=lambda row: integer(row['Fieldvalues.StartAddr']))
        for position, row in enumerate(ordered_rows, start=1):
            try:
                values = extract(blob, row)
                xaxis = axis(blob, row, 'AxisX', values.shape[1])
                yaxis = axis(blob, row, 'AxisY', values.shape[0])

                # Temp Ignition Retard uses fixed physical temperature axes
                # instead of the raw/internal values supplied by the CSV/ROM.
                #
                # 8x8 layout:
                #   X/IAT: -30, -14, 0, 15, 17, 30, 60, 101 ºC
                #   Y/CLT: -29, -10, 15, 30, 50, 80, 101, 132 ºC
                #
                # 5x8 layout:
                #   X/IAT keeps the last five points: 15, 17, 30, 60, 101 ºC
                #   Y/CLT is unchanged.
                if row['Name'].strip().casefold() == 'temp ignition retard':
                    fixed_y = np.array([-29, -10, 15, 30, 50, 80, 101, 132], dtype=float)

                    if values.shape == (8, 8):
                        fixed_x = np.array([-30, -14, 0, 15, 17, 30, 60, 101], dtype=float)
                    elif values.shape == (8, 5):
                        fixed_x = np.array([15, 17, 30, 60, 101], dtype=float)
                    else:
                        raise ValueError(
                            'Temp Ignition Retard fixed axes support 8 x 8 or 5 x 8 tables; '
                            f'got {values.shape[1]} x {values.shape[0]}'
                        )

                    xaxis = (fixed_x, xaxis[1])
                    yaxis = (fixed_y, yaxis[1])

                folder = f"{position}-{safe_name(row['Name'])}"
                jobs.append(dict(binary=binary, definition=definition, row=row, values=values,
                                 xaxis=xaxis, yaxis=yaxis, folder=folder, position=position))
            except ValueError as exc:
                errors.append(f"{binary.name} / {row['Name']} / line {row['_line']}: {exc}")
    # Prevent silent overwrites caused by identical or sanitized names.
    destinations = Counter((j['folder'].casefold(), j['binary'].name.casefold()) for j in jobs)
    if any(count > 1 for count in destinations.values()):
        parser.error('Two definitions produce the same output path; correct their names/addresses')
    ranges = defaultdict(list)
    for job in jobs:
        key = (job['row']['Name'].casefold(), job['row'].get('Fieldvalues.Unit'))
        ranges[key].extend([float(job['values'].min()), float(job['values'].max())])
    args.output.mkdir(parents=True, exist_ok=True)
    manifest = []
    for job in jobs:
        destination = args.output / job['folder'] / (job['binary'].name + '.png')
        destination.parent.mkdir(parents=True, exist_ok=True)
        key = (job['row']['Name'].casefold(), job['row'].get('Fieldvalues.Unit'))
        bounds = ranges[key] if args.scale == 'name' else [job['values'].min(), job['values'].max()]
        render(job, destination, (min(bounds), max(bounds)), args.cmap, args.dpi)
        manifest.append(dict(binary=job['binary'].name, definition=job['definition'].name,
                             position=job['position'], name=job['row']['Name'], address=job['row']['Fieldvalues.StartAddr'],
                             image=str(destination.relative_to(args.output)),
                             minimum=float(job['values'].min()), maximum=float(job['values'].max()),
                             axes=dict(x=axis_metadata(job['row'], 'AxisX', *job['xaxis']),
                                       y=axis_metadata(job['row'], 'AxisY', *job['yaxis']))))
    (args.output / 'manifest.json').write_text(json.dumps({'images': manifest, 'errors': errors}, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'{len(manifest)} images generated in {args.output}; {len(errors)} errors.')
    for error in errors:
        print(error, file=sys.stderr)
    return 1 if errors else 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, OSError) as exc:
        sys.exit(f'Error: {exc}')
