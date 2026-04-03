# 230 Kompressor M111 Bosch ME2.1 TUNE

> [!CAUTION]
> Do not flash any binary directly to your car or you will not be able to start it.
>
> Changes to the tune need to be copied manually in order to preserve your key code and other variations in your map.

# Content
- Original map: `230k_ME21_STOCK.BIN`
- Tuned map: `230k_ME21.BIN`
- Mappacks for WINOLS
  - `230kompressor_ME2.1.kp`
  - `230kompressor_ME2.1.csv`
- Hex dump of binary file (only used for comparing binary changes in plain text)  `230k_ME21.BIN.XXD`

# Changes
- Increased fuel tables
- Increased spark advance (98 RON gas)
- Removed MAF limiter
- Pops & bangs
- Increased rev-limiter to 6500rpm


# Relevant information
- [How to flash ME2.1 ECU](https://www.benzworld.org/threads/kompressor-bosch-me2-0-me2-1-ecu-boost-limiter-and-how-to-remove-it-without-maf-clamp-including-read-and-flash-guide.3069431/page-4)
- After modifying the tune you can fix the checksum with ECM titanium

# ECU versión

- HW: 0285454532
- SW: 114.236438