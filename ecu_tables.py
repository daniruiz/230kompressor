#!/usr/bin/env python3
"""Export WinOLS CSV definitions as PNG tables, MAF curves and ASCII SW versions.

Axis units and conversions come from the CSV; raw axes are not converted to
physical units automatically. CSV comments are displayed below each image.
Camshaft adjustment images include the hexadecimal bytes and decoded variants.
Supercharger clutch images use the same categorical design for OFF/ON/HOLD.
BIN files are never modified.
"""
import argparse
import csv
import io
import json
import re
import sys
import textwrap
from collections import Counter, defaultdict
from pathlib import Path

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.colors import BoundaryNorm, ListedColormap, Normalize
from matplotlib.font_manager import FontProperties
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


def table_name(row):
    """Match table names with or without an exported numeric prefix."""
    return re.sub(r'^\d+\s*-\s*', '', row['Name'].strip()).casefold()


def assign_table_folders(definitions):
    """Give corresponding tables one global index across all CSV versions.

    Match by bank, normalized name and occurrence within that bank. Addresses
    and dimensions can vary between versions. Order the resulting catalog by
    each table's lowest observed address, retaining CSV order for ties.
    """
    catalog = {}
    identified_rows = []
    for _, rows in definitions:
        bank = None
        occurrences = Counter()
        for row in sorted(rows, key=lambda r: integer(r['Fieldvalues.StartAddr'])):
            name = table_name(row)
            marker = re.fullmatch(r'=+\s*bank\s+(\d+)\s*=+', name)
            if marker:
                bank = int(marker.group(1))
            occurrences[bank, name] += 1
            key = (bank, name, occurrences[bank, name])
            address = integer(row['Fieldvalues.StartAddr'])
            if key not in catalog:
                catalog[key] = [address, safe_name(row['Name'])]
            else:
                catalog[key][0] = min(catalog[key][0], address)
            identified_rows.append((row, key))
    width = len(str(len(catalog)))
    folders = {}
    for position, key in enumerate(sorted(catalog, key=lambda k: catalog[k][0]), 1):
        folders[key] = (position, f'{position:0{width}d}-{catalog[key][1]}')
    for row, key in identified_rows:
        row['_position'], row['_folder'] = folders[key]


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


def extract_ascii(blob, row):
    """Decode the original SW bytes without applying numeric conversions."""
    if row['DataOrg'] != 'eByte':
        raise ValueError('SW Version requires byte data')
    for key in ('SkipBytes', 'LineSkipBytes'):
        if integer(row.get(key) or '0'):
            raise ValueError(f'SW Version: {key} is unsupported')
    cols, rows = integer(row['Columns']), integer(row['Rows'])
    if cols <= 0 or rows <= 0 or cols * rows > 100000:
        raise ValueError('Invalid or excessive dimensions')
    raw = read_values(blob, integer(row['Fieldvalues.StartAddr']),
                      rows * cols, 'eByte').astype('u1').tobytes()
    text = raw.rstrip(b'\x00 ').decode('ascii')
    if any(ord(char) < 32 or ord(char) == 127 for char in text):
        raise ValueError('SW Version contains non-printable ASCII bytes')
    return text


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


CAM_STATES = ('OFF', 'TOGGLE', 'HOLD', 'ON')
CAM_COLORS = ('#e2e8ef', '#f3c6cf', '#ffebad', '#bce4d4')
CAM_CONDITIONS = (('Automatic', 'Warm', 0), ('Automatic', 'Cold', 2),
                  ('Manual', 'Warm', 4), ('Manual', 'Cold', 6))
CLUTCH_STATES = ('OFF', 'ON', 'HOLD')
CLUTCH_COLORS = (CAM_COLORS[0], CAM_COLORS[3], CAM_COLORS[2])


def is_camshaft(row):
    return (table_name(row) == 'camshaft adjustment'
            or re.fullmatch(r'cam_command_b\d+', row.get('IdName', '')) is not None)


def is_supercharger_clutch(row):
    return table_name(row) in ('supercharger clutch', 'supercharger clutch state')


def clutch_states(row, values):
    """ME2.x clutch cells are direct commands: 0 clears, 1 sets, others hold.

    Unlike the cam map these bytes are not packed condition/previous-state bits.
    Retain the original values for the HEX panel and classify every other byte
    as HOLD, as the control branches do in all seven defined software versions.
    """
    if (row['DataOrg'] != 'eByte' or row.get('bSigned') == '1'
            or number(row['Fieldvalues.Factor']) != 1
            or number(row['Fieldvalues.Offset']) != 0
            or values.ndim != 2 or values.size == 0
            or not np.isfinite(values).all()
            or not np.equal(values, np.floor(values)).all()
            or values.min() < 0 or values.max() > 255):
        raise ValueError('Supercharger Clutch State requires unsigned bytes with identity conversion')
    return dict(states=np.minimum(values, 2).astype(np.uint8),
                previous_off=(values == 1).astype(np.uint8),
                previous_on=(values != 0).astype(np.uint8))


def camshaft_variants(blob, row, values):
    """Decode audited ME2.x command pairs; resolve axes from the CSV, not ROM guesses.

    Within each pair the low bit is the new command when previously OFF, and
    the high bit when previously ON. Pair values 0/1/2/3 mean OFF/TOGGLE/HOLD/ON.
    Current definitions list four absolute RPM node addresses in their comment.
    Older definitions without that list can only describe their bound axis.
    """
    if (row['DataOrg'] != 'eByte' or row.get('bSigned') == '1'
            or number(row['Fieldvalues.Factor']) != 1
            or number(row['Fieldvalues.Offset']) != 0
            or values.shape != (8, 8)
            or not np.isfinite(values).all()
            or not np.equal(values, np.floor(values)).all()
            or values.min() < 0 or values.max() > 255):
        raise ValueError('Camshaft adjustment requires an unsigned 8 x 8 byte map with identity conversion')
    if row.get('AxisX.DataSrc') != 'eRom':
        raise ValueError('Camshaft adjustment requires its RPM axis in ROM')
    raw = values.astype(np.uint8)
    variants = [dict(transmission=gear, temperature=temp, bits=[shift, shift+1],
                     states=(raw >> shift) & 3)
                for gear, temp, shift in CAM_CONDITIONS]
    marker = re.search(r'RPM node addresses\s*:\s*([^\r\n.]*)',
                       row.get('Comment') or '', re.IGNORECASE)
    bound = integer(row['AxisX.DataAddr'])
    if marker:
        tokens = [s.strip() for s in marker.group(1).split(',')]
        if (len(tokens) != 4
                or any(re.fullmatch(r'0x[0-9a-f]+', t, re.IGNORECASE) is None for t in tokens)):
            raise ValueError('Camshaft adjustment: expected four hexadecimal RPM node addresses in Comment')
        addresses = [integer(t) for t in tokens]
        if len(set(addresses)) != 4 or addresses[0] != bound:
            raise ValueError('Camshaft adjustment: RPM variant 0 must match AxisX, with four distinct addresses')
    else:
        addresses = [bound]
    rpm_axes, groups = [], []
    for selector, address in enumerate(addresses):
        definition = dict(row, **{'AxisX.DataAddr': f'${address:X}'})
        if marker:
            definition['AxisX.Name'] = f'RPM (axis {selector})'
        rpm, label = axis(blob, definition, 'AxisX', values.shape[1])
        if not np.all(np.diff(rpm) > 0):
            raise ValueError(f'Camshaft adjustment: RPM axis at 0x{address:X} must increase strictly')
        rpm_axes.append(dict(selector=selector if marker else None,
                             **axis_metadata(definition, 'AxisX', rpm, label)))
        group = next((g for g in groups if np.array_equal(g['values'], rpm)), None)
        if group is None:
            group = dict(selectors=[], addresses=[], values=rpm)
            groups.append(group)
        group['selectors'].append(selector if marker else None)
        group['addresses'].append(address)
    return dict(variants=variants, rpm_axes=rpm_axes, rpm_groups=groups,
                all_rpm_variants=marker is not None)


def camshaft_metadata(data):
    return dict(state_labels=list(CAM_STATES), rpm_axes=data['rpm_axes'],
                all_rpm_variants=data['all_rpm_variants'],
                variants=[dict(transmission=v['transmission'], temperature=v['temperature'],
                               bits=v['bits'], states=v['states'].tolist(),
                               previous_off=(v['states'] & 1).tolist(),
                               previous_on=((v['states'] >> 1) & 1).tolist())
                          for v in data['variants']])


def draw_state_grid(ax, values, xaxis, yaxis, title, labels, colors,
                    packed=False, xprecision=0, yprecision=0, fontsize=9):
    """Shared discrete table styling; numeric codes never use a continuous scale."""
    rows, cols = values.shape
    x, xlabel = xaxis
    y, ylabel = yaxis
    if packed:
        ax.imshow(np.zeros_like(values), cmap=ListedColormap(['#edf1f5']),
                  vmin=0, vmax=1, aspect='auto', interpolation='nearest')
    else:
        cmap = ListedColormap(colors)
        norm = BoundaryNorm(np.arange(-.5, len(colors)+.5, 1), cmap.N)
        ax.imshow(values, cmap=cmap, norm=norm, aspect='auto', interpolation='nearest')
    for (r, c), value in np.ndenumerate(values):
        text = f'{int(value):02X}' if packed else labels[int(value)]
        ax.text(c, r, text, ha='center', va='center', fontsize=fontsize,
                color='#172f40', weight='bold', family='monospace' if packed else None)
    ax.set_xticks(range(cols), [fmt(v, xprecision) for v in x])
    ax.set_yticks(range(rows), [fmt(v, yprecision) for v in y])
    ax.tick_params(labelsize=9)
    ax.xaxis.tick_top()
    ax.xaxis.set_label_position('top')
    ax.set_xlabel(xlabel, labelpad=10)
    ax.set_ylabel(ylabel, fontsize=10)
    ax.set_title(title, fontsize=12, weight='bold', pad=55)
    ax.set_xticks(np.arange(-.5, cols, 1), minor=True)
    ax.set_yticks(np.arange(-.5, rows, 1), minor=True)
    ax.grid(which='minor', color='white', linewidth=1.5)
    ax.tick_params(which='minor', bottom=False, left=False)
    for spine in ax.spines.values():
        spine.set_visible(False)


def render_clutch(job, destination, dpi):
    row, data = job['row'], job['clutch']
    raw = job['values'].astype(np.uint8)
    rows, cols = raw.shape
    fig = plt.figure(figsize=(max(16, cols*.85+2), max(9, rows*.45+5.5)))
    layout = fig.add_gridspec(2, 2, height_ratios=[1, 1.5])
    original, legend, decoded = (fig.add_subplot(layout[0, 0]),
                                 fig.add_subplot(layout[0, 1]), fig.add_subplot(layout[1, :]))
    draw_state_grid(original, raw, (np.arange(cols), 'Throttle column index'),
                    (np.arange(rows), 'RPM row index'),
                    f"Original bytes (hexadecimal) | 0x{integer(row['Fieldvalues.StartAddr']):X}",
                    CLUTCH_STATES, CLUTCH_COLORS, packed=True, fontsize=8)
    draw_state_grid(decoded, data['states'], job['xaxis'], job['yaxis'],
                    'Supercharger clutch command', CLUTCH_STATES, CLUTCH_COLORS,
                    xprecision=integer(row.get('AxisX.Precision') or '-1'),
                    yprecision=integer(row.get('AxisY.Precision') or '-1'))
    legend.set_axis_off()
    legend.set_title('How to read the states', fontsize=13, weight='bold', loc='left', pad=12)
    legend.text(0, .95, 'New command when previously OFF / previously ON',
                fontsize=11, va='top', transform=legend.transAxes)
    for i, (code, transition, description) in enumerate([
            ('00', '0 / 0', 'Disengage clutch'), ('01', '1 / 1', 'Engage clutch'),
            ('02-FF', '0 / 1', 'Keep previous command')]):
        ypos = .78 - .17*i
        legend.text(.02, ypos, CLUTCH_STATES[i], fontsize=10, weight='bold',
                    bbox=dict(boxstyle='round,pad=.45', facecolor=CLUTCH_COLORS[i], edgecolor='none'),
                    transform=legend.transAxes)
        legend.text(.20, ypos, f'{code} hex   |   {transition}   {description}', fontsize=10,
                    transform=legend.transAxes)
    legend.text(0, .24, 'Each byte directly selects a command. States are not interpolated.\n'
                'HOLD retains the previous state; it does not engage the clutch.\n'
                'The stock maps use 00, 01 and 02. Other bytes also retain state.',
                fontsize=10, va='top', linespacing=1.5, transform=legend.transAxes)
    fig.suptitle(f"{row['Name']} | decoded control states\n{job['binary'].name}",
                 fontsize=15, weight='bold', y=.995)
    fig.text(.02, .015, f"CSV: {job['definition'].name} | {rows} x {cols} direct command bytes\n"
             f"Axes: X = {axis_origin(row, 'AxisX')} | Y = {axis_origin(row, 'AxisY')}\n"
             'Table request only. Enable conditions and ECU overrides can change the final clutch command.', fontsize=10)
    fig.tight_layout(rect=(0, .08, 1, .93), h_pad=3.5, w_pad=3)
    save_figure(fig, job, destination, dpi)


def render_camshaft(job, destination, dpi):
    row, data = job['row'], job['camshaft']
    raw = job['values'].astype(np.uint8)
    y, ylabel = job['yaxis']
    groups = data['rpm_groups']
    fig, axes = plt.subplots(1 + 2*len(groups), 2,
                             figsize=(16, 1.5 + 4.5*(1 + 2*len(groups))), squeeze=False)

    def grid(ax, values, x, title, packed=False):
        draw_state_grid(ax, values, (x, 'RPM column index' if packed else 'RPM (nominal)'),
                        (y, ylabel), title, CAM_STATES, CAM_COLORS, packed=packed,
                        yprecision=integer(row.get('AxisY.Precision') or '-1'))

    grid(axes[0, 0], raw, np.arange(8),
         f"Packed bytes (hexadecimal) | 0x{integer(row['Fieldvalues.StartAddr']):X}", packed=True)
    legend = axes[0, 1]
    legend.set_axis_off()
    legend.set_title('How to read the variants', fontsize=13, weight='bold', loc='left', pad=12)
    legend.text(0, .95, 'New command when previously OFF / previously ON',
                fontsize=11, va='top', transform=legend.transAxes)
    for i, (state, transition, description) in enumerate([
            (0, '0 / 0', 'Force OFF'), (3, '1 / 1', 'Force ON'),
            (2, '0 / 1', 'Keep previous command'), (1, '1 / 0', 'Switch to the opposite command')]):
        ypos = .83 - .115*i
        legend.text(.02, ypos, CAM_STATES[state], fontsize=10, weight='bold',
                    bbox=dict(boxstyle='round,pad=.45', facecolor=CAM_COLORS[state], edgecolor='none'),
                    transform=legend.transAxes)
        legend.text(.21, ypos, f'{transition}   {description}', fontsize=10, transform=legend.transAxes)
    legend.text(0, .32, 'One bit for each previous state. States are not interpolated.\n'
                'ON/OFF is a software command, not measured cam angle.\n'
                'Warm/cold refers to the ECU selection, not a fixed temperature.',
                fontsize=10, va='top', linespacing=1.5, transform=legend.transAxes)
    addresses = ' | '.join(f"{a['selector']}: 0x{integer(a['address']):X}" for a in data['rpm_axes'])
    legend.text(0, .08, ('RPM selector / node address: ' + addresses) if data['all_rpm_variants'] else
                'Only the bound RPM axis is defined; other coding variants are unknown.',
                fontsize=9, va='top', wrap=True, transform=legend.transAxes)
    for g, group in enumerate(groups):
        selectors = ', '.join(str(v) for v in group['selectors'])
        axis_title = ('RPM coding variant' + ('s ' if len(group['selectors'])>1 else ' ') + selectors
                      if data['all_rpm_variants'] else 'Bound RPM axis only')
        if len(group['selectors']) > 1:
            axis_title += ' (identical axes)'
        for i, variant in enumerate(data['variants']):
            grid(axes[1+g*2+i//2, i%2], variant['states'], group['values'],
                 f"{variant['transmission']} / {variant['temperature']} | bits {variant['bits'][0]}/{variant['bits'][1]}\n{axis_title}")
    fig.suptitle(f"{row['Name']} | decoded control variants\n{job['binary'].name}",
                 fontsize=15, weight='bold', y=.995)
    fig.text(.02, .015, f"CSV: {job['definition'].name} | Each panel reads the same 64 packed bytes.\n"
             'Rows and columns select ranges; endpoint indices clamp. Enable conditions and diagnostic overrides still apply.',
             fontsize=10)
    fig.tight_layout(rect=(0, .055, 1, .94), h_pad=3.5, w_pad=3)
    save_figure(fig, job, destination, dpi)


def save_figure(fig, job, destination, dpi):
    """Append a readable CSV comment without shrinking the existing plot."""
    try:
        comment = (job['row'].get('Comment') or '').strip()
        if comment:
            fig.canvas.draw()
            renderer = fig.canvas.get_renderer()
            font = FontProperties(size=9)
            available = fig.get_figwidth() * fig.dpi * .96
            width = max(1, int(available / (font.get_size_in_points() * fig.dpi / 72 * .5)))
            paragraphs = ('Comment: ' + comment).splitlines()
            while True:
                wrapped = '\n'.join(textwrap.fill(p, width=width, break_on_hyphens=False)
                                    for p in paragraphs)
                if width == 1 or all(renderer.get_text_width_height_descent(line, font, False)[0]
                                     <= available for line in wrapped.splitlines()):
                    break
                width -= 1
            # Place the comment outside the original canvas. The tight bounding
            # box expands the exported image to include it and leaves plot sizes intact.
            fig.text(.02, -.12 / fig.get_figheight(), wrapped, ha='left', va='top', fontproperties=font,
                     color='#333333', linespacing=1.4, parse_math=False)
            fig.savefig(destination, dpi=dpi, bbox_inches='tight')
        else:
            fig.savefig(destination, dpi=dpi)
    finally:
        plt.close(fig)


def render(job, destination, limits, cmap_name, dpi):
    if 'clutch' in job:
        render_clutch(job, destination, dpi)
        return
    if 'camshaft' in job:
        render_camshaft(job, destination, dpi)
        return
    if 'text' in job:
        fig, ax = plt.subplots(figsize=(10, 2.4))
        ax.set_axis_off()
        ax.text(.5, .5, job['text'], ha='center', va='center',
                fontsize=18, family='monospace', transform=ax.transAxes,
                parse_math=False)
        fig.tight_layout()
        save_figure(fig, job, destination, dpi)
        return

    row, values = job['row'], job['values']
    rows, cols = values.shape
    x, xlabel = job['xaxis']
    y, ylabel = job['yaxis']
    if (re.match(r'^maf\b', table_name(row))
            and values.size == 512 and 1 in values.shape):
        coordinates, label = (x, xlabel) if rows == 1 else (y, ylabel)
        fig, ax = plt.subplots(figsize=(10, 5))
        ax.plot(coordinates, values.ravel(), color='#2463a6', linewidth=1.8)
        ax.set_xlabel(label)
        unit = row.get('Fieldvalues.Unit', '-')
        value_label = row.get('Fieldvalues.Name') or 'Value'
        ax.set_ylabel(value_label + (f' [{unit}]' if unit != '-' else ''))
        lo, hi = limits
        margin = (hi - lo) * .05 if hi != lo else .5
        ax.set_ylim(lo - margin, hi + margin)
        ax.grid(alpha=.25)
        ax.set_title(row['Name'] + '\n' + job['binary'].name, fontsize=11)
        fig.tight_layout()
        save_figure(fig, job, destination, dpi)
        return

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
    save_figure(fig, job, destination, dpi)


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
    assign_table_folders(definitions.values())
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
        for row in ordered_rows:
            position, folder = row['_position'], row['_folder']
            try:
                if table_name(row) == 'sw version':
                    jobs.append(dict(binary=binary, definition=definition, row=row,
                                     text=extract_ascii(blob, row), position=position,
                                     folder=folder))
                    continue
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

                job = dict(binary=binary, definition=definition, row=row, values=values,
                           xaxis=xaxis, yaxis=yaxis, folder=folder, position=position)
                if is_camshaft(row):
                    job['camshaft'] = camshaft_variants(blob, row, values)
                elif is_supercharger_clutch(row):
                    job['clutch'] = clutch_states(row, values)
                jobs.append(job)
            except ValueError as exc:
                errors.append(f"{binary.name} / {row['Name']} / line {row['_line']}: {exc}")
    # Prevent silent overwrites caused by identical or sanitized names.
    destinations = Counter((j['folder'].casefold(), j['binary'].name.casefold()) for j in jobs)
    if any(count > 1 for count in destinations.values()):
        parser.error('Two definitions produce the same output path; correct their names/addresses')
    ranges = defaultdict(list)
    for job in jobs:
        if 'text' in job:
            continue
        key = (job['row']['Name'].casefold(), job['row'].get('Fieldvalues.Unit'))
        ranges[key].extend([float(job['values'].min()), float(job['values'].max())])
    args.output.mkdir(parents=True, exist_ok=True)
    manifest = []
    for job in jobs:
        destination = args.output / job['folder'] / (job['binary'].name + '.png')
        destination.parent.mkdir(parents=True, exist_ok=True)
        if 'text' in job:
            render(job, destination, None, args.cmap, args.dpi)
            manifest.append(dict(binary=job['binary'].name, definition=job['definition'].name,
                                 position=job['position'], name=job['row']['Name'],
                                 address=job['row']['Fieldvalues.StartAddr'],
                                 image=str(destination.relative_to(args.output)),
                                 text=job['text']))
            continue
        key = (job['row']['Name'].casefold(), job['row'].get('Fieldvalues.Unit'))
        bounds = ranges[key] if args.scale == 'name' else [job['values'].min(), job['values'].max()]
        render(job, destination, (min(bounds), max(bounds)), args.cmap, args.dpi)
        manifest.append(dict(binary=job['binary'].name, definition=job['definition'].name,
                             position=job['position'], name=job['row']['Name'], address=job['row']['Fieldvalues.StartAddr'],
                             image=str(destination.relative_to(args.output)),
                             minimum=float(job['values'].min()), maximum=float(job['values'].max()),
                             axes=dict(x=axis_metadata(job['row'], 'AxisX', *job['xaxis']),
                                       y=axis_metadata(job['row'], 'AxisY', *job['yaxis']))))
        if 'camshaft' in job:
            manifest[-1]['camshaft'] = camshaft_metadata(job['camshaft'])
        elif 'clutch' in job:
            manifest[-1]['supercharger_clutch'] = dict(
                state_labels=list(CLUTCH_STATES),
                **{key: value.tolist() for key, value in job['clutch'].items()})
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
