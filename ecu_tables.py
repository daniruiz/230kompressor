#!/usr/bin/env python3
"""Export WinOLS CSV definitions as PNG tables. BIN files are never modified."""
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


def read_values(blob, address, count, organization, signed=False):
    formats = {'eByte': ('i1' if signed else 'u1'),
               'eLoHi': ('<i2' if signed else '<u2'),
               'eHiLo': ('>i2' if signed else '>u2')}
    if organization not in formats:
        raise ValueError(f'Unsupported DataOrg: {organization}')
    dtype = np.dtype(formats[organization])
    end = address + count * dtype.itemsize
    if address < 0 or count <= 0 or end > len(blob):
        raise ValueError(f'Read outside binary bounds: 0x{address:X}..0x{end:X}, size {len(blob)}')
    return np.frombuffer(blob, dtype=dtype, count=count, offset=address).astype(float)


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


def axis(row, prefix, count):
    if row.get(prefix + '.DataSrc', 'eDataSrcNone') != 'eDataSrcNone':
        raise ValueError(f'{prefix}: only calculated eDataSrcNone axes are supported')
    for suffix in ('.bBackwards', '.bReciprocal', '.SkipBytes'):
        if integer(row.get(prefix + suffix) or '0'):
            raise ValueError(f'{prefix + suffix} is unsupported')
    values = np.arange(count) * number(row.get(prefix + '.Factor') or '1')
    values += number(row.get(prefix + '.Offset') or '0')
    name = row.get(prefix + '.Name', '-')
    unit = row.get(prefix + '.Unit', '-')
    label = name if name != '-' else 'Index'
    if unit and unit != '-':
        label += f' [{unit}]'
    return values, label


def fmt(value, precision=-1):
    if precision >= 0:
        return f'{value:.{min(precision, 8)}f}'
    return f'{value:.4f}'.rstrip('0').rstrip('.') if value else '0'


def render(job, destination, limits, cmap_name, dpi):
    row, values = job['row'], job['values']
    rows, cols = values.shape
    x, xlabel = axis(row, 'AxisX', cols)
    y, ylabel = axis(row, 'AxisY', rows)
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
             'Axes calculated from index 0', fontsize=8)
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
                axis(row, 'AxisX', values.shape[1])
                axis(row, 'AxisY', values.shape[0])
                folder = f"{position}-{safe_name(row['Name'])}"
                jobs.append(dict(binary=binary, definition=definition, row=row, values=values, folder=folder, position=position))
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
                             minimum=float(job['values'].min()), maximum=float(job['values'].max())))
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
