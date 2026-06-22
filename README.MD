# 230 Kompressor M111 Bosch ME2.1 TUNE

> [!CAUTION]
> Do not flash any binary directly to your ECU.
>
> Changes to the tune need to be copied manually in order to preserve your software and variations of your map.

# Content
- Original map: `230k_ME21_STOCK.BIN`
- Tuned map: `230k_ME21.BIN`
- Mappacks for WINOLS
  - `230kompressor_ME2.1.kp`
  - `230kompressor_ME2.1.csv`
- Hex dump of binary file (only used for comparing binary changes in plain text)  `230k_ME21.BIN.XXD`

# Tables
- Fuel
- Spark Advance
- Bypass Valve position
- Rev limiter
- MAF limiter
- Torque limiter
    - If disabled it also removes the 5700rpm limit without ABS


# Relevant information
- [How to flash ME2.1 ECU](https://www.benzworld.org/threads/kompressor-bosch-me2-0-me2-1-ecu-boost-limiter-and-how-to-remove-it-without-maf-clamp-including-read-and-flash-guide.3069431)
- After modifying the tune you can fix the checksum with ECM titanium

# Mods

## STAGE 1 (~210-220hp)
- 218mm crankshaft pulley
- Cold air intake
- Exhaust
- CAT delete (optional)
- Zener diode (4.7v 1w 1N4732A) or ECU tune removing MAF limiter (optional)

## STAGE 2 (~230-240hp)
- [Ported supercharger](https://www.benzworld.org/threads/home-port-job-of-the-eaton-m62-s-c.1516156/)
- Intercooler
- Remove MAF and throttle body screen
- Wideband
- 320cc Bosch Red injectors 0280155759
- 1-6 Bar adjustable fpr (adjust pressure to reach ~12.2 AFR at 5200rpm WOT)
- BOSCH FR6DC spark plugs
- ECU tune

## Extra mods
- Shorter diff 3.92 (for example from clk200 not kompressor). Original is 3.46
    - CLK200 W208 A2023502314
    - SLK200 R170 A1243504598 / A1703501114
    - C W202
      - A2023502314
      - A1243504598
      - A2023501814
      - A1703501114
      - A2023504603
      - A1243507514
      - A1243502874
      - A1243509614
    - E W210
      - A2023502314
      - A1703501114
      - A2023501814

# ECU versión

- HW: 0285454532
- SW: 114.236438
