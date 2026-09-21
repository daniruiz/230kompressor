#!/usr/bin/env python3
"""Mercedes 28F200 checksum fixer.

Observed/validated layout for the supplied 256 KiB binaries:
  CS1 = sum of uint16 little-endian words in [0x4000, 0x8000)
        stored as uint32 little-endian at 0x3FFB4
  CS2 = sum of uint16 little-endian words in [0x30000, 0x34000)
        stored as uint32 little-endian at 0x3FFE0

The sums are reduced modulo 2**32.
"""

from __future__ import annotations

import argparse
import struct
from pathlib import Path

FLASH_SIZE = 0x40000

CS1_START = 0x4000
CS1_END = 0x8000
CS1_OFFSET = 0x3FFB4

CS2_START = 0x30000
CS2_END = 0x34000
CS2_OFFSET = 0x3FFE0


def sum_u16le(data: bytes | bytearray, start: int, end: int) -> int:
    block = memoryview(data)[start:end]
    if len(block) % 2:
        raise ValueError("Checksum block length must be even")
    return sum(v[0] for v in struct.iter_unpack("<H", block)) & 0xFFFFFFFF


def calculate(data: bytes | bytearray) -> tuple[int, int]:
    if len(data) != FLASH_SIZE:
        raise ValueError(
            f"Expected a 0x{FLASH_SIZE:X}-byte (256 KiB) image, got 0x{len(data):X} bytes"
        )
    cs1 = sum_u16le(data, CS1_START, CS1_END)
    cs2 = sum_u16le(data, CS2_START, CS2_END)
    return cs1, cs2


def stored(data: bytes | bytearray) -> tuple[int, int]:
    cs1 = int.from_bytes(data[CS1_OFFSET:CS1_OFFSET + 4], "little")
    cs2 = int.from_bytes(data[CS2_OFFSET:CS2_OFFSET + 4], "little")
    return cs1, cs2


def fix(data: bytes | bytearray) -> bytearray:
    out = bytearray(data)
    cs1, cs2 = calculate(out)
    out[CS1_OFFSET:CS1_OFFSET + 4] = cs1.to_bytes(4, "little")
    out[CS2_OFFSET:CS2_OFFSET + 4] = cs2.to_bytes(4, "little")
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description="Verify/fix Mercedes 28F200 checksums")
    ap.add_argument("bin", type=Path, help="256 KiB ECU binary")
    ap.add_argument("-o", "--output", type=Path, help="write corrected image here")
    ap.add_argument("--in-place", action="store_true", help="overwrite the input file")
    args = ap.parse_args()

    if args.output and args.in_place:
        ap.error("use either --output or --in-place, not both")

    data = args.bin.read_bytes()
    calc1, calc2 = calculate(data)
    old1, old2 = stored(data)

    print(f"CS1 [0x{CS1_START:05X}:0x{CS1_END:05X}] -> @0x{CS1_OFFSET:05X}")
    print(f"    stored     0x{old1:08X}")
    print(f"    calculated 0x{calc1:08X}  {'OK' if old1 == calc1 else 'BAD'}")
    print(f"CS2 [0x{CS2_START:05X}:0x{CS2_END:05X}] -> @0x{CS2_OFFSET:05X}")
    print(f"    stored     0x{old2:08X}")
    print(f"    calculated 0x{calc2:08X}  {'OK' if old2 == calc2 else 'BAD'}")

    dst = args.bin if args.in_place else args.output
    if dst is not None:
        corrected = fix(data)
        dst.write_bytes(corrected)
        print(f"Written: {dst}")

    return 0 if (old1 == calc1 and old2 == calc2) else 1


if __name__ == "__main__":
    raise SystemExit(main())
