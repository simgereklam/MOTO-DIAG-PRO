# Yamaha XT660 ECU research review

Reviewed source: https://github.com/terrafirma2021/yamaha-xt660
Default branch inspected: `main`
Review date: 2026-11-12

## Decision

**High-value research reference for a future, separate XT660 K-Line adapter/profile; not directly compatible with the current MOTO DIAG PRO Viecar VP002 Wi-Fi/ELM transport.** Do not add its serial protocol to the current Viecar session or claim XT660 support based only on this repository.

No MOTO DIAG PRO runtime source, transport, DTC database, UI, or build configuration was changed by this review.

## What the repository contains

The README describes a substantial firmware-research project covering XT660R, XT660X and XT660Z Ténéré, with nine registered BIN records across two application families. The dataset explicitly says no matched XT660Z BIN is currently available. Model/year/market/ECU associations carry qualifications; catalogue replacement numbers and shared code are not proof of interchangeability.

Relevant material inspected:

- `analysis/semantics/K_LINE.md`: recovered SCI1 request/reply behavior and serial register analysis.
- `analysis/semantics/DIAGNOSTICS.md`: dashboard diagnostic menu, sensor values, actuator selectors and fault bitmap.
- `analysis/semantics/PROTOCOL_VERIFICATION.md`: byte-backed software verification and explicit limits.
- `analysis/semantics/IO_AND_DIAGNOSTICS.md` and `CONNECTOR_ROUTE_AUDIT.md`: sensor/output correlations and harness uncertainty.
- `dataset/README.md`: model/market/ECU applicability limits.
- `flasher/README.md`: Windows KKL flash utility and write boundaries.
- `emulator/README.md`: software emulator and simulation calibration boundaries.

## Useful diagnostic research (not yet a production protocol)

The recovered application uses a byte-oriented SCI1 request/reply exchange. The code sets an SCI baud divisor of 0x0040, giving bus_clock / 1024; 15,625 baud follows only if the MCU bus clock is 16 MHz. The repository cites a Yamaha service-tool request for 15,625 baud as supporting evidence, but says the actual clock and complete framing configuration need confirmation.

Important limitations stated by the repository:

- Calling the line K-Line still needs electrical board/waveform evidence; line voltage, transceiver and ISO compliance are not established.
- No generic ISO 9141 five-baud init or KWP fast-init sequence is recovered.
- The normal application accepts individual peer bytes and emits five-byte replies: four payload bytes plus additive checksum `(B0+B1+B2+B3) & 0xFF`.
- This is not a generic OBD-II Mode 01 / UDS service implementation. Do not reuse generic KWP/UDS request assumptions.
- The offline Python decoder is passive and takes known frame boundaries/direction; it does not communicate with hardware.

The diagnostic menu includes these sensor-oriented items:

| Menu item | Recovered meaning |
|---|---|
| 01 | Throttle angle |
| 03 | Intake pressure |
| 05 | Intake temperature |
| 06 | Coolant temperature |
| 07 | Vehicle-speed pulse |
| 08 | Lean-angle input |
| 09 | Fuel-system supply monitor |
| 20 | Sidestand input |
| 21 | Neutral input (variant-dependent) |
| 60 | EEPROM fault detail |
| 61 | Fault history |
| 62 | History count / erase mode |
| 70 | Program control number |

The source warns that the serial-byte-to-engineering-unit scaling is not fully validated. The emulator's estimated-voltage, temperature, speed and throttle display conversions must not be copied as factory calibration.

### Fault-number bitmap

The recovered application maps four bitmap bytes as follows (bit 7 to bit 0):

- `08AF`: 11, 12, 13, 14, 15, 16, 19, 20
- `08B0`: 21, 22, 23, 24, 30, 31, 32, reserved
- `08B1`: 33, 34, 37, 41, 42, 43, 44, 46
- `08B2`: 50, then seven reserved entries

This is a firmware-derived XT660-family map, not a universal Yamaha DTC table. Diagnostic menu numbers and fault numbers are separate namespaces (for example, menu item 30 is an ignition-coil test; fault 30 is a fault bit).

## Actuator tests and clearing

The repository identifies menu items 30 (ignition coil), 36 (injector), 48 (air induction), 50 (fuel injection relay), 51 (fan relay) and 52 (headlight relay) as actuator selections. These are active tests, not passive reads. They must not be triggered from MOTO DIAG PRO without model-specific factory procedure, safe interlocks, and physical validation.

Menu item 62 can enter persistent fault-history erase behavior. Do not implement or expose clearing based only on the menu label; exact user confirmation, state handling and readback semantics require a separate verified implementation.

## Hardware and compatibility

The flash-tool instructions specify a Windows USB KKL interface at 9,600 baud / 8N1 for the **bootloader flashing protocol**. That is distinct from the recovered application SCI1 dashboard exchange described above. The flash tool's connector drawing maps ECU power/ground/K-line to OBD breakout terminals, but the project explicitly says the internal route, boot serial port and physical ECU/adapter transaction have not been hardware-validated.

The current MOTO DIAG PRO path is Viecar VP002 Wi-Fi / ELM-style OBD at 192.168.0.10:35000/OBD. The XT660 project expects a different USB KKL/serial setup for its flash utility and does not establish that its application protocol is tunnelled through an ELM adapter. Keep the existing Viecar path unchanged. Any XT660 support needs a separately selected and electrically validated adapter/transport.

## Flashing tool: exclude from this project

The repository's Windows tool can read and write ECU application images. It reads the CPU range twice, validates image compatibility/checksums, backs up before writes and verifies readback. Its own README labels it a **bench-test prerelease** and explicitly says no physical ECU/adapter transaction has been performed. Do not integrate, launch, or use its write path. MOTO DIAG PRO remains read/diagnose/clear only where separately validated; no ECU firmware writing or updating.

## Evidence and confidence

The repository reports synthetic CPU12 instruction-fixture verification, including 4,096 checksum fixtures and diagnostic navigation tests. It explicitly says these are not whole-ECU hardware tests, do not establish interrupt timing or electrical waveforms, and example captures are synthetic rather than motorcycle recordings.

Outstanding physical evidence includes actual MCU clock, physical K-Line/transceiver/waveform, exact connector cavity and board continuity, boot services, timing, stored vehicle registration bytes, and sensor engineering-unit calibration. The repository audit also says physical sensor transfer functions and exact ECU/harness applicability remain open.

## Licensing / reuse

A root-level `LICENSE` was not found in the inspected repository paths. `emulator/COPYING` contains GPL-2.0 text, but that does not establish that every repository component, firmware image, XDF, flash tool, or Yamaha asset is covered by that license. Do not copy source code, binaries, BINs, XDFs, images or firmware into MOTO DIAG PRO until the specific component's rights and required notices are confirmed. A factual protocol/diagnostic research summary is recorded here without copying implementation code.

## Recommended next step

Retain this as an XT660-specific research reference. If XT660 support is later prioritized, first obtain the exact motorcycle year/market/ECU part number and a timestamped bidirectional capture from a correctly wired, current-limited and electrically appropriate bench/vehicle interface. Validate voltage, waveform, baud, request/reply boundaries and raw scaling before implementing a separate transport. Keep actuator and history-erase operations disabled until independently validated.
